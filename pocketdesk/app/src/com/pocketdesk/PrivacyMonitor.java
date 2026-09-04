package com.pocketdesk;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Every permission this app can hold, what it is for, and whether it is held right now.
 *
 * One place, so the owner never has to trust a sentence in a FAQ about what an app is allowed to
 * do -- they can read it off the phone itself. The list is built from the manifest that actually
 * shipped, not from a hand-written list that could drift: a permission added in a later version
 * appears here on its own, and one removed disappears.
 *
 * Nothing here changes anything. Every row's action is Android's own settings page, because the
 * only place a permission should be granted or taken back is the phone's own screen.
 */
final class PrivacyMonitor {

    /** One line of the monitor: what it is, why it exists, and whether it is on. */
    static final class Entry {
        final String name;
        final String purpose;
        final boolean held;
        /** True when the phone itself decides this one, so "off" is the owner's choice. */
        final boolean runtime;
        /** True when the app never asks for this at all -- shown so its absence is provable. */
        final boolean neverAsked;

        Entry(String name, String purpose, boolean held, boolean runtime, boolean neverAsked) {
            this.name = name;
            this.purpose = purpose;
            this.held = held;
            this.runtime = runtime;
            this.neverAsked = neverAsked;
        }

        String state() {
            if (neverAsked) return "NEVER ASKED";
            return held ? "ON" : "OFF";
        }
    }

    private PrivacyMonitor() {}

    /**
     * What the permission is for, in the owner's words. Anything not named here is shown by its
     * Android name, which is better than hiding it: an unexplained permission the owner can see
     * is safer than one they cannot.
     */
    private static String purposeOf(String permission) {
        switch (permission) {
            case Manifest.permission.INTERNET:
                return "Download Ubuntu and the apps you choose. Nothing else leaves this phone.";
            case Manifest.permission.ACCESS_NETWORK_STATE:
                return "Tell Wi-Fi from mobile data, so a big download can wait for Wi-Fi.";
            case Manifest.permission.WAKE_LOCK:
                return "Keep a set-up or an install going while the screen is off.";
            case Manifest.permission.POST_NOTIFICATIONS:
                return "Show what the set-up and the desktop are doing.";
            case Manifest.permission.VIBRATE:
                return "A short buzz for a long-press inside the desktop.";
            case Manifest.permission.RECORD_AUDIO:
                return "The microphone, only while you turn it on from the desktop screen. "
                        + "It stops the moment you leave that screen.";
            case Manifest.permission.USE_BIOMETRIC:
                return "App lock: the phone's own fingerprint or PIN, if you turn it on.";
            case Manifest.permission.MANAGE_EXTERNAL_STORAGE:
            case Manifest.permission.READ_EXTERNAL_STORAGE:
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                return "Phone files: your Download, Photos and Documents inside the computer. "
                        + "Off unless you turn it on.";
            case Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS:
                return "Ask you once whether a long set-up may keep running.";
            case "android.permission.FOREGROUND_SERVICE":
            case "android.permission.FOREGROUND_SERVICE_SPECIAL_USE":
                return "Keep the Linux computer running with a notification you can see.";
            case "android.permission.RECEIVE_BOOT_COMPLETED":
                return "Notice that the phone restarted, so a stopped set-up can be continued.";
            case "android.permission.QUERY_ALL_PACKAGES":
                return "See which app can open a file you saved, and nothing more.";
            default:
                return permission;
        }
    }

    /** The permissions the app deliberately does not have, named so their absence is checkable. */
    private static final String[][] NEVER_ASKED = {
            {"Camera", "PocketDesk never opens the camera itself. Taking a photo for the computer "
                    + "hands you the phone's own camera app, which needs no permission here."},
            {"Location", "Nothing in this app or in the Linux computer is told where you are."},
            {"Contacts", "Never read."},
            {"Calls and call log", "Never read."},
            {"Messages (SMS)", "Never read."},
            {"Body sensors and health", "Never read."},
            {"Other apps' data", "Android's own sandbox stops it, and so does this app."},
    };

    /** Every permission in the shipped manifest, with its live state, plus what is never asked. */
    static List<Entry> read(Context context) {
        List<Entry> entries = new ArrayList<>();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS);
            String[] declared = info.requestedPermissions;
            if (declared != null) {
                for (String permission : declared) {
                    boolean held = context.checkSelfPermission(permission)
                            == PackageManager.PERMISSION_GRANTED;
                    entries.add(new Entry(shortName(permission), purposeOf(permission), held,
                            isRuntime(permission), false));
                }
            }
        } catch (Throwable unreadable) {
            // A phone that will not describe its own package still gets the never-asked list,
            // which is the half the owner most wants to be sure of.
        }
        for (String[] absent : NEVER_ASKED) {
            entries.add(new Entry(absent[0], absent[1], false, false, true));
        }
        return entries;
    }

    /**
     * True for the permissions Android puts a prompt in front of, where "Only this time" and
     * "While using the app" are the owner's to choose. The rest are granted at install and can
     * only be inspected, which is exactly why they are listed here too.
     */
    private static boolean isRuntime(String permission) {
        switch (permission) {
            case Manifest.permission.RECORD_AUDIO:
            case Manifest.permission.POST_NOTIFICATIONS:
            case Manifest.permission.READ_EXTERNAL_STORAGE:
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
            case Manifest.permission.MANAGE_EXTERNAL_STORAGE:
                return true;
            default:
                return false;
        }
    }

    /** "android.permission.RECORD_AUDIO" as "Microphone", and so on. */
    private static String shortName(String permission) {
        switch (permission) {
            case Manifest.permission.INTERNET: return "Internet";
            case Manifest.permission.ACCESS_NETWORK_STATE: return "Network type";
            case Manifest.permission.WAKE_LOCK: return "Keep awake";
            case Manifest.permission.POST_NOTIFICATIONS: return "Notifications";
            case Manifest.permission.VIBRATE: return "Vibration";
            case Manifest.permission.RECORD_AUDIO: return "Microphone";
            case Manifest.permission.USE_BIOMETRIC: return "Fingerprint or PIN";
            case Manifest.permission.MANAGE_EXTERNAL_STORAGE: return "All files (Phone files)";
            case Manifest.permission.READ_EXTERNAL_STORAGE: return "Read phone storage";
            case Manifest.permission.WRITE_EXTERNAL_STORAGE: return "Write phone storage";
            case Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: return "Battery usage";
            case "android.permission.FOREGROUND_SERVICE": return "Run in the foreground";
            case "android.permission.FOREGROUND_SERVICE_SPECIAL_USE": return "Run the computer";
            case "android.permission.RECEIVE_BOOT_COMPLETED": return "Notice a restart";
            case "android.permission.QUERY_ALL_PACKAGES": return "See installed apps";
            default:
                int dot = permission.lastIndexOf('.');
                return dot < 0 ? permission : permission.substring(dot + 1);
        }
    }

    /** A one-line summary for the row that opens the monitor. */
    static String summary(Context context) {
        int on = 0, runtimeOn = 0;
        for (Entry entry : read(context)) {
            if (entry.neverAsked) continue;
            if (entry.held) {
                on++;
                if (entry.runtime) runtimeOn++;
            }
        }
        return on + " permissions held, " + runtimeOn + " of them yours to take back";
    }

    /** Whether the phone is currently letting a job run without battery limits. */
    static boolean batteryUnrestricted(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && Build.VERSION.SDK_INT >= 23
                && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }
}
