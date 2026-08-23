package app.litesaver

import android.app.Application
import app.litesaver.data.prefs.OptionsRepo
import app.litesaver.util.Notifications
import app.litesaver.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LiteSaverApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // WorkManager persists across boots; re-enqueue defensively (KEEP/UPDATE).
        appScope.launch {
            val options = OptionsRepo.get(this@LiteSaverApp).current()
            Scheduler.ensure(this@LiteSaverApp, options)
        }
    }
}
