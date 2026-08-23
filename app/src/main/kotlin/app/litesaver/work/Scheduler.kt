package app.litesaver.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.litesaver.core.logic.Speed
import app.litesaver.data.prefs.Options
import java.util.concurrent.TimeUnit

/**
 * WorkManager wiring. WorkManager persists across reboots; Application.onCreate
 * re-enqueues with KEEP/UPDATE so nothing is ever lost (no boot receiver needed).
 */
object Scheduler {

    private const val W_COMPRESS = "litesaver.compress"
    private const val W_MAINTAIN = "litesaver.maintain"
    private const val W_INSTANT = "litesaver.instant"
    private const val W_NOW = "litesaver.now"
    private const val W_MAINTAIN_NOW = "litesaver.maintain.now"

    fun ensure(context: Context, options: Options) {
        val wm = WorkManager.getInstance(context)

        val constraints = when (options.speed) {
            Speed.CHARGING_ONLY -> Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            else -> Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
        }
        val compress = PeriodicWorkRequestBuilder<CompressWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(W_COMPRESS, ExistingPeriodicWorkPolicy.UPDATE, compress)

        val maintain = PeriodicWorkRequestBuilder<MaintainWorker>(60, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(W_MAINTAIN, ExistingPeriodicWorkPolicy.KEEP, maintain)

        if (options.speed == Speed.INSTANT) {
            enqueueInstant(context)
        } else {
            wm.cancelUniqueWork(W_INSTANT)
        }
    }

    /** INSTANT mode: re-armed after every run (content triggers are one-shot). */
    fun enqueueInstant(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(10, TimeUnit.MINUTES)
            .build()
        val request = OneTimeWorkRequestBuilder<CompressWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(W_INSTANT, ExistingWorkPolicy.REPLACE, request)
    }

    /** "Run now" button: one compress pass without charging constraints. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CompressWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(W_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun maintainNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MaintainWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(W_MAINTAIN_NOW, ExistingWorkPolicy.KEEP, request)
    }
}
