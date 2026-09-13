package com.pocketide;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Linux the editor runs on: download it, unpack it, and run one command inside it.
 *
 * This is Ubuntu 24.04.5 LTS for ARM64, from Canonical's own mirror, pinned by SHA-256, running
 * under PRoot on the phone's own kernel. No virtual machine, no root, no special Android
 * version -- which is the whole reason a four-year-old phone can run it at all.
 *
 * What is deliberately absent is as important as what is here. There is no X display, no window
 * manager and no VNC. An earlier build of this project ran a full Electron IDE over a
 * framebuffer and it crashed against Android's process ceiling on 3.9 GB of RAM; the editor here
 * is a Node server whose interface is drawn by the phone's own browser engine, which is both
 * lighter and the only version of this that ever felt like a phone.
 *
 * Every step records that it finished. A set-up interrupted by a flat battery, a lost signal or
 * Android reclaiming the app continues from where it stopped rather than fetching a third of a
 * gigabyte again.
 */
final class Workspace {

    /** Ubuntu's own base image, from Canonical's own mirror, pinned by digest. */
    static final String IMAGE_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/"
                    + "ubuntu-base-24.04.5-base-arm64.tar.gz";
    static final String IMAGE_SHA256 =
            "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2";
    static final String IMAGE_LABEL = "Ubuntu 24.04.5 LTS · ARM64";

    /** Loopback only. Nothing on the network can reach the editor; see the password below. */
    static final int EDITOR_PORT = 8391;

    /** The scripts copied out of the APK on every start, so an update replaces them. */
    static final String[] SCRIPTS = {
            "pocketide-bootstrap.sh", "pocketide-editor.sh", "pocketide-tools.sh"};

    interface Progress { void line(String message); }

    private Workspace() {}

    // ------------------------------------------------------------------ where things are

    static File root(Context context) {
        return new File(context.getFilesDir(), "linux");
    }

    /** Where the owner's own projects live. Inside the app, so uninstalling removes them. */
    static File projects(Context context) {
        return new File(root(context), "root/projects");
    }

    static boolean installed(Context context) {
        return Prefs.of(context).getBoolean(Prefs.INSTALLED, false)
                && new File(root(context), "etc/os-release").isFile();
    }

    static boolean editorInstalled(Context context) {
        return new File(root(context), "opt/code-server/bin/code-server").isFile();
    }

