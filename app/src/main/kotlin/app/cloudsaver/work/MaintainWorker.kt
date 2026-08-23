package app.cloudsaver.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.cloudsaver.engine.MaintainEngine
import app.cloudsaver.util.AppLog

/** Hourly bookkeeping pass; no constraints, designed to finish in seconds. */
class MaintainWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        try {
            MaintainEngine(applicationContext).run()
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            // WorkManager stopped us; report that, do not claim success.
            throw ce
        } catch (e: Exception) {
            AppLog.log(applicationContext, "maintain", "worker failed: ${e.message}")
        }
        val tookMs = System.currentTimeMillis() - started
        if (tookMs > 10_000) {
            AppLog.log(applicationContext, "maintain", "slow pass: ${tookMs}ms")
        }
        return Result.success()
    }
}
