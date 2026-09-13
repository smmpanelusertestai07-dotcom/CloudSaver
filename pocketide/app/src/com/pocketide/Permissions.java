package com.pocketide;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Everything this app has to ask the phone for, and the one page on this phone that grants it.
 *
 * Android has no page for a single permission, and the ones that matter most here are not
 * permissions at all -- auto-launch and background activity live in whatever the manufacturer
 * called their own security app. Sending every row to App info would be the same as telling
 * the owner to find it themselves, so each row goes to the nearest real page, and App info is
 * only the last resort.
 *
 * The OEM component lists below are the part that actually earns its place. Realme, OPPO,
 * Xiaomi, vivo, OnePlus, Huawei and Samsung each hide auto-launch somewhere different, several
 * moved it between their own versions, and from Android 11 on package visibility hides those
 * security-centre packages from resolveActivity -- which answers null on exactly the phones
 * that do have the page. So the components are started rather than resolved: a phone without
 * the page throws, which is the same answer, arrived at honestly.
 */
final class Permissions {

    static final int REQUEST_NOTIFICATIONS = 41;

    private Permissions() {}

    // ------------------------------------------------------------------ state

    static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean batteryUnrestricted(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** True when something the owner should know about is still missing. */
    static boolean anythingMissing(Context context) {
        return !notificationsAllowed(context) || !batteryUnrestricted(context);
    }

    // ------------------------------------------------------------------ asking

    /**
     * Asks for notifications. The second time Android stops showing the prompt, so a request
     * that cannot be shown goes to the notification page instead of doing nothing at all.
     */
    static void askNotifications(Activity activity, boolean fromRow) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (notificationsAllowed(activity)) {
            if (fromRow) openNotificationSettings(activity);
            return;
        }
        if (fromRow && !activity.shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS)) {
            openNotificationSettings(activity);
            return;
        }
        activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    static void openNotificationSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        if (!launch(activity, intent)) openAppInfo(activity);
    }

    /**
     * The battery prompt: a single yes/no dialog where the phone allows it, the system list
     * where it does not, and App info as the floor.
     */
    static void openBatterySettings(Activity activity) {
        if (batteryUnrestricted(activity)) {
            openAppInfo(activity);
            return;
        }
        if (launch(activity, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.getPackageName())))) {
            return;
        }
        if (!launch(activity, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppInfo(activity);
        }
    }

    /** Auto-launch, wherever this manufacturer decided to keep it. */
    static boolean openAutoStartSettings(Activity activity) {
        String[][] targets = {
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
        };
        for (String[] target : targets) {
            Intent intent = new Intent().setComponent(new ComponentName(target[0], target[1]));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (launch(activity, intent)) return true;
        }
        return false;
    }

    /**
     * Background activity, which on Realme and OPPO is a separate switch from battery
     * optimisation and is the one that actually ends a long download.
     */
    static boolean openBackgroundActivitySettings(Activity activity) {
        String[][] targets = {
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"},
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
        };
        for (String[] target : targets) {
            Intent intent = new Intent().setComponent(new ComponentName(target[0], target[1]));
            intent.putExtra("package_name", activity.getPackageName());
            intent.putExtra("packageName", activity.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (launch(activity, intent)) return true;
        }
        return false;
    }

    static void openAppInfo(Activity activity) {
        if (!launch(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())))) {
            launch(activity, new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /** Android's own data-saver page, which can block this app's downloads outright. */
    static void openDataSaverSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= 24) {
            if (launch(activity, new Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())))) {
                return;
            }
        }
        openAppInfo(activity);
    }

    static boolean launch(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (Throwable noSuchPage) {
            return false;
        }
    }
}
