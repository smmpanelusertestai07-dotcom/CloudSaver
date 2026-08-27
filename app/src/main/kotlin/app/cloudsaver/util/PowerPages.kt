package app.cloudsaver.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The background-work settings this phone actually has, and where they live.
 *
 * Every OEM breaks background work in its own way and hides the switch in its
 * own place, so "open app info and look around" is not an instruction anyone
 * can follow. Where the state can be read, it is read; where it cannot, the
 * app says so plainly rather than guessing, and still offers the one tap that
 * gets there.
 */
object PowerPages {

    enum class Vendor { COLOR_OS, MIUI, ONE_UI, VIVO, PIXEL, OTHER }

    /**
     * One thing the user may need to allow.
     *
     * [readable] is false when no public API reports the state; those are
     * shown as "check this" rather than as a problem, because claiming a
     * setting is off when it might be on trains people to ignore the app.
     */
    data class Requirement(
        val id: String,
        val readable: Boolean,
        val satisfied: Boolean
    )

    const val ID_BATTERY_UNRESTRICTED = "battery_unrestricted"
    const val ID_AUTO_LAUNCH = "auto_launch"
    const val ID_BACKGROUND_ACTIVITY = "background_activity"

    fun vendor(
        manufacturer: String = Build.MANUFACTURER,
        brand: String = Build.BRAND
    ): Vendor {
        val name = "${manufacturer.lowercase()} ${brand.lowercase()}"
        return when {
            listOf("oppo", "realme", "oneplus").any { it in name } -> Vendor.COLOR_OS
            listOf("xiaomi", "redmi", "poco").any { it in name } -> Vendor.MIUI
            "samsung" in name -> Vendor.ONE_UI
            listOf("vivo", "iqoo").any { it in name } -> Vendor.VIVO
            listOf("google", "pixel").any { it in name } -> Vendor.PIXEL
            else -> Vendor.OTHER
        }
    }

    /**
     * What to ask for on this phone. Battery optimisation is readable
     * everywhere; auto-launch and background activity are not readable on any
     * of these skins, so they are listed as unverifiable checks.
     */
    fun requirementsFor(vendor: Vendor, ignoringBatteryOptimizations: Boolean): List<Requirement> {
        val battery = Requirement(
            ID_BATTERY_UNRESTRICTED, readable = true, satisfied = ignoringBatteryOptimizations
        )
        val unverifiable = when (vendor) {
            Vendor.COLOR_OS -> listOf(ID_BACKGROUND_ACTIVITY, ID_AUTO_LAUNCH)
            Vendor.MIUI, Vendor.VIVO -> listOf(ID_AUTO_LAUNCH)
            Vendor.ONE_UI -> listOf(ID_BACKGROUND_ACTIVITY)
            Vendor.PIXEL, Vendor.OTHER -> emptyList()
        }
        return listOf(battery) + unverifiable.map {
            Requirement(it, readable = false, satisfied = false)
        }
    }

    /** Auto-launch / auto-start list, by skin. */
    private val AUTO_LAUNCH: Map<Vendor, List<ComponentName>> = mapOf(
        Vendor.COLOR_OS to listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            ComponentName(
                "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
        ),
        Vendor.MIUI to listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        ),
        Vendor.VIVO to listOf(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            ComponentName(
                "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            )
        ),
        Vendor.ONE_UI to listOf(
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity"
            )
        )
    )

    /**
     * Background-activity page. On ColorOS this is the app's own battery
     * screen, which is where "Allow background activity" lives.
     */
    fun openBackgroundActivity(context: Context): Boolean {
        val direct = when (vendor()) {
            Vendor.COLOR_OS -> listOf(
                ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                ),
                ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerSaverModeActivity"
                )
            )
            Vendor.ONE_UI -> listOf(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            )
            else -> emptyList()
        }
        if (start(context, direct)) return true
        // Every skin puts a per-app battery entry inside app info, so that is
        // one tap away from the right switch rather than a dead end.
        return OemPages.openAppInfo(context)
    }

    fun openAutoLaunch(context: Context): Boolean {
        if (start(context, AUTO_LAUNCH[vendor()].orEmpty())) return true
        if (OemPages.openAutoStart(context)) return true
        return OemPages.openAppInfo(context)
    }

    fun open(context: Context, requirementId: String): Boolean = when (requirementId) {
        ID_BATTERY_UNRESTRICTED -> OemPages.requestIgnoreBatteryOptimizations(context)
        ID_AUTO_LAUNCH -> openAutoLaunch(context)
        ID_BACKGROUND_ACTIVITY -> openBackgroundActivity(context)
        else -> OemPages.openAppInfo(context)
    }

    /**
     * Tries each component in turn, without asking whether it resolves first.
     *
     * Package visibility hides these skin packages from `resolveActivity` on
     * Android 11 and up, so the check said "no such page" on every phone that
     * actually had one and the user was always dropped in app info. A start
     * that cannot happen throws, and the throw is the answer.
     */
    private fun start(context: Context, components: List<ComponentName>): Boolean {
        for (component in components) {
            try {
                val intent = Intent().setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Try the next component; skins rename these between versions.
            }
        }
        return false
    }
}
