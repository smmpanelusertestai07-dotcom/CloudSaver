package app.entesaver.util

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Ente Saver's one home-screen entry: the launcher alias every install has
 * had since version 1, under the one name, Ente Saver.
 *
 * Versions 11.0 to 12.2 let the person pick another home-screen name, each
 * its own launcher alias, and picking one switched this entry off. Those
 * aliases are gone, so an install that had picked one would be left with no
 * icon to open it by. [ensureVisible] switches this entry back on - at every
 * start, and right after the update ([Updated]), before anyone looks for it.
 */
object LauncherEntry {

    /**
     * The alias's full class name, exactly as the manifest declares it.
     * Launchers keep home-screen icons and shortcuts by it, so it never
     * changes; it is in the app's permanent id, not the code's package.
     */
    const val COMPONENT = "app.cloudsaver.MainActivity"

    /** Where 11.0-12.2 kept a chosen name waiting to be applied. */
    private const val OLD_LOOKS_PREFS = "looks"

    fun ensureVisible(context: Context) {
        val pm = context.packageManager
        val entry = ComponentName(context.packageName, COMPONENT)
        runCatching {
            if (pm.getComponentEnabledSetting(entry) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                pm.setComponentEnabledSetting(
                    entry,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
        runCatching { context.deleteSharedPreferences(OLD_LOOKS_PREFS) }
    }

    /**
     * Android's word that this app was just updated. It comes before anyone
     * opens the app - which, with the icon switched off by an old choice of
     * name, they could not.
     */
    class Updated : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ensureVisible(context)
        }
    }
}
