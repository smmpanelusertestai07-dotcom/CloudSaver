package com.pocketide.ide

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pocketide.MainActivity
import com.pocketide.R
import com.pocketide.core.Channels
import com.pocketide.core.NotificationIds
import com.pocketide.graph
import kotlinx.coroutines.launch

/**
 * Keeps PocketIDE running while its computer is on, so Android does not end the agents' work when
 * the owner switches to another app or turns the screen off. An ongoing notification says so,
 * with a Stop button. The computer's programs are this app's own child processes: without this
 * service Android would stop them with the app.
 */
class ComputerService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_KEEP -> keep()
            ACTION_STOP -> stopComputer()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun keep() {
        // Android 14 names the kind of work; older versions take the manifest's word for it.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NotificationIds.COMPUTER_ON, ongoing(), type)
    }

    private fun stopComputer() {
        lifecycleScope.launch {
            graph.stopEverything()
            end()
        }
    }

    private fun end() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ongoing(): Notification {
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ComputerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Channels.COMPUTER)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle(getString(R.string.computer_on_title))
            .setContentText(getString(R.string.computer_on_text))
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.computer_stop), stop)
            .build()
    }

    companion object {
        private const val ACTION_KEEP = "com.pocketide.computer.KEEP"
        private const val ACTION_STOP = "com.pocketide.computer.STOP"
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2

        /** Starts the service; called when code-server starts, which the owner does with the app in front. */
        fun keep(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ComputerService::class.java).setAction(ACTION_KEEP))
        }

        /** Ends the service; the computer has already stopped. */
        fun release(context: Context) {
            context.stopService(Intent(context, ComputerService::class.java))
        }

        fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
