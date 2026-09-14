package com.pocketide;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeping the computer current, for years rather than for a week.
 *
 * Everything this app installs is pinned, and it has to be: a pinned Ubuntu image and a pinned
 * editor archive are what let the app say a download is the file it expected rather than
 * whatever answered. But a pin is right on the day it is made and wrong a year later. Ubuntu
 * ships a security fix for openssl within hours of a CVE; a machine that never runs apt never
 * receives it, and after a year of that the "computer in your pocket" is a computer with a
 * year of known holes in it.
 *
 * So the pins are the floor, not the ceiling. The first install is pinned by checksum; from
 * then on this keeps the machine moving, and the checks that replace the pin for an update are
 * named in pocketide-update.sh rather than left to be assumed.
 *
 * WHEN IT RUNS, and why it cannot be a schedule. There is no systemd here -- PRoot has no init,
 * no cgroups and no timers, so unattended-upgrades has nothing to hang off. More fundamentally,
 * Linux only exists while this app is running: Android does not keep a foreign userland alive
 * behind a closed app, and anything claiming otherwise on a phone is describing a bug. So the
 * honest design is the one below -- when the app is open, the editor is not in use, the phone
 * is on Wi-Fi and a day has passed, the machine catches itself up quietly in the background.
 *
 * WHAT IS AUTOMATIC. Ubuntu's security updates, and nothing else. They are small, they are the
 * ones that matter, and Ubuntu's own maintainers have already decided they are safe to take on
 * a stable release. The editor's own version and the extensions move only when the owner asks,
 * because those change what the workspace looks like and an interface that rearranged itself
 * overnight without being asked is not a kindness. The switch for all of it is in Settings.
 */
final class Updates {

    /** What the workspace last reported, parsed. */
    static final class Status {
        final int ubuntuSecurity;
        final int ubuntuAll;
        final String editorCurrent;
        final String editorLatest;
        final int extensions;
        final String supportedUntil;
        final long checkedAt;

        Status(int ubuntuSecurity, int ubuntuAll, String editorCurrent, String editorLatest,
               int extensions, String supportedUntil, long checkedAt) {
            this.ubuntuSecurity = ubuntuSecurity;
            this.ubuntuAll = ubuntuAll;
            this.editorCurrent = editorCurrent;
            this.editorLatest = editorLatest;
            this.extensions = extensions;
            this.supportedUntil = supportedUntil;
            this.checkedAt = checkedAt;
        }

        /** True only when both versions are known AND differ. An unknown answer is not news. */
        boolean editorOutOfDate() {
            return !editorCurrent.isEmpty() && !editorLatest.isEmpty()
                    && !editorCurrent.equals(editorLatest);
        }

        boolean anythingWaiting() { return ubuntuSecurity > 0 || editorOutOfDate(); }

        boolean everChecked() { return checkedAt > 0; }

        /** One line for a settings row: what is waiting, or that nothing is. */
        String summary() {
            if (!everChecked()) return "Not checked yet";
            StringBuilder words = new StringBuilder();
            if (ubuntuSecurity > 0) {
                words.append(ubuntuSecurity).append(" Ubuntu security update")
                        .append(ubuntuSecurity == 1 ? "" : "s");
            }
            if (editorOutOfDate()) {
                if (words.length() > 0) words.append(" · ");
                words.append("Editor ").append(editorLatest).append(" available");
            }
            if (words.length() == 0) words.append("Everything is up to date");
            words.append(" · checked ").append(ago(checkedAt));
            return words.toString();
        }
    }

    /** How long an answer is treated as current. A phone is not a server; daily is plenty. */
    static final long CHECK_EVERY_MS = 24L * 60 * 60 * 1000;

    private static volatile boolean running;

    private Updates() {}

    // ------------------------------------------------------------------ the switch

    /** On unless the owner turned it off. Security updates are the default a phone deserves. */
    static boolean automatic(Context context) {
        return Prefs.of(context).getBoolean(Prefs.AUTO_UPDATE, true);
    }

    static void setAutomatic(Context context, boolean on) {
        Prefs.of(context).edit().putBoolean(Prefs.AUTO_UPDATE, on).apply();
    }

    /** True while a check or an update is in flight, so two cannot start at once. */
    static boolean busy() { return running; }

