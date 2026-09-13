package com.pocketagent.doors;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Ubuntu the agents run in, and nothing else.
 *
 * PocketLinux's container carries a whole desktop: an X display, a window manager, a file
 * manager, a browser, a viewer. None of that is here. An agent driven through its publisher's
 * own interface needs a filesystem, a shell, a network and somewhere to put Node -- so this is
 * the download, the unpack, and one way to run a command, at about a tenth of the size.
 *
 * Every step writes down that it finished. A set-up interrupted by a flat battery, a lost
 * signal, or Android reclaiming the app continues from where it stopped rather than fetching
 * a third of a gigabyte again.
 */
final class Ubuntu {
    static final String PREFS = "pocketdoors";
    static final String KEY_STAGE = "setup_stage";
    static final String KEY_INSTALLED = "ubuntu_installed";

    /** Ubuntu's own base image, from Canonical's own mirror, pinned by digest. */
    static final String IMAGE_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz";
    static final String IMAGE_SHA256 =
            "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2";
    static final String IMAGE_LABEL = "Ubuntu 24.04.4 LTS · ARM64";
    /** What the download costs, so the phone can say it before spending someone's data. */
    static final long IMAGE_BYTES = 30L * 1024 * 1024;
    static final long WORKSPACE_BYTES = 1200L * 1024 * 1024;

    interface Progress { void line(String message); }

    private Ubuntu() {}

    static File root(Context context) {
        return new File(context.getFilesDir(), "ubuntu");
    }

    static File workspace(Context context) {
        return new File(root(context), "root/work");
    }

