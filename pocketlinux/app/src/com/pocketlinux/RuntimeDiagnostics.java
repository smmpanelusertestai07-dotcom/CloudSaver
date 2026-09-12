package com.pocketlinux;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Bounded host-side evidence survives losing the guest filesystem view; no argv or tokens. */
final class RuntimeDiagnostics {
    static File file(Context context) { return new File(context.getFilesDir(), "runtime-events.log"); }

    static void snap(Context context, String event) {
        try {
            write(context, event, ProotProcess.diagnosticSummary(), true);
        } catch (Exception inaccessible) {
            write(context, event, "Process metadata unavailable", true);
        }
    }

    /** The Java process tracker supplies its cached counts; periodic reporting starts no child. */
    static void sample(Context context, String event, String processSummary) {
        write(context, event, processSummary, false);
    }

    private static synchronized void write(Context context, String event, String processSummary,
                                           boolean includeHistory) {
        try {
            File output = file(context);
            if (output.length() > 96 * 1024) {
                File previous = new File(output.getParentFile(), "runtime-events.previous.log");
                java.nio.file.Files.move(output.toPath(), previous.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (PrintWriter writer = new PrintWriter(new FileOutputStream(output, true))) {
                String clean = event.replaceAll("([A-Za-z][A-Za-z0-9+.-]*://[^\\s?#\"'<>]*)[?#][^\\s\"'<>]*", "$1?[redacted]")
                        .replace('\n', ' ').replace('\r', ' ');
                writer.println("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date())
                        + " | PocketLinux " + MainActivity.VERSION + " | " + clean.substring(0, Math.min(300, clean.length())));
                writer.println("Android " + Build.VERSION.RELEASE + " | " + Build.MODEL
                        + " | desktopRunning=" + LinuxService.isDesktopRunning()
                        + " | desktopStarting=" + LinuxService.isDesktopStarting());
                ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (manager != null) {
                    ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                    manager.getMemoryInfo(memory);
                    writer.println("Host RAM MiB: available=" + memory.availMem / 1048576L
                            + " total=" + memory.totalMem / 1048576L + " lowMemory=" + memory.lowMemory
                            + " threshold=" + memory.threshold / 1048576L);
                    if (includeHistory && Build.VERSION.SDK_INT >= 30) {
                        for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 3)) {
                            writer.println("Android app exit: at=" + exit.getTimestamp() + " reason=" + exit.getReason()
                                    + " status=" + exit.getStatus() + " importance=" + exit.getImportance());
                        }
                    }
                }
                // Android 12 and later end every one of an app's forked processes once there
                // are more than 32 of them, and under PRoot each Linux process is one of those.
                // That is the ceiling the computer lives under, so it is reported as a number
                // against a number rather than as a setting nobody can act on.
                writer.println("Android child-process ceiling: 32; the computer keeps itself below "
                        + "26 by clearing finished processes and closing one program if it has to");
                writer.println(processSummary);
                // Pressure stalls and OOM score are useful context, not proof of who killed
                // a child. On vendor kernels these files may be hidden; report that plainly.
                writer.println("Host memory pressure: " + smallFile("/proc/pressure/memory", 500));
                writer.println("Android app oom_score_adj: " + smallFile("/proc/self/oom_score_adj", 32));
            }
        } catch (Exception unavailable) { /* A failed report must never end the running desktop. */ }
    }

    private static String smallFile(String path, int limit) {
        try (java.io.FileInputStream input = new java.io.FileInputStream(path)) {
            byte[] bytes = new byte[limit];
            int count = input.read(bytes);
            return count > 0 ? new String(bytes, 0, count, java.nio.charset.StandardCharsets.US_ASCII)
                    .trim().replace('\n', ';') : "empty";
        } catch (Exception inaccessible) { return "unavailable"; }
    }
}
