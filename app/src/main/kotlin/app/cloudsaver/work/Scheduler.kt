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

    /**
     * Every unique work name this object enqueues under.
     *
     * Kept as one list so that cancelling "all" of them cannot drift out of
     * step with the set that exists, which is exactly what happened: two names
     * were added over time and the cancel was never widened to match.
     */
    private val ALL_WORK = listOf(
        W_COMPRESS, W_MAINTAIN, W_TRIGGER, W_NOW, W_MAINTAIN_NOW
    )

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

    /**
     * Leaves no work behind - all five names, not the three it used to cancel.
     *
     * "Optimise now" and "Maintain now" enqueue under their own unique names,
     * and neither was in this list. So a run the user started by tapping kept
     * going after setup was reset or the app was told to stop, which is the
     * one run a person is most likely to be watching. It also let one
     * instrumented test's tap finish compressing inside the next test, which
     * is how it was noticed.
     */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        for (name in ALL_WORK) wm.cancelUniqueWork(name)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CompressWorker>()
            .setInputData(Data.Builder().putBoolean(CompressWorker.KEY_MANUAL, true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(W_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * True while a compression run is executing, either the periodic one or
     * one the user started. Home shows progress instead of an action then -
     * offering "Optimise now" during a run invites a tap that does nothing.
     */
    fun runningFlow(context: Context): kotlinx.coroutines.flow.Flow<Boolean> {
        val wm = WorkManager.getInstance(context)
        return kotlinx.coroutines.flow.combine(
            wm.getWorkInfosForUniqueWorkFlow(W_COMPRESS),
            wm.getWorkInfosForUniqueWorkFlow(W_NOW)
        ) { periodic, manual ->
            (periodic + manual).any { it.state == androidx.work.WorkInfo.State.RUNNING }
        }
    }

    fun maintainNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MaintainWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(W_MAINTAIN_NOW, ExistingWorkPolicy.KEEP, request)
    }
}
