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
     * Asks for notifications, in the one state where asking still works.
     *
     * Android gives an app three states and only two ways to read them. After two refusals it
     * stops showing the prompt at all -- requestPermissions returns instantly, nothing appears,
     * and the app looks broken -- and the flag that would tell you,
     * shouldShowRequestPermissionRationale, is false in that state AND false before the first
     * ask. Reading it alone therefore treats a fresh install as a permanent refusal, which is
     * what this did: the first tap opened a Settings page instead of the prompt.
     *
     * So the app remembers whether it has ever asked. Never asked means ask. Asked, and the
     * rationale flag is true, means the owner said no once and Android will still show it, so
     * ask again with the reason first. Asked, and the flag is false, means Android will never
     * show it again and the only honest move is to open the page where it can be changed by
     * hand.
     */
    static void askNotifications(Activity activity, boolean fromRow) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (notificationsAllowed(activity)) {
            if (fromRow) openNotificationSettings(activity);
            return;
        }
        boolean askedBefore = Prefs.of(activity).getBoolean(Prefs.ASKED_NOTIFICATIONS, false);
        boolean willShow = !askedBefore || activity.shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS);
        if (!willShow) {
            // Android will not show the prompt again, whatever this app does with it.
            openNotificationSettings(activity);
            return;
        }
        Prefs.of(activity).edit().putBoolean(Prefs.ASKED_NOTIFICATIONS, true).apply();
        activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    /**
     * Explains, then asks, at the moment the permission is about to matter.
     *
     * Set-up runs for twenty to forty minutes in a foreground service, and the notification IS
     * the progress and the Stop button. Starting that without ever offering the permission is
     * how an owner ends up with a long job running and no way to see it or end it. Asking here,
     * with the reason, is Android's own guidance and it is also simply the moment the answer
     * means something.
     *
     * Refusing is not a dead end: set-up runs either way, the Home screen carries a row that
     * explains what is missing, and Settings can turn it on later.
     */
    static void askNotificationsBeforeLongWork(Activity activity, Runnable then) {
        if (Build.VERSION.SDK_INT < 33 || notificationsAllowed(activity)) {
            then.run();
            return;
        }
        if (Prefs.of(activity).getBoolean(Prefs.ASKED_NOTIFICATIONS, false)) {
            // Already answered once. Do not ask again on the way into a job; the row on Home
            // is where it can be reconsidered.
            then.run();
            return;
        }
        Dialogs.confirm(activity, "Show progress while this runs?",
                "Setting up takes twenty to forty minutes. A notification is how you see how "
                        + "far it has got, and how you stop it without hunting through the "
                        + "phone's settings.\n\nIt makes no sound, and it is the only "
                        + "notification this app ever posts.",
                "Allow", () -> {
                    pendingAfterNotifications = then;
                    askNotifications(activity, false);
                });
        // Refusing the dialog leaves the work unstarted, which is the honest reading of Cancel
        // on a question asked before anything has begun.
    }

    /** What to run once the notification answer arrives, whichever way it went. */
    private static Runnable pendingAfterNotifications;

    /** Activities hand their onRequestPermissionsResult here. */
    static void onAnswered(int request) {
        if (request != REQUEST_NOTIFICATIONS) return;
        Runnable next = pendingAfterNotifications;
        pendingAfterNotifications = null;
        // Runs whether it was allowed or refused: the work does not depend on the permission,
        // only the owner's view of it does.
        if (next != null) next.run();
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

    /**
     * The one place this app hands the owner to a page of Android's own.
     *
     * Every open* method above goes through here, which is what makes the AppLock line below
     * safe to write once. Without it, an owner with the app lock on taps "Battery", grants what
     * was asked, comes back -- and is met by a fingerprint prompt for an errand the app sent
     * them on. A lock that interrupts the owner's own action is a lock they turn off.
     */
    static boolean launch(Activity activity, Intent intent) {
        try {
            AppLock.expectReturn();
            activity.startActivity(intent);
            return true;
        } catch (Throwable noSuchPage) {
            AppLock.returned();
            return false;
        }
    }
}
