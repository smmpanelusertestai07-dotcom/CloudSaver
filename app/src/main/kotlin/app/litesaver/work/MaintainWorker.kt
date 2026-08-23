package app.litesaver.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.litesaver.engine.MaintainEngine
import app.litesaver.util.LiteLog

/** Hourly bookkeeping pass; no constraints, designed to finish in seconds. */
class MaintainWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        try {
            MaintainEngine(applicationContext).run()
        } catch (e: Exception) {
            LiteLog.log(applicationContext, "maintain", "worker failed: ${e.message}")
        }
        val tookMs = System.currentTimeMillis() - started
        if (tookMs > 10_000) {
            LiteLog.log(applicationContext, "maintain", "slow pass: ${tookMs}ms")
        }
        return Result.success()
    }
}
