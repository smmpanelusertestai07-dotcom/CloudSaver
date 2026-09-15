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

import java.util.Locale;

/**
 * Everything this app has to ask the phone for, and the one page on this phone that grants it.
 *
 * Android has no page for a single permission, and the ones that matter most here are not
 * permissions at all -- auto-launch and background activity live in whatever the manufacturer
 * called their own security app. Sending every row to App info would be the same as telling
 * the owner to find it themselves, so each row goes to the nearest real page, and App info is
 * only the last resort.
 *
 * Two things about those maker's switches are worth stating, because both were got wrong here
 * once:
 *
 *   They cannot be READ. No app on any Android skin can ask whether its own auto-launch or
 *   background switch is on. A row that pretends to know -- "CHECK", or worse, "Off" -- teaches
 *   people to ignore the app. So the rows say plainly that Android cannot report them, and then
 *   print the path through this phone's own menus, in the words that phone uses, so the owner
 *   can look for themselves. The paths are per maker: on a realme the switch is under Battery,
 *   on a Xiaomi under Apps, on a Samsung it is a list of apps allowed never to sleep.
 *
 *   The battery prompt closes itself when the app is already exempt. Android finishes the
 *   "ignore optimisations" dialog silently in that state, so on precisely the phone the row was
 *   written for -- battery unrestricted, the maker's own switch still killing the app -- a tap
 *   used to do nothing at all. An already-exempt tap goes to the maker's background page now.
 *
 * The OEM component lists below are the part that actually earns its place. Realme, OPPO,
 * Xiaomi, vivo, OnePlus, Huawei and Samsung each hide auto-launch somewhere different, several
 * moved it between their own versions, and from Android 11 on package visibility hides those
 * security-centre packages from resolveActivity -- which answers null on exactly the phones
 * that do have the page. So the components are started rather than resolved: a phone without
 * the page throws, which is the same answer, arrived at honestly. The newer oplus components
 * come before the older coloros ones, which is the order realme UI 3 and later need.
 */
final class Permissions {

    static final int REQUEST_NOTIFICATIONS = 41;

    /** What every row says about a switch the phone will not let an app read. */
    static final String CANNOT_READ = "Android cannot report this one";

    private Permissions() {}

    // ------------------------------------------------------------------ whose phone

    /** The maker's skin, judged from the phone's own identification. */
    enum Skin { REALME, OPPO, ONEPLUS, XIAOMI, VIVO, HUAWEI, SAMSUNG, OTHER }

    static Skin skin() {
        String maker = ((Build.MANUFACTURER == null ? "" : Build.MANUFACTURER) + " "
                + (Build.BRAND == null ? "" : Build.BRAND)).toLowerCase(Locale.ROOT);
        if (maker.contains("realme")) return Skin.REALME;
        if (maker.contains("oneplus")) return Skin.ONEPLUS;
        if (maker.contains("oppo")) return Skin.OPPO;
        if (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")) {
            return Skin.XIAOMI;
        }
        if (maker.contains("vivo") || maker.contains("iqoo")) return Skin.VIVO;
        if (maker.contains("huawei") || maker.contains("honor")) return Skin.HUAWEI;
        if (maker.contains("samsung")) return Skin.SAMSUNG;
        return Skin.OTHER;
    }

    /**
     * The path to the auto-launch switch, in the words this phone's own menus use.
     *
     * Printed on the row because the switch cannot be read: the one honest thing the app can
     * do is say where it is. The realme one is the one this app was tested on and is exact for
     * realme UI 2 through 5; the others follow each maker's own current layout.
     */
    static String autoLaunchPath() {
        switch (skin()) {
            case REALME:
            case OPPO:
                return "Settings › Battery › App battery management › PocketIDE › "
                        + "Allow auto-launch";
            case ONEPLUS:
                return "Settings › Apps › PocketIDE › Battery usage › Allow auto-launch";
            case XIAOMI:
                return "Settings › Apps › Manage apps › PocketIDE › Autostart";
            case VIVO:
                return "i Manager › App manager › Autostart manager › PocketIDE";
            case HUAWEI:
                return "Settings › Apps › App launch › PocketIDE › Manage manually › "
                        + "Auto-launch";
            case SAMSUNG:
                return "not a switch on Samsung; Background activity is the one that matters";
            default:
                return "Settings › Apps › PocketIDE › Battery";
        }
    }

