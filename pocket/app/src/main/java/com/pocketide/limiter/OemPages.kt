package com.pocketide.limiter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the page that holds the fix for a condition. The phone maker's own page comes first,
 * app info last, because every skin keeps a per-app battery entry there.
 *
 * Components are started rather than resolved first: from Android 11, package visibility hides
 * these security-centre packages from resolveActivity, which answered "no such page" on the
 * very phones that have one. A phone without the page throws, and the throw is the answer.
 */
internal class OemPages(private val context: Context, private val vendor: Vendor) {

    fun open(conditionId: String): Boolean = when (conditionId) {
        ConditionRules.OEM_BATTERY ->
            start(batteryPage(), withPackage = true) || start(autoLaunch()) || appInfo()
        ConditionRules.BATTERY_RESTRICTED, ConditionRules.STANDBY_RESTRICTED ->
            start(batteryPage(), withPackage = true) || appInfo()
        ConditionRules.DATA_SAVER ->
            action(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, forThisApp = true) || appInfo()
        ConditionRules.BATTERY_SAVER ->
            action(Settings.ACTION_BATTERY_SAVER_SETTINGS) || action(Settings.ACTION_SETTINGS)
        else -> appInfo()
    }

    /** The per-app battery page: on ColorOS that is where auto-launch and background activity live. */
    private fun batteryPage(): List<ComponentName> = when (vendor) {
        Vendor.COLOR_OS -> listOf(
            // The page moved to "oplus" in realme UI 3 and later; Android 11 realme phones keep the older name.
            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"),
        )
        Vendor.ONE_UI -> listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        else -> emptyList()
    }

    private fun autoLaunch(): List<ComponentName> = when (vendor) {
        Vendor.COLOR_OS -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        Vendor.MIUI -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        Vendor.VIVO -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
        Vendor.HUAWEI -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        )
        Vendor.ONE_UI, Vendor.OTHER -> emptyList()
    }

    private fun start(components: List<ComponentName>, withPackage: Boolean = false): Boolean = components.any { component ->
        val intent = Intent().setComponent(component)
        // Both spellings the skins have used, so the page opens on this app rather than the list.
        if (withPackage) intent.putExtra("package_name", context.packageName).putExtra("packageName", context.packageName)
        launch(intent)
    }

    private fun action(action: String, forThisApp: Boolean = false): Boolean {
        val intent = Intent(action)
        if (forThisApp) intent.data = Uri.parse("package:${context.packageName}")
        return launch(intent)
    }

    private fun appInfo(): Boolean = action(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, forThisApp = true)

    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        // Not on this phone (ActivityNotFoundException) or not exported to us (SecurityException).
        false
    }
}