    /**
     * Takes the one slot, or refuses. Synchronised rather than a plain read-then-write:
     * onResume can fire twice in quick succession -- a permission dialog closing, the phone
     * rotating -- and two threads that both read `running` as false before either sets it are
     * two apt runs fighting over the same dpkg lock, which is exactly the state that leaves a
     * workspace "interrupted" and refusing every later install.
     */
    private static synchronized boolean claim() {
        if (running) return false;
        running = true;
        return true;
    }

    private static synchronized void release() { running = false; }

    // ------------------------------------------------------------------ what is known

    static Status last(Context context) {
        android.content.SharedPreferences prefs = Prefs.of(context);
        return new Status(
                prefs.getInt(Prefs.UPDATE_UBUNTU_SECURITY, 0),
                prefs.getInt(Prefs.UPDATE_UBUNTU_ALL, 0),
                prefs.getString(Prefs.UPDATE_EDITOR_CURRENT, ""),
                prefs.getString(Prefs.UPDATE_EDITOR_LATEST, ""),
                prefs.getInt(Prefs.UPDATE_EXTENSIONS, 0),
                prefs.getString(Prefs.UPDATE_SUPPORTED_UNTIL, "June 2029"),
                prefs.getLong(Prefs.UPDATE_CHECKED_AT, 0));
    }

    /** When the last automatic run finished, and whether it worked. Empty before the first. */
    static String lastRunNote(Context context) {
        return Prefs.of(context).getString(Prefs.UPDATE_LAST_RESULT, "");
    }

    // ------------------------------------------------------------------ conditions

    /**
     * Whether the background run may go ahead right now.
     *
     * Every clause is a way this could be rude rather than helpful: updating while the owner is
     * using the editor restarts things under them; updating on mobile data spends an allowance
     * they did not offer; updating during set-up competes with it for the same apt lock.
     */
    static String whyNotNow(Context context) {
        if (!Workspace.installed(context)) return "Linux is not set up yet";
        if (!automatic(context)) return "Automatic updates are off";
        if (running) return "Already checking";
        if (WorkspaceService.busy()) return "The workspace is busy";
        if (WorkspaceService.editorRunning()) return "The editor is open";
        if (!DeviceProbe.hasInternet(context)) return "Offline";
        // Wi-Fi, always -- not the WIFI_ONLY setting, which governs the downloads an owner
        // starts themselves. This run starts itself, and spending someone's mobile allowance on
        // something they did not ask for at that moment is not a call this app gets to make.
        if (!DeviceProbe.isWifi(context)) return "Waiting for Wi-Fi";
        return "";
    }

    /**
     * Starts a quiet check-and-apply if everything is right for one, and does nothing at all
     * otherwise. Safe to call from onResume: it returns immediately either way.
     */
    static void maybeRunInBackground(final Context context) {
        if (!whyNotNow(context).isEmpty()) return;
        long since = System.currentTimeMillis()
                - Prefs.of(context).getLong(Prefs.UPDATE_CHECKED_AT, 0);
        if (since < CHECK_EVERY_MS) return;

        // The application context, never the Activity. This thread outlives a rotation, and a
        // thread holding an Activity across one is a leaked screen.
        final Context app = context.getApplicationContext();
        if (!claim()) return;
        new Thread(() -> {
            try {
                Status status = doCheck(app, line -> {});
                if (status != null && status.ubuntuSecurity > 0) {
                    boolean ok = doRun(app, "ubuntu", line -> {});
                    note(app, ok
                            ? "Installed " + status.ubuntuSecurity + " Ubuntu security update"
                                    + (status.ubuntuSecurity == 1 ? "" : "s")
                            : "Some Ubuntu security updates could not be installed");
                    // Re-read, so the screen does not go on offering updates already taken.
                    doCheck(app, line -> {});
                }
            } finally {
                release();
            }
        }, "updates").start();
    }

    // ------------------------------------------------------------------ doing it

    /**
     * Asks the workspace what is available and records the answer. Call from a background
     * thread: it starts PRoot and waits for apt.
     */
    static Status check(Context context, Workspace.Progress progress) {
        if (!claim()) return last(context);
        try {
            return doCheck(context, progress);
        } finally {
            release();
        }
    }

