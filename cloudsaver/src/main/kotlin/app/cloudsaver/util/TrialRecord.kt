package app.cloudsaver.util

import android.content.Context
import androidx.core.content.edit

/**
 * Which files the "try it on a few photos" card made copies of.
 *
 * Kept on disk, not in the view model. The card and its Remove button used to
 * live only in memory, so the phone closing the app in the background lost
 * both - while the copies themselves stayed inside the app, now with no
 * button anywhere that could remove them. Private to the app, never in a
 * backup: these are row ids, and they mean nothing on another phone.
 */
object TrialRecord {

    private const val PREFS = "trial"
    private const val KEY_IDS = "ids"

    fun read(context: Context): Set<Long> = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }.getOrDefault(emptySet())

    fun write(context: Context, ids: Set<Long>) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                if (ids.isEmpty()) remove(KEY_IDS) else putStringSet(KEY_IDS, ids.map { it.toString() }.toSet())
            }
        }
    }
}
