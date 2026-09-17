package com.pocketide;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Why the app stopped last time, in words rather than silence.
 *
 * This exists because of the single worst failure a phone development environment has: the
 * owner leaves the editor to answer a message, comes back, and everything is gone -- the
 * terminal, the build, the agent's half-finished work. Nothing on screen says why, because
 * nothing on screen can: the process that would have explained it is the one that was killed.
 *
 * Android does keep the answer, in ApplicationExitInfo, and almost nothing reads it. Four of
 * the reasons matter here and each needs a different response from the owner, which is exactly
 * why lumping them together as "it crashed" is useless:
 *
 *   The Android 17 memory limiter. Shipped June 2026, and it applies REGARDLESS of what the
 *   app targets, so there is no manifest line that opts out of it. Android sets a per-app
 *   memory ceiling from the device's total RAM, pushes the app's pages into zRAM as it
 *   approaches, and then kills it. A workspace running the editor, an extension host and a
 *   headless browser is exactly the shape of thing it is aimed at. It reports itself as
 *   REASON_OTHER with "MemoryLimiter:AnonSwap" in the description, and the number it measures
 *   is RssAnon + VmSwap rather than the RSS most tools show.
 *
 *   The Task Manager's Stop button. Android 13 and later. It kills the whole app with no
 *   callback of any kind -- there is no onDestroy, no chance to save, nothing. An owner who
 *   used it deliberately does not need an explanation; one who tapped it thinking it would
 *   just close the window does.
 *
 *   The low-memory killer, which is the older and blunter one, and usually means something
 *   else on the phone wanted the memory.
 *
 *   A genuine crash or ANR in this app, which is the only one of the four that is this app's
 *   fault and the only one worth reporting as a bug.
 *
 * Nothing here is a permission. ApplicationExitInfo only ever returns this app's own records.
 */
final class Exits {

    /** One past exit, already translated. */
    static final class Exit {
        final long when;
        final String headline;
        final String explanation;
        final boolean worthShowing;

        Exit(long when, String headline, String explanation, boolean worthShowing) {
            this.when = when;
            this.headline = headline;
            this.explanation = explanation;
            this.worthShowing = worthShowing;
        }
    }

    private Exits() {}

    /** Whether Linux was up when this process last died. Read once, at process start. */
    private static volatile boolean linuxWasRunningAtLastExit;

    /**
     * Called once per process start, before the service can write the flag again.
     *
     * The flag is the service's: true while Linux runs, false once it has stopped tidily, and
     * whatever it was if the process was killed. Read here and put back to false, so that it
     * describes the death that just happened and not one from a week ago.
     */
    static void noteStart(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        linuxWasRunningAtLastExit = prefs.getBoolean(Prefs.LINUX_WAS_RUNNING, false);
        if (linuxWasRunningAtLastExit) {
            prefs.edit().putBoolean(Prefs.LINUX_WAS_RUNNING, false).apply();
        }
    }

    /**
     * The most recent exit worth telling the owner about, or null.
     *
     * "Worth telling about" excludes the ordinary ones. An app that was swiped away, or that
     * exited because the owner stopped the workspace, does not need a notice the next time it
     * opens -- a screen that explains things nobody asked about is a screen people stop
     * reading.
     */
    static Exit last(Context context) {
        if (Build.VERSION.SDK_INT < 30) return null;      // ApplicationExitInfo is Android 11+
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return null;
        List<ApplicationExitInfo> records;
        try {
            records = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
        } catch (Throwable unavailable) {
            return null;
        }
        if (records == null || records.isEmpty()) return null;

        for (int i = 0; i < records.size(); i++) {
            // Only the newest record can be matched to the flag; an older stop is not told
            // apart from an idle app being closed, so it is left alone.
            Exit exit = translate(records.get(i), i == 0 && linuxWasRunningAtLastExit);
            if (exit != null && exit.worthShowing) return exit;
        }
        return null;
    }

