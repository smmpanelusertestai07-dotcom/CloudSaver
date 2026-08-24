package app.cloudsaver.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.cloudsaver.core.logic.SpeedMode
import app.cloudsaver.data.prefs.Options
import java.util.concurrent.TimeUnit

/**
 * WorkManager wiring (13.G). Constraints stay deliberately thin - only
 * "battery not low" (plus "charging" in Charging-only mode). Everything else
 * is decided at runtime by RunDecider so the app can explain its waiting.
 *
 * WorkManager persists across reboots; Application.onCreate re-enqueues with
 * KEEP/UPDATE so nothing is ever lost (no boot receiver needed).
 */
object Scheduler {

    private const val W_COMPRESS = "cloudsaver.compress"
    private const val W_MAINTAIN = "cloudsaver.maintain"
    private const val W_TRIGGER = "cloudsaver.trigger"
    private const val W_NOW = "cloudsaver.now"
    private const val W_MAINTAIN_NOW = "cloudsaver.maintain.now"

    fun ensure(context: Context, options: Options) {
        // Nothing runs until setup has been finished with an explicit tap.
        // Changing a setting mid-setup used to schedule the periodic work, so
        // the first backup could start before the person had seen which
        // folder it would write to.
        if (!options.onboardingDone) {
            cancelAll(context)
            return
        }
        val wm = WorkManager.getInstance(context)

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .apply { if (options.speed == SpeedMode.CHARGING_ONLY) setRequiresCharging(true) }
            .build()
        val compress = PeriodicWorkRequestBuilder<CompressWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(W_COMPRESS, ExistingPeriodicWorkPolicy.UPDATE, compress)

        // Maintenance (release, verify, lazy delete) is cheap and must run in
        // every mode, charging or not, budget or no budget.
        val maintain = PeriodicWorkRequestBuilder<MaintainWorker>(60, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(W_MAINTAIN, ExistingPeriodicWorkPolicy.KEEP, maintain)

        if (options.speed == SpeedMode.FAST) {
            enqueueContentTrigger(context)
        } else {
            wm.cancelUniqueWork(W_TRIGGER)
        }
    }

    /** FAST mode: react to new gallery items; re-armed after every run. */
    fun enqueueContentTrigger(context: Context) {
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
            .enqueueUniqueWork(W_TRIGGER, ExistingWorkPolicy.REPLACE, request)
    }

    /** "Run now": user-initiated, so it ignores mode, budget and screen state. */
    /** Used while setup is unfinished: leave no scheduled work behind. */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(W_COMPRESS)
        wm.cancelUniqueWork(W_MAINTAIN)
        wm.cancelUniqueWork(W_TRIGGER)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CompressWorker>()
            .setInputData(Data.Builder().putBoolean(CompressWorker.KEY_MANUAL, true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(W_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun maintainNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MaintainWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(W_MAINTAIN_NOW, ExistingWorkPolicy.KEEP, request)
    }
}
