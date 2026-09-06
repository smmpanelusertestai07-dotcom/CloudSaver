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
 * (build.sh sets the same minimum, and the tests check the two agree); memory; and free space.
 *
 * There are two answers to "will it run here", not one, and saying so is the point of this file.
 * The AI desktop apps are Chromium programs: each one needs roughly 700 MB of its own, which is
 * a fact about Chromium and not something any amount of care here can change. The Linux computer
 * underneath them is not: a desktop, a terminal, an editor, the file manager and the whole
 * development toolchain run in a few hundred megabytes. A 2 GB phone was refused outright before
 * this, and it was refused for something it was never going to be asked to do.
 */
final class DeviceCheck {
    /** Must equal --min-sdk-version in build.sh; tests/run-tests.sh checks that it does. */
    static final int MIN_SDK = 29;
    static final int TARGET_SDK = 35;
    /** Enough for the four AI desktop apps. Each is a Chromium program with its own ~700 MB. */
    static final long MIN_RAM_GB = 4;
    /** Enough for the Linux computer itself: desktop, terminal, editor, files, dev tools. */
    static final long MIN_RAM_GB_DESKTOP = 2;
    /**
     * Marketed RAM and reported RAM are not the same number: the kernel, the bootloader and any
     * carved-out video memory come off the top before totalMem, so a phone sold as 4 GB reports
     * about 3.6. Rounding hid that and then refused it anyway on a bad day; an explicit tolerance
     * says out loud how much slack the threshold has.
     */
    static final long RAM_TOLERANCE_BYTES = 700_000_000L;
    /** Decimal, like Android's own Settings screen prints sizes. */
    static final long MIN_FREE_BYTES = 6_000_000_000L;
    /**
     * The desktop-only path: the Ubuntu base and the desktop packages, apt's unpack space, and
     * the breathing room below. This, not memory, is what actually stops an Android Go phone.
     */
    static final long MIN_FREE_BYTES_DESKTOP = 3_500_000_000L;
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
        /** True when the Linux computer will run here but the AI desktop apps will not. */
        boolean desktopOnly;
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
                + "ARM processor (ARM64). The AI desktop apps need " + MIN_RAM_GB + " GB of RAM and "
                + DeviceProbe.formatBytes(MIN_FREE_BYTES) + " free; the Linux computer on its own "
                + "needs " + MIN_RAM_GB_DESKTOP + " GB and "
                + DeviceProbe.formatBytes(MIN_FREE_BYTES_DESKTOP) + ".";
    }

    /**
     * A phone Android itself calls low-memory, or one with under 3 GB.
     *
     * Android sets the flag on devices configured for the low-RAM profile, which is what an
     * Android (Go edition) phone is, and the whole app can use it to choose smaller defaults
     * without asking the owner to find a setting: a smaller framebuffer, a cheaper pixel format,
     * no wide workspace, no opening splash.
     */
    static boolean isSmallPhone(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null && manager.isLowRamDevice()) return true;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        return memory.totalMem > 0 && memory.totalMem < 3_000_000_000L;
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
        // Bytes, with a stated tolerance, rather than a rounded gigabyte count: a phone sold as
        // 4 GB reports about 3.6, and rounding turned that into a refusal.
        boolean enoughForApps = memory.totalMem
                >= MIN_RAM_GB * 1_000_000_000L - RAM_TOLERANCE_BYTES;
        boolean enoughForDesktop = memory.totalMem
                >= MIN_RAM_GB_DESKTOP * 1_000_000_000L - RAM_TOLERANCE_BYTES;
        if (!enoughForDesktop) {
            return new Result(false, "Not enough memory for a Linux computer",
                    facts + "\n\n" + requirements() + "\n\nWith " + ramGb + " GB of RAM there is "
                            + "not enough left for the desktop itself once Android has taken its "
                            + "share.");
        }
        boolean installed = ContainerRuntime.isInstalled(context);
        long spaceNeeded = enoughForApps ? MIN_FREE_BYTES : MIN_FREE_BYTES_DESKTOP;
        if (!installed && freeBytes < spaceNeeded) {
            return new Result(false, "Free up space first",
                    facts + "\n\n" + requirements() + "\n\nDelete or move "
                            + DeviceProbe.formatBytes(spaceNeeded - freeBytes)
                            + " and this phone qualifies.", true);
        }
        if (!enoughForApps) {
            // Not a refusal. The computer runs here; four Chromium programs do not, and saying
            // which is which up front is better than an app that opens and quietly dies.
            Result desktop = new Result(true, "The Linux computer runs here; the AI apps will not",
                    facts + "\n\nWith " + ramGb + " GB of RAM this phone runs the Linux desktop, "
                            + "the terminal, the editor, the file manager and the whole development "
                            + "toolchain -- Python, Node, Java, Git and the mobile-app tools.\n\n"
                            + "ChatGPT, Claude, Cursor and Antigravity are Chromium programs and "
                            + "each one needs about 700 MB of its own. PocketLinux will say so "
                            + "rather than starting one that cannot stay open. That is a fact "
                            + "about those apps, not a limit this app could lift.\n\n"
                            + "Requirements: " + requirements());
            desktop.desktopOnly = true;
            return desktop;
        }
        String note = ramGb < 6
                ? "With " + ramGb + " GB of RAM the AI desktop apps open and run; the first open "
                        + "takes a minute or two, and one AI app at a time is best."
                : "Runs well here.";
        return new Result(true, "Your phone is compatible",
                facts + "\n\n" + note + "\n\nRequirements: " + requirements());
    }
}
