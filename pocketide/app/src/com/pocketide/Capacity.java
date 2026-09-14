package com.pocketide;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.StatFs;

import java.util.Locale;

/**
 * What this computer actually is, in the numbers of the phone it is running on.
 *
 * "Ubuntu on your phone" tells an owner nothing about whether their project will build. The
 * questions they really have are how much memory it gets, how many cores, how much room, and
 * how big a thing they can build before it stops working -- and every one of those has a real
 * answer on THIS phone rather than a general one.
 *
 * Two of the answers are counter-intuitive enough to be worth stating plainly, because getting
 * them wrong is what sends someone off to buy a phone they did not need:
 *
 *   THE APK'S SIZE IS NOT THE LIMIT. A 200 MB APK is no harder to produce than a 2 MB one --
 *   it is bytes through a zip. What costs memory is the compiler: how many modules, how many
 *   source files, how large the dependency graph, and whether R8 has to rewrite the whole
 *   program at the end. A small app with two hundred dependencies is a heavier build than a
 *   large app with ten.
 *
 *   THERE IS NO FIXED ALLOCATION. Nothing here reserves 2 GB or 4 GB. Linux gets what Android
 *   is not using, moment to moment, and what takes it away is another app being opened. The one
 *   hard ceiling is the Android 17 memory limiter, which is per-app, derived from the device's
 *   total RAM, and cannot be opted out of -- see Exits.java, which is what reports it when it
 *   fires.
 *
 * The Java heap limit an Android developer would reach for -- ActivityManager.getMemoryClass()
 * -- is deliberately NOT used here, and it is the obvious mistake to make. It bounds the app's
 * own Dalvik heap. Everything in the workspace is a separate native process started through
 * PRoot, with its own address space, and none of them are inside that heap. Quoting it would
 * have told every owner their computer had 256 MB.
 */
final class Capacity {

    /** Everything the screen needs, measured rather than assumed. */
    static final class Reading {
        final int cores;
        final long totalRam;
        final long availableRam;
        final long freeStorage;
        final long workspaceBytes;      // -1 until it has been measured
        final int buildHeapMb;
        final int workers;

        Reading(int cores, long totalRam, long availableRam, long freeStorage,
                long workspaceBytes, int buildHeapMb, int workers) {
            this.cores = cores;
            this.totalRam = totalRam;
            this.availableRam = availableRam;
            this.freeStorage = freeStorage;
            this.workspaceBytes = workspaceBytes;
            this.buildHeapMb = buildHeapMb;
            this.workers = workers;
        }
    }

    private Capacity() {}

    /** Cheap: no PRoot, no disk walk. Safe on the drawing thread. */
    static Reading read(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(memory);

        StatFs storage = new StatFs(context.getFilesDir().getAbsolutePath());
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return new Reading(cores, memory.totalMem, memory.availMem, storage.getAvailableBytes(),
                -1, buildHeapMb(memory.totalMem), workers(memory.totalMem, cores));
    }

    /**
     * The build heap this phone gets, matching pocketide-tools.sh exactly.
     *
     * The same ladder lives in both places because both have to be true at once: the script
     * writes the number into Gradle's settings, and this screen tells the owner what was
     * written. A gate compares them, so they cannot drift apart silently.
     */
    static int buildHeapMb(long totalRam) {
        if (totalRam >= 11_500_000L * 1024) return 3072;
        if (totalRam >= 7_500_000L * 1024) return 2048;
        if (totalRam >= 5_500_000L * 1024) return 1536;
        if (totalRam >= 3_500_000L * 1024) return 1024;
        return 768;
    }

    static int workers(long totalRam, int cores) {
        int byMemory;
        if (totalRam >= 11_500_000L * 1024) byMemory = 4;
        else if (totalRam >= 7_500_000L * 1024) byMemory = 3;
        else if (totalRam >= 3_500_000L * 1024) byMemory = 2;
        else byMemory = 1;
        return Math.max(1, Math.min(byMemory, cores));
    }

    /** One line for the row: cores, memory, room. */
    static String oneLine(Reading reading) {
        return reading.cores + (reading.cores == 1 ? " core · " : " cores · ")
                + DeviceProbe.formatBytes(reading.totalRam) + " RAM · "
                + DeviceProbe.formatBytes(reading.freeStorage) + " free";
    }

