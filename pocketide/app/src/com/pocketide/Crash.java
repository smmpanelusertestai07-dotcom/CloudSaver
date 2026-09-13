package com.pocketide;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * What the app writes down when it stops unexpectedly.
 *
 * The owner of this app has no computer, which means no adb and no logcat. If the app dies and
 * leaves nothing behind, a person can do exactly one thing about it: guess. So the last stack
 * trace is written to a file inside the app's own storage, and Help can hand the whole thing on
 * to whoever is being asked for help.
 *
 * Nothing here is sent anywhere. The file is read by the Help screen and by nobody else.
 */
final class Crash {
    private static final String FILE = "last-crash.txt";
    private static final int KEEP_BYTES = 24 * 1024;

    private Crash() {}

    /** Installs the handler. Called once, from App. */
    static void arm(final Context context) {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                record(context, thread, error);
            } catch (Throwable writingFailed) {
                // A failure to write the record must not replace the original failure, which
                // is the one the owner actually needs to see reported by the system.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /**
     * Records a failure the app caught and recovered from, rather than one that killed it.
     *
     * Worth keeping because the interesting ones never reach the uncaught handler: a biometric
     * prompt that threw on one manufacturer's build, a file the phone refused for a reason the
     * API does not name. Without this they are invisible -- the app quietly takes its fallback
     * path and nobody ever learns which phones needed it.
     */
    static void save(Context context, Throwable error) {
        try {
            record(context, Thread.currentThread(), error);
        } catch (Throwable writingFailed) {
            // Same reasoning as in arm(): a failure to write the record must never become the
            // failure the owner sees.
        }
    }

    private static void record(Context context, Thread thread, Throwable error) throws IOException {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        writer.println("PocketIDE " + BuildFacts.VERSION_NAME + " (" + BuildFacts.VERSION_CODE + ")");
        writer.println("When: " + new java.util.Date());
        writer.println("Thread: " + thread.getName());
        writer.println("Phone: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " · Android " + android.os.Build.VERSION.RELEASE
                + " · " + (android.os.Build.SUPPORTED_ABIS.length > 0
                        ? android.os.Build.SUPPORTED_ABIS[0] : "unknown"));
        writer.println();
        error.printStackTrace(writer);
        writer.flush();
        String text = buffer.toString();
        if (text.length() > KEEP_BYTES) text = text.substring(0, KEEP_BYTES);
        File file = new File(context.getFilesDir(), FILE);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    /** The last recorded crash, or an empty string. */
    static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.isFile()) return "";
        try {
            byte[] bytes = new byte[(int) Math.min(file.length(), KEEP_BYTES)];
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                int read = input.read(bytes);
                return read <= 0 ? "" : new String(bytes, 0, read, StandardCharsets.UTF_8);
            }
        } catch (IOException unreadable) {
            return "";
        }
    }

    static boolean exists(Context context) {
        return new File(context.getFilesDir(), FILE).isFile();
    }

    static void clear(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (file.isFile() && !file.delete()) file.deleteOnExit();
    }
}
