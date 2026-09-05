package com.pocketlinux;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * Whether this phone can run the Linux computer, in words the owner can read before installing.
 *
 * The requirements are the real ones, and they live here as constants so every sentence in the
 * app is built from the same numbers: a 64-bit ARM processor, because the Ubuntu system and
 * the AI apps are ARM64 builds; Android 10 or newer, which is what the APK is built for
 * (build.sh sets the same minimum, and the tests check the two agree); 4 GB of RAM, because
 * the AI desktop apps are full computer programs; and enough free space to set up.
 */
final class DeviceCheck {
    /** Must equal --min-sdk-version in build.sh; tests/run-tests.sh checks that it does. */
    static final int MIN_SDK = 29;
    static final int TARGET_SDK = 35;
    static final long MIN_RAM_GB = 4;
    /** Decimal, like Android's own Settings screen prints sizes. */
    static final long MIN_FREE_BYTES = 6_000_000_000L;
    /**
     * Once installed, the desktop still needs room to breathe. Android itself only warns at
     * min(5 % of the partition, 500 MB) and starts deleting app caches there, so the figure the
     * app asks for is the one that still leaves room to act on: a browser cache, an app's
     * temporary unpack space, and a day's downloads.
     */
    static final long LOW_FREE_BYTES = 2_000_000_000L;

    static final class Result {
        final boolean compatible;
        final String headline;
        final String detail;
        /** True when free space is the only thing standing in the way. */
        final boolean onlySpace;
        Result(boolean compatible, String headline, String detail) {
            this(compatible, headline, detail, false);
        }
        Result(boolean compatible, String headline, String detail, boolean onlySpace) {
            this.compatible = compatible;
            this.headline = headline;
            this.detail = detail;
            this.onlySpace = onlySpace;
        }
    }

    private DeviceCheck() {}

    /** "Android 10" for API 29, and so on, for the sentences that name a version. */
    static String releaseName(int sdk) {
        switch (sdk) {
            case 29: return "Android 10";
            case 30: return "Android 11";
            case 31: case 32: return "Android 12";
            case 33: return "Android 13";
            case 34: return "Android 14";
            case 35: return "Android 15";
            case 36: return "Android 16";
            default: return "Android (API " + sdk + ")";
        }
    }

    /** One sentence for the requirements, used wherever they are stated. */
    static String requirements() {
        return "Works on " + releaseName(MIN_SDK) + " and above, on any brand of phone with a 64-bit "
                + "ARM processor (ARM64), " + MIN_RAM_GB + " GB of RAM and "
                + DeviceProbe.formatBytes(MIN_FREE_BYTES) + " free to set up.";
    }

    static Result run(Context context) {
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) if ("arm64-v8a".equals(abi)) arm64 = true;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) manager.getMemoryInfo(memory);
        long ramGb = Math.round(memory.totalMem / 1e9);
        long freeBytes = DeviceProbe.read(context).freeStorage;
        String phone = (Build.MANUFACTURER + " " + Build.MODEL).trim();

        String facts = phone + " · Android " + Build.VERSION.RELEASE + " · "
                + (arm64 ? "ARM64" : "not ARM64") + " · " + ramGb + " GB RAM · "
                + DeviceProbe.formatBytes(freeBytes) + " free";
        if (!arm64) {
            return new Result(false, "This phone cannot run PocketLinux",
                    facts + "\n\n" + requirements() + "\n\nThis phone reports no 64-bit ARM "
                            + "processor, which nearly every phone made since 2017 has.");
        }
        if (ramGb < MIN_RAM_GB) {
            return new Result(false, "Not enough memory for the AI desktop apps",
                    facts + "\n\n" + requirements() + "\n\nThe AI desktop apps are full computer "
                            + "programs; with " + ramGb + " GB of RAM they would not stay open.");
        }
        boolean installed = ContainerRuntime.isInstalled(context);
        if (!installed && freeBytes < MIN_FREE_BYTES) {
            return new Result(false, "Free up space first",
                    facts + "\n\n" + requirements() + "\n\nDelete or move "
                            + DeviceProbe.formatBytes(MIN_FREE_BYTES - freeBytes)
                            + " and this phone qualifies.", true);
        }
        String note = ramGb < 6
                ? "With " + ramGb + " GB of RAM the AI desktop apps open and run; the first open "
                        + "takes a minute or two, and one AI app at a time is best."
                : "Runs well here.";
        return new Result(true, "Your phone is compatible",
                facts + "\n\n" + note + "\n\nRequirements: " + requirements());
    }
}
