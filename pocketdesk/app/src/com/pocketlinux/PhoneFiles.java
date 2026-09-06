package com.pocketlinux;

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
 * The phone's own storage, inside the Linux computer as the folder "Phone".
 *
 * Off by default: PocketLinux asks for nothing it does not need, and a computer that cannot see
 * the phone's files is a safe computer. On, the phone's storage is bound into the container at
 * /home/coder/Phone, so ChatGPT's "attach a file" dialog, the browser's uploads and the file
 * manager all show the phone's Download, DCIM and Documents folders next to the computer's
 * own. Android 11 and newer call this "All files access" and grant it on a Settings page;
 * Android 10 grants it with the ordinary storage permission.
 */
final class PhoneFiles {
    static final int REQUEST_STORAGE = 44;

    private PhoneFiles() {}

    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** /storage/emulated/0: Download, DCIM, Documents, Pictures and everything else. */
    static File root() {
        return Environment.getExternalStorageDirectory();
    }

    /** Opens the place Android grants it, or asks directly on Android 10. */
    static void request(Activity activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable error) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable ignored) {
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
}
