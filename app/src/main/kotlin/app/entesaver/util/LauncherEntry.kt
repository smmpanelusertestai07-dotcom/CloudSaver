package app.entesaver.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Ente Saver's home-screen entry, under the one name, Ente Saver.
 *
 * Versions 11.0 to 12.2 let the person pick another home-screen name, each
 * its own launcher entry that switched the main one off. The choice is gone,
 * but Android keeps a component's on/off state across an update, so an
 * install that made it still opens through that entry. The manifest keeps
 * those entries declared, off by default, reading Ente Saver with the Ente
 * Saver icon, and nothing switches them: the person's home-screen icon stays
 * where they put it, and no timing of the update can leave a phone without
 * an icon to open the app by.
 */
object LauncherEntry {

    /**
     * The main entry's full class name, exactly as the manifest declares it.
     * Launchers keep home-screen icons and shortcuts by it, so it never
     * changes; it is in the app's permanent id, not the code's package.
     */
    const val COMPONENT = "app.cloudsaver.MainActivity"

    /** The entries of the names 11.0-12.2 offered; off unless one was picked. */
    val OLD_NAMES = listOf(
        "app.entesaver.AliasStorageSaver",
        "app.cloudsaver.AliasSaver",
        "app.entesaver.AliasPhotoSaver"
    )

    /** Where 11.0-12.2 kept a chosen name waiting to be applied. */
    private const val OLD_LOOKS_PREFS = "looks"

    /**
     * A safety net, at every start: should no entry be on at all, the main
     * one comes back. An install that opens through an old name's entry
     * keeps it; switching would take its icon off the home screen.
     */
    fun ensureVisible(context: Context) {
        val pm = context.packageManager
        runCatching {
            val main = ComponentName(context.packageName, COMPONENT)
            val mainState = pm.getComponentEnabledSetting(main)
            val mainOn = mainState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                mainState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val oldOn = OLD_NAMES.any {
                pm.getComponentEnabledSetting(ComponentName(context.packageName, it)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            if (!mainOn && !oldOn) {
                pm.setComponentEnabledSetting(
                    main,
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
        runCatching { context.deleteSharedPreferences(OLD_LOOKS_PREFS) }
    }
}
