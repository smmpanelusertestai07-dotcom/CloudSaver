package com.pocketdesk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps real kernel counters visible, with legacy stand-ins for Android-denied paths only. */
final class ProcFiles {
    interface Opener { InputStream open(String path) throws IOException; }

    private ProcFiles() {}

    static Map<String, String> fallbackBinds(File directory) throws IOException {
        return fallbackBinds(directory, FileInputStream::new);
    }

    static Map<String, String> fallbackBinds(File directory, Opener opener) throws IOException {
        Map<String, String> binds = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : legacyContents().entrySet()) {
            // procfs often reports a zero file length even when its live contents are readable.
            // Recheck the host on every start; an old stand-in must not hide restored access.
            if (hasContents(entry.getKey(), opener)) continue;
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Could not create the /proc stand-in directory");
            }
            String name = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
            File file = new File(directory, name);
            // These are ordinary private files, not procfs. Preserve existing legacy fallbacks.
            if (!file.exists() || file.length() == 0) {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
            }
            binds.put(entry.getKey(), file.getAbsolutePath());
        }
        return binds;
    }

    private static boolean hasContents(String path, Opener opener) {
        // Read exactly one byte from the explicit small set below. Do not enumerate /proc or
        // rely on canRead(): SELinux can deny an open or read after a permission check succeeds.
        try (InputStream input = opener.open(path)) {
            return input.read() != -1;
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    private static Map<String, String> legacyContents() {
        // Keep the old compatibility bytes for paths denied by Android. These placeholders
        // are not resource telemetry and must never replace readable real kernel values.
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("/proc/loadavg", "0.32 0.28 0.24 1/512 4096\n");
        contents.put("/proc/uptime", "1234.56 4321.00\n");
        contents.put("/proc/version",
                "Linux version 6.2.1 (pocketdesk@localhost) (gcc 13.2.0) #1 SMP PREEMPT\n");
        contents.put("/proc/sys/kernel/cap_last_cap", "40\n");
        contents.put("/proc/sys/fs/inotify/max_user_watches", "524288\n");
        StringBuilder stat = new StringBuilder("cpu  100000 0 50000 900000 0 0 0 0 0 0\n");
        for (int cpu = 0; cpu < 8; cpu++) {
            stat.append("cpu").append(cpu).append(" 12500 0 6250 112500 0 0 0 0 0 0\n");
        }
        stat.append("intr 0\nctxt 100000\nbtime 1700000000\nprocesses 4096\n")
                .append("procs_running 1\nprocs_blocked 0\nsoftirq 0\n");
        contents.put("/proc/stat", stat.toString());
        String[] keys = {"nr_free_pages", "nr_zone_inactive_anon", "nr_zone_active_anon",
                "nr_zone_inactive_file", "nr_zone_active_file", "nr_dirty", "nr_writeback",
                "pgpgin", "pgpgout", "pswpin", "pswpout", "pgfault", "pgmajfault"};
        StringBuilder vmstat = new StringBuilder();
        for (String key : keys) vmstat.append(key).append(" 0\n");
        contents.put("/proc/vmstat", vmstat.toString());
        return contents;
    }
}
