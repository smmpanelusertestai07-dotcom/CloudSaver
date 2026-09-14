package com.pocketlinux;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The background-work settings this phone actually has, and where they live.
 *
 * Every phone maker breaks background work in its own way and hides the switch in its own place,
 * so "open App info and look around" is not an instruction anyone can follow. Where the state can
 * be read, it is read; where it cannot, the app says so plainly rather than guessing, and still
 * offers the one tap that gets there.
 *
 * Ported from the sister app in this repository, where it was written for a realme phone that
 * kept stopping a long download.
 */
final class PowerPages {

    enum Vendor { COLOR_OS, MIUI, ONE_UI, VIVO, HUAWEI, PIXEL, OTHER }

    /**
     * One thing the owner may need to allow.
     *
     * {@link #readable} is false when no public API reports the state; those are shown as "check
     * this" rather than as a problem, because claiming a setting is off when it might be on trains
     * people to ignore the app.
     */
    static final class Requirement {
        final String id;
        final boolean readable;
        final boolean satisfied;

        Requirement(String id, boolean readable, boolean satisfied) {
            this.id = id;
            this.readable = readable;
            this.satisfied = satisfied;
        }
    }

    static final String ID_BATTERY_UNRESTRICTED = "battery_unrestricted";
    static final String ID_AUTO_LAUNCH = "auto_launch";
    static final String ID_BACKGROUND_ACTIVITY = "background_activity";

    private PowerPages() {}

    static Vendor vendor() {
        return vendor(Build.MANUFACTURER, Build.BRAND);
    }

    /** Both names are checked: some phones carry the maker in one field and the skin in the other. */
    static Vendor vendor(String manufacturer, String brand) {
        String name = lower(manufacturer) + " " + lower(brand);
        if (any(name, "oppo", "realme", "oneplus")) return Vendor.COLOR_OS;
        if (any(name, "xiaomi", "redmi", "poco")) return Vendor.MIUI;
        if (any(name, "samsung")) return Vendor.ONE_UI;
        if (any(name, "vivo", "iqoo")) return Vendor.VIVO;
        if (any(name, "huawei", "honor")) return Vendor.HUAWEI;
        if (any(name, "google", "pixel")) return Vendor.PIXEL;
        return Vendor.OTHER;
    }

    /**
     * What to ask for on this phone. Battery optimisation is readable everywhere; auto-launch and
     * background activity are not readable on any of these skins, so they are listed as checks the
     * app cannot verify.
     */
    static List<Requirement> requirementsFor(Vendor vendor, boolean ignoringBatteryOptimizations) {
        List<Requirement> requirements = new ArrayList<>();
        requirements.add(new Requirement(ID_BATTERY_UNRESTRICTED, true, ignoringBatteryOptimizations));
        switch (vendor) {
            case COLOR_OS:
                requirements.add(new Requirement(ID_BACKGROUND_ACTIVITY, false, false));
                requirements.add(new Requirement(ID_AUTO_LAUNCH, false, false));
                break;
            case MIUI:
            case VIVO:
            case HUAWEI:
                requirements.add(new Requirement(ID_AUTO_LAUNCH, false, false));
                break;
            case ONE_UI:
                requirements.add(new Requirement(ID_BACKGROUND_ACTIVITY, false, false));
                break;
            default:
                // Pixel and anything unrecognised: Android's own battery setting is the only
                // switch this app can name with confidence, so it does not invent a second one.
                break;
        }
        return requirements;
    }

