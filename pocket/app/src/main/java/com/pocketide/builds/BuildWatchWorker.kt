package com.pocketide.builds

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketide.R
import com.pocketide.core.Channels
import com.pocketide.core.NotificationIds
import com.pocketide.graph
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Follows one dispatched run and posts "Build finished" or "Build failed" when it ends. Each
 * check is a short job that schedules the next one, so nothing waits in the background for the
 * length of a build.
 */
class BuildWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT) ?: return Result.success()
        val runId = inputData.getLong(KEY_RUN, 0L).takeIf { it > 0 } ?: return Result.success()
        val title = inputData.getString(KEY_TITLE) ?: "Build"
        val check = inputData.getInt(KEY_CHECK, 0)
        val graph = applicationContext.graph
        val project = graph.projects.loaded().firstOrNull { it.id == projectId } ?: return Result.success()
        val run = try {
            graph.gitHub.run(project.owner, project.repo, runId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        when {
            run?.status == "completed" -> BuildNotices.post(applicationContext, runId, title, "${project.owner}/${project.repo}", run.conclusion)
            check + 1 < MAX_CHECKS -> watch(applicationContext, projectId, runId, title, check + 1)
        }
        return Result.success()
    }

    companion object {
        private const val KEY_PROJECT = "project"
        private const val KEY_RUN = "run"
        private const val KEY_TITLE = "title"
        private const val KEY_CHECK = "check"
        private const val CHECK_EVERY_MINUTES = 2L

        /** Two minutes apart: four hours, longer than any template's time limit. */
        private const val MAX_CHECKS = 120

        fun watch(context: Context, projectId: String, runId: Long, title: String, check: Int = 0) {
            val request = OneTimeWorkRequestBuilder<BuildWatchWorker>()
                .setInitialDelay(CHECK_EVERY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_PROJECT to projectId, KEY_RUN to runId, KEY_TITLE to title, KEY_CHECK to check))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("pocketide.build.$runId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Notifications on the "Builds" channel. Their text is PocketIDE's own, never a workflow's. */
internal object BuildNotices {
    fun post(context: Context, runId: Long, title: String, repo: String, conclusion: String?) {
        val text = when (conclusion) {
            "success" -> "$title finished on $repo. Bring the results into Media from the Builds tab."
            "cancelled" -> "$title was cancelled on $repo."
            else -> "$title failed on $repo. Open the run on GitHub to see why."
        }
        val heading = if (conclusion == "success") "Build finished" else "Build did not finish"
        notify(context, "build:$runId", NotificationIds.BUILD_ENDED, heading, text)
    }

    /** A scheduled task ended: one notice per task, replaced by that task's next one. */
    fun taskEnded(context: Context, taskId: String, heading: String, text: String) =
        notify(context, "task:$taskId", NotificationIds.SCHEDULED_TASK_ENDED, heading, text)

    /**
     * Posted under a fixed id from [NotificationIds] and a [tag] of its own, so notices of this
     * kind stand side by side and never replace another kind's, as a hashed id could.
     */
    private fun notify(context: Context, tag: String, id: Int, heading: String, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, Channels.BUILDS)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(tag, id, notification)
        } catch (_: SecurityException) {
            // The permission was withdrawn a moment ago.
        }
    }

    private fun openApp(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
