package com.pocketide.lock

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pocketide.graph
import java.util.concurrent.TimeUnit

/**
 * Asks GitHub and Drive every 15 minutes (WorkManager's shortest period) whether access still
 * stands. It needs a network: offline never locks, so there is nothing to learn without one.
 */
class AccessCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        applicationContext.graph.access.check()
        return Result.success()
    }

    companion object {
        private const val NAME = "pocketide.access-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AccessCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
