package com.pocketide.schedule

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ExperimentalWorkRequestBuilderApi
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketide.R
import com.pocketide.core.Channels
import com.pocketide.core.NotificationIds
import com.pocketide.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Puts scheduled tasks into WorkManager and takes them out. */
internal interface TaskScheduler {
    /** [update] replaces a job already there (an edited task); otherwise an existing job is kept. */
    fun schedule(task: ScheduledTask, update: Boolean = true)
    fun cancel(taskId: String)
    fun runOnce(taskId: String, sessionId: String)

    /** The session of the task's "Run now" run still waiting or running in WorkManager, if any. */
    suspend fun queuedRun(taskId: String): String?
}

/**
 * The WorkManager side. Every run waits for the phone to be charging, on an unmetered network
 * (Wi-Fi) and with a battery that is not low, so a task never drains the battery or uses
 * mobile data.
 */
internal object ScheduleWork {
    const val KEY_TASK = "task"
    const val KEY_SESSION = "session"
    const val TAG = "pocketide.schedule"
    private const val PERIODIC = "pocketide.schedule."
    private const val ONCE = "pocketide.schedule.now."

    /** Tags a "Run now" request with its session: WorkInfo shows tags, not input data. */
    private const val SESSION_TAG = "pocketide.schedule.session:"

    /** A run Android stops (quota, a job that ran too long) waits this long before its note is written. */
    private const val INTERRUPTED_BACKOFF_MINUTES = 30L

    fun constraints(): Constraints = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    // setBackoffForSystemInterruptions is marked experimental in WorkManager 2.11.
    @OptIn(ExperimentalWorkRequestBuilderApi::class)
    fun periodic(task: ScheduledTask): PeriodicWorkRequest {
        require(task.everyHours >= 1) { "A task runs at most once an hour." }
        return PeriodicWorkRequestBuilder<ScheduledTaskWorker>(task.everyHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_TASK to task.id))
            .addTag(TAG)
            .setBackoffCriteria(BackoffPolicy.LINEAR, INTERRUPTED_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .setBackoffForSystemInterruptions()
            .build()
    }

    @OptIn(ExperimentalWorkRequestBuilderApi::class)
    fun once(taskId: String, sessionId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(KEY_TASK to taskId, KEY_SESSION to sessionId))
            .addTag(TAG)
            .addTag(SESSION_TAG + sessionId)
            .setBackoffCriteria(BackoffPolicy.LINEAR, INTERRUPTED_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .setBackoffForSystemInterruptions()
            .build()

    /** The session a "Run now" request was made for, from its tags. */
    fun sessionOf(tags: Set<String>): String? = tags.firstOrNull { it.startsWith(SESSION_TAG) }?.removePrefix(SESSION_TAG)

    class Manager(private val context: Context) : TaskScheduler {
        private val work get() = WorkManager.getInstance(context)

        override fun schedule(task: ScheduledTask, update: Boolean) {
            if (!task.enabled) return cancel(task.id)
            val policy = if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
            work.enqueueUniquePeriodicWork(PERIODIC + task.id, policy, periodic(task))
        }

        override fun cancel(taskId: String) {
            work.cancelUniqueWork(PERIODIC + taskId)
        }

        override fun runOnce(taskId: String, sessionId: String) {
            work.enqueueUniqueWork(ONCE + taskId, ExistingWorkPolicy.KEEP, once(taskId, sessionId))
        }

        override suspend fun queuedRun(taskId: String): String? =
            work.getWorkInfosForUniqueWorkFlow(ONCE + taskId).first()
                .firstOrNull { !it.state.isFinished }
                ?.let { sessionOf(it.tags) }
    }
}

/**
 * Runs one scheduled task in its agent's room, one run of a task at a time. A run that Android
 * cut off is not started again (WorkManager would, at once): it is ended in its own session.
 */
class ScheduledTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(ScheduleWork.KEY_TASK) ?: return Result.success()
        val schedules = applicationContext.graph.schedules as? TaskSchedules ?: return Result.success()
        schedules.load()
        val sessionId = inputData.getString(ScheduleWork.KEY_SESSION)
        val task = schedules.find(taskId)?.takeIf { it.enabled || sessionId != null } ?: return Result.success()
        val ran = schedules.exclusively(task.id) {
            // WorkManager counts each start; a second one in the same period follows a cut-off run.
            if (runAttemptCount > 0) {
                schedules.runner.endCutOff(task)
            } else {
                run(schedules.runner, task, sessionId)
            }
        }
        // A "Run now" that found the task already running says so in its own session.
        if (ran == null && sessionId != null) schedules.runner.endAsBusy(task, sessionId)
        return Result.success()
    }

    private suspend fun run(runner: ScheduledRun, task: ScheduledTask, sessionId: String?) {
        try {
            runner.run(task, sessionId, background = !promoted())
        } catch (refused: ScheduleException) {
            // Battery or heat said no: the next period tries again.
        }
    }

    /** Past ten minutes a job needs the foreground; false when Android refuses it. */
    private suspend fun promoted(): Boolean = try {
        setForeground(getForegroundInfo())
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Started from the background on Android 12+ while PocketIDE has battery restrictions.
        false
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, Channels.BUILDS)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle("Scheduled task running")
            .setContentText("An agent is working on a scheduled task while the phone charges.")
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(FOREGROUND_ID, notification)
        }
    }

    private companion object {
        const val FOREGROUND_ID = NotificationIds.SCHEDULED_RUN
    }
}