    /**
     * The editor's password, generated once on this phone and never leaving it.
     *
     * Android does not keep loopback private between apps: without a password, any other app on
     * the same phone could open the editor and read every file in the workspace. code-server has
     * its own password auth, so this is simply given to it, and this app signs in with it.
     *
     * 24 bytes from SecureRandom, written as hex. Not a word an owner could be asked to
     * remember, because they are never asked: the app holds it and signs in for them.
     */
    static String editorPassword(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        String stored = prefs.getString(Prefs.EDITOR_PASSWORD, "");
        if (!stored.isEmpty()) return stored;
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b));
        String fresh = hex.toString();
        prefs.edit().putString(Prefs.EDITOR_PASSWORD, fresh).apply();
        return fresh;
    }

    /**
     * The name of the cookie code-server keeps its session in.
     *
     * Read out of the release this app pins, not guessed:
     * out/common/http.js, getCookieSessionName(suffix) returns "code-server-session" when no
     * --cookie-suffix is given, and this app gives none.
     */
    static final String EDITOR_COOKIE_NAME = "code-server-session";

    /**
     * The exact value code-server will accept in that cookie, computed here.
     *
     * This is the one piece of this app that had to be read out of the editor's source rather
     * than assumed, and an earlier draft of this screen assumed wrong -- it put the password in
     * a query parameter, which code-server has never supported, and would have shown every
     * owner a sign-in box instead of an editor.
     *
     * What the source actually says, in code-server 4.137.0:
     *
     *   getPasswordMethod(hashed)   out/node/util.js -- a hash containing "$argon" means ARGON2,
     *                               anything else means SHA256. A plain hex digest is SHA256.
     *   isCookieValid(...)          out/node/util.js -- under SHA256 the check is a constant-time
     *                               compare of the cookie against the configured hashed-password,
     *                               with no further hashing.
     *   handlePasswordValidation    out/node/util.js -- under SHA256 the form password is checked
     *                               as sha256(password) == hashed-password.
     *
     * So the app writes sha256(password) into the config as hashed-password, and puts that same
     * digest in the cookie. Both doors open with the same key, and neither needs argon2 on this
     * side. The typed password still works too, which is what the sign-in fallback uses.
     */
    static String editorSessionToken(Context context) {
        return sha256Hex(editorPassword(context));
    }

    static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException never) {
            // SHA-256 is required of every Android platform since API 1.
            throw new IllegalStateException(never);
        }
    }

    static String editorUrl() {
        return "http://127.0.0.1:" + EDITOR_PORT + "/";
    }

    // ------------------------------------------------------------------ set-up

    interface StageProgress {
        void stage(Stage stage, String message, int percentWithinStage);
    }

    /**
     * Downloads, verifies, unpacks and prepares everything, reporting which stage it is in.
     *
     * Safe to call again: each stage checks whether its own work is already done, so a retry
     * resumes rather than restarting. Runs on a worker -- it downloads.
     */
    static void install(Context context, StageProgress progress)
            throws IOException, ErrnoException {
        File root = root(context);
        SharedPreferences prefs = Prefs.of(context);

        progress.stage(Stage.CHECK, "Checking this phone…", 0);
        String refusal = DeviceCheck.refusal(context);
        if (refusal != null) throw new IOException(refusal);
        String waiting = DataBudget.whyBlocked(context);
        if (!waiting.isEmpty()) throw new IOException(waiting);

        if (!new File(root, "etc/os-release").isFile()) {
            File archive = new File(context.getCacheDir(), "ubuntu-base.tar.gz");
            if (!verified(archive)) {
                progress.stage(Stage.UBUNTU_DOWNLOAD, "Downloading " + IMAGE_LABEL + "…", 0);
                download(IMAGE_URL, archive, (done, total) -> progress.stage(
                        Stage.UBUNTU_DOWNLOAD,
                        "Downloading Ubuntu… " + DeviceProbe.formatBytes(done)
                                + (total > 0 ? " of " + DeviceProbe.formatBytes(total) : ""),
                        total > 0 ? (int) (done * 100 / total) : 0));
                if (!verified(archive)) {
                    // A truncated or tampered download must not be unpacked, and must not be
                    // kept: leaving it would make the next attempt trust a bad file.
                    if (!archive.delete()) {
                        throw new IOException("Could not discard a bad download.");
                    }
                    throw new IOException(
                            "The Ubuntu download did not match its published checksum.");
                }
            }
            progress.stage(Stage.UBUNTU_UNPACK, "Unpacking Ubuntu…", 0);
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IOException("Cannot create Linux folder.");
            }
            try (InputStream input = new FileInputStream(archive)) {
                TarGzExtractor.extract(input, root, (files, name) -> {
                    // A base image is roughly 12,000 files. A count every few hundred is enough
                    // to show it is moving without repainting the screen constantly.
                    if (files % 400 == 0) {
                        progress.stage(Stage.UBUNTU_UNPACK, "Unpacking… " + files + " files",
                                Math.min(99, files / 120));
                    }
                });
            }
            archive.delete();
        }

        progress.stage(Stage.UBUNTU_TOOLS, "Installing tools…", 0);
        writeScripts(context);
        int tools = run(context, "bash /opt/pocketide/pocketide-bootstrap.sh",
                line -> progress.stage(Stage.UBUNTU_TOOLS, line, -1));
        if (tools != 0) throw new IOException("Tool setup stopped with code " + tools + ".");

        progress.stage(Stage.EDITOR, "Installing the editor…", 0);
        int editor = run(context, "bash /opt/pocketide/pocketide-editor.sh install",
                line -> progress.stage(Stage.EDITOR, line, -1));
        if (editor != 0) throw new IOException("The editor did not install (code " + editor + ").");

        progress.stage(Stage.READY, "Finishing…", 0);
        prefs.edit().putBoolean(Prefs.INSTALLED, true).putString(Prefs.STAGE, "ready").apply();
        progress.stage(Stage.READY, "Ready.", 100);
    }

    /** Copies the scripts out of the APK, every time, so an app update replaces them. */
    static void writeScripts(Context context) throws IOException {
        File directory = new File(root(context), "opt/pocketide");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create the scripts folder.");
        }
        for (String name : SCRIPTS) {
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

    // ------------------------------------------------------------------ download

    private interface Bytes { void moved(long done, long total); }

    /** Resumes a partial file with a Range request, so a dropped signal does not start over. */
    private static void download(String url, File target, Bytes onProgress) throws IOException {
        long have = target.isFile() ? target.length() : 0;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
        connection.connect();
        int status = connection.getResponseCode();
        boolean resuming = status == HttpURLConnection.HTTP_PARTIAL;
        if (!resuming && status != HttpURLConnection.HTTP_OK) {
            throw new IOException("The download server answered " + status + ".");
        }
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
                if (done - announced > 1024L * 1024) {
                    announced = done;
                    onProgress.moved(done, total);
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
    }

    static boolean verified(File archive) throws IOException {
        return IMAGE_SHA256.equalsIgnoreCase(checksum(archive));
    }

    /** SHA-256 of a file as lowercase hex, or an empty string when there is no file. */
    static String checksum(File file) throws IOException {
        if (!file.isFile() || file.length() == 0) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable on this phone.", impossible);
        }
    }

    // ------------------------------------------------------------------ running

    /** Starts a command inside Linux. The caller owns the process and must stop it. */
    static Process start(Context context, String command) throws IOException {
        File root = root(context);
        // Android gives a container no working resolver. Without this every fetch fails with
        // "Temporary failure resolving", which is how the first set-up on a real phone died.
        Dns.refresh(context);
        preferIPv4(root);
        File natives = new File(context.getApplicationInfo().nativeLibraryDir);
        File temporary = new File(context.getFilesDir(), "proot-tmp");
        if (!temporary.isDirectory() && !temporary.mkdirs()) {
            throw new IOException("Cannot create Linux's temporary folder.");
        }

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
        // Android hides several /proc files ordinary Linux software reads. A real file bound
        // over each missing path is what proot-distro does, and it has to come after -b /proc
        // so that it wins.
        for (Map.Entry<String, String> fake : ProcFiles.fallbackBinds(
                directory(context, "proc-fakes")).entrySet()) {
            args.add("-b");
            args.add(fake.getValue() + ":" + fake.getKey());
        }
        // The phone's own storage, only when the owner turned it on and Android granted it.
        // Binding it unconditionally would put every photo on the phone inside a workspace an
        // agent runs commands in, which is exactly the thing the switch exists to decide.
        if (PhoneFiles.enabled(context)) {
            args.add("-b");
            args.add(PhoneFiles.root().getAbsolutePath() + ":" + PhoneFiles.GUEST_PATH);
        }
        args.add("-w");
        args.add("/root");
        args.add("/usr/bin/env");
        args.add("-i");
        args.add("HOME=/root");
        args.add("USER=root");
        args.add("LOGNAME=root");
        args.add("SHELL=/bin/bash");
        args.add("PATH=/opt/code-server/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        args.add("TERM=xterm-256color");
        args.add("LANG=C.UTF-8");
        args.add("TMPDIR=/tmp");
        args.add("TZ=" + java.util.TimeZone.getDefault().getID());
        args.add("PIDE_PORT=" + EDITOR_PORT);
        args.add("PIDE_PASSWORD=" + editorPassword(context));
        args.add("PIDE_HASHED_PASSWORD=" + editorSessionToken(context));
        args.add("/bin/bash");
        args.add("-lc");
        args.add(command);

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        builder.environment().put("PROOT_TMP_DIR", temporary.getAbsolutePath());
        builder.environment().put("PROOT_LOADER",
                new File(natives, "libproot-loader.so").getAbsolutePath());
        // Without the accelerator PRoot is slower and always works. The editor is not redrawing
        // a screen from here, so the speed is not felt, and a failed start would cost far more.
        builder.environment().put("PROOT_NO_SECCOMP", "1");
        builder.environment().put("PROOT_NO_MOUNTINFO", "1");
        builder.environment().put("LD_LIBRARY_PATH", natives.getAbsolutePath());
        return builder.start();
    }

    /** Runs a command to completion, passing each line on. Returns its exit code. */
    static int run(Context context, String command, Progress progress) throws IOException {
        Process process = start(context, command);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
            output.write(("# PocketIDE: prefer IPv4, because a mobile network often answers "
                    + "AAAA and then refuses the connection.\nprecedence ::ffff:0:0/96  100\n")
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (IOException unwritable) {
            // A rootfs still extracting is not a reason to refuse to start.
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
        if (progress != null && message != null && !message.trim().isEmpty()) {
            progress.line(message);
        }
    }

    /** How much room is left where Linux lives. */
    static long freeBytes(Context context) {
        try {
            android.system.StructStatVfs stat =
                    Os.statvfs(context.getFilesDir().getAbsolutePath());
            return stat.f_bavail * stat.f_frsize;
        } catch (ErrnoException unavailable) {
            return context.getFilesDir().getUsableSpace();
        }
    }

    /** How much room the workspace is currently taking. Walks the tree, so call it off-thread. */
    static long sizeBytes(Context context) {
        return sizeOf(root(context));
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        File[] children = file.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (File child : children) {
            // A symlink into the tree would otherwise be followed forever.
            try {
                if (!child.getCanonicalPath().startsWith(file.getCanonicalPath())) continue;
            } catch (IOException unreadable) {
                continue;
            }
            total += sizeOf(child);
        }
        return total;
    }

    /** Deletes everything: Linux, the editor, the extensions, the projects. */
    static void removeEverything(Context context) {
        delete(root(context));
        delete(new File(context.getFilesDir(), "proot-tmp"));
        delete(new File(context.getCacheDir(), "ubuntu-base.tar.gz"));
        Prefs.of(context).edit()
                .remove(Prefs.INSTALLED).remove(Prefs.STAGE)
                .remove(Prefs.INSTALLED_EXTENSIONS)
                .remove(Prefs.SETUP_ELAPSED_MS).remove(Prefs.SETUP_STARTED_AT)
                .apply();
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }
}