    /** The path to the background-activity switch, in this phone's own words. */
    static String backgroundPath() {
        switch (skin()) {
            case REALME:
            case OPPO:
                return "Settings › Battery › App battery management › PocketIDE › "
                        + "Allow background activity";
            case ONEPLUS:
                return "Settings › Apps › PocketIDE › Battery usage › "
                        + "Allow background activity";
            case XIAOMI:
                return "Settings › Apps › Manage apps › PocketIDE › Battery saver › "
                        + "No restrictions";
            case VIVO:
                return "Settings › Battery › Background power consumption management › "
                        + "PocketIDE";
            case HUAWEI:
                return "Settings › Apps › App launch › PocketIDE › Manage manually › "
                        + "Run in background";
            case SAMSUNG:
                return "Settings › Apps › PocketIDE › Battery › Unrestricted";
            default:
                return "Settings › Apps › PocketIDE › Battery › Unrestricted";
        }
    }

    /** True where the maker keeps switches of its own beyond Android's battery optimisation. */
    static boolean makerHasOwnSwitches() {
        return skin() != Skin.OTHER;
    }

    // ------------------------------------------------------------------ state

    static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        // The permission is one switch; the app's notifications as a whole are another, on
        // every Android version. A row that said "Allowed" while the owner had turned them
        // off on the app's page lied on Android 10 to 12, where there is no permission at all.
        try {
            android.app.NotificationManager manager = (android.app.NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager == null || manager.areNotificationsEnabled();
        } catch (Throwable unreadable) {
            return true;
        }
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
        if (Build.VERSION.SDK_INT < 33) {
            // No runtime permission before Android 13: the only switch is the app's own page.
            if (fromRow || !notificationsAllowed(activity)) openNotificationSettings(activity);
            return;
        }
        if (notificationsAllowed(activity)) {
            if (fromRow) openNotificationSettings(activity);
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            // The permission is granted and the app's notifications are off as a whole: no
            // prompt can change that, only the page.
            openNotificationSettings(activity);
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
        Dialogs.ask(activity, "Show progress while this runs?",
                "Setting up takes twenty to forty minutes. A notification is how you see how "
                        + "far it has got, and how you stop it without hunting through the "
                        + "phone's settings.\n\nIt makes no sound, and it is the only "
                        + "notification this app ever posts.",
                "Allow", "Not now",
                () -> {
                    pendingAfterNotifications = then;
                    askNotifications(activity, false);
                },
                // "Not now" answers the notification, not the set-up: the work starts either
                // way, as the screen behind this dialog said it would. The button used to say
                // Cancel and leave nothing running, on a screen whose headline said Starting.
                then);
    }

    /**
     * Drops whatever was waiting on an answer.
     *
     * The waiting Runnable holds the screen that asked. A screen destroyed before the answer
     * arrives would otherwise be kept alive by it, and a late answer would start work on a
     * screen that is gone.
     */
    static void forget() { pendingAfterNotifications = null; }

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
     *
     * Already exempt, the tap goes to the maker's own background page instead. Android closes
     * the "ignore optimisations" dialog silently when the app is already exempt, so on the one
     * phone this row was written for the tap used to do nothing -- and the switch that was
     * still ending long downloads was the maker's, on the page this now opens.
     */
    static void openBatterySettings(Activity activity) {
        if (batteryUnrestricted(activity)) {
            if (!openBackgroundActivitySettings(activity)) openAppInfo(activity);
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
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
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
     *
     * The oplus package first: the per-app battery page moved packages when ColorOS became
     * "oplus" (realme UI 3 and later), and the older name is what an Android 11 realme still
     * has. Both carry the package as an extra, under both spellings the skins have used, so
     * the page opens on this app rather than on the list.
     */
    static boolean openBackgroundActivitySettings(Activity activity) {
        String[][] targets = {
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"},
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerSaverModeActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
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

    /** The phone's own security page, for the owner who removed their screen lock. */
    static void openSecuritySettings(Activity activity) {
        if (!launch(activity, new Intent(Settings.ACTION_SECURITY_SETTINGS))) {
            launch(activity, new Intent(Settings.ACTION_SETTINGS));
        }
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
