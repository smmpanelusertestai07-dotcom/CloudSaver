package app.cloudsaver

import android.app.Application
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.engine.ActivityLog
import app.cloudsaver.engine.StartupRecovery
import app.cloudsaver.util.DeviceTier
import app.cloudsaver.util.FirstFrame
import app.cloudsaver.util.Notifications
import app.cloudsaver.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CloudSaverApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Before any window exists: the first frame follows the theme the
        // person chose, not the phone's night setting (FirstFrame).
        FirstFrame.apply(this)
        Notifications.createChannels(this)
        // WorkManager persists across boots; re-enqueue defensively (KEEP/UPDATE).
        appScope.launch {
            // Recovery first: after clear-data or a reinstall the database is
            // empty and the hidden snapshot is the only state there is, so it
            // has to be back before anything schedules work against it.
            runCatching { StartupRecovery(this@CloudSaverApp).run() }
                .onSuccess { result ->
                    if (result.removedPlaceholders > 0) {
                        OptionsRepo.get(this@CloudSaverApp)
                            .setBool(OptionsRepo.K.PLACEHOLDER_REMOVED, true)
                    }
                    // Rebuilding state from a snapshot is the least visible
                    // thing the app ever does and the one people most need to
                    // know happened.
                    if (result.restoredItems > 0) {
                        ActivityLog(this@CloudSaverApp).record(
                            ActivityLog.Kind.RECOVERED,
                            count = result.restoredItems
                        )
                    }
                }
            val options = OptionsRepo.get(this@CloudSaverApp).current()
            Scheduler.ensure(this@CloudSaverApp, options)
        }
    }

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
