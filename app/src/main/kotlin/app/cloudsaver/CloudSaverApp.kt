package app.cloudsaver

import android.app.Application
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.engine.ActivityLog
import app.cloudsaver.engine.StartupRecovery
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
}