    /** The work itself, for a caller that already holds the slot. */
    private static Status doCheck(Context context, Workspace.Progress progress) {
        Map<String, String> values = new HashMap<>();
        if (!collect(context, "check", values, progress)) return null;
        long now = System.currentTimeMillis();
        Prefs.of(context).edit()
                .putInt(Prefs.UPDATE_UBUNTU_SECURITY, number(values.get("ubuntu_security")))
                .putInt(Prefs.UPDATE_UBUNTU_ALL, number(values.get("ubuntu_all")))
                .putString(Prefs.UPDATE_EDITOR_CURRENT, text(values.get("editor_current")))
                .putString(Prefs.UPDATE_EDITOR_LATEST, text(values.get("editor_latest")))
                .putInt(Prefs.UPDATE_EXTENSIONS, number(values.get("extensions")))
                .putString(Prefs.UPDATE_SUPPORTED_UNTIL,
                        month(text(values.get("ubuntu_supported_until"))))
                .putLong(Prefs.UPDATE_CHECKED_AT, now)
                .apply();
        return last(context);
    }

    /**
     * Runs one update command, reporting each line as it arrives. Background thread only.
     *
     * The commands are the script's own: ubuntu, ubuntu-all, editor, extensions, all.
     */
    static boolean run(Context context, String what, Workspace.Progress progress) {
        if (!Workspace.installed(context)) {
            progress.line("Linux is not set up yet.");
            return false;
        }
        if (!claim()) {
            progress.line("Another update is already running. It will finish on its own.");
            return false;
        }
        try {
            return doRun(context, what, progress);
        } finally {
            release();
        }
    }

    private static boolean doRun(Context context, String what, Workspace.Progress progress) {
        try {
            Process process = Workspace.start(context,
                    "bash /opt/pocketide/pocketide-update.sh " + what);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) progress.line(line);
                }
            }
            return process.waitFor() == 0;
        } catch (Throwable failed) {
            progress.line(failed.getMessage() == null
                    ? failed.getClass().getSimpleName() : failed.getMessage());
            return false;
        }
    }

    private static boolean collect(Context context, String command, Map<String, String> into,
                                   Workspace.Progress progress) {
        if (!Workspace.installed(context)) return false;
        try {
            Process process = Workspace.start(context,
                    "bash /opt/pocketide/pocketide-update.sh " + command);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int equals = line.indexOf('=');
                    if (equals > 0) {
                        into.put(line.substring(0, equals).trim(),
                                line.substring(equals + 1).trim());
                    } else if (!line.trim().isEmpty()) {
                        progress.line(line);
                    }
                }
            }
            process.waitFor();
            return !into.isEmpty();
        } catch (Throwable unreadable) {
            progress.line(unreadable.getMessage() == null
                    ? unreadable.getClass().getSimpleName() : unreadable.getMessage());
            return false;
        }
    }

    private static void note(Context context, String words) {
        Prefs.of(context).edit()
                .putString(Prefs.UPDATE_LAST_RESULT, words)
                .putLong(Prefs.UPDATE_LAST_RUN_AT, System.currentTimeMillis())
                .apply();
    }

    private static int number(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }

    /**
     * "2 hours ago", in plain English.
     *
     * Android's own DateUtils.getRelativeTimeSpanString would be the obvious call and is not
     * used on purpose: it follows the phone's language, and every word this app puts on a
     * screen is English. A row that reads "checked 2 घंटे पहले" beside "Everything is up to
     * date" is the kind of half-translated interface that looks broken rather than localised.
     */
    private static String ago(long when) {
        long seconds = Math.max(0, (System.currentTimeMillis() - when) / 1000);
        if (seconds < 90) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    /** "2029-06" as the script prints it, into "June 2029" as a person reads it. */
    private static String month(String iso) {
        String[] names = {"January", "February", "March", "April", "May", "June", "July",
                "August", "September", "October", "November", "December"};
        if (iso.length() == 7 && iso.charAt(4) == '-') {
            try {
                int index = Integer.parseInt(iso.substring(5)) - 1;
                if (index >= 0 && index < 12) return names[index] + " " + iso.substring(0, 4);
            } catch (NumberFormatException notAMonth) {
                // Falls through to the default below, which is the same date in words.
            }
        }
        return "June 2029";
    }
}
