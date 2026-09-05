package com.pocketlinux;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

public final class ProcFilesTest {
    private static final Set<String> NAMED_PATHS = new HashSet<>(Arrays.asList(
            "/proc/loadavg", "/proc/uptime", "/proc/version", "/proc/stat", "/proc/vmstat",
            "/proc/sys/kernel/cap_last_cap", "/proc/sys/fs/inotify/max_user_watches"));

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static InputStream readable() {
        return new ByteArrayInputStream("live kernel contents\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void readableEntriesStayLive(Path root) throws Exception {
        Path host = Files.createDirectories(root.resolve("host"));
        Path realUptime = host.resolve("uptime");
        Files.write(realUptime, "100.00 300.00\n".getBytes(StandardCharsets.UTF_8));
        Path fallbacks = Files.createDirectories(root.resolve("old-fallbacks"));
        Path oldUptime = fallbacks.resolve("uptime");
        Files.write(oldUptime, "legacy stand-in\n".getBytes(StandardCharsets.UTF_8));
        ProcFiles.Opener opener = path -> "/proc/uptime".equals(path)
                ? new FileInputStream(realUptime.toFile()) : readable();
        check(ProcFiles.fallbackBinds(fallbacks.toFile(), opener).isEmpty(),
                "readable counters were hidden by an existing stand-in");
        Files.write(realUptime, "101.00 302.00\n".getBytes(StandardCharsets.UTF_8));
        check(ProcFiles.fallbackBinds(fallbacks.toFile(), opener).isEmpty(),
                "updated live counters were replaced on the next start");
        check("101.00 302.00\n".equals(read(realUptime.toFile())), "real proc data was modified");
        check("legacy stand-in\n".equals(read(oldUptime.toFile())), "unused legacy file was changed");
    }

    private static void unavailableEntriesKeepCompatibility(Path root) throws Exception {
        Path directory = root.resolve("unavailable");
        Path absentHostFile = root.resolve("does-not-exist");
        Map<String, String> binds = ProcFiles.fallbackBinds(directory.toFile(), path -> {
            if ("/proc/uptime".equals(path)) return new FileInputStream(absentHostFile.toFile());
            if ("/proc/loadavg".equals(path)) return new ByteArrayInputStream(new byte[0]);
            if ("/proc/version".equals(path)) throw new SecurityException("denied by policy");
            if ("/proc/stat".equals(path)) throw new FileNotFoundException("permission denied");
            return readable();
        });
        check(binds.keySet().equals(new HashSet<>(Arrays.asList(
                "/proc/uptime", "/proc/loadavg", "/proc/version", "/proc/stat"))),
                "missing, empty or denied paths did not get only their own fallbacks");
        check("1234.56 4321.00\n".equals(read(new File(binds.get("/proc/uptime")))),
                "old uptime compatibility contents changed");
        check("0.32 0.28 0.24 1/512 4096\n".equals(read(new File(binds.get("/proc/loadavg")))),
                "old load compatibility contents changed");
        check(read(new File(binds.get("/proc/stat"))).contains("cpu7 12500 0 6250 112500"),
                "denied Android stat no longer has its legacy fallback");
        // A future firmware grant takes precedence over the fallback created above.
        check(ProcFiles.fallbackBinds(directory.toFile(), path -> readable()).isEmpty(),
                "a previously denied path stayed bound after host access became available");
    }

    private static void probesAreBoundedAndNamed(Path root) throws Exception {
        Set<String> opened = new HashSet<>();
        int[] reads = {0};
        int[] closes = {0};
        Map<String, String> binds = ProcFiles.fallbackBinds(root.resolve("not-needed").toFile(), path -> {
            check(NAMED_PATHS.contains(path), "an unrelated path was probed: " + path);
            check(opened.add(path), "a named path was probed more than once");
            return new InputStream() {
                private int count;
                @Override public int read() {
                    check(++count == 1, "proc probe read more than one byte");
                    reads[0]++;
                    return '1';
                }
                @Override public void close() { closes[0]++; }
            };
        });
        check(opened.equals(NAMED_PATHS), "the compatibility path list changed");
        check(reads[0] == 7 && closes[0] == 7, "probes were not read once and closed");
        check(binds.isEmpty() && !Files.exists(root.resolve("not-needed")),
                "readable proc values unnecessarily created stand-ins");
    }

    private static void readAndCloseErrorsAreSafe(Path root) throws Exception {
        int[] closes = {0};
        Map<String, String> binds = ProcFiles.fallbackBinds(root.resolve("read-errors").toFile(), path -> {
            if (!"/proc/vmstat".equals(path) && !"/proc/stat".equals(path)) return readable();
            return new InputStream() {
                @Override public int read() throws IOException {
                    if ("/proc/vmstat".equals(path)) throw new IOException("read denied");
                    return '1';
                }
                @Override public void close() throws IOException {
                    closes[0]++;
                    if ("/proc/stat".equals(path)) throw new IOException("close failed");
                }
            };
        });
        check(closes[0] == 2, "failed probes leaked a stream");
        check(binds.keySet().equals(new HashSet<>(Arrays.asList("/proc/vmstat", "/proc/stat"))),
                "I/O errors prevented a compatibility fallback");
    }

    private static void readableProcNeedsNoWritableFallbackDirectory(Path root) throws Exception {
        Path notDirectory = root.resolve("ordinary-file");
        Files.write(notDirectory, new byte[]{1});
        check(ProcFiles.fallbackBinds(notDirectory.toFile(), path -> readable()).isEmpty(),
                "real proc access was made dependent on fallback storage");
        try {
            ProcFiles.fallbackBinds(notDirectory.toFile(), path -> {
                throw new IOException("proc access denied");
            });
            throw new AssertionError("unwritable fallback storage produced a broken bind");
        } catch (IOException expected) {
            check(expected.getMessage().contains("stand-in directory"),
                    "fallback storage failure was not reported");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("pocketdesk-proc-test-");
        try {
            readableEntriesStayLive(root);
            unavailableEntriesKeepCompatibility(root);
            probesAreBoundedAndNamed(root);
            readAndCloseErrorsAreSafe(root);
            readableProcNeedsNoWritableFallbackDirectory(root);
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("PASS ProcFiles (live counters, stale fallbacks, denied reads, bounded named probes, storage failure)");
    }
}