    /**
     * Where the switch is, in the words the phone itself uses.
     *
     * Android cannot read the maker's own switches, so the row cannot say On or Off, but it can
     * say exactly where to look, which "please check it yourself" never did. The labels are the
     * skins' own English ones; a phone set to another language shows its translation of the same
     * item in the same place.
     */
    static String pathHint(Vendor vendor, String requirementId) {
        if (ID_AUTO_LAUNCH.equals(requirementId)) {
            switch (vendor) {
                case COLOR_OS:
                    return "Settings → Battery → App battery management → PocketLinux → Allow "
                            + "auto-launch (some phones: Settings → App management → App list → "
                            + "PocketLinux → Allow auto-launch, or Settings → Privacy → Startup manager)";
                case MIUI:
                    return "Settings → Apps → Manage apps → PocketLinux → Autostart";
                case VIVO:
                    return "i Manager → App manager → Autostart manager → PocketLinux";
                case HUAWEI:
                    return "Settings → Apps → App launch → PocketLinux → Manage manually: "
                            + "Auto-launch, Secondary launch, Run in background";
                default:
                    return null;
            }
        }
        if (ID_BACKGROUND_ACTIVITY.equals(requirementId)) {
            switch (vendor) {
                case COLOR_OS:
                    return "Settings → Battery → App battery management → PocketLinux → Allow "
                            + "background activity, and Don't optimise (some phones: App info → "
                            + "Battery usage)";
                case ONE_UI:
                    return "Settings → Apps → PocketLinux → Battery → Unrestricted";
                default:
                    return null;
            }
        }
        if (ID_BATTERY_UNRESTRICTED.equals(requirementId)) {
            switch (vendor) {
                case COLOR_OS:
                    return "Settings → Battery → App battery management → PocketLinux → Don't optimise";
                case MIUI:
                    return "Settings → Apps → Manage apps → PocketLinux → Battery saver → No restrictions";
                case ONE_UI:
                    return "Settings → Apps → PocketLinux → Battery → Unrestricted";
                default:
                    return "Settings → Apps → PocketLinux → Battery → Unrestricted (or Don't optimise)";
            }
        }
        return null;
    }

