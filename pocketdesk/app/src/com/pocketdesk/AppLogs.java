package com.pocketdesk;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Reads the reports pocketdesk-open writes when a Linux app is launched.
 *
 * These live inside the container, which is this app's own private storage, so they can be read
 * straight off disk. That matters: finding out why ChatGPT did not open should be one tap here,
 * not a hunt through a file manager on a phone-sized screen.
 */
final class AppLogs {

    private AppLogs() {}

    static File directory(Context context) {
        return new File(ContainerRuntime.rootfs(context), "home/coder/.pocketdesk/logs");
    }

    /** ChatGPT keeps its own startup story here; reading it beats guessing at ours. */
    static File codexOwnLogs(Context context) {
        return new File(ContainerRuntime.rootfs(context), "home/coder/.local/state/codex/logs");
    }

    /** The reports, newest first. Empty when no app has been launched yet. */
    static File[] newestFirst(Context context) {
        java.util.List<File> all = new java.util.ArrayList<>();
        File[] ours = directory(context).listFiles((dir, name) -> name.endsWith(".log"));
        if (ours != null) all.addAll(Arrays.asList(ours));
        File[] theirs = codexOwnLogs(context).listFiles();
        if (theirs != null) {
            for (File log : theirs) if (log.isFile()) all.add(log);
        }
        if (all.isEmpty()) return new File[0];
        File[] logs = all.toArray(new File[0]);
        Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return logs;
    }

    static boolean any(Context context) {
        return newestFirst(context).length > 0;
    }

    /** Plain-English name of the app a report belongs to, from its file name. */
    static String appName(File log) {
        String name = log.getName();
        return name.endsWith(".log") ? name.substring(0, name.length() - 4) : name;
    }

    /** The whole report, newest last, capped so a runaway log cannot fill the dialog. */
    static String read(File log) {
        try (InputStream input = new FileInputStream(log)) {
            byte[] buffer = new byte[64 * 1024];
            int read = input.read(buffer);
            if (read <= 0) return "";
            return new String(buffer, 0, read, "UTF-8");
        } catch (IOException error) {
            return "Could not read " + log.getName() + ": " + error.getMessage();
        }
    }

    /** Every report joined, so one share carries the whole picture. */
    static String readAll(Context context) {
        StringBuilder all = new StringBuilder();
        SimpleDateFormat when = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());
        File[] logs = newestFirst(context);
        for (int i = 0; i < Math.min(logs.length, 6); i++) {
            File log = logs[i];
            all.append("=== ").append(appName(log))
                    .append(" · ").append(when.format(new Date(log.lastModified())))
                    .append(" ===\n").append(read(log)).append('\n');
        }
        return all.toString();
    }
}
