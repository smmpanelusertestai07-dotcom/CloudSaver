package app.cloudsaver.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.cloudsaver.data.prefs.OptionsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the "Mute for 7 days" button on an alert.
 *
 * An alert the user cannot silence from the alert itself is one they silence
 * by turning off notifications altogether - and then they miss the one that
 * mattered.
 */
class AlertActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_MUTE) return
        val app = context.applicationContext
        val until = System.currentTimeMillis() + Notifications.MUTE_MS
        // The receiver's own lifetime ends with onReceive, so the write is
        // held open by goAsync until DataStore has committed it.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Nothing thrown here may escape: this coroutine has no parent
                // to hand a failure to, so an unwritable DataStore would take
                // the whole process down from a notification button. A write
                // that did not happen leaves the alert exactly as it was,
                // which is the honest outcome - so the alert is only taken
                // down once the value is actually stored.
                val muted = runCatching {
                    OptionsRepo.get(app).setLong(OptionsRepo.K.ALERTS_MUTED_UNTIL, until)
                }.isSuccess
                // Only the alerts: the "working" note belongs to a run in
                // progress, and muting warnings is not stopping the work.
                if (muted) Notifications.clearAlerts(app)
            } finally {
                pending.finish()
            }
        }
    }
}
