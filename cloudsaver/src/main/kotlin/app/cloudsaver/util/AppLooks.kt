package app.cloudsaver.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import app.cloudsaver.R

/**
 * The name Ente Saver wears on the home screen: "Ente Saver", or "CloudSaver"
 * - the name earlier versions had, for anyone who knows it by that - always
 * with the one Ente Saver icon.
 *
 * Each look is a launcher alias in the manifest; exactly one is switched on.
 * A switch is not made the moment it is chosen: turning off the alias the
 * app was opened from can close the app under the person's finger, so the
 * choice is remembered and applied when the app goes to the background
 * ([applyPending]). Launchers then take a moment to redraw the icon.
 */
object AppLooks {

    enum class Look(val alias: String, val nameRes: Int) {
        ENTE_SAVER(".MainActivity", R.string.app_name),
        CLOUDSAVER(".AliasSaver", R.string.app_name_classic)
    }

    /** The look every install starts with: the one alias enabled in the manifest. */
    val DEFAULT = Look.ENTE_SAVER

    private const val PREFS = "looks"
    private const val KEY_PENDING = "pending"

    private fun component(context: Context, look: Look) =
        ComponentName(context.packageName, context.packageName + look.alias)

    /**
     * Makes sure the app has a home-screen icon at all. If every alias is
     * switched off - a switch cut short by the app being stopped half-way,
     * or a look from an older build that no longer exists - the default one
     * is switched back on. Cheap: two package-manager reads at start-up.
     */
    fun ensureVisible(context: Context) {
        if (Look.entries.any { isOn(context, it) }) return
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component(context, DEFAULT),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    /**
     * Whether [look]'s alias is on the home screen: switched on, or left at
     * the manifest's own state, which is on only for the default one.
     */
    private fun isOn(context: Context, look: Look): Boolean =
        when (runCatching { context.packageManager.getComponentEnabledSetting(component(context, look)) }.getOrNull()) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> look == DEFAULT
            else -> false
        }

    /** The look the home screen shows now (before any pending switch). */
    fun current(context: Context): Look = Look.entries.firstOrNull { isOn(context, it) } ?: DEFAULT

    /** The look that will show after the app next goes to the background. */
    fun chosen(context: Context): Look = pending(context) ?: current(context)

    fun choose(context: Context, look: Look) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (look == current(context)) remove(KEY_PENDING) else putString(KEY_PENDING, look.name)
        }
    }

    private fun pending(context: Context): Look? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, null)
            ?.let { name -> Look.entries.firstOrNull { it.name == name } }

    /**
     * Switches to the chosen look, if one is waiting. The new alias is turned
     * on before the old one goes, so there is never a moment with no icon.
     */
    fun applyPending(context: Context) {
        val target = pending(context) ?: return
        val pm = context.packageManager
        val ok = runCatching {
            pm.setComponentEnabledSetting(
                component(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            for (other in Look.entries) {
                if (other == target) continue
                pm.setComponentEnabledSetting(
                    component(context, other),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }.isSuccess
        if (ok) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_PENDING) }
        }
    }
}
