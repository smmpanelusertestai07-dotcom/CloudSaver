package app.cloudsaver.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
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
                OptionsRepo.get(app).setLong(OptionsRepo.K.ALERTS_MUTED_UNTIL, until)
                runCatching { NotificationManagerCompat.from(app).cancelAll() }
                AppLog.log(app, "alerts", "muted for 7 days")
            } finally {
                pending.finish()
            }
        }
    }
}
