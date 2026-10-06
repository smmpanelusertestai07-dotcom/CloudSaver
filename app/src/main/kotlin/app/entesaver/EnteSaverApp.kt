package app.entesaver

import android.app.Application
import androidx.work.Configuration
import app.entesaver.data.prefs.OptionsRepo
import app.entesaver.engine.ActivityLog
import app.entesaver.engine.StartupRecovery
import app.entesaver.util.AppLooks
import app.entesaver.util.DeviceTier
import app.entesaver.util.FirstFrame
import app.entesaver.util.Notifications
import app.entesaver.work.Scheduler
import app.entesaver.work.WorkerNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EnteSaverApp : Application(), Configuration.Provider {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Before any window exists: the first frame follows the theme the
        // person chose, not the phone's night setting (FirstFrame).
        FirstFrame.apply(this)
        Notifications.createChannels(this)
        // WorkManager persists across boots; re-enqueue defensively (KEEP/UPDATE).
        appScope.launch {
            // Never a phone with Ente Saver installed and no icon to open it
            // by. Package-manager calls, so off the main thread.
            AppLooks.ensureVisible(this@EnteSaverApp)
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
     * own start-up hook is removed in the manifest), so work an older version
     * scheduled under its old class names still runs (WorkerNames).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(WorkerNames).build()

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