    /** The auto-launch or auto-start list, by skin. */
    private static ComponentName[] autoLaunchComponents(Vendor vendor) {
        switch (vendor) {
            case COLOR_OS:
                return new ComponentName[]{
                        new ComponentName("com.coloros.safecenter",
                                "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                        new ComponentName("com.coloros.safecenter",
                                "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                        new ComponentName("com.oppo.safe",
                                "com.oppo.safe.permission.startup.StartupAppListActivity"),
                        new ComponentName("com.oneplus.security",
                                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")};
            case MIUI:
                return new ComponentName[]{
                        new ComponentName("com.miui.securitycenter",
                                "com.miui.permcenter.autostart.AutoStartManagementActivity")};
            case VIVO:
                return new ComponentName[]{
                        new ComponentName("com.vivo.permissionmanager",
                                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                        new ComponentName("com.iqoo.secure",
                                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")};
            case ONE_UI:
                return new ComponentName[]{
                        new ComponentName("com.samsung.android.lool",
                                "com.samsung.android.sm.battery.ui.BatteryActivity"),
                        new ComponentName("com.samsung.android.lool",
                                "com.samsung.android.sm.ui.battery.BatteryActivity")};
            case HUAWEI:
                return new ComponentName[]{
                        new ComponentName("com.huawei.systemmanager",
                                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                        new ComponentName("com.huawei.systemmanager",
                                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")};
            default:
                return new ComponentName[0];
        }
    }

    /**
     * Every known auto-launch page, tried after the guess for this phone.
     *
     * A phone whose brand nobody recognises can still be a rebadged skin with one of these pages,
     * so the blind chain stays as a second chance rather than being replaced by the vendor list.
     */
    private static final ComponentName[] ALL_AUTO_LAUNCH = {
            new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            new ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            new ComponentName("com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"),
            new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            new ComponentName("com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            new ComponentName("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"),
            new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            new ComponentName("com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")};

    /**
     * The background-activity page. On ColorOS this is the app's own battery screen, which is
     * where "Allow background activity" lives.
     */
    static boolean openBackgroundActivity(Context context) {
        ComponentName[] direct;
        switch (vendor()) {
            case COLOR_OS:
                // The per-app battery page moved packages when ColorOS became "oplus" (realme UI 3
                // and later), so the newer name is tried first and the older one is what an
                // Android 11 realme still has. Both carry the package as an extra, under both
                // spellings the skins have used, so the page opens on this app rather than on the
                // whole list.
                direct = new ComponentName[]{
                        new ComponentName("com.oplus.battery",
                                "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
                        new ComponentName("com.oplus.battery",
                                "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"),
                        new ComponentName("com.coloros.oppoguardelf",
                                "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                        new ComponentName("com.coloros.oppoguardelf",
                                "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"),
                        new ComponentName("com.coloros.oppoguardelf",
                                "com.coloros.powermanager.fuelgaue.PowerSaverModeActivity")};
                break;
            case ONE_UI:
                direct = new ComponentName[]{
                        new ComponentName("com.samsung.android.lool",
                                "com.samsung.android.sm.battery.ui.BatteryActivity"),
                        new ComponentName("com.samsung.android.lool",
                                "com.samsung.android.sm.ui.battery.BatteryActivity")};
                break;
            default:
                direct = new ComponentName[0];
                break;
        }
        if (start(context, direct, true)) return true;
        // Every skin puts a per-app battery entry inside App info, so that is one tap away from
        // the right switch rather than a dead end.
        return openAppInfo(context);
    }

    static boolean openAutoLaunch(Context context) {
        if (start(context, autoLaunchComponents(vendor()), false)) return true;
        if (start(context, ALL_AUTO_LAUNCH, false)) return true;
        return openAppInfo(context);
    }

    /** Opens the phone's own page for one requirement. */
    static boolean open(Context context, String requirementId) {
        if (ID_BATTERY_UNRESTRICTED.equals(requirementId)) {
            // Android closes the "ignore optimisations" dialog silently when the app is already
            // exempt, so on the phone this was written for -- battery already unrestricted, the
            // maker's own switch still killing the app -- that tap did nothing at all. Already
            // exempt, the tap goes to the page that holds the other switches instead.
            return batteryUnrestricted(context)
                    ? openBackgroundActivity(context)
                    : requestIgnoreBatteryOptimizations(context);
        }
        if (ID_AUTO_LAUNCH.equals(requirementId)) return openAutoLaunch(context);
        if (ID_BACKGROUND_ACTIVITY.equals(requirementId)) return openBackgroundActivity(context);
        return openAppInfo(context);
    }

    /** Whether Android is currently letting this app work without battery limits. */
    static boolean batteryUnrestricted(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** The one-tap yes/no prompt, with the system's own list and App info behind it. */
    static boolean requestIgnoreBatteryOptimizations(Context context) {
        if (launch(context, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName())))) {
            return true;
        }
        // Some skins strip the per-app dialog; the system's own list of optimised apps still
        // exists everywhere and is one tap from the switch, which App info is not.
        if (launch(context, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            return true;
        }
        return openAppInfo(context);
    }

    static boolean openAppInfo(Context context) {
        if (launch(context, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.getPackageName())))) {
            return true;
        }
        return launch(context, new Intent(Settings.ACTION_SETTINGS));
    }

    /**
     * Tries each page in turn, without asking first whether it exists.
     *
     * Package visibility hides these maker packages from resolveActivity on Android 11 and up, so
     * the check answered "no such page" on every phone that actually had one and the owner was
     * always dropped in App info. A start that cannot happen throws, and the throw is the answer.
     */
    private static boolean start(Context context, ComponentName[] components, boolean withPackageExtras) {
        for (ComponentName component : components) {
            Intent intent = new Intent().setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (withPackageExtras) {
                intent.putExtra("package_name", context.getPackageName());
                intent.putExtra("packageName", context.getPackageName());
            }
            if (launch(context, intent)) return true;
        }
        return false;
    }

    private static boolean launch(Context context, Intent intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable missing) {
            // The next page in the list, or App info; skins rename these between versions.
            return false;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static boolean any(String name, String... needles) {
        for (String needle : needles) {
            if (name.contains(needle)) return true;
        }
        return false;
    }
}
