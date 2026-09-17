package app.cloudsaver.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.cloudsaver.engine.MaintainEngine

/** Hourly bookkeeping pass; no constraints, designed to finish in seconds. */
class MaintainWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            MaintainEngine(applicationContext).run()
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            // WorkManager stopped us; report that, do not claim success.
            throw ce
        } catch (e: Exception) {
        }
        return Result.success()
    }
}
