package com.pocketide;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * WHERE THE KEY IS. adb, its paired key and its server socket live in a directory of the
 * app's own storage that is bound into PRoot only for the app's own adb commands (see
 * Phone.binds). The editor and its terminals run in a PRoot without that bind: for them
 * /root/.android is the empty one in the rootfs, the raw adb there has no key and no server,
 * and nothing under ~ can reach the socket. PRoot is not a security sandbox against a program
 * that sets out to escape it, and this class does not claim otherwise; what it claims is that
 * the workspace has no path to the phone except the one below.
 *
 * WHAT THE DOOR ALLOWS. A socket bound into the editor's PRoot at /run/pocketide/phone.sock,
 * spoken to by the "phone" command the tools script installs. Every request is one of a short
 * list, every argument is validated before it goes anywhere near a shell, and every operation
 * that touches an app is allowed only for a package this bridge itself installed from an APK
 * under ~/projects: install, uninstall, launch, stop, clear, run its instrumented tests, read
 * its own log (by process id, so no other app's lines), and -- only while that package is
 * the one on the screen -- a screenshot, a tap, typed text or a key. No shell. No pull. No
 * package list. No device properties. No forwarding. The list is the whole list.
 *
 * The bridge answers only while the editor runs, because the adb server it relies on lives
 * exactly that long (WorkspaceService.ensureAdbServer), and it says so when it is asked for
 * something it will not do, with the list of what it will.
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

    private static final Pattern PACKAGE =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");
    private static final Pattern COMPONENT_TAIL = Pattern.compile("^[A-Za-z0-9_.$]+$");
    private static final Pattern KEYCODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern NUMBER = Pattern.compile("^[0-9]{1,5}$");
    private static final Pattern TOP = Pattern.compile("u0 ([A-Za-z0-9_.]+)/");

    private final WorkspaceService service;
    private LocalSocket bound;
    private LocalServerSocket listener;
    private volatile boolean running;

    /** Packages this bridge installed, and the APK each came from. The only ones it may touch. */
    private final Map<String, String> allowed = new LinkedHashMap<>();

    private PhoneBroker(WorkspaceService service) {
        this.service = service;
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

    void stop() {
        running = false;
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
    }

    private void acceptLoop() {
        while (running) {
            final LocalSocket client;
            try {
                client = listener.accept();
            } catch (IOException closed) {
                break;
            }
            new Thread(() -> serve(client), "phone-request").start();
        }
    }

    // ------------------------------------------------------------------ one request

    /** Lines back to the client. A write that fails means the client has gone. */
    private static final class Reply {
        private final OutputStream out;
        private boolean gone;

        Reply(OutputStream out) {
            this.out = out;
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
            JSONObject request = new JSONObject(readLine(client.getInputStream(), 8192));
            String op = request.optString("op", "help");
            JSONArray array = request.optJSONArray("args");
            List<String> args = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length() && i < 16; i++) args.add(array.optString(i, ""));
            }
            String cwd = request.optString("cwd", "/root");
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
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
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
            "phone devices                    is the phone connected\n"
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
                    + "shell, no other app, no device details.";

    private int handle(String op, List<String> args, String cwd, Reply reply)
            throws IOException {
        switch (op) {
            case "help":
                for (String line : HELP.split("\n")) reply.line(line);
                return 0;
            case "devices":
                return withPhone(reply) ? adbTo(reply, "adb devices") : 3;
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
                return forAllowed(args, reply, pkg -> adbTo(reply, "adb uninstall " + pkg) == 0
                        ? forget(pkg) : 1);
            case "launch":
                return forAllowed(args, reply, pkg -> launch(pkg, reply));
            case "stop":
                return forAllowed(args, reply, pkg ->
                        adbTo(reply, "adb shell am force-stop " + pkg));
            case "clear":
                return forAllowed(args, reply, pkg -> adbTo(reply, "adb shell pm clear " + pkg));
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
        if (args.isEmpty() || !PACKAGE.matcher(args.get(0)).matches()) {
            reply.line("Give a package name, as in com.example.app. phone allowed lists them.");
            return 2;
        }
        String pkg = args.get(0);
        synchronized (allowed) {
            if (!allowed.containsKey(pkg)) {
                reply.line(pkg + " was not installed through this bridge, so it cannot be "
                        + "touched from here. phone install <its .apk> first; phone allowed "
                        + "lists what can be.");
                return 3;
            }
        }
        if (!withPhone(reply)) return 3;
        return then.run(pkg);
    }

    /** The server and the connection, or a sentence about which is missing. */
    private boolean withPhone(Reply reply) {
        if (!Phone.adbInstalled(service)) {
            reply.line("adb is not installed. In PocketIDE: Settings → The computer → Test on "
                    + "this phone.");
            return false;
        }
        if (!service.ensureAdbServer()) {
            reply.line("adb's server is not answering. Stop the editor and open it again.");
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
        PackageInfo info;
        try {
            info = service.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
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
        if (!withPhone(reply)) return 3;
        int code = adbTo(reply, "adb install -r -t " + quote(guestPath(apk)));
        if (code == 0) {
            allow(info.packageName, guestPath(apk));
            reply.line("Installed " + info.packageName + ". Next: phone launch "
                    + info.packageName);
        }
        return code;
    }

    private int launch(String pkg, Reply reply) throws IOException {
        String component = "";
        for (String line : adbLines("adb shell " + quote("cmd package resolve-activity --brief "
                + "-c android.intent.category.LAUNCHER " + pkg + " 2>/dev/null | tail -n 1"))) {
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
        return adbTo(reply, "adb shell am start -W -n " + quote(component));
    }

    private int instrument(String pkg, List<String> args, Reply reply) throws IOException {
        String runner = args.size() > 1 ? args.get(1) : "androidx.test.runner.AndroidJUnitRunner";
        if (!COMPONENT_TAIL.matcher(runner).matches()) {
            reply.line("The runner is a class name, as in androidx.test.runner.AndroidJUnitRunner.");
            return 2;
        }
        return adbTo(reply, "adb shell am instrument -w -r " + quote(pkg + "/" + runner));
    }

    private int log(String pkg, List<String> args, Reply reply) throws IOException {
        String pid = "";
        for (String line : adbLines("adb shell pidof " + pkg)) {
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
        boolean dump = args.contains("-d");
        return adbTo(reply, "adb logcat --pid=" + pid + (dump ? " -d" : ""));
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
        if (!onScreen(pkg, reply)) return 3;
        Process process = Workspace.start(service, "adb exec-out screencap -p 2>/dev/null",
                Phone.binds(service));
        long bytes = 0;
        try (InputStream in = process.getInputStream();
             FileOutputStream file = new FileOutputStream(out)) {
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
            process.destroy();
        }
        if (bytes < 8 || !isPng(out)) {
            out.delete();
            reply.line("No screenshot came back.");
            return 1;
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
        if (!onScreen(pkg, reply)) return 3;
        return adbTo(reply, "adb shell " + quote(remote));
    }

    /**
     * True only while {@code pkg} is the activity on the screen.
     *
     * This is the rule that keeps a screenshot or a tap from reaching any other app, this one
     * included: the owner in the editor is not on a screen an agent may photograph.
     */
    private boolean onScreen(String pkg, Reply reply) throws IOException {
        String top = "";
        for (String line : adbLines("adb shell " + quote("dumpsys activity activities 2>/dev/null"
                + " | grep -m1 -E 'topResumedActivity|mResumedActivity'"))) {
            Matcher found = TOP.matcher(line);
            if (found.find()) {
                top = found.group(1);
                break;
            }
        }
        if (pkg.equals(top)) return true;
        reply.line("Only while " + pkg + " is on the screen; "
                + (top.isEmpty() ? "nothing was found" : "another app is in front")
                + ". phone launch " + pkg + " first.");
        return false;
    }

    // ------------------------------------------------------------------ the allow-list

    private File allowedFile() {
        return new File(service.getFilesDir(), "phone/allowed.txt");
    }

    private void loadAllowed() {
        synchronized (allowed) {
            allowed.clear();
            File file = allowedFile();
            if (!file.isFile()) return;
            try {
                for (String line : new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8).split("\n")) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String pkg = line.substring(0, tab).trim();
                    if (PACKAGE.matcher(pkg).matches()) allowed.put(pkg, line.substring(tab + 1));
                }
            } catch (IOException unreadable) {
                // An unreadable list is an empty list: nothing is allowed by accident.
            }
        }
    }

    private void allow(String pkg, String apk) {
        synchronized (allowed) {
            allowed.put(pkg, apk);
            saveAllowed();
        }
    }

    private int forget(String pkg) {
        synchronized (allowed) {
            allowed.remove(pkg);
            saveAllowed();
        }
        return 0;
    }

    private void saveAllowed() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> each : allowed.entrySet()) {
            text.append(each.getKey()).append('\t').append(each.getValue()).append('\n');
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
     */
    private File projectFile(String guest, String cwd, String extension) {
        try {
            String path = guest.startsWith("/") ? guest : cwd + "/" + guest;
            if (!path.startsWith("/")) return null;
            File host = new File(Workspace.root(service), path.substring(1));
            File canonicalRoot = Workspace.projects(service).getCanonicalFile();
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

    /** The guest's name for a host file inside the rootfs. */
    private String guestPath(File host) {
        String root = Workspace.root(service).getAbsolutePath();
        String path = host.getAbsolutePath();
        return path.startsWith(root) ? path.substring(root.length()) : path;
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

    /** One adb command in the phone's own PRoot -- the one with the key -- streamed to the client. */
    private int adbTo(Reply reply, String command) throws IOException {
        Process process = Workspace.start(service, command, Phone.binds(service));
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!reply.line(Workspace.clean(line))) {
                    // The client went away: a log being read was closed. Nothing runs on.
                    process.destroy();
                    return 130;
                }
            }
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 130;
        } finally {
            process.destroy();
        }
    }

    private List<String> adbLines(String command) throws IOException {
        final List<String> lines = new ArrayList<>();
        Workspace.run(service, command, Phone.binds(service), line -> {
            if (lines.size() < 200) lines.add(line);
        });
        return lines;
    }

    /** Single-quoted for a shell, which is the only quoting that means "exactly this". */
    static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }
}
