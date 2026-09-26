package com.pocketide.agents

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pocketide.R
import com.pocketide.core.Channels
import com.pocketide.core.NotificationIds
import com.pocketide.graph
import com.pocketide.linux.ComputerState
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The weekly search of Open VSX for new agents, on Wi-Fi. It only offers; nothing is installed. */
class AgentDiscoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        applicationContext.graph.agents.discover()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: IOException) {
        if (runAttemptCount < AgentWorkers.MAX_ATTEMPTS) Result.retry() else Result.success()
    }
}

/**
 * Installs agents that are missing and moves each to its newest compatible version, with the
 * doctor and rollback. A room in use is left alone until the next run.
 */
class AgentUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        if (graph.computer.state.value != ComputerState.Ready) return Result.success()
        if (!graph.limiter.canStartHeavyWork("Agent updates").allowed) return Result.retry()
        // Refused from the background, the job keeps Android's ten minutes: a download cut
        // off there is continued by the next run, not started over.
        runInForeground("Updating agents", "Downloading and checking the newest agent versions.")
        return try {
            graph.agents.updateAll()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: IOException) {
            if (runAttemptCount < AgentWorkers.MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (failed: IllegalStateException) {
            Result.success()
        }
    }
}

/**
 * Moves a long update job (hundreds of MB, then minutes of install) into the foreground, where
 * Android does not stop it after ten minutes. False when Android refuses (a start from the
 * background on Android 12+ while PocketIDE has battery restrictions).
 */
internal suspend fun CoroutineWorker.runInForeground(title: String, text: String): Boolean {
    val notification = NotificationCompat.Builder(applicationContext, Channels.AGENTS)
        .setSmallIcon(R.drawable.ic_stat_pocketide)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        .build()
    return try {
        setForeground(ForegroundInfo(NotificationIds.UPDATE_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalStateException) {
        false
    }
}

/** Start-up and set-up call these; each keeps one job, so calling again changes nothing. */
object AgentWorkers {
    internal const val MAX_ATTEMPTS = 3
    private const val DISCOVERY = "pocketide.agents.discovery"
    private const val UPDATES = "pocketide.agents.updates"
    private const val INSTALL_NOW = "pocketide.agents.install"

    /** Weekly discovery and daily agent updates, both on unmetered networks with battery to spare. */
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        val discovery = PeriodicWorkRequestBuilder<AgentDiscoveryWorker>(7, TimeUnit.DAYS)
            .setConstraints(unmetered())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        manager.enqueueUniquePeriodicWork(DISCOVERY, ExistingPeriodicWorkPolicy.UPDATE, discovery)
        val updates = PeriodicWorkRequestBuilder<AgentUpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(unmetered(storage = true))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
            .build()
        manager.enqueueUniquePeriodicWork(UPDATES, ExistingPeriodicWorkPolicy.UPDATE, updates)
    }

    /**
     * Installs the agents now, once the computer is set up (Wi-Fi by default, as the data
     * rules say: on mobile data the download waits and the job tries again).
     */
    fun installNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AgentUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(INSTALL_NOW, ExistingWorkPolicy.KEEP, request)
    }

    private fun unmetered(storage: Boolean = false): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(storage)
        .build()
}
