package com.pocketide.sync

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketide.R
import com.pocketide.core.Channels
import com.pocketide.core.NotificationIds
import com.pocketide.graph
import java.util.concurrent.TimeUnit

/**
 * The sync job: soon after a task ends (and every few minutes while agents run), as soon as a
 * network is back when something waits on the phone, and once an hour as a safety net, when an
 * idle phone stops before the network. It is a durable WorkManager job, so it finishes after a
 * reboot or a kill; a large upload moves it to the foreground so it is not cut off.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = applicationContext.graph.sync as? DriveSyncEngine ?: return Result.success()
        return when (engine.runScheduled(periodic = inputData.getBoolean(PERIODIC_KEY, false), onLargeUpload = ::promote)) {
            WorkResult.OK -> Result.success()
            WorkResult.RETRY -> Result.retry()
        }
    }

    /** Needed for expedited work before Android 12, where it runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = SyncForeground.info(applicationContext)

    private suspend fun promote() {
        try {
            setForeground(getForegroundInfo())
        } catch (_: IllegalStateException) {
            // Android refused a foreground start from the background; the upload resumes next run.
        }
    }

    internal companion object {
        /** Marks the hourly run, which stops early when there is nothing to do. */
        const val PERIODIC_KEY = "periodic"
    }
}

/** The daily job: Recently deleted, retention, phone clean-up, re-encryption, orphan sweep. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = applicationContext.graph.sync as? DriveSyncEngine ?: return Result.success()
        return when (engine.runMaintenance()) {
            WorkResult.OK -> Result.success()
            WorkResult.RETRY -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = SyncForeground.info(applicationContext)
}

internal object SyncForeground {
    private const val ID = NotificationIds.SYNC_RUNNING

    fun info(context: Context): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, Channels.SYNC)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle("Syncing chats")
            .setContentText("Encrypted chats are going to your Google Drive.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        return ForegroundInfo(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

internal fun openApp(context: Context): PendingIntent? {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

internal class WorkScheduler(private val context: Context) : SyncScheduling {
    private val work get() = WorkManager.getInstance(context)

    override fun requestSoon() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
    }

    override fun requestWhenOnline() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(ONLINE, ExistingWorkPolicy.KEEP, request)
    }

    override fun requestMaintenance() {
        work.enqueueUniqueWork(MAINTAIN_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MaintenanceWorker>().build())
    }

    /**
     * Real work asks for its own sync (see [requestSoon] and [requestWhenOnline]), so the periodic
     * run is a safety net: hourly, and stopping before the network when there is nothing to do.
     */
    override fun schedulePeriodic() {
        val sync = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(online())
            .setInputData(workDataOf(SyncWorker.PERIODIC_KEY to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // UPDATE, so a request made by an older version takes this period and input.
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, sync)
        val daily = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, daily)
    }

    override fun cancelAll() {
        listOf(SOON, ONLINE, MAINTAIN_NOW, PERIODIC, DAILY).forEach(work::cancelUniqueWork)
    }

    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private companion object {
        const val SOON = "pocketide.sync.soon"
        const val ONLINE = "pocketide.sync.online"
        const val MAINTAIN_NOW = "pocketide.maintenance.now"
        const val PERIODIC = "pocketide.sync.periodic"
        const val DAILY = "pocketide.maintenance.daily"
        const val BACKOFF_SECONDS = 30L
    }
}

/** Notifications on the "Sync and storage" channel. */
internal class AndroidSyncNotifier(private val context: Context) : SyncNotifier {
    override fun post(notice: Notice) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification: Notification = NotificationCompat.Builder(context, Channels.SYNC)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(SyncNoticeIds.of(notice.key), notification)
        } catch (_: SecurityException) {
            // The permission was withdrawn a moment ago; the status in the app says the same.
        }
    }
}

/** Each kind of sync notice has its own id in [NotificationIds]' sync range, so one kind replaces only itself. */
internal object SyncNoticeIds {
    val KEYS = listOf("lease", "google-full", "share-full", "keep", "trim", "phone-80", "phone-90", "computer", "drive")

    /** A kind missing from [KEYS] shares the range's last id rather than another notice's. */
    fun of(key: String): Int {
        val i = KEYS.indexOf(key)
        return if (i >= 0) NotificationIds.SYNC_FIRST + i else NotificationIds.SYNC_LAST
    }
}
