package com.pocketdesk;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;

/** Writes the last fatal error to app storage so the recovery screen can explain what happened. */
final class Crash {
    private static final String FILE = "last-crash.txt";

    private Crash() {}

    /** Chained once per process: MainActivity and App both call this, and a chain of handlers
     *  grew one link deeper on every screen creation. */
    private static boolean installed;

    static void install(final Context context) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            save(context, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static void save(Context context, Throwable error) {
        try {
            StringWriter buffer = new StringWriter();
            PrintWriter writer = new PrintWriter(buffer);
            writer.println(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()));
            writer.println("Android " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MODEL);
            error.printStackTrace(writer);
            writer.flush();
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE))) {
                out.write(buffer.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // Never let crash reporting cause a second crash.
        }
    }

    /**
     * A failure that was handled, kept the same way a crash is.
     *
     * A set-up or an install that fails says why in a dialog, and the dialog is gone the moment
     * it is dismissed -- taking with it the one line that would have explained the failure. This
     * keeps it, so "Last error report" answers for both kinds of trouble.
     */
    static void note(Context context, String title, String detail) {
        try {
            StringBuilder text = new StringBuilder();
            text.append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()))
                    .append('\n')
                    .append("Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" \u00b7 ").append(android.os.Build.MODEL).append('\n')
                    .append("PocketDesk ").append(MainActivity.VERSION).append("\n\n")
                    .append(title).append("\n\n").append(detail == null ? "" : detail);
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE))) {
                out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // Never let recording a failure cause another one.
        }
    }

    static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.isFile()) return "";
        try (Scanner scanner = new Scanner(file, "UTF-8")) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (Exception error) {
            return "";
        }
    }

    /** When the current report was written, or 0 if there is none. */
    static long recordedAt(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        return file.isFile() ? file.lastModified() : 0L;
    }

    static void clear(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (file.isFile()) file.delete();
    }
}
