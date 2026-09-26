package com.pocketide.linux

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pocketide.agents.runInForeground
import com.pocketide.github.createPublicReleases
import com.pocketide.graph
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The computer's daily upkeep, on Wi-Fi: Ubuntu's security fixes, and code-server moved to its
 * newest stable release ([CodeServerChannel]), or at least to the version this app pins, so the
 * agents' extensions never outgrow it between app updates. Nothing runs while the phone asks for
 * no heavy work (low battery, heat), and a code-server switch waits until no agent is open.
 */
class ComputerUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val computer = graph.computer
        if (computer.state.value !is ComputerState.Ready) return Result.success()
        if (!graph.limiter.canStartHeavyWork("Computer updates").allowed) return Result.retry()
        // Past ten minutes a job needs the foreground; refused, the next run continues the download.
        runInForeground("Updating the computer", "Installing Ubuntu's security fixes and the newest code-server.")
        val outcomes = listOf(computer.updateBase(), computer.updateCodeServer(codeServerTarget()))
        val again = outcomes.any { it is UpdateOutcome.Failed || it is UpdateOutcome.Waiting }
        return if (again && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    /** The newest stable code-server when GitHub can say, else the pinned one. */
    private suspend fun codeServerTarget(): CodeServerPin {
        val floor = LinuxPins.codeServer
        val latest = try {
            createPublicReleases().latest(CodeServerChannel.REPOSITORY)
        } catch (_: IOException) {
            return floor
        }
        return CodeServerChannel.newer(latest, floor) ?: floor
    }

    companion object {
        private const val NAME = "pocketide.computer.update"
        private const val MAX_ATTEMPTS = 3

        /** Keeps one daily job, with this version's rules (UPDATE: an app update may change them); calling it again changes nothing. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<ComputerUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
