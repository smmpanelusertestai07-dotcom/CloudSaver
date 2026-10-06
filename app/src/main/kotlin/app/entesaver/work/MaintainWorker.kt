package app.entesaver.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.entesaver.data.prefs.OptionsRepo
import app.entesaver.engine.MaintainEngine
import app.entesaver.util.SpaceLimits

/** Hourly bookkeeping pass; no constraints, designed to finish in seconds. */
class MaintainWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            // Releases and clean-up use the space limits, so Automatic ones
            // are brought up to date first.
            if (OptionsRepo.get(applicationContext).current().spaceAuto) {
                runCatching { SpaceLimits.refresh(applicationContext) }
            }
            MaintainEngine(applicationContext).run()
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            // WorkManager stopped us; report that, do not claim success.
            throw ce
        } catch (e: Exception) {
        }
        return Result.success()
    }
}
