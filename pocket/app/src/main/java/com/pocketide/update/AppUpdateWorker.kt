package com.pocketide.update

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pocketide.R
import com.pocketide.agents.AgentNotices
import com.pocketide.core.Channels
import com.pocketide.core.NotificationIds
import com.pocketide.graph
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The daily look at PocketIDE's releases, on Wi-Fi. A newer version is downloaded and checked
 * in the background, and one notification says it is ready; installing is always the owner's tap.
 */
class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updater = applicationContext.graph.updater
        updater.check()
        if (updater.state.value is UpdateState.Available) {
            try {
                updater.download()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (waiting: IOException) {
                return retry()
            }
        }
        return when (val state = updater.state.value) {
            is UpdateState.Ready -> {
                UpdateNotices.readyOnce(applicationContext, state.release)
                Result.success()
            }
            is UpdateState.Failed -> retry()
            else -> Result.success()
        }
    }

    private fun retry(): Result = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()

    companion object {
        private const val NAME = "pocketide.app.update"
        private const val MAX_ATTEMPTS = 3

        /** Keeps one daily job; calling it again changes nothing. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** "PocketIDE <version> is ready to install", posted once per version. */
internal object UpdateNotices {
    private const val NOTIFICATION_ID = NotificationIds.APP_UPDATE
    private const val PREFS = "pocketide.update"
    private const val NOTIFIED = "notified_version"

    fun readyOnce(context: Context, release: AppRelease) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(NOTIFIED, null) == release.version) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val text = "Its signature matches this app. Open Settings in PocketIDE and tap Install."
        val notification = NotificationCompat.Builder(context, Channels.AGENTS)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle("PocketIDE ${release.version} is ready to install")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(AgentNotices.openApp(context))
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            prefs.edit().putString(NOTIFIED, release.version).apply()
        } catch (denied: SecurityException) {
            // Notifications were turned off in the meantime; Settings still offers the update.
        }
    }
}