    static boolean installed(Context context) {
        return prefs(context).getBoolean(KEY_INSTALLED, false)
                && new File(root(context), "etc/os-release").isFile();
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ set-up

    /**
     * Download, verify, unpack, and put the door scripts in place. Safe to call again: each
     * stage is skipped once it has been recorded, so a retry resumes instead of restarting.
     *
     * Runs on a worker. Never on the main thread -- it downloads.
     */
    static void install(Context context, Progress progress) throws IOException, ErrnoException {
        File root = root(context);
        SharedPreferences prefs = prefs(context);

        if (!new File(root, "etc/os-release").isFile()) {
            File archive = new File(context.getCacheDir(), "ubuntu-base.tar.gz");
            if (!verified(archive)) {
                say(progress, "Downloading " + IMAGE_LABEL + "…");
                download(IMAGE_URL, archive, progress);
                if (!verified(archive)) {
                    // A truncated or tampered download must not be unpacked, and must not be
                    // kept: leaving it would make the next attempt trust a bad file.
                    if (!archive.delete()) throw new IOException("Could not discard a bad download.");
                    throw new IOException("The Ubuntu download did not match its published checksum.");
                }
            }
            say(progress, "Unpacking Ubuntu…");
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create the workspace folder.");
            try (InputStream input = new java.io.FileInputStream(archive)) {
                // Unpacking a base image is thousands of small files and a minute of silence
                // otherwise; a count every few hundred is enough to show it is moving.
                TarGzExtractor.extract(input, root, (files, name) -> {
                    if (files % 400 == 0) say(progress, "Unpacking… " + files + " files");
                });
            }
            // The archive is a third of the app's storage and is never needed again.
            archive.delete();
        }

        say(progress, "Preparing the workspace…");
        writeScripts(context);
        int code = run(context, "bash /opt/doors/doors-bootstrap.sh", progress);
        if (code != 0) throw new IOException("Workspace setup stopped with code " + code + ".");

        prefs.edit().putBoolean(KEY_INSTALLED, true).putString(KEY_STAGE, "ready").apply();
        say(progress, "Workspace ready.");
    }

    /**
     * Ask glibc to try IPv4 first.
     *
     * On mobile data an AAAA record frequently resolves and then will not connect, and apt
     * reports that as a name-resolution failure -- indistinguishable, from the screen, from
     * having no DNS at all. One line in gai.conf turns a long stall into a normal fetch.
     */
    private static void preferIPv4(File root) {
        File etc = new File(root, "etc");
        if (!etc.isDirectory()) return;
        File gai = new File(etc, "gai.conf");
        if (gai.isFile()) return;
        try (FileOutputStream output = new FileOutputStream(gai)) {
            output.write(("# PocketAgent: prefer IPv4, because a mobile network often answers "
                    + "AAAA and then refuses the connection.\nprecedence ::ffff:0:0/96  100\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (IOException unwritable) {
            // A rootfs still extracting is not a reason to refuse to start.
        }
    }

    /** Copies the door scripts out of the APK, every time, so an update replaces them. */
    static void writeScripts(Context context) throws IOException {
        File directory = new File(root(context), "opt/doors");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create the scripts folder.");
        for (String name : Doors.SCRIPTS) {
            File target = new File(directory, name);
            try (InputStream input = context.getAssets().open(name);
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.getFD().sync();
            }
            target.setExecutable(true, false);
        }
    }

    private static boolean verified(File archive) throws IOException {
        if (!archive.isFile() || archive.length() == 0) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new java.io.FileInputStream(archive)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return IMAGE_SHA256.contentEquals(hex);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable on this phone.", impossible);
        }
    }

    /** Resumes a partial file with a Range request, so a dropped signal does not start over. */
    private static void download(String url, File target, Progress progress) throws IOException {
        long have = target.isFile() ? target.length() : 0;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
        connection.connect();
        int status = connection.getResponseCode();
        boolean resuming = status == HttpURLConnection.HTTP_PARTIAL;
        if (!resuming && status != HttpURLConnection.HTTP_OK)
            throw new IOException("The download server answered " + status + ".");
        if (!resuming) have = 0;
        long total = connection.getContentLengthLong() + have;
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target, resuming)) {
            byte[] buffer = new byte[65536];
            long done = have;
            long announced = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                done += read;
                if (done - announced > 2L * 1024 * 1024) {
                    announced = done;
                    say(progress, total > 0
                            ? "Downloading Ubuntu… " + (done * 100 / total) + "%"
                            : "Downloading Ubuntu… " + (done / (1024 * 1024)) + " MB");
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
    }

    // ------------------------------------------------------------------ running

    /** Starts a command inside Ubuntu. The caller owns the process and must stop it. */
    static Process start(Context context, String command) throws IOException {
        File root = root(context);
        // Android gives a container no working resolver. Without this every fetch fails with
        // "Temporary failure resolving", which is how the first set-up on a real phone died.
        Dns.refresh(context);
        preferIPv4(root);
        File natives = new File(context.getApplicationInfo().nativeLibraryDir);
        File temporary = new File(context.getFilesDir(), "proot-tmp");
        if (!temporary.isDirectory()) temporary.mkdirs();

        List<String> args = new ArrayList<>();
        args.add(new File(natives, "libproot.so").getAbsolutePath());
        args.add("--link2symlink");
        args.add("--kill-on-exit");
        args.add("-0");
        args.add("-r");
        args.add(root.getAbsolutePath());
        args.add("-b");
        args.add("/dev");
        args.add("-b");
        args.add("/proc");
        args.add("-b");
        args.add("/sys");
        // Android hides several /proc files that ordinary Linux software reads. A real file
        // bound over each missing path is what proot-distro does, and it has to come after
        // -b /proc so that it wins.
        for (Map.Entry<String, String> fake : ProcFiles.fallbackBinds(
                directory(context, "proc-fakes")).entrySet()) {
            args.add("-b");
            args.add(fake.getValue() + ":" + fake.getKey());
        }
        args.add("-w");
        args.add("/root");
        args.add("/usr/bin/env");
        args.add("-i");
        args.add("HOME=/root");
        args.add("USER=root");
        args.add("LOGNAME=root");
        args.add("SHELL=/bin/bash");
        args.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        args.add("TERM=xterm-256color");
        args.add("LANG=C.UTF-8");
        args.add("TMPDIR=/tmp");
        args.add("DOORS_TZ=" + java.util.TimeZone.getDefault().getID());
        args.add("/bin/bash");
        args.add("-lc");
        args.add(command);

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        builder.environment().put("PROOT_TMP_DIR", temporary.getAbsolutePath());
        builder.environment().put("PROOT_LOADER",
                new File(natives, "libproot-loader.so").getAbsolutePath());
        // Without the accelerator PRoot is slower and always works. An agent daemon is not
        // redrawing a screen, so the speed is not felt, and a failed start would cost far more.
        builder.environment().put("PROOT_NO_SECCOMP", "1");
        builder.environment().put("PROOT_NO_MOUNTINFO", "1");
        builder.environment().put("LD_LIBRARY_PATH", natives.getAbsolutePath());
        return builder.start();
    }

    /** Runs a command to completion, passing each line on. Returns its exit code. */
    static int run(Context context, String command, Progress progress) throws IOException {
        Process process = start(context, command);
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) say(progress, clean(line));
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("Stopped.", interrupted);
        } finally {
            process.destroy();
        }
    }

    /** Terminal colour codes are for a terminal; the phone shows plain text. */
    static String clean(String line) {
        String plain = line.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "");
        return plain.length() > 400 ? plain.substring(0, 400) : plain;
    }

    private static File directory(Context context, String name) throws IOException {
        File file = new File(context.getFilesDir(), name);
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create " + name + ".");
        return file;
    }

    private static void say(Progress progress, String message) {
        if (progress != null && message != null && !message.trim().isEmpty()) progress.line(message);
    }

    /** How much room is left where Ubuntu lives, for the set-up screen to check first. */
    static long freeBytes(Context context) {
        try {
            android.system.StructStatVfs stat = Os.statvfs(context.getFilesDir().getAbsolutePath());
            return stat.f_bavail * stat.f_frsize;
        } catch (ErrnoException unavailable) {
            return context.getFilesDir().getUsableSpace();
        }
    }
}
