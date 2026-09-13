package com.pocketide;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;

/**
 * The phone's own storage, inside the workspace as the folder ~/phone.
 *
 * Off by default, and the default is the point. A development environment that cannot reach
 * the phone's Downloads, DCIM and Documents is one that cannot leak them either, and most of
 * what anyone does here never needs them. It is a switch in Settings, not a permission asked
 * for at set-up, because a permission requested before it is wanted is a permission granted
 * without a reason.
 *
 * When it is on it earns its place. An agent asked to build a screen from a mock-up needs to
 * read the mock-up; an agent that has just built an APK needs somewhere to put it that survives
 * the app being uninstalled; and git repositories cloned onto the phone's storage outlive the
 * workspace they were cloned from.
 *
 * Android calls this "All files access" from 11 onwards and grants it on a Settings page of its
 * own; on 10 it is the ordinary storage permission. Both paths are here because the app still
 * supports API 29.
 */
final class PhoneFiles {

    static final int REQUEST_STORAGE = 44;

    /** Where it appears inside Linux. Under the home directory, so a terminal lands next to it. */
    static final String GUEST_PATH = "/root/phone";

    private PhoneFiles() {}

    /** Whether the owner has turned it on AND Android has actually granted it. */
    static boolean enabled(Context context) {
        return Prefs.of(context).getBoolean(Prefs.PHONE_FILES, false) && allowed(context);
    }

    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** /storage/emulated/0: Download, DCIM, Documents, Pictures and the rest. */
    static File root() {
        return Environment.getExternalStorageDirectory();
    }

    /**
     * Opens the page Android grants it on, or asks directly on 10.
     *
     * AppLock.expectReturn() first, because this sends the owner out to the phone's own
     * Settings and coming back to a fingerprint prompt in the middle of their own action is the
     * app fighting them.
     */
    static void request(Activity activity) {
        AppLock.expectReturn();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                activity.startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable noDirectPage) {
                try {
                    activity.startActivity(
                            new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable noListPage) {
                    // Last resort: the app's own details page, which every Android build has.
                    activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName())));
                }
            }
            return;
        }
        activity.requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
    }

    /**
     * What to say on the Settings row, in the state it is actually in.
     *
     * Three states, not two: off, on, and "turned on here but Android has not granted it" --
     * which is what happens when someone flips the switch and then backs out of the Settings
     * page without granting. Showing that as simply "on" is how an owner ends up believing a
     * folder exists that does not.
     */
    static String state(Context context) {
        boolean asked = Prefs.of(context).getBoolean(Prefs.PHONE_FILES, false);
        if (!asked) return "Off · Linux cannot see the phone's files";
        if (!allowed(context)) return "Waiting for Android to grant it · tap to finish";
        return "On · the phone's storage is ~/phone inside Linux";
    }
}