    private static Exit translate(ApplicationExitInfo info, boolean linuxWasRunning) {
        String description = info.getDescription() == null ? "" : info.getDescription();
        long when = info.getTimestamp();

        // Checked before the reason, because Android reports it as REASON_OTHER and the
        // description is the only thing that distinguishes it.
        if (description.contains("MemoryLimiter")) {
            return new Exit(when, "Android ran Linux out of memory",
                    "Android 17 gives every app a memory ceiling worked out from the phone's "
                            + "total RAM, and stops the app when it is reached. It applies to "
                            + "every app on the phone and there is no setting that turns it "
                            + "off.\n\n"
                            + "The editor, its extension host and a headless browser together "
                            + "are what usually reaches it. Running fewer of them at once is "
                            + "the only real answer: close the browser between tasks, and on a "
                            + "4 GB phone do not leave a build running while one is open.",
                    true);
        }

        switch (info.getReason()) {
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                // Stopping an app that had nothing running is not worth a notice, and the
                // explanation below -- "Linux had no chance to shut down" -- would be untrue.
                if (!linuxWasRunning) return new Exit(when, "", "", false);
                return new Exit(when, "The app was stopped from the phone's Task Manager",
                        "Android's Task Manager has a Stop button beside apps that are running "
                                + "in the background. It stops the whole app at once, without "
                                + "telling it first, so Linux had no chance to shut "
                                + "down tidily.\n\n"
                                + "Nothing was lost: files are written as they go. Anything the "
                                + "agent was part way through will need starting again.",
                        true);

            case ApplicationExitInfo.REASON_LOW_MEMORY:
                if (!linuxWasRunning) return new Exit(when, "", "", false);
                return new Exit(when, "The phone ran short of memory",
                        "Something else on the phone needed the memory and Android reclaimed it "
                                + "from here. It is likelier when several large apps are open; "
                                + "closing them, and closing the browser when an agent is not "
                                + "using it, makes it less likely.",
                        true);

            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return new Exit(when, "The app stopped unexpectedly",
                        "This one is this app's fault rather than the phone's. Linux and "
                                + "its files are untouched — they live in their own storage.",
                        true);

            case ApplicationExitInfo.REASON_ANR:
                return new Exit(when, "The app stopped responding",
                        "Something took too long on the screen's own thread and Android ended "
                                + "it. Linux and its files are untouched.",
                        true);

            default:
                // Swiped away, stopped normally, updated, or any of the ordinary reasons. Not
                // worth a notice.
                return new Exit(when, "", "", false);
        }
    }

    /**
     * The footprint the Android 17 limiter measures: RssAnon + VmSwap, summed over every
     * process this app has -- its own AND the whole workspace.
     *
     * Not RSS and not VSS, which is why the number here will not match what a task manager
     * shows.
     *
     * The sum is the correction. This used to read /proc/self/status alone, so it returned the
     * Android process's footprint and nothing else -- and the screen printed it as "counted
     * against Android's limit" directly beside a workspace total in the gigabytes. Everything
     * that actually consumes memory here is a PRoot child: the editor, its extension host, a
     * compiler. Reporting the app process alone meant the row sat at around a hundred megabytes
     * and gave no warning at all, right up to the kill this class then explains as "Android ran
     * Linux out of memory".
     *
     * The limiter is per-app, so a child process under the app's own uid counts against the
     * same ceiling as the app. Nothing outside this app is readable here and nothing is asked
     * for: Android has restricted /proc since Nougat to a process's own and its children's.
     *
     * The list is passed in rather than fetched, because the one caller has already walked
     * /proc to build it and doing it twice per refresh is a second pass over every process on
     * the phone for a number it already has.
     */
    static long footprintBytes(List<Running.Process> workspace) {
        long total = kilobytesOf(new File("/proc/self/status"));
        for (Running.Process process : workspace) {
            total += kilobytesOf(new File("/proc/" + process.pid + "/status"));
        }
        return total * 1024;
    }

