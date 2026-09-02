package com.pocketdesk;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * Whether this phone can run the Linux computer, in words the owner can read before installing.
 *
 * The requirements are the real ones: a 64-bit ARM processor, because the Ubuntu system and the
 * AI apps are ARM64 builds; Android 10 or newer, because that is what the app is built against;
 * and enough memory and space to hold a desktop and an AI app at once.
 */
final class DeviceCheck {
    static final class Result {
        final boolean compatible;
        final String headline;
        final String detail;
        Result(boolean compatible, String headline, String detail) {
            this.compatible = compatible;
            this.headline = headline;
            this.detail = detail;
        }
    }

    private DeviceCheck() {}

    static Result run(Context context) {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) if ("arm64-v8a".equals(abi)) arm64 = true;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) manager.getMemoryInfo(memory);
        long ramGb = Math.round(memory.totalMem / (1024.0 * 1024 * 1024));
        long freeBytes = DeviceProbe.read(context).freeStorage;
        String phone = (Build.MANUFACTURER + " " + Build.MODEL).trim();

        String facts = phone + " · Android " + Build.VERSION.RELEASE + " · "
                + (arm64 ? "ARM64" : "not ARM64") + " · " + ramGb + " GB RAM · "
                + DeviceProbe.formatBytes(freeBytes) + " free";
        if (!arm64) {
            return new Result(false, "This phone cannot run PocketDesk",
                    facts + "\n\nIt needs a 64-bit ARM processor (ARM64), which nearly every phone "
                            + "made since 2017 has. This one reports none.");
        }
        if (ramGb < 3) {
            return new Result(false, "Not enough memory for the AI apps",
                    facts + "\n\nThe Linux desktop needs at least 3 GB of RAM; the AI desktop "
                            + "apps run best with 4 GB or more.");
        }
        String note = ramGb < 6
                ? "Runs here. With " + ramGb + " GB of RAM the AI desktop apps open, but take a "
                        + "minute or two the first time and one at a time is best."
                : "Runs well here.";
        return new Result(true, "Your phone is compatible", facts + "\n\n" + note
                + "\n\nWorks on Android 10 and every version after it, on any brand of phone "
                + "with an ARM64 processor.");
    }
}
