package app.entesaver

import android.app.Application
import android.text.format.DateFormat
import androidx.work.Configuration
import app.entesaver.data.prefs.OptionsRepo
import app.entesaver.engine.ActivityLog
import app.entesaver.engine.StartupRecovery
import app.entesaver.util.DeviceTier
import app.entesaver.util.FirstFrame
import app.entesaver.util.Formats
import app.entesaver.util.Notifications
import app.entesaver.work.Scheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EnteSaverApp : Application(), Configuration.Provider {

    /**
     * Start-up runs in every process, background wakes included. On a full or
     * damaged phone a settings or database write can throw here, and an
     * uncaught throw would end every start before the person could get in to
     * free space. A failed step is dropped instead: nothing was scheduled or
     * deleted on its account, and the next start tries again.
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> }
    )

    override fun onCreate() {
        super.onCreate()
        // Before any window exists: the first frame follows the theme the
        // person chose, not the phone's night setting (FirstFrame).
        FirstFrame.apply(this)
        Formats.clock24 = DateFormat.is24HourFormat(this)
        Notifications.createChannels(this)
        // WorkManager persists across boots; re-enqueue defensively (KEEP/UPDATE).
        appScope.launch {
            // Recovery first: after clear-data or a reinstall the database is
            // empty and the hidden snapshot is the only state there is, so it
            // has to be back before anything schedules work against it.
            runCatching { StartupRecovery(this@EnteSaverApp).run() }
                .onSuccess { result ->
                    if (result.removedPlaceholders > 0) {
                        OptionsRepo.get(this@EnteSaverApp)
                            .setBool(OptionsRepo.K.PLACEHOLDER_REMOVED, true)
                    }
                    // Rebuilding state from a snapshot is the least visible
                    // thing the app ever does and the one people most need to
                    // know happened.
                    if (result.restoredItems > 0) {
                        ActivityLog(this@EnteSaverApp).record(
                            ActivityLog.Kind.RECOVERED,
                            count = result.restoredItems
                        )
                    }
                }
            val options = OptionsRepo.get(this@EnteSaverApp).current()
            Scheduler.ensure(this@EnteSaverApp, options)
        }
    }

    /**
     * WorkManager starts on first use with this configuration (the library's
     * own start-up hook is removed in the manifest).
     *
     * When internal storage is full or damaged, WorkManager cannot open or
     * tidy its own database and, with no handler, throws on its own thread -
     * a crash a second after every start, on exactly the phones this app is
     * for. Background work just waits until storage recovers instead. The
     * scheduling handler covers phones whose job limit or system service
     * refuses a job; the work is enqueued again on the next start.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setInitializationExceptionHandler { }
            .setSchedulingExceptionHandler { }
            .build()

    /**
     * Android says memory is about to run out while the app is working:
     * photos are decoded smaller for the rest of this process (DeviceTier),
     * rather than the system ending the app halfway through one.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && level < TRIM_MEMORY_UI_HIDDEN) {
            DeviceTier.memoryCritical = true
        }
    }
}