    /** RssAnon + VmSwap out of one /proc/<pid>/status, in kilobytes. Zero if it is gone. */
    private static long kilobytesOf(File status) {
        long anon = 0, swap = 0;
        try {
            for (String line : new String(Files.readAllBytes(status.toPath()),
                    StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("RssAnon:")) anon = kilobytes(line);
                else if (line.startsWith("VmSwap:")) swap = kilobytes(line);
            }
        } catch (Throwable unreadable) {
            // A process that exited between the listing and this read is not an error; it is
            // simply no longer part of the footprint.
            return 0;
        }
        return anon + swap;
    }

    private static long kilobytes(String line) {
        String[] parts = line.split("\\s+");
        for (String part : parts) {
            try {
                return Long.parseLong(part);
            } catch (NumberFormatException notTheNumber) {
                // The label and the unit. Keep looking.
            }
        }
        return 0;
    }

    /**
     * Android's own record of the last three exits, one line each, for the recovery screen.
     *
     * Reason names rather than numbers, because the number is what the owner would have to
     * look up and the name is what whoever helps them needs to read: CRASH, ANR, LOW_MEMORY,
     * EXCESSIVE_RESOURCE_USAGE, OTHER with the system's own description -- which on a phone
     * whose maker kills apps of its own accord is the one line that says so.
     */
    static String recent(Context context) {
        if (Build.VERSION.SDK_INT < 30) return "";
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return "";
        List<ApplicationExitInfo> records;
        try {
            records = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 3);
        } catch (Throwable unavailable) {
            return "";
        }
        if (records == null || records.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        SimpleDateFormat clock = new SimpleDateFormat("d MMM HH:mm",
                Locale.ROOT);
        for (ApplicationExitInfo info : records) {
            out.append("  ").append(clock.format(new Date(info.getTimestamp())))
                    .append("  ").append(reasonName(info.getReason()))
                    .append(info.getStatus() != 0 ? " status " + info.getStatus() : "")
                    .append(info.getDescription() == null || info.getDescription().isEmpty()
                            ? "" : " — " + info.getDescription())
                    .append('\n');
        }
        return out.toString();
    }

    /** The platform's constant names, for people rather than for the compiler. */
    private static final Map<Integer, String> REASON_NAMES = new HashMap<>();

    static {
        REASON_NAMES.put(ApplicationExitInfo.REASON_EXIT_SELF, "EXIT_SELF");
        REASON_NAMES.put(ApplicationExitInfo.REASON_SIGNALED, "SIGNALED");
        REASON_NAMES.put(ApplicationExitInfo.REASON_LOW_MEMORY, "LOW_MEMORY");
        REASON_NAMES.put(ApplicationExitInfo.REASON_CRASH, "CRASH");
        REASON_NAMES.put(ApplicationExitInfo.REASON_CRASH_NATIVE, "CRASH_NATIVE");
        REASON_NAMES.put(ApplicationExitInfo.REASON_ANR, "ANR");
        REASON_NAMES.put(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                "INITIALIZATION_FAILURE");
        REASON_NAMES.put(ApplicationExitInfo.REASON_PERMISSION_CHANGE, "PERMISSION_CHANGE");
        REASON_NAMES.put(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                "EXCESSIVE_RESOURCE_USAGE");
        REASON_NAMES.put(ApplicationExitInfo.REASON_USER_REQUESTED, "USER_REQUESTED");
        REASON_NAMES.put(ApplicationExitInfo.REASON_USER_STOPPED, "USER_STOPPED");
        REASON_NAMES.put(ApplicationExitInfo.REASON_DEPENDENCY_DIED, "DEPENDENCY_DIED");
        REASON_NAMES.put(ApplicationExitInfo.REASON_OTHER, "OTHER");
    }

    private static String reasonName(int reason) {
        String name = REASON_NAMES.get(reason);
        return name == null ? "REASON_" + reason : name;
    }
}
