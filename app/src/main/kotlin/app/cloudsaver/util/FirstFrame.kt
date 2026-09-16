package app.cloudsaver.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import app.cloudsaver.core.logic.ThemeMode

/**
 * The first frame the app draws follows the theme the person chose, not the
 * phone's night setting.
 *
 * The window's background comes from the activity theme, which is DayNight:
 * it follows the phone. Everything the app draws follows the app's own
 * setting. So someone who set CloudSaver to Dark on a phone in Light mode
 * got a white window for one frame and then a dark screen painted over it,
 * on every open - the kind of flash that reads as a glitch. The setting
 * lives in DataStore, which cannot be read before the first frame without
 * blocking, so the choice is mirrored into a plain preference whenever it
 * changes and read back at process start, before any window exists. The
 * night mode is set only then: set while a screen is up it would recreate
 * the activity, and Compose already repaints the theme live.
 *
 * The sister project in this repository fixed the same flash the same way.
 */
object FirstFrame {

    private const val PREFS = "first_frame"
    private const val KEY_THEME = "theme"

    /** Remember the choice for the next process start. Cheap; writes only on change. */
    fun remember(context: Context, mode: ThemeMode) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_THEME, null) != mode.name) {
                prefs.edit().putString(KEY_THEME, mode.name).apply()
            }
        }
    }

    /** Called once, at process start, before any activity is created. */
    fun apply(context: Context) {
        val stored = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)
        }.getOrNull() ?: return
        val mode = runCatching { ThemeMode.valueOf(stored) }.getOrNull() ?: return
        AppCompatDelegate.setDefaultNightMode(nightMode(mode))
    }

    /** The app's three choices as AppCompat's night modes; SYSTEM stays with the phone. */
    fun nightMode(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }
}
