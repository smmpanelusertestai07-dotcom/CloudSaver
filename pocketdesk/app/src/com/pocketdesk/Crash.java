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

    static void install(final Context context) {
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

    static void clear(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (file.isFile()) file.delete();
    }
}