    /**
     * The full account, written for someone deciding whether to start a real project here.
     *
     * Every number in it is either measured from this phone or published by the people who
     * decide it. Nothing is a benchmark, because a benchmark run on one phone and quoted for
     * every phone is the kind of claim that makes the rest of a screen untrustworthy.
     */
    static String describe(Context context, Reading reading) {
        StringBuilder words = new StringBuilder();

        words.append("THIS PHONE, RIGHT NOW\n\n")
                .append("Processor · ").append(reading.cores)
                .append(reading.cores == 1 ? " core" : " cores")
                .append(", ").append(Build.SUPPORTED_ABIS.length == 0
                        ? "arm64" : Build.SUPPORTED_ABIS[0])
                .append(". Linux sees every one of them. PRoot translates system calls; it does "
                        + "not emulate a processor, so code here runs at the phone's own speed "
                        + "rather than a fraction of it.\n\n")

                .append("Memory · ").append(DeviceProbe.formatBytes(reading.totalRam))
                .append(" in the phone, ").append(DeviceProbe.formatBytes(reading.availableRam))
                .append(" free at this moment. None of it is reserved for Linux: it takes what "
                        + "Android is not using, and gives it back when another app wants it. "
                        + "Closing a few apps before a long build is the single most effective "
                        + "thing you can do.\n\n")

                .append("Storage · ").append(DeviceProbe.formatBytes(reading.freeStorage))
                .append(" free. Linux lives inside this app's own storage on the phone's "
                        + "internal drive — not on an SD card, which Android does not allow an "
                        + "app to use this way. Uninstalling PocketIDE deletes all of it.\n\n")

                .append("Build memory · ").append(reading.buildHeapMb).append(" MB, with ")
                .append(reading.workers).append(reading.workers == 1
                        ? " module compiled at a time" : " modules compiled at a time")
                .append(". Both are worked out from the memory above and written into Gradle's "
                        + "own settings when the Android build tools are installed. They can be "
                        + "changed in ~/.gradle/gradle.properties like on any other machine.\n\n")

                .append("HOW BIG A PROJECT CAN BE BUILT\n\n")

                .append("The APK's size is not the limit. A 200 MB APK is no harder to produce "
                        + "than a 2 MB one — it is bytes through a zip file. What costs memory "
                        + "is the compiler, and what decides that is the number of modules, the "
                        + "number of source files, the size of the dependency graph, and "
                        + "whether R8 has to rewrite the whole program at the end. A small app "
                        + "with two hundred dependencies is a heavier build than a large app "
                        + "with ten.\n\n")

                .append("So the honest answer is a shape rather than a number:\n\n")

                .append("· A single-module app — one screen or fifty — builds comfortably on "
                        + "any phone this app will install on.\n")
                .append("· A multi-module app builds fine; each extra module in parallel wants "
                        + "its own memory, which is why the worker count above is capped.\n")
                .append("· A release build with R8 minification is the heaviest step in any "
                        + "Android build, and the one most likely to need more heap than a "
                        + "phone with 4 GB can give. Building debug first, and release when "
                        + "the phone is otherwise idle, is what makes it finish.\n")
                .append("· Anything with C or C++ in it cannot be built at all, on any phone. "
                        + "Google publishes no arm64 Android NDK. That is their decision, not a "
                        + "limit of this hardware.\n\n")

                .append("Websites, servers, command-line programs and anything else that "
                        + "compiles for arm64 Linux have no such ceiling. Node, Deno, Python, "
                        + "Go, Rust, Ruby, PHP, C and C++ all build here natively, at full "
                        + "speed, for this machine.\n\n")

                .append("WHAT ACTUALLY STOPS A BUILD\n\n")

                .append("Memory, and specifically Android taking it back. From Android 17 "
                        + "(June 2026) there is a per-app memory ceiling derived from the "
                        + "device's total RAM, applied whatever an app targets, with no way to "
                        + "opt out. A workspace running the editor, an extension host and a "
                        + "compiler is exactly the shape it is aimed at. When it fires, "
                        + "PocketIDE reads Android's own record and says so on the Home screen "
                        + "rather than leaving you to guess.\n\n")

                .append("Heat, second. A phone has no fan. A long build raises the temperature "
                        + "until Android slows the processor down to protect it — the build "
                        + "still finishes, it just takes longer. The Home screen shows the "
                        + "phone's thermal state for this reason.\n\n")

                .append("Battery, third. A twenty-minute build with the screen off is exactly "
                        + "what a phone's battery manager exists to stop. The permission rows "
                        + "in Settings are the switches that let it continue.\n\n")

                .append("WHAT MAKES IT FASTER\n\n")

                .append("· Plug the phone in. Charging relaxes both the battery manager and, on "
                        + "most phones, the processor's own governor.\n")
                .append("· Close other apps before a long build, for the memory.\n")
                .append("· Leave Gradle's build cache on — it is on by default here — so the "
                        + "second build of a project does a fraction of the work of the first.\n")
                .append("· Keep the phone out of a case and off a bed or sofa. Heat is the "
                        + "limit long before the processor is.\n");

        return words.toString();
    }

    /** How the phone compares to what a project's own documentation usually assumes. */
    static String shortVerdict(Reading reading) {
        long gb = Math.round(reading.totalRam / 1e9);
        if (gb >= 12) return "Large projects, including release builds with minification";
        if (gb >= 8) return "Most real projects, including multi-module ones";
        if (gb >= 6) return "Real projects; release builds want the phone otherwise idle";
        if (gb >= 4) return "Single-module apps and web projects comfortably";
        return String.format(Locale.ROOT, "Small projects; %d GB is tight for a compiler", gb);
    }
}
