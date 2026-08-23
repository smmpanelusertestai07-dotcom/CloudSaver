package app.cloudsaver

import android.app.Application
import app.cloudsaver.data.prefs.OptionsRepo
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
            val options = OptionsRepo.get(this@CloudSaverApp).current()
            Scheduler.ensure(this@CloudSaverApp, options)
        }
    }
}
