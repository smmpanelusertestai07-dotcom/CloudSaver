package com.pocketide;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The workspace's door to the phone: a scoped door, not the whole key.
 *
 * Wireless debugging hands whoever holds the paired key everything a computer on a USB cable
 * has: a shell as the "shell" user, every app's name, the shared storage, screenshots of
 * whatever is on the screen, taps into any app, install and uninstall of anything. An agent
 * that is asked to test the app it just built needs about one per cent of that, and an owner
 * who pairs the phone deserves to know that the other ninety-nine never enter the Linux the
 * agent works in. So they do not.
 *
 * WHERE THE KEY IS. adb, its paired key and its server socket live in the app's own storage,
 * outside the Linux rootfs, and adb runs there in a PRoot of its own (Workspace.startPrivate)
 * whose root is the one the APK ships (Phone.root): a program run by absolute path with no
 * shell, from files the workspace cannot reach, with an environment set by the app. The editor
 * and its terminals run in the rootfs, where there is no adb at all. PRoot is not a security
 * sandbox against a program that sets out to escape it, and this class does not claim
 * otherwise; what it claims is that the workspace has no path to the phone except the one
 * below, and that nothing the workspace can write is ever executed with the key in reach.
 * The line that remains is the phone's own: everything this app runs is one Android user,
 * and a program written to read another process's memory can read the adb server's. That
 * is why pairing is a switch the owner turns off when not testing, and why Android turns it
 * off at every restart.
 *
 * WHAT THE DOOR ALLOWS. A socket bound into the editor's PRoot at /run/pocketide/phone.sock,
 * spoken to by the "phone" command the editor's start installs. Every request is one of a
 * short list, every argument is validated before it goes anywhere, arguments reach adb as
 * arguments and never as a command line, and every operation that touches an app is allowed
 * only for a package this bridge itself installed from an APK under ~/projects: install,
 * uninstall, launch, stop, clear, run its instrumented tests, read its own log (by process id,
 * so no other app's lines), and -- only while that package is the one on the screen, checked
 * and acted on in one command on the phone so nothing can slip between -- a screenshot, a tap,
 * typed text or a key. No shell. No pull. No package list. No device properties. No
 * forwarding. The list is the whole list.
 *
 * The APK an agent asks to install is copied into the app's own storage first and read and
 * installed from the copy: the file under ~/projects is the agent's, and a file the agent
 * owns can change between the moment it is inspected and the moment it is installed.
 *
 * TWO MODES, because Wireless debugging costs an owner their Developer options and not every
 * owner wants to pay it. Paired, everything above is automatic. UNPAIRED, install and launch
 * still work: Android lets an app install another app and open it, with the permission the
 * phone's own permission manager calls "Install unknown apps" and a confirmation tapped every
 * time, and Installer does exactly that. Reading a log, taking a screenshot, driving taps and
 * uninstalling are the ones that need adb and say so. An agent can therefore build, install
 * and run the app it wrote with no Developer options at all, and gets the rest by pairing if
 * the owner wants it.
 *
 * The bridge answers only while the editor runs, because the adb server it relies on lives
 * exactly that long (WorkspaceService.ensureAdbServer), serves a few requests at a time and
 * says "busy" past that rather than growing a thread per request, and says so when it is
 * asked for something it will not do, with the list of what it will.
 */
final class PhoneBroker {

    /** Where the workspace reaches this: a directory bound into the editor's PRoot only. */
    static final String GUEST_DIR = "/run/pocketide";
    static final String GUEST_SOCKET = GUEST_DIR + "/phone.sock";

    /** Everything the bridge does. Anything else is refused by name. */
    static final String[] OPS = {"devices", "install", "uninstall", "launch", "stop", "clear",
            "instrument", "log", "screenshot", "tap", "text", "key", "allowed", "help"};

    /** The character that starts the last line of every reply, followed by the exit status. */
    static final char EXIT_MARK = (char) 0x1e;

    /** Requests served at once; the same number again may wait; the rest are told "busy". */
    static final int AT_ONCE = 4;

    private static final Pattern PACKAGE =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");
    private static final Pattern COMPONENT_TAIL = Pattern.compile("^[A-Za-z0-9_.$]+$");
    private static final Pattern KEYCODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern NUMBER = Pattern.compile("^[0-9]{1,5}$");

    private final WorkspaceService service;
    private LocalSocket bound;
    private LocalServerSocket listener;
    private volatile boolean running;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(AT_ONCE, AT_ONCE,
            30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(AT_ONCE));
    private final AtomicInteger stagedFiles = new AtomicInteger();

    /** Packages this bridge installed, and the APK each came from. The only ones it may touch. */
    private final Map<String, String> allowed = new LinkedHashMap<>();
    /**
     * And the signing certificate each was installed with. A name alone is not an identity:
     * an owner who removes what the agent built and installs a real app of the same name
     * from a store would otherwise have handed that app to the bridge. Checked whenever the
     * installed package can be seen; a package this app cannot see (one with no launcher
     * activity, such as a test package) is taken on its name, which is all there is.
     */
    private final Map<String, String> certificates = new LinkedHashMap<>();

    private PhoneBroker(WorkspaceService service) {
        this.service = service;
        requests.allowCoreThreadTimeOut(true);
    }

    // ------------------------------------------------------------------ lifecycle

    /** The host directory bound into the editor's PRoot as /run/pocketide. */
    static File bridgeDir(Context context) {
        File dir = new File(context.getFilesDir(), "phone/bridge");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** The binds the EDITOR's PRoot gets: the bridge, and nothing of the phone's. */
    static List<String> editorBinds(Context context) {
        return Collections.singletonList(bridgeDir(context).getAbsolutePath() + ":" + GUEST_DIR);
    }

    static PhoneBroker start(WorkspaceService service) {
        PhoneBroker broker = new PhoneBroker(service);
        try {
            broker.loadAllowed();
            broker.listen();
            WorkspaceService.record("Phone bridge: listening for the phone command.");
            return broker;
        } catch (Throwable failed) {
            WorkspaceService.record("Phone bridge: could not start ("
                    + (failed.getMessage() == null ? failed.getClass().getSimpleName()
                    : failed.getMessage()) + ").");
            broker.stop();
            return null;
        }
    }

    private void listen() throws IOException {
        File socket = new File(bridgeDir(service), "phone.sock");
        socket.delete();
        bound = new LocalSocket();
        bound.bind(new LocalSocketAddress(socket.getAbsolutePath(),
                LocalSocketAddress.Namespace.FILESYSTEM));
        listener = new LocalServerSocket(bound.getFileDescriptor());
        running = true;
        new Thread(this::acceptLoop, "phone-bridge").start();
    }

    /**
     * Closes the door. The accept thread is woken first: closing a socket another thread is
     * blocked in accept() on does not wake that thread on Linux, and a thread left there
     * would hold the old socket, and its name, for as long as the process lived. shutdown()
     * on the listening socket does wake it, with an error it answers by leaving.
     */
    void stop() {
        running = false;
        try {
            if (bound != null) {
                Os.shutdown(bound.getFileDescriptor(),
                        OsConstants.SHUT_RDWR);
            }
        } catch (Throwable alreadyDown) {
            // Then close() below is what there is.
        }
        try {
            if (listener != null) listener.close();
        } catch (Throwable alreadyClosed) {
            // Nothing to undo.
        }
        try {
            if (bound != null) bound.close();
        } catch (Throwable alreadyClosed) {
            // Nothing to undo.
        }
        new File(bridgeDir(service), "phone.sock").delete();
        // The requests still queued never run; their clients are closed rather than left
        // waiting on a bridge that is gone.
        for (Runnable never : requests.shutdownNow()) {
            if (never instanceof Pending) closeQuietly(((Pending) never).client);
        }
    }

    /** A request waiting for a worker, with the socket it came in on. */
    private final class Pending implements Runnable {
        final LocalSocket client;

        Pending(LocalSocket client) {
            this.client = client;
        }

        @Override public void run() {
            serve(client);
        }
    }

    private void acceptLoop() {
        while (running) {
            final LocalSocket client;
            try {
                client = listener.accept();
            } catch (IOException closed) {
                break;
            }
            try {
                requests.execute(new Pending(client));
            } catch (RejectedExecutionException full) {
                refuse(client, "The phone bridge is busy: " + AT_ONCE + " requests are already "
                        + "running and as many are waiting. Try again in a moment.");
            }
        }
    }

    private static void refuse(LocalSocket client, String why) {
        try {
            Reply reply = new Reply(client.getOutputStream());
            reply.line(why);
            reply.exit(75);
        } catch (IOException gone) {
            // The client left before it could be told.
        } finally {
            closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ one request

    /**
     * Lines back to the client, and the client's departure.
     *
     * A write that fails means the client has gone; but a command that streams -- phone log
     * on a quiet app -- may not write for minutes, and a worker blocked reading adb's pipe
     * would never learn that its client pressed Ctrl-C. So the client's socket is watched
     * from serve(): the client keeps its write side open after the request, and the read
     * that returns when it closes ends whatever process is being streamed to it. Four such
     * workers stranded used to be a bridge that answered "busy" until the editor restarted.
     */
    private static final class Reply {
        private final OutputStream out;
        private volatile boolean gone;
        private volatile Process watched;

        Reply(OutputStream out) {
            this.out = out;
        }

        /** The process whose output is going to this client; ended if the client has left. */
        void watch(Process process) {
            watched = process;
            if (gone) Workspace.quit(process);
        }

        void unwatch() {
            watched = null;
        }

        /** The client has closed its socket. Called from the watcher thread. */
        void left() {
            gone = true;
            Process process = watched;
            if (process != null) Workspace.quit(process);
        }

        boolean line(String text) {
            if (gone) return false;
            try {
                out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException left) {
                gone = true;
                return false;
            }
        }

        void exit(int code) {
            line(EXIT_MARK + Integer.toString(code));
        }
    }

    private void serve(LocalSocket client) {
        Reply reply;
        try {
            reply = new Reply(client.getOutputStream());
        } catch (IOException unusable) {
            closeQuietly(client);
            return;
        }
        try {
            final InputStream in = client.getInputStream();
            // A client that connects and never asks does not hold a worker for good.
            client.setSoTimeout(15_000);
            JSONObject request = new JSONObject(readLine(in, 8192));
            client.setSoTimeout(0);
            String op = request.optString("op", "help");
            JSONArray array = request.optJSONArray("args");
            List<String> args = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length() && i < 16; i++) args.add(array.optString(i, ""));
            }
            String cwd = request.optString("cwd", "/root");
            // From here the client sends nothing more; the next thing its socket says is that
            // it has closed, which is the one thing a streaming command needs to know.
            Thread watcher = new Thread(() -> {
                try {
                    byte[] ignored = new byte[64];
                    while (in.read(ignored) != -1) { /* nothing is expected */ }
                } catch (Throwable closed) {
                    // Closed from this side when the reply ends, or by the client.
                }
                reply.left();
            }, "phone-client");
            watcher.setDaemon(true);
            watcher.start();
            reply.exit(handle(op, args, cwd, reply));
        } catch (Throwable failed) {
            reply.line("The request could not be read: "
                    + (failed.getMessage() == null ? failed.getClass().getSimpleName()
                    : failed.getMessage()));
            reply.exit(2);
        } finally {
            closeQuietly(client);
        }
    }

    private static String readLine(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (line.size() >= limit) throw new IOException("request too long");
            line.write(c);
        }
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (Throwable alreadyClosed) {
            // Nothing to undo.
        }
    }

    // ------------------------------------------------------------------ the operations

    static final String HELP =
            "phone devices                    is the phone paired and connected\n"
                    + "phone install <app.apk>          install an APK built under ~/projects\n"
                    + "phone launch <package>           open it; it comes to the front\n"
                    + "phone stop <package>             force-stop it\n"
                    + "phone clear <package>            clear its data\n"
                    + "phone uninstall <package>\n"
                    + "phone instrument <test package> [runner]   its instrumented tests\n"
                    + "phone log <package> [-d]         its log, by process; -d dumps and returns\n"
                    + "phone screenshot <package> <out.png>   only while it is on the screen\n"
                    + "phone tap <package> <x> <y>      a tap, only while it is on the screen\n"
                    + "phone text <package> <text>      typed text, same rule\n"
                    + "phone key <package> <KEYCODE>    a key, same rule (KEYCODE_BACK ...)\n"
                    + "phone allowed                    the packages this bridge may touch\n"
                    + "Only packages installed through phone install, from ~/projects. No "
                    + "shell, no other app, no device details.\n"
                    + "install and launch work without pairing: Android asks you to confirm "
                    + "each install. log, screenshot, tap, text, key, instrument and uninstall "
                    + "need the phone paired (PocketIDE: Settings, The computer, Test on this "
                    + "phone).";

    private int handle(String op, List<String> args, String cwd, Reply reply)
            throws IOException {
        switch (op) {
            case "help":
                for (String line : HELP.split("\n")) reply.line(line);
                return 0;
            case "devices":
                return withPhone(reply) ? adbTo(reply, "devices") : 3;
            case "allowed":
                synchronized (allowed) {
                    if (allowed.isEmpty()) {
                        reply.line("Nothing yet. phone install <app.apk> first.");
                    }
                    for (Map.Entry<String, String> each : allowed.entrySet()) {
                        reply.line(each.getKey() + "  from  " + each.getValue());
                    }
                }
                return 0;
            case "install":
                return install(args, cwd, reply);
            case "uninstall":
                return forAllowed(args, reply, pkg -> adbTo(reply, "uninstall", pkg) == 0
                        ? forget(pkg) : 1);
            case "launch":
                // Needs no phone of its own: forAllowed's connection check is skipped for it.
                return forAllowed(args, reply, false, pkg -> launch(pkg, reply));
            case "stop":
                return forAllowed(args, reply, pkg ->
                        adbTo(reply, "shell", "am force-stop " + pkg));
            case "clear":
                return forAllowed(args, reply, pkg -> adbTo(reply, "shell", "pm clear " + pkg));
            case "instrument":
                return forAllowed(args, reply, pkg -> instrument(pkg, args, reply));
            case "log":
                return forAllowed(args, reply, pkg -> log(pkg, args, reply));
            case "screenshot":
                return forAllowed(args, reply, pkg -> screenshot(pkg, args, cwd, reply));
            case "tap":
            case "text":
            case "key":
                return forAllowed(args, reply, pkg -> input(op, pkg, args, reply));
            default:
                reply.line("The phone bridge does not do \"" + op + "\". What it does:");
                for (String line : HELP.split("\n")) reply.line(line);
                return 2;
        }
    }

    private interface OnPackage {
        int run(String pkg) throws IOException;
    }

    /** The one rule every operation but install shares: a package this bridge installed. */
    private int forAllowed(List<String> args, Reply reply, OnPackage then) throws IOException {
        return forAllowed(args, reply, true, then);
    }

    private int forAllowed(List<String> args, Reply reply, boolean needsPhone, OnPackage then)
            throws IOException {
        if (args.isEmpty() || !PACKAGE.matcher(args.get(0)).matches()) {
            reply.line("Give a package name, as in com.example.app. phone allowed lists them.");
            return 2;
        }
        String pkg = args.get(0);
        String expected;
        synchronized (allowed) {
            if (!allowed.containsKey(pkg)) {
                reply.line(pkg + " was not installed through this bridge, so it cannot be "
                        + "touched from here. phone install <its .apk> first; phone allowed "
                        + "lists what can be.");
                return 3;
            }
            expected = certificates.get(pkg);
        }
        if (expected != null && !expected.isEmpty()) {
            String now = installedCertificate(pkg);
            if (now != null && !now.equals(expected)) {
                forget(pkg);
                reply.line(pkg + " on the phone is not the one this bridge installed: it was "
                        + "replaced by an app of the same name signed by someone else. It cannot "
                        + "be touched from here; phone install <the .apk built here> puts the "
                        + "built one back.");
                return 3;
            }
        }
        if (needsPhone && !withPhone(reply)) return 3;
        return then.run(pkg);
    }

    /**
     * True when adb's server answers and a device is actually on the end of it.
     *
     * A running server is not a connected phone: unpaired, or with Wireless debugging off, the
     * server answers and lists nothing. The ops that need adb ask this; install and launch use
     * it only to choose the quicker of their two paths.
     */
    private boolean connected() {
        // Before Android 11 there is no Wireless debugging to have paired with, so no server
        // is started only to be asked.
        if (!Phone.supported() || !service.ensureAdbServer()) return false;
        try {
            for (String line : adbLines("devices")) {
                String trimmed = line.trim();
                if (trimmed.startsWith(Phone.LOOPBACK) && trimmed.endsWith("device")) return true;
            }
        } catch (IOException unreadable) {
            return false;
        }
        return false;
    }

    /** The server, or a sentence about why there is none. */
    private boolean withPhone(Reply reply) {
        if (!service.ensureAdbServer()) {
            reply.line("adb's server is not answering. Stop the editor and open it again; the "
                    + "Activity screen in PocketIDE says why if adb could not be unpacked.");
            return false;
        }
        return true;
    }

    private int install(List<String> args, String cwd, Reply reply) throws IOException {
        if (args.isEmpty()) {
            reply.line("Usage: phone install <path to an .apk under ~/projects>");
            return 2;
        }
        File apk = projectFile(args.get(0), cwd, ".apk");
        if (apk == null || !apk.isFile()) {
            reply.line("Only an .apk under ~/projects can be installed, and that is not one.");
            return 2;
        }
        // A copy of the agent's file, in storage the agent cannot reach, is what gets read
        // and installed: the file under ~/projects can be replaced between the two.
        File staged = stage(apk, ".apk");
        if (staged == null) {
            reply.line("That file could not be read.");
            return 2;
        }
        try {
            PackageInfo info;
            try {
                info = service.getPackageManager().getPackageArchiveInfo(
                        staged.getAbsolutePath(), 0);
            } catch (Throwable unreadable) {
                info = null;
            }
            if (info == null || info.packageName == null
                    || !PACKAGE.matcher(info.packageName).matches()) {
                reply.line("That file is not an APK Android can read.");
                return 2;
            }
            if (service.getPackageName().equals(info.packageName)) {
                reply.line("Not this app itself.");
                return 3;
            }
            String from = guestPath(apk);
            String certificate = certificateOf(staged);
            if (connected()) {
                int code = adbTo(reply, "install", "-r", "-t", "/stage/" + staged.getName());
                if (code == 0) {
                    // Allowed only once it is on the phone: what this bridge may touch is
                    // what this bridge put there, not what it was asked to.
                    allow(info.packageName, from, certificate);
                    reply.line("Installed " + info.packageName + ". Next: phone launch "
                            + info.packageName);
                }
                return code;
            }
            // No pairing. Android's own installer, which asks the owner once and answers back.
            reply.line("The phone is not paired, so Android will ask you to confirm this "
                    + "install.");
            String failure = Installer.install(service, staged, info.packageName, reply::line);
            if (!failure.isEmpty()) {
                reply.line(failure);
                return 1;
            }
            allow(info.packageName, from, certificate);
            reply.line("Installed " + info.packageName + ". Next: phone launch "
                    + info.packageName);
            return 0;
        } finally {
            staged.delete();
        }
    }

    private int launch(String pkg, Reply reply) throws IOException {
        if (!connected()) {
            // No pairing needed to open an app this app installed; see Installer.launch for
            // what Android does and does not allow from where.
            String failure = Installer.launch(service, pkg);
            if (failure.isEmpty()) {
                reply.line("Opened " + pkg + " on the phone.");
                return 0;
            }
            reply.line(failure);
            return 1;
        }
        String component = "";
        for (String line : adbLines("shell", "cmd package resolve-activity --brief "
                + "-c android.intent.category.LAUNCHER " + pkg + " 2>/dev/null | tail -n 1")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(pkg + "/")
                    && COMPONENT_TAIL.matcher(trimmed.substring(pkg.length() + 1)).matches()) {
                component = trimmed;
            }
        }
        if (component.isEmpty()) {
            reply.line(pkg + " has no launcher activity to open.");
            return 1;
        }
        return adbTo(reply, "shell", "am start -W -n " + quote(component));
    }

    private int instrument(String pkg, List<String> args, Reply reply) throws IOException {
        String runner = args.size() > 1 ? args.get(1) : "androidx.test.runner.AndroidJUnitRunner";
        if (!COMPONENT_TAIL.matcher(runner).matches()) {
            reply.line("The runner is a class name, as in androidx.test.runner.AndroidJUnitRunner.");
            return 2;
        }
        return adbTo(reply, "shell", "am instrument -w -r " + quote(pkg + "/" + runner));
    }

    private int log(String pkg, List<String> args, Reply reply) throws IOException {
        String pid = "";
        for (String line : adbLines("shell", "pidof " + pkg)) {
            String first = line.trim().split("\\s+")[0];
            if (NUMBER.matcher(first).matches()) {
                pid = first;
                break;
            }
        }
        if (pid.isEmpty()) {
            reply.line(pkg + " is not running. phone launch " + pkg + " first.");
            return 1;
        }
        return args.contains("-d")
                ? adbTo(reply, "logcat", "--pid=" + pid, "-d")
                : adbTo(reply, "logcat", "--pid=" + pid);
    }

    private int screenshot(String pkg, List<String> args, String cwd, Reply reply)
            throws IOException {
        if (args.size() < 2) {
            reply.line("Usage: phone screenshot <package> <out.png>");
            return 2;
        }
        File out = projectFile(args.get(1), cwd, ".png");
        if (out == null || out.getParentFile() == null || !out.getParentFile().isDirectory()) {
            reply.line("The screenshot goes to a .png under ~/projects, in a folder that exists.");
            return 2;
        }
        // Taken only while the package is on the screen, decided and done in one command on
        // the phone; when it is not, the command prints why instead of a picture.
        File staged = new File(Phone.stageDir(service),
                "shot-" + stagedFiles.incrementAndGet() + ".png");
        Process process = Phone.start(service, "exec-out", onlyOnScreen(pkg, "screencap -p"));
        reply.watch(process);
        long bytes = 0;
        try (InputStream in = process.getInputStream();
             FileOutputStream file = new FileOutputStream(staged)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                file.write(buffer, 0, read);
                bytes += read;
            }
            process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            reply.unwatch();
            Workspace.quit(process);
        }
        if (bytes < 8 || !isPng(staged)) {
            String said = bytes > 0 && bytes < 4000 ? new String(
                    Files.readAllBytes(staged.toPath()), StandardCharsets.UTF_8).trim() : "";
            staged.delete();
            reply.line(said.isEmpty() ? "No screenshot came back." : Workspace.clean(said));
            return said.startsWith("Only while") ? 3 : 1;
        }
        try {
            Files.move(staged.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException acrossDevices) {
            Files.move(staged.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        reply.line("Saved " + guestPath(out) + " (" + bytes + " bytes)");
        return 0;
    }

    private int input(String op, String pkg, List<String> args, Reply reply) throws IOException {
        String remote;
        switch (op) {
            case "tap":
                if (args.size() < 3 || !NUMBER.matcher(args.get(1)).matches()
                        || !NUMBER.matcher(args.get(2)).matches()) {
                    reply.line("Usage: phone tap <package> <x> <y>, in pixels.");
                    return 2;
                }
                remote = "input tap " + args.get(1) + " " + args.get(2);
                break;
            case "text":
                if (args.size() < 2 || args.get(1).isEmpty() || args.get(1).length() > 500) {
                    reply.line("Usage: phone text <package> <text>");
                    return 2;
                }
                // input text takes %s for a space and cannot take a newline at all.
                remote = "input text " + quote(args.get(1).replace("\n", " ").replace(" ", "%s"));
                break;
            default:
                if (args.size() < 2 || !KEYCODE.matcher(args.get(1)).matches()) {
                    reply.line("Usage: phone key <package> <KEYCODE>, as in KEYCODE_BACK.");
                    return 2;
                }
                remote = "input keyevent " + args.get(1);
        }
        return adbTo(reply, "shell", onlyOnScreen(pkg, remote));
    }

    /**
     * {@code action}, run on the phone only while {@code pkg} is the activity on the screen.
     *
     * This is the rule that keeps a screenshot or a tap from reaching any other app, this one
     * included: the owner in the editor is not on a screen an agent may photograph. The check
     * and the action are one shell command on the phone, so no other app can come to the
     * front between them: a check made here and an action sent afterwards had exactly that
     * gap. The package name was validated against PACKAGE before it got here, and the action
     * is one this class wrote.
     */
    static String onlyOnScreen(String pkg, String action) {
        return "t=$(dumpsys activity activities 2>/dev/null"
                + " | grep -m1 -E 'topResumedActivity|mResumedActivity'); "
                + "case \"$t\" in *' u0 " + pkg + "/'*) " + action + ";; "
                + "*) echo 'Only while " + pkg + " is on the screen: another app is in front, "
                + "or it is not running. phone launch " + pkg + " first.'; exit 3;; esac";
    }

    // ------------------------------------------------------------------ the allow-list

    private File allowedFile() {
        return new File(service.getFilesDir(), "phone/allowed.txt");
    }

    private void loadAllowed() {
        synchronized (allowed) {
            allowed.clear();
            certificates.clear();
            File file = allowedFile();
            if (!file.isFile()) return;
            try {
                for (String line : new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8).split("\n")) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length < 2) continue;
                    String pkg = fields[0].trim();
                    if (!PACKAGE.matcher(pkg).matches()) continue;
                    allowed.put(pkg, fields[1]);
                    certificates.put(pkg, fields.length > 2 ? fields[2].trim() : "");
                }
            } catch (IOException unreadable) {
                // An unreadable list is an empty list: nothing is allowed by accident.
            }
        }
    }

    private void allow(String pkg, String apk, String certificate) {
        synchronized (allowed) {
            allowed.put(pkg, apk);
            certificates.put(pkg, certificate == null ? "" : certificate);
            saveAllowed();
        }
    }

    private int forget(String pkg) {
        synchronized (allowed) {
            allowed.remove(pkg);
            certificates.remove(pkg);
            saveAllowed();
        }
        return 0;
    }

    /** SHA-256 of the signing certificates in an APK on disk, or "" when it has none. */
    private String certificateOf(File apk) {
        try {
            return digestOf(service.getPackageManager().getPackageArchiveInfo(
                    apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES));
        } catch (Throwable unreadable) {
            return "";
        }
    }

    /**
     * The same digest for the package as installed, or null when this app cannot see it --
     * which is not the same as it being absent: a package with no launcher activity is
     * hidden from this app by Android's package visibility, whoever installed it.
     */
    private String installedCertificate(String pkg) {
        try {
            return digestOf(service.getPackageManager().getPackageInfo(pkg,
                    PackageManager.GET_SIGNING_CERTIFICATES));
        } catch (Throwable hiddenOrAbsent) {
            return null;
        }
    }

    private static String digestOf(PackageInfo info) throws NoSuchAlgorithmException {
        if (info == null || info.signingInfo == null) return "";
        Signature[] signers = info.signingInfo.hasMultipleSigners()
                ? info.signingInfo.getApkContentsSigners()
                : info.signingInfo.getSigningCertificateHistory();
        if (signers == null || signers.length == 0) return "";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // The history's oldest certificate, or every current signer: the identity that
        // survives a key rotation is the original one.
        if (info.signingInfo.hasMultipleSigners()) {
            for (Signature signer : signers) digest.update(signer.toByteArray());
        } else {
            digest.update(signers[0].toByteArray());
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private void saveAllowed() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> each : allowed.entrySet()) {
            String certificate = certificates.get(each.getKey());
            text.append(each.getKey()).append('\t').append(each.getValue()).append('\t')
                    .append(certificate == null ? "" : certificate).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(allowedFile())) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException notWritten) {
            // Kept in memory for this editor session; the next install writes it again.
        }
    }

    // ------------------------------------------------------------------ paths

    /**
     * A file under ~/projects, or null: the guest path resolved against the request's
     * working directory, then canonicalised on the host and required to stay under the
     * projects directory, with the extension the operation needs.
     *
     * The projects directory and /root above it have to be real directories, checked without
     * following links: both are the guest's to rename, and a ~/projects that had become a
     * link to / would have made the whole host "under ~/projects" once resolved.
     */
    private File projectFile(String guest, String cwd, String extension) {
        try {
            String path = guest.startsWith("/") ? guest : cwd + "/" + guest;
            if (!path.startsWith("/")) return null;
            File home = new File(Workspace.root(service), "root");
            File projects = Workspace.projects(service);
            if (!plainDirectory(home) || !plainDirectory(projects)) return null;
            File host = new File(Workspace.root(service), path.substring(1));
            File canonicalRoot = projects.getCanonicalFile();
            File canonical = host.getCanonicalFile();
            if (!canonical.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
                return null;
            }
            if (!canonical.getName().toLowerCase(Locale.ROOT).endsWith(extension)) return null;
            return canonical;
        } catch (IOException unresolvable) {
            return null;
        }
    }

    /** A directory that is a directory, not a link to one. */
    private static boolean plainDirectory(File file) {
        try {
            BasicFileAttributes facts = Files.readAttributes(file.toPath(),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return facts.isDirectory() && !facts.isSymbolicLink();
        } catch (Throwable missing) {
            return false;
        }
    }

    /** A copy of a guest file in the app's own storage, or null when it could not be read. */
    private File stage(File source, String extension) {
        File target = new File(Phone.stageDir(service),
                "install-" + stagedFiles.incrementAndGet() + extension);
        try {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException unreadable) {
            target.delete();
            return null;
        }
    }

    /**
     * The guest's name for a host file inside the rootfs.
     *
     * Both sides canonical: the files here come out of projectFile() canonicalised, which on
     * Android turns /data/user/0 into /data/data, and a prefix taken from getFilesDir() as
     * it is would never match -- every reply and the allow-list then carried a host path
     * that does not exist inside Linux.
     */
    private String guestPath(File host) {
        String root;
        try {
            root = Workspace.root(service).getCanonicalPath();
        } catch (IOException unresolvable) {
            root = Workspace.root(service).getAbsolutePath();
        }
        String path = host.getAbsolutePath();
        try {
            path = host.getCanonicalPath();
        } catch (IOException unresolvable) {
            // The absolute path, then, and the prefix test below says whether it matched.
        }
        return path.startsWith(root + File.separator) ? path.substring(root.length()) : path;
    }

    private static boolean isPng(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = new byte[8];
            int read = in.read(head);
            return read == 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N'
                    && head[3] == 'G';
        } catch (IOException unreadable) {
            return false;
        }
    }

    // ------------------------------------------------------------------ running adb

    /** One adb command in the app's own PRoot -- the one with the key -- streamed to the client. */
    private int adbTo(Reply reply, String... adbArguments) throws IOException {
        Process process = Phone.start(service, adbArguments);
        reply.watch(process);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!reply.line(Workspace.clean(line))) {
                    // The client went away: a log being read was closed. Nothing runs on.
                    Workspace.quit(process);
                    return 130;
                }
            }
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 130;
        } finally {
            reply.unwatch();
            Workspace.quit(process);
        }
    }

    private List<String> adbLines(String... adbArguments) throws IOException {
        final List<String> lines = new ArrayList<>();
        Process process = Phone.start(service, adbArguments);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() < 200) lines.add(Workspace.clean(line));
            }
            process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            Workspace.quit(process);
        }
        return lines;
    }

    /**
     * Single-quoted for the PHONE's shell, which is the only quoting that means "exactly
     * this". No shell runs on this side: adb gets arguments, and this is for the one argument
     * that adb hands to the phone's shell to run.
     */
    static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }
}
