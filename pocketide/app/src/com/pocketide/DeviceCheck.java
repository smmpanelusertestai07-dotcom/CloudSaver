package com.pocketide;

import android.content.Context;
import android.os.Build;

/**
 * Whether this phone can run the workspace, answered before anything is downloaded.
 *
 * Every check here exists because failing it later costs an owner far more than failing it now:
 * a 64-bit-only rootfs on a 32-bit phone fails after the download, and running out of space
 * fails after twenty minutes of it. The answers are read from the phone -- there is nothing to
 * type in and nothing to guess.
 */
final class DeviceCheck {
    /** The base image plus the tools plus the editor, before any agent is added. */
    static final long NEEDED_BYTES = 1_400L * 1000 * 1000;
    static final int MIN_SDK = 29;

    private DeviceCheck() {}

    /** The reason this phone cannot proceed, or null when it can. */
    static String refusal(Context context) {
        if (!DeviceProbe.isArm64()) {
            return "This phone is not 64-bit ARM (arm64-v8a), which is the only architecture "
                    + "Ubuntu's ARM image and the editor are published for.";
        }
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            return "This app needs Android 10 or newer. This phone is on Android "
                    + Build.VERSION.RELEASE + ".";
        }
        if (!DeviceProbe.hasInternet(context)) {
            return "There is no internet connection. Set-up downloads about "
                    + DeviceProbe.formatBytes(BuildFacts.BASE_DOWNLOAD_BYTES) + ".";
        }
        long free = Workspace.freeBytes(context);
        if (free < NEEDED_BYTES) {
            return "About " + DeviceProbe.formatBytes(NEEDED_BYTES) + " of free space is needed "
                    + "and this phone has " + DeviceProbe.formatBytes(free) + ". Delete or move "
                    + "some files and try again.";
        }
        return null;
    }

    /** A short line for the Home card: what this phone is, and whether it qualifies. */
    static String summary(Context context) {
        DeviceProbe probe = DeviceProbe.read(context);
        return probe.model + " · " + probe.androidVersion + " · " + probe.abi
                + " · " + DeviceProbe.formatBytes(probe.totalRam) + " RAM";
    }

    static boolean ready(Context context) {
        return refusal(context) == null;
    }
}
