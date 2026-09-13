package com.pocketide;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.util.List;

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
        final String raw;

        Exit(long when, String headline, String explanation, boolean worthShowing, String raw) {
            this.when = when;
            this.headline = headline;
            this.explanation = explanation;
            this.worthShowing = worthShowing;
            this.raw = raw;
        }
    }

    private Exits() {}

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

        for (ApplicationExitInfo info : records) {
            Exit exit = translate(info);
            if (exit != null && exit.worthShowing) return exit;
        }
        return null;
    }

    private static Exit translate(ApplicationExitInfo info) {
        String description = info.getDescription() == null ? "" : info.getDescription();
        long when = info.getTimestamp();
        String raw = "reason=" + info.getReason()
                + " status=" + info.getStatus()
                + " importance=" + info.getImportance()
                + (description.isEmpty() ? "" : " description=" + description);

        // Checked before the reason, because Android reports it as REASON_OTHER and the
        // description is the only thing that distinguishes it.
        if (description.contains("MemoryLimiter")) {
            return new Exit(when, "Android ran the workspace out of memory",
                    "Android 17 gives every app a memory ceiling worked out from the phone's "
                            + "total RAM, and stops the app when it is reached. It applies to "
                            + "every app on the phone and there is no setting that turns it "
                            + "off.\n\n"
                            + "The editor, its extension host and a headless browser together "
                            + "are what usually reaches it. Running fewer of them at once is "
                            + "the only real answer: close the browser between tasks, and on a "
                            + "4 GB phone do not leave a build running while one is open.",
                    true, raw);
        }

        switch (info.getReason()) {
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return new Exit(when, "The app was stopped from the phone's Task Manager",
                        "Android's Task Manager has a Stop button beside apps that are running "
                                + "in the background. It stops the whole app at once, without "
                                + "telling it first, so the workspace had no chance to shut "
                                + "down tidily.\n\n"
                                + "Nothing was lost: files are written as they go. Anything the "
                                + "agent was part way through will need starting again.",
                        true, raw);

            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return new Exit(when, "The phone ran short of memory",
                        "Something else on the phone needed the memory and Android reclaimed it "
                                + "from here. It is likelier when several large apps are open.\n\n"
                                + "Turning on Battery unrestricted in Settings makes it less "
                                + "likely, and so does closing the browser when an agent is not "
                                + "using it.",
                        true, raw);

            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return new Exit(when, "The app stopped unexpectedly",
                        "This one is this app's fault rather than the phone's. The workspace and "
                                + "its files are untouched — they live in their own storage.",
                        true, raw);

            case ApplicationExitInfo.REASON_ANR:
                return new Exit(when, "The app stopped responding",
                        "Something took too long on the screen's own thread and Android ended "
                                + "it. The workspace and its files are untouched.",
                        true, raw);

            default:
                // Swiped away, stopped normally, updated, or any of the ordinary reasons. Not
                // worth a notice.
                return new Exit(when, "", "", false, raw);
        }
    }

    /**
     * This app's memory footprint as the Android 17 limiter measures it: RssAnon + VmSwap.
     *
     * Not RSS and not VSS, which is why the number here will not match what a task manager
     * shows. Read from this process's own /proc/self/status, which needs no permission.
     */
    static long footprintBytes() {
        long anon = 0, swap = 0;
        try {
            for (String line : new String(java.nio.file.Files.readAllBytes(
                    new java.io.File("/proc/self/status").toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("RssAnon:")) anon = kilobytes(line);
                else if (line.startsWith("VmSwap:")) swap = kilobytes(line);
            }
        } catch (Throwable unreadable) {
            return 0;
        }
        return (anon + swap) * 1024;
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
}
