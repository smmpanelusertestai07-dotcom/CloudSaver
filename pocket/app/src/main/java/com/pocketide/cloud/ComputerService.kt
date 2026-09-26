package com.pocketide.cloud

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pocketide.MainActivity
import com.pocketide.R
import com.pocketide.core.Channels
import com.pocketide.core.NotificationIds
import com.pocketide.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps PocketIDE's connection to a running cloud computer while the app is in the background,
 * with an ongoing notification that says so and a Stop button that saves the free hours.
 *
 * The agents themselves run on GitHub, not here: they keep working whether this service runs or
 * not, until GitHub stops the idle computer. The service only keeps the open page warm, and ends
 * by itself when GitHub reports the computer stopped.
 */
class ComputerService : LifecycleService() {
    private var watching: Job? = null
    private var name: String? = null
    private var title: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STAY -> stay(intent.getStringExtra(EXTRA_NAME), intent.getStringExtra(EXTRA_TITLE).orEmpty())
            ACTION_STOP_COMPUTER -> stopComputer()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stay(computer: String?, label: String) {
        if (computer == null) return stopSelf()
        name = computer
        title = label
        // Android 14 names the kind of work; older versions take the manifest's word for it.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NotificationIds.COMPUTER_ON, ongoing(), type)
        watching?.cancel()
        watching = lifecycleScope.launch { watch(computer) }
    }

    /** Asks GitHub every minute; once it has stopped the computer (idle, or elsewhere), so does this. */
    private suspend fun watch(computer: String) {
        while (lifecycleScope.isActive) {
            delay(CHECK_EVERY_MS)
            val state = try {
                graph.computers.get(computer)?.state
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Offline for a moment says nothing about the computer; the next check tries again.
                continue
            }
            if (state == null || !state.running) {
                notifyStopped()
                end()
                return
            }
        }
    }

    private fun stopComputer() {
        val computer = name ?: return end()
        lifecycleScope.launch {
            try {
                graph.computers.stop(computer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // GitHub still stops it when idle; the app shows the computer's real state when opened.
            }
            end()
        }
    }

    private fun end() {
        graph.computerPage.releaseIfHidden()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ongoing(): Notification {
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ComputerService::class.java).setAction(ACTION_STOP_COMPUTER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Channels.COMPUTER)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle(getString(R.string.computer_on_title))
            .setContentText(getString(R.string.computer_on_text, title))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.computer_stop), stop)
            .build()
    }

    private fun notifyStopped() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notice = NotificationCompat.Builder(this, Channels.COMPUTER)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle(getString(R.string.computer_stopped_title))
            .setContentText(getString(R.string.computer_stopped_text, title))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NotificationIds.COMPUTER_STOPPED, notice)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN,
        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_COMPUTER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val ACTION_STAY = "com.pocketide.computer.STAY"
        private const val ACTION_STOP_COMPUTER = "com.pocketide.computer.STOP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TITLE = "title"
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val CHECK_EVERY_MS = 60_000L

        /** Starts (or retargets) the service; called from the computer screen, while the app is in front. */
        fun stayConnected(context: Context, computer: Computer) {
            val intent = Intent(context, ComputerService::class.java)
                .setAction(ACTION_STAY)
                .putExtra(EXTRA_NAME, computer.name)
                .putExtra(EXTRA_TITLE, computer.repo.name)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Ends the service without touching the computer. */
        fun disconnect(context: Context) {
            context.stopService(Intent(context, ComputerService::class.java))
        }
    }
}
