package com.pocketide.lock

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pocketide.graph
import java.util.concurrent.TimeUnit

/**
 * Asks GitHub and Drive every [PERIOD_HOURS] hours whether access still stands, for a phone left
 * alone. A revoked account only matters before new work starts, and that is where the app asks:
 * when it opens or comes back to the front, with every sync that goes to the network, and before
 * a scheduled task runs. It needs a network: offline never locks, so there is nothing to learn
 * without one.
 */
class AccessCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        applicationContext.graph.access.check()
        return Result.success()
    }

    companion object {
        private const val NAME = "pocketide.access-check"
        const val PERIOD_HOURS = 6L

        fun request(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<AccessCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        /** UPDATE, so a phone that has an earlier version's 15-minute job takes this period. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request())
        }
    }
}
