package com.pocketlinux;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class ContainerRuntime {
    static final String PREFS = "pocketdesk_preferences";
    static final String KEY_WIFI_ONLY = "wifi_only";
    static final String KEY_THERMAL_GUARD = "thermal_guard";
    static final String KEY_SESSION_MINUTES = "session_minutes";
    /** Stop when the phone says so, not when a clock does. Stored in place of a minute count. */
    static final int SESSION_SMART = -1;
    /** How long a desktop nobody is touching is allowed to keep running, in minutes. */
    static final int SMART_IDLE_MINUTES = 25;
    /** Below this, and off the charger, a session is costing more than it is worth. */
    static final int SMART_BATTERY_FLOOR = 15;
    static final String KEY_ORIENTATION = "orientation";
    static final String KEY_THEME = "theme";
    static final String KEY_POLICY_V2 = "balanced_policy_v2";
    static final String KEY_PERMISSION_INTRO = "permission_intro_v1";
    static final String KEY_CRASH_SEEN = "crash_seen_at";
    static final String KEY_DESKTOP_INSTALLED = "desktop_installed";
    static final String KEY_UI_SCALE = "ui_scale_dpi";
    /** Where new files are saved: ask, the private computer, or the phone's public Download. */
    static final String KEY_DOWNLOAD_TARGET = "download_target";
    static final String DOWNLOAD_ASK = "ask";
    static final String DOWNLOAD_COMPUTER = "computer";
    static final String DOWNLOAD_PHONE = "phone";
    /**
     * How big the Linux desktop's text is, chosen from the phone rather than fixed at 120.
     *
     * The desktop is drawn at the phone's own pixel count -- one Linux pixel per phone pixel --
     * so 120 dpi put Sans 11 at 18 real pixels. On the reference phone, whose screen is about
     * 270 dpi, that is 1.7 mm of type: roughly two thirds the size of Android's own body text,
     * and the reason the computer inside reads as "too small" however good the screen is. A
     * phone screen is not a monitor at arm's length; it is 30 cm from a face.
     *
     * 0.56 of the phone's density puts an 11-point face at about 2.6 mm, which is where Android
     * itself lands. Bounded at both ends: below 120 nothing improves, and above 200 a window's
     * own minimum width stops fitting on a portrait phone.
     */
    static int defaultUiScale(Context context) {
        int density = context.getResources().getDisplayMetrics().densityDpi;
        return Math.max(120, Math.min(200, Math.round(density * 0.56f)));
    }
    /** Long side of the desktop framebuffer; keeps memory sane on a 4 GB phone. */
    static final int GEOMETRY_CAP = 1600;

    static final String UBUNTU_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz";
    /** Failover order for the base archive. Every mirror is checked against UBUNTU_SHA256. */
    static final String[] UBUNTU_MIRRORS = {
            UBUNTU_URL,
            "https://mirror.us.leaseweb.net/ubuntu-cdimage/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
    };
    static final String UBUNTU_SHA256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2";
    /**
     * Where Canonical lists every base image for this release, and the digests for all of them.
     *
     * The pinned file above is a point release -- 24.04.4 today, 24.04.5 one day -- and Canonical
     * eventually prunes the older ones from this directory. On the day that happens, a phone
     * setting up for the first time would get a 404 and no computer at all, from an app nobody
     * had touched. So when the pinned name is gone, the newest arm64 base image in this same
     * directory is used instead, and checked against the digest listed beside it rather than
     * against a constant compiled into an old APK. Same host, same HTTPS, same publisher.
     */
    static final String UBUNTU_RELEASE_DIRECTORY =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/";
    static final String UBUNTU_LABEL = "Ubuntu 24.04.4 LTS · ARM64";
    static final String KEY_SETUP_STAGE = "setup_stage";

    interface OutputListener { void line(String line); }

    private ContainerRuntime() {}

    static File rootfs(Context context) {
        return new File(context.getFilesDir(), "ubuntu-rootfs");
    }

    static File shared(Context context) {
        File external = context.getExternalFilesDir("Shared");
        File result = external != null ? external : new File(context.getFilesDir(), "shared");
        if (!result.exists()) result.mkdirs();
        return result;
    }

    static File downloadFile(Context context) {
        return new File(context.getCacheDir(), "ubuntu-base-24.04.4-arm64.tar.gz");
    }

    static boolean isInstalled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DESKTOP_INSTALLED, false)
                && new File(rootfs(context), "etc/os-release").isFile()
                && new File(rootfs(context), "usr/bin/Xtigervnc").isFile();
    }

    static void installRuntime(Context context) throws IOException, ErrnoException {
        File usr = new File(context.getFilesDir(), "usr");
        File nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        String[] required = {"libproot.so", "libproot-loader.so", "libandroid-shmem.so", "libtallocxx.so"};
        for (String name : required) {
            if (!new File(nativeDirectory, name).isFile()) {
                throw new IOException("Signed runtime component is missing: " + name);
            }
        }
        File tmp = new File(usr, "tmp");
        if (!tmp.exists() && !tmp.mkdirs()) throw new IOException("Could not create runtime temp directory");
        Os.chmod(tmp.getAbsolutePath(), 0700);
    }

    /** Compatibility stand-ins only for named /proc entries this Android app cannot read. */
    private static Map<String, String> fakeProcFiles(Context context) throws IOException {
        return ProcFiles.fallbackBinds(new File(context.getFilesDir(), "proc-fakes"));
    }

    static Process startContainer(Context context, String command) throws IOException {
        return startContainer(context, command, false);
    }

    /**
     * @param accelerated run with PRoot's seccomp accelerator. Used for the desktop session,
     *                    where speed is what the owner feels; installs keep the plain, slower,
     *                    always-works mode because a failed install costs the whole download again.
     */
    static Process startContainer(Context context, String command, boolean accelerated) throws IOException {
        // Stop/Open must not start a new :1 display while the old tracer is still cleaning up.
        ProotProcess.awaitPendingStops();
        File root = rootfs(context);
        LinuxDns.refresh(context);
        File nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDirectory, "libproot.so");
        File guestShared = shared(context);
        File guestMountPoint = new File(root, "home/coder/Shared");
        if (!guestMountPoint.exists() && !guestMountPoint.mkdirs()) {
            throw new IOException("Could not create the Linux shared mount point");
        }
        List<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("--link2symlink");
        args.add("--kill-on-exit");
        args.add("-0");
        args.add("-r");
        args.add(root.getAbsolutePath());
        args.add("-b");
        args.add("/dev");
        args.add("-b");
        args.add("/proc");
        // Chromium reads /sys to learn what machine it is on. Without it, it logs
        // "Failed to initialize cpuinfo" and guesses -- including how many threads to start.
        args.add("-b");
        args.add("/sys");
        // Android hides several /proc files that ordinary Linux software expects to read.
        // A real file bound over each missing path is what proot-distro does; the bind has to
        // come after -b /proc so that it wins.
        for (Map.Entry<String, String> fake : fakeProcFiles(context).entrySet()) {
            args.add("-b");
            args.add(fake.getValue() + ":" + fake.getKey());
        }
        args.add("-b");
        args.add(guestShared.getAbsolutePath() + ":/home/coder/Shared");
        // The phone's own folders, only while the owner allows it, and only the folders a person
        // means by "my files" -- see PHONE_FOLDERS. Without the permission the folder holds one
        // note saying where to turn it on.
        File phoneMount = new File(root, "home/coder/Phone");
        if (!phoneMount.exists()) phoneMount.mkdirs();
        if (PhoneFiles.allowed(context)) {
            bindPhoneFolders(args, phoneMount);
            new File(phoneMount, "Phone files are off.txt").delete();
        } else {
            File note = new File(phoneMount, "Phone files are off.txt");
            if (!note.exists()) {
                try {
                    writeText(note, "This folder shows your phone's own files once Phone files is on:\n"
                            + "PocketLinux → Settings → Permissions → Phone files.\n"
                            + "Then open the desktop again: Download, DCIM (photos) and Documents appear here,\n"
                            + "and every app's Open dialog lists Phone on the left.\n");
                } catch (IOException ignored) {
                    // A missing note costs nothing; the Settings row says the same.
                }
            }
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
        // Written into /var/lib/pocketdesk/basics-version, so Settings can offer the basics
        // update only when this version has something newer to install.
        args.add("POCKETDESK_APP_VERSION=" + MainActivity.VERSION);
        // Include Android's effective compatibility target in the exact install report,
        // so a vendor policy or a future accidental target bump is diagnosable on the phone.
        args.add("POCKETDESK_ANDROID_TARGET_SDK="
                + context.getApplicationInfo().targetSdkVersion);
        // The desktop clock, file times and git commits should be the owner's own time, wherever
        // they are. Read at every start, so it follows the phone across a time zone.
        args.add("POCKETDESK_TZ=" + java.util.TimeZone.getDefault().getID());
        args.add("/bin/bash");
        args.add("-lc");
        args.add(command);

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        builder.environment().put("PROOT_TMP_DIR", new File(context.getFilesDir(), "usr/tmp").getAbsolutePath());
        builder.environment().put("PROOT_LOADER", new File(nativeDirectory, "libproot-loader.so").getAbsolutePath());
        if (!accelerated) builder.environment().put("PROOT_NO_SECCOMP", "1");
        builder.environment().put("PROOT_NO_MOUNTINFO", "1");
        builder.environment().put("LD_LIBRARY_PATH", nativeDirectory.getAbsolutePath());
        Process process = builder.start();
        ProotProcess.track(process);
        return process;
    }

    static int runContainer(Context context, String command, OutputListener listener)
            throws IOException, InterruptedException {
        Process process = startContainer(context, command);
        try {
            return ProcessOutput.consume(process, line -> {
                if (listener != null) listener.line(line);
            });
        } finally {
            ProotProcess.stopAndWait(process);
        }
    }

    /**
     * Everything the computer needs, in steps that can be repeated safely.
     *
     * Each step records that it finished under /var/lib/pocketdesk/stage, so a set-up that was
     * stopped -- by the owner, a flat battery, or Android ending the app -- continues from the
     * step it reached instead of downloading the whole 550 MB again. Each step also repairs a
     * half-applied install and retries three times before giving up, and gives up with its own
     * exit code so the phone can say which part failed rather than "code 1".
     */
    static String bootstrapCommand() {
        return "set -eu; "
                // Android supplies the active network's DNS before every launch. Only a rootfs
                // with no usable resolver gets a fallback; never overwrite carrier DNS here.
                + "if ! grep -q '^nameserver ' /etc/resolv.conf 2>/dev/null; then "
                + "rm -f /etc/resolv.conf; printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\noptions timeout:2 attempts:2\\n' > /etc/resolv.conf; fi; "
                + "printf '127.0.0.1 localhost\\n::1 localhost\\n' > /etc/hosts; "
                // IPv4 first. A phone network that hands out an IPv6 address it cannot route
                // left every page "loading" until the IPv6 attempt timed out.
                + "printf 'precedence ::ffff:0:0/96  100\\n' > /etc/gai.conf; "
                + LinuxApps.APT_HELPERS
                + "pd_repair; "
                + "pd_update || exit 11; "
                // The desktop itself: X server, window manager, panel, file manager, terminal.
                + "pd_step desktop tigervnc-standalone-server openbox lxterminal pcmanfm tint2 dbus-x11 dbus-system-bus-common "
                // No xfonts-base: apt runs --no-install-recommends, every font here is named
                // through fontconfig, and Xtigervnc's font path ends in its own built-ins.
                + "x11-xserver-utils x11-utils fonts-dejavu-core ca-certificates curl gnupg git nano sudo "
                + "|| exit 12; "
                // What makes it look and behave like a computer: icons and a pointer theme
                // (librsvg2-common or every SVG icon falls back to a generic diamond), window
                // controls, the table a browser reads to hand a sign-in link back to the app
                // that asked for it, on-screen messages, sound, and the small everyday tools.
                + "pd_step extras " + LinuxApps.DESKTOP_PACKAGES + " || exit 13; "
                // The everyday programs -- a text editor, an archive tool, a picture viewer, a
                // calculator, a task manager, the volume mixer, a screenshot tool. Deliberately
                // NOT guarded by "|| exit": a universe package having a bad day must never cost
                // the owner a desktop, so a failure here is printed into the set-up log and
                // set-up carries on to a complete computer.
                + "pd_step tools " + LinuxApps.TOOL_PACKAGES
                + " || echo 'PocketLinux: some everyday tools did not install this time; "
                + "Settings, Update the computer basics will add them'; "
                // The developer tools an agentic development environment needs from the first
                // minute: a compiler, Python, Node.js, Git and SSH. One set-up, nothing to add.
                + "pd_step devtools " + LinuxApps.DEVELOPER_PACKAGES + " || exit 14; "
                // A desktop clock is only useful in the owner's own time.
                + LinuxApps.PD_TIMEZONE
                + "id coder >/dev/null 2>&1 || useradd -m -s /bin/bash coder; "
                + "printf 'coder ALL=(ALL) NOPASSWD:ALL\\n' > /etc/sudoers.d/coder; chmod 0440 /etc/sudoers.d/coder; "
                + "mkdir -p /home/coder/Desktop /home/coder/.config /home/coder/Projects "
                + "/home/coder/Downloads /usr/share/backgrounds; "
                // The browser is Google Chrome, from Google's own repository (arm64 since July
                // 2026). Best-effort: a network hiccup here must not fail a set-up that is
                // otherwise finished, and Settings -> Storage installs it on the next update.
                // The marker is written from what is actually on the computer afterwards, not
                // from the exit status: inside an "|| true" list a failure is invisible.
                + "if [ ! -f \"$PD_STATE/stage/chrome\" ]; then "
                + "( " + LinuxApps.CHROME_INSTALL + " ) || true; "
                + "if [ -x /usr/bin/google-chrome-stable ]; then : > \"$PD_STATE/stage/chrome\"; "
                + "else echo 'PocketLinux: Google Chrome did not install this time'; fi; fi; "
                // Which app version built these basics, so the phone can offer an update only
                // when there is one to make.
                + "printf '%s' \"${POCKETDESK_APP_VERSION:-unknown}\" > \"$PD_STATE/basics-version\"; "
                // Not "chown -R /home/coder": Phone and Shared are bind mounts of the phone's own
                // storage, and Android 11+ refuses to list /Android/data even to this app -- chown
                // then exits 1 and set -e threw away a finished 40-minute set-up.
                + "chown coder:coder /home/coder 2>/dev/null || true; "
                + "find /home/coder -mindepth 1 -maxdepth 1 ! -name Phone ! -name Shared "
                + "-exec chown -R coder:coder {} + 2>/dev/null || true; " 
                // The .deb files are gone (about 550 MB reclaimed) but the package LISTS stay.
                // Deleting them saved 60 MB of storage and then cost 40 MB of mobile data on
                // every later install, because apt had to fetch the whole list again.
                + "apt-get clean";
    }

    /** What a bootstrap exit code means, in the owner's words. Null when it is not one of ours. */
    static String setupFailureReason(int code) {
        switch (code) {
            case 11: return "The Ubuntu package servers could not be reached. Check the internet "
                    + "connection, then tap Continue set-up — it carries on from where it stopped.";
            case 12: return "The desktop packages did not finish downloading. Check the connection "
                    + "and free space, then tap Continue set-up — the parts already installed are kept.";
            case 13: return "The desktop's icons, sound and tools did not finish installing. Tap "
                    + "Continue set-up to finish that step; nothing already done is repeated.";
            case 14: return "The developer tools did not finish installing. Tap Continue set-up to "
                    + "finish that step; nothing already done is repeated.";
            case 15: return "One of the app repositories did not answer, so it was removed again "
                    + "rather than left to break later installs. Check the internet connection and "
                    + "try again; everything else that was installed is kept.";
            case 20: return "The mobile development tools did not finish installing. Check the "
                    + "connection and free space, then tap the row again — what was already "
                    + "installed is kept.";
            case 18: return "The download did not finish. Check the internet connection and try "
                    + "again; nothing was half-installed.";
            case 19: return "The file downloaded, but it could not be installed. It may not be the "
                    + "ARM64 build. Nothing on the Linux side was changed.";
            default: return null;
        }
    }

    /** The app version that last brought the computer's basics up to date, or null. */
    static String basicsVersion(Context context) {
        File marker = new File(rootfs(context), LinuxApps.BASICS_VERSION_FILE);
        if (!marker.isFile()) return null;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(marker)))) {
            String value = reader.readLine();
            return value == null ? null : value.trim();
        } catch (IOException error) {
            return null;
        }
    }

    /** True when the basics were built by an older version of the app and an update is due. */
    static boolean basicsUpdateDue(Context context) {
        if (!isInstalled(context)) return false;
        // Chrome installs best-effort, and the basics-version stamp is written whether it landed
        // or not -- so the version alone hid the one row that installs it again, at exactly the
        // moment the owner needed it. /usr/bin/google-chrome-stable is a link into /opt, which
        // reads as missing from Android's side, so the link itself is inspected and the real
        // binary is checked too.
        File root = rootfs(context);
        if (!Trees.exists(new File(root, "usr/bin/google-chrome-stable"))
                && !new File(root, "opt/google/chrome/google-chrome").isFile()) return true;
        return !MainActivity.VERSION.equals(basicsVersion(context));
    }

    static void writeDesktopScripts(Context context) throws IOException, ErrnoException {
        copyAsset(context, "dbus-system.conf", "usr/local/share/pocketdesk/dbus-system.conf");
        copyAsset(context, "pocketdesk-desktop.sh", "usr/local/bin/pocketdesk-desktop");
        copyAsset(context, "pocketdesk-browser.sh", "usr/local/bin/pocketdesk-browser");
        copyAsset(context, "pocketdesk-xdg-open.sh", "usr/local/bin/xdg-open");
        copyAsset(context, "pocketdesk-menu.sh", "usr/local/bin/pocketdesk-menu");
        copyAsset(context, "pocketdesk-open.sh", "usr/local/bin/pocketdesk-open");
        copyAsset(context, "pocketdesk-graphics.py", "usr/local/bin/pocketdesk-graphics.py");
        // The process helper the app supervisor reads: everything it does is decided from
        // /proc rather than from a command line, so a shell or the PRoot tracer carrying the
        // same path in its arguments can never be signalled by mistake.
        // Where a file that did not come through the browser is placed: the same setting
        // Chrome is given, applied to whatever an AI app or a build wrote into Downloads.
        // What can be built for a phone here, how to start it, and how to build-install-open
        // it on the connected one. Also the honest iPhone answer, in one place.
        copyAsset(context, "pocketdesk-mobile.sh", "usr/local/bin/pocketdesk-mobile");
        copyAsset(context, "pocketdesk-save.sh", "usr/local/bin/pocketdesk-save");
        copyAsset(context, "pocketdesk-procinfo.py", "usr/local/bin/pocketdesk-procinfo.py");
        copyAsset(context, "pocketdesk-appprocess.py", "usr/local/bin/pocketdesk-appprocess.py");
        copyAsset(context, "pocketdesk-childwatch.py", "usr/local/bin/pocketdesk-childwatch.py");
        copyAsset(context, "pocketdesk-windows.sh", "usr/local/bin/pocketdesk-windows");
        copyAsset(context, "pocketdesk-window-guard.sh", "usr/local/bin/pocketdesk-window-guard");
        copyAsset(context, "pocketdesk-status.sh", "usr/local/bin/pocketdesk-status");
        // What opens when a downloaded .deb is tapped: PocketLinux's own app installer.
        copyAsset(context, "pocketdesk-install.sh", "usr/local/bin/pocketdesk-install");
        // Storage, and a screenshot, from inside the computer.
        copyAsset(context, "pocketdesk-storage.sh", "usr/local/bin/pocketdesk-storage");
        copyAsset(context, "pocketdesk-software.sh", "usr/local/bin/pocketdesk-software");
        copyAsset(context, "pocketdesk-mcp.py", "usr/local/bin/pocketdesk-mcp");
        copyAsset(context, "pocketdesk-agent.sh", "usr/local/bin/pocketdesk-agent");
        copyAsset(context, "pocketdesk-appshot.sh", "usr/local/bin/pocketdesk-appshot");
        copyAsset(context, "pocketdesk-adb.sh", "usr/local/bin/pocketdesk-adb");
        copyAsset(context, "pocketdesk-shot.sh", "usr/local/bin/pocketdesk-shot");
        copyAsset(context, "pocketdesk-settings.sh", "usr/local/bin/pocketdesk-settings");
        // A blue Linux wallpaper with Tux (see OPEN_SOURCE_NOTICES.md).
        copyAsset(context, "wallpaper.jpg", "usr/share/backgrounds/pocketdesk.jpg");
        // Antigravity ships as a tarball with no packaged icon, so it borrows Google's own.
        copyAsset(context, "antigravity.png", "usr/share/pixmaps/antigravity.png");
        // A folder that looks like a folder: the theme's file-manager mark reads as a grey box.
        copyAsset(context, "pocketdesk-files.png", "usr/share/pixmaps/pocketdesk-files.png");
        // The phone with a folder on its screen: the Phone files icon on the desktop and panel.
        copyAsset(context, "pocketdesk-phone.png", "usr/share/pixmaps/pocketdesk-phone.png");
        // Tux, the Linux mascot, on the panel's Apps button (Larry Ewing, see the notices).
        copyAsset(context, "pocketdesk-linux.png", "usr/share/pixmaps/pocketdesk-linux.png");
        // PocketLinux's own mark, in the far corner of the panel. Its own artwork, so no
        // third-party trademark is involved -- see OPEN_SOURCE_NOTICES.md.
        copyAsset(context, "pocketdesk-mark.png", "usr/share/pixmaps/pocketdesk-mark.png");
        // Four marks PocketLinux draws itself, because Ubuntu 24.04's Adwaita has no full-colour
        // application icons at all any more -- only the symbolic set, which GTK will not use for
        // a launcher. Asking for a theme name here is what left Software wearing a blank sheet.
        copyAsset(context, "pocketdesk-projects.png", "usr/share/pixmaps/pocketdesk-projects.png");
        copyAsset(context, "pocketdesk-settings.png", "usr/share/pixmaps/pocketdesk-settings.png");
        copyAsset(context, "pocketdesk-software.png", "usr/share/pixmaps/pocketdesk-software.png");
        copyAsset(context, "pocketdesk-package.png", "usr/share/pixmaps/pocketdesk-package.png");
    }

    /**
     * The folders of the phone the computer may see -- and, just as importantly, the ones it
     * may not.
     *
     * The whole storage card used to be bound in one line. That gave every program inside the
     * container, an AI agent's shell included, a writable path to every app's data folder, every
     * messaging app's media, every backup: one mistaken "rm -rf" and it was gone, with no Android
     * bin to recover it from, because deleting a path outright never reaches the trash MediaStore
     * keeps. Nothing inside a PRoot container can be made read-only -- PRoot rewrites paths, it
     * does not enforce permissions, and the real write is done by the app's own Android identity
     * -- so the honest lever is not "allow less", it is "name less". What is not bound cannot be
     * reached, whatever asks for it and however convincingly.
     *
     * These six are the phone's own public folders. Anything else -- Android/data, a messaging
     * app's media, another app's private storage -- is simply not connected now, and a file from
     * one of those can still be brought in by hand, one at a time, through
     * Window -> Add a file from the phone or a cloud drive.
     */
    private static final String[] PHONE_FOLDERS = {
            "Download", "DCIM", "Documents", "Pictures", "Music", "Movies",
    };

    private static void bindPhoneFolders(List<String> args, File phoneMount) {
        File card = PhoneFiles.root();
        for (String folder : PHONE_FOLDERS) {
            File source = new File(card, folder);
            if (!source.isDirectory()) continue;          // not every phone has all six
            File target = new File(phoneMount, folder);
            if (!target.exists() && !target.mkdirs()) continue;
            args.add("-b");
            args.add(source.getAbsolutePath() + ":/home/coder/Phone/" + folder);
        }
        try {
            writeText(new File(phoneMount, "About this folder.txt"),
                    "These are your phone's own folders, inside the Linux computer.\n"
                    + "\n"
                    + "Only these are here: " + String.join(", ", PHONE_FOLDERS) + ".\n"
                    + "Nothing else on the phone can be reached from the computer at all -- not\n"
                    + "another app's data, not its private storage, not a backup. They are not\n"
                    + "hidden: they are not connected, so no program in here can name them.\n"
                    + "\n"
                    + "What IS here is the real thing, and a change is a real change: a file\n"
                    + "deleted in this folder is deleted on the phone, and Android has no bin to\n"
                    + "take it back from. Keep anything you would miss somewhere the computer\n"
                    + "cannot see, and hand single files to an AI app through PocketLinux's own\n"
                    + "picker instead -- the desktop screen, Window, Add a file from the phone or\n"
                    + "a cloud drive.\n");
        } catch (IOException ignored) {
            // The note is a courtesy; the Settings screen says the same thing.
        }
    }

    /** The desktop scripts live as real shell files in assets, so they can be read and linted. */
    private static void copyAsset(Context context, String asset, String relativePath)
            throws IOException, ErrnoException {
        File target = new File(rootfs(context), relativePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getName());
        }
        int mode = asset.endsWith(".sh") || asset.endsWith(".py") ? 0755 : 0644;
        // Every Open desktop refreshes these assets. Rewriting and fsyncing dozens of files
        // even when the APK has not changed needlessly stalls startup on phone flash storage.
        // Compare bytes, not version markers: this also repairs a changed/corrupt helper.
        if (target.isFile() && !java.nio.file.Files.isSymbolicLink(target.toPath())) {
            try (InputStream assetInput = context.getAssets().open(asset);
                 InputStream installedInput = new FileInputStream(target)) {
                if (sameContents(assetInput, installedInput)) {
                    if ((Os.stat(target.getAbsolutePath()).st_mode & 0777) != mode) {
                        Os.chmod(target.getAbsolutePath(), mode);
                    }
                    return;
                }
            }
        }
        // Menu refresh can run while these shell scripts are executing. Truncating the live
        // inode changed the remaining instructions underneath a running desktop/launcher.
        File temporary = File.createTempFile(".pocketdesk-asset-", ".tmp", parent);
        try {
            try (InputStream input = context.getAssets().open(asset);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.getFD().sync();
            }
            Os.chmod(temporary.getAbsolutePath(), mode);
            Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
        } finally {
            temporary.delete();
        }
    }

    private static boolean sameContents(InputStream expected, InputStream actual) throws IOException {
        byte[] expectedBytes = new byte[8192];
        byte[] actualBytes = new byte[8192];
        int count;
        while ((count = expected.read(expectedBytes)) != -1) {
            int total = 0;
            while (total < count) {
                int read = actual.read(actualBytes, total, count - total);
                if (read == -1) return false;
                total += read;
            }
            for (int i = 0; i < count; i++) {
                if (expectedBytes[i] != actualBytes[i]) return false;
            }
        }
        return actual.read() == -1;
    }

    /** Rebuilds the desktop's menu, panel and icons from what is really installed. */
    static void refreshDesktopEntries(Context context) throws IOException, ErrnoException {
        copyAsset(context, "pocketdesk-browser.sh", "usr/local/bin/pocketdesk-browser");
        copyAsset(context, "pocketdesk-xdg-open.sh", "usr/local/bin/xdg-open");
        copyAsset(context, "pocketdesk-menu.sh", "usr/local/bin/pocketdesk-menu");
        copyAsset(context, "pocketdesk-open.sh", "usr/local/bin/pocketdesk-open");
        copyAsset(context, "pocketdesk-graphics.py", "usr/local/bin/pocketdesk-graphics.py");
        // The process helper the app supervisor reads: everything it does is decided from
        // /proc rather than from a command line, so a shell or the PRoot tracer carrying the
        // same path in its arguments can never be signalled by mistake.
        // Where a file that did not come through the browser is placed: the same setting
        // Chrome is given, applied to whatever an AI app or a build wrote into Downloads.
        // What can be built for a phone here, how to start it, and how to build-install-open
        // it on the connected one. Also the honest iPhone answer, in one place.
        copyAsset(context, "pocketdesk-mobile.sh", "usr/local/bin/pocketdesk-mobile");
        copyAsset(context, "pocketdesk-save.sh", "usr/local/bin/pocketdesk-save");
        copyAsset(context, "pocketdesk-procinfo.py", "usr/local/bin/pocketdesk-procinfo.py");
        copyAsset(context, "pocketdesk-appprocess.py", "usr/local/bin/pocketdesk-appprocess.py");
        copyAsset(context, "pocketdesk-childwatch.py", "usr/local/bin/pocketdesk-childwatch.py");
        copyAsset(context, "pocketdesk-windows.sh", "usr/local/bin/pocketdesk-windows");
        copyAsset(context, "pocketdesk-window-guard.sh", "usr/local/bin/pocketdesk-window-guard");
        copyAsset(context, "pocketdesk-status.sh", "usr/local/bin/pocketdesk-status");
        copyAsset(context, "pocketdesk-install.sh", "usr/local/bin/pocketdesk-install");
        copyAsset(context, "pocketdesk-storage.sh", "usr/local/bin/pocketdesk-storage");
        copyAsset(context, "pocketdesk-software.sh", "usr/local/bin/pocketdesk-software");
        copyAsset(context, "pocketdesk-mcp.py", "usr/local/bin/pocketdesk-mcp");
        copyAsset(context, "pocketdesk-agent.sh", "usr/local/bin/pocketdesk-agent");
        copyAsset(context, "pocketdesk-appshot.sh", "usr/local/bin/pocketdesk-appshot");
        copyAsset(context, "pocketdesk-adb.sh", "usr/local/bin/pocketdesk-adb");
        copyAsset(context, "pocketdesk-shot.sh", "usr/local/bin/pocketdesk-shot");
        copyAsset(context, "pocketdesk-settings.sh", "usr/local/bin/pocketdesk-settings");
        copyAsset(context, "pocketdesk-mark.png", "usr/share/pixmaps/pocketdesk-mark.png");
        copyAsset(context, "pocketdesk-files.png", "usr/share/pixmaps/pocketdesk-files.png");
        copyAsset(context, "pocketdesk-phone.png", "usr/share/pixmaps/pocketdesk-phone.png");
        copyAsset(context, "pocketdesk-projects.png", "usr/share/pixmaps/pocketdesk-projects.png");
        copyAsset(context, "pocketdesk-settings.png", "usr/share/pixmaps/pocketdesk-settings.png");
        copyAsset(context, "pocketdesk-software.png", "usr/share/pixmaps/pocketdesk-software.png");
        copyAsset(context, "pocketdesk-package.png", "usr/share/pixmaps/pocketdesk-package.png");
    }

    static boolean isAppInstalled(Context context, LinuxApps.App app) {
        return new File(rootfs(context), app.marker.substring(1)).exists();
    }

    /** Removed in 10.0.30; kept only so an upgrade can clear the old boolean value. */
    static final String KEY_SHARE_DOWNLOADS = "share_downloads";
    static final String KEY_APP_LOCK = "app_lock";
    /** Set when the app lock switched itself off because the phone's own lock was removed. */
    static final String KEY_LOCK_NOTICE = "app_lock_notice";
    /** "finger" (tap where you touch, the phone way) or "mouse" (an arrow you drag). */
    static final String KEY_POINTER_MODE = "pointer_mode";
    /** Where the desktop screen's control bar sits: "top" or "bottom". */
    static final String KEY_CONTROLS_AT = "controls_at";
    /** Whether the row of special keys is shown under the control bar. */
    static final String KEY_KEY_ROW = "key_row";
    /** When and why the Linux computer last stopped by itself, so the home screen can say. */
    static final String KEY_LAST_STOP_AT = "last_stop_at";
    static final String KEY_LAST_STOP_REASON = "last_stop_reason";
    static final String KEY_LAST_OPENED_AT = "last_opened_at";
    /**
     * Set while the desktop is running and cleared when it ends in any way the service sees.
     * Still set at the next start, it means Android ended the whole app while the desktop was
     * open -- the one stop the service itself can never write down at the time.
     */
    static final String KEY_DESKTOP_ALIVE = "desktop_alive";
    /** Stamped every half minute while the desktop runs: the "when" of an unseen stop. */
    static final String KEY_HEARTBEAT_AT = "desktop_heartbeat_at";
    /**
     * PRoot's seccomp accelerator cuts the ptrace stops per system call by a large factor,
     * which is most of the difference between a desktop that lags and one that does not. A
     * few Android kernels cannot run it; the first desktop start that dies without a display
     * sets this and the next start runs without it, permanently.
     */
    static final String KEY_PROOT_NO_SECCOMP = "proot_no_seccomp";
    /**
     * "Faster desktop": run the desktop with PRoot's seccomp accelerator. Off by default,
     * because Chromium and Electron apps (ChatGPT, Claude, Cursor, Antigravity, Brave) reset
     * their own signal handlers at startup, which breaks the accelerator's SIGSYS emulation and
     * makes socket(), readlink() and unlink() return "Function not implemented" in the app's
     * main process -- ChatGPT then aborts and the screen drops back to the home tab. Without the
     * accelerator every syscall is traced instead, which is a little slower but lets every app
     * actually run. The owner can turn this on to trade reliability for speed.
     */
    static final String KEY_FAST_DESKTOP = "fast_desktop";

    static String startDesktopCommand(int width, int height, int dpi) {
        return startDesktopCommand(width, height, dpi, DOWNLOAD_ASK, THEME_DARK);
    }

    /**
     * The desktop size actually started: the long side 800-1920, the short side 480-1200, even,
     * whichever way the phone is held. Clamping width and height separately turned a portrait
     * 720x1600 into 800x1200 -- the wrong shape -- and the status text then lied about it.
     */
    static int[] safeGeometry(int width, int height) {
        boolean portrait = height > width;
        int longSide = even(Math.max(800, Math.min(Math.max(width, height), 1920)));
        int shortSide = even(Math.max(480, Math.min(Math.min(width, height), 1200)));
        return portrait ? new int[]{shortSide, longSide} : new int[]{longSide, shortSide};
    }

    /** Only the three compiled-in values may become shell arguments or a managed browser policy. */
    static String normaliseDownloadTarget(String value) {
        if (DOWNLOAD_COMPUTER.equals(value) || DOWNLOAD_PHONE.equals(value)) return value;
        return DOWNLOAD_ASK;
    }

    /** Only "light" and "dark" may become a shell argument; anything else is the dark desktop. */
    static final String THEME_DARK = "dark";
    static final String THEME_LIGHT = "light";

    static String normaliseTheme(String value) {
        return THEME_LIGHT.equals(value) ? THEME_LIGHT : THEME_DARK;
    }

    static String startDesktopCommand(int width, int height, int dpi, String requestedTarget,
            String requestedTheme) {
        int[] safe = safeGeometry(width, height);
        int safeWidth = safe[0];
        int safeHeight = safe[1];
        int safeDpi = Math.max(96, Math.min(dpi, 240));
        String target = normaliseDownloadTarget(requestedTarget);
        String downloadDirectory = DOWNLOAD_PHONE.equals(target)
                ? "/home/coder/Phone/Download/PocketLinux" : "/home/coder/Downloads";
        String prompt = DOWNLOAD_ASK.equals(target) ? "true" : "false";
        String theme = normaliseTheme(requestedTheme);
        return "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1; "
                + "mkdir -p /tmp/.X11-unix; chmod 1777 /tmp /tmp/.X11-unix; "
                // Android hands the container supplementary GIDs that Ubuntu has no names for.
                // Naming them stops every login shell printing "groups: cannot find name for group ID".
                + "for gid in $(id -G 2>/dev/null); do "
                + "getent group \"$gid\" >/dev/null 2>&1 || echo \"android$gid:x:$gid:\" >> /etc/group; done; "
                // Bootstrap owns the one recursive ownership pass. Walking the whole home
                // again visits every browser cache and library on every Open desktop, and
                // can consume the display startup limit before Xtigervnc is even launched.
                + "chown coder:coder /home/coder 2>/dev/null || true; "
                // The browser on a phone with no graphics driver: hardware acceleration
                // never (the compositor path stalled for seconds per page here) and a blank
                // start page (the thumbnail page was the "Page Unresponsive"). Only keys that
                // exist in GNOME Web 45's schema: an unknown key is ignored with a warning.
                + "mkdir -p /usr/share/glib-2.0/schemas; "
                + "printf 'precedence ::ffff:0:0/96  100\\n' > /etc/gai.conf; "
                // The phone's own time zone, re-read at every start so the desktop clock and
                // every file time follow the owner when they travel.
                + LinuxApps.PD_TIMEZONE
                // The table of which app answers which link scheme, rebuilt from what is
                // installed now, so a sign-in that opens in the browser finds its way back.
                + "command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database /usr/share/applications >/dev/null 2>&1; "
                // Chrome reads a system policy rather than XDG user-dirs. Write just this one
                // per-session policy as root; the directory and boolean came from the closed set
                // above, never from an untrusted string.
                + "mkdir -p /etc/opt/chrome/policies/managed; "
                // Rewrite the base policy on every start as well as new Chrome installs. This
                // removes the old forced download block from already-set-up computers without
                // reinstalling Chrome or touching any of the owner's files.
                + "printf '%s\\n' '" + LinuxApps.CHROME_POLICY + "' "
                + "> /etc/opt/chrome/policies/managed/pocketdesk.json; "
                + "printf '%s\\n' '{\"PromptForDownloadLocation\":" + prompt
                + ",\"DownloadDirectory\":\"" + downloadDirectory + "\"}' "
                + "> /etc/opt/chrome/policies/managed/pocketdesk-downloads.json; "
                // The system daemon must prepare its socket and messagebus identity before
                // su. The helper retains a live listener and reports bounded startup failures.
                + "/usr/local/bin/pocketdesk-desktop --prepare-system-bus || true; "
                + "exec su - coder -c 'exec env POCKETDESK_DOWNLOAD_TARGET=" + target
                + " POCKETDESK_DOWNLOAD_DIR=" + downloadDirectory
                + " POCKETDESK_DOWNLOAD_PROMPT=" + (DOWNLOAD_ASK.equals(target) ? 1 : 0)
                // The computer looks the way the app around it looks. Light or dark, already
                // resolved on the phone, so "System" means the phone's own system here too.
                + " POCKETDESK_THEME=" + theme
                + " /usr/local/bin/pocketdesk-desktop "
                + safeWidth + "x" + safeHeight + " " + safeDpi + "'";
    }

    private static int even(int value) {
        return value - (value % 2);
    }

    private static void writeExecutable(File file, String value) throws IOException, ErrnoException {
        writeText(file, value);
        Os.chmod(file.getAbsolutePath(), 0755);
    }

    private static void writeText(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    /** Recursive on-disk size, used to show how much space Linux is really taking. */
    private static final java.util.concurrent.atomic.AtomicBoolean SIZE_RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long cachedSize = -1L;
    private static volatile long cachedAt = 0L;
    private static final long SIZE_TTL_MS = 60_000L;

    /**
     * Forget the measured size: the container changed on disk and the old number would lie.
     * The generation counter also disowns a walk that is already running, so its result cannot
     * land in the cache after the install that made it stale.
     */
    static void invalidateSize() {
        cachedSize = -1L;
        cachedAt = 0L;
        sizeGeneration++;
    }

    private static volatile int sizeGeneration;

    static boolean hasFreshSize() {
        return cachedSize >= 0 && android.os.SystemClock.elapsedRealtime() - cachedAt < SIZE_TTL_MS;
    }

    /**
     * The size of the container, measured at most once a minute and never twice at once.
     *
     * Walking 2-3 GB of Ubuntu is minutes of disk on a phone; it used to start again on every
     * return to the home screen, three walks racing each other while the desktop wanted the same
     * disk. Nothing here holds the screen it came from: the caller passes the handler.
     */
    static void measureSize(Context context, File root, android.os.Handler main,
                            java.util.concurrent.atomic.AtomicBoolean cancelled,
                            SizeListener onDone) {
        if (hasFreshSize()) {
            onDone.size(cachedSize);
            return;
        }
        if (!SIZE_RUNNING.compareAndSet(false, true)) return;   // one walk at a time, ever
        final int generation = sizeGeneration;
        new Thread(() -> {
            long bytes = -1L;
            try {
                bytes = androidReportedSize(context);
                if (bytes < 0) bytes = directorySize(root);
            } catch (Throwable ignored) {
                // A container being deleted underneath the walk is not worth a crash.
            } finally {
                SIZE_RUNNING.set(false);
            }
            if (bytes >= 0 && generation == sizeGeneration) {
                cachedSize = bytes;
                cachedAt = android.os.SystemClock.elapsedRealtime();
            }
            final long result = generation == sizeGeneration ? bytes : -1L;
            if (result >= 0 && (cancelled == null || !cancelled.get())) {
                main.post(() -> onDone.size(result));
            }
        }, "pocketdesk-size").start();
    }

    interface SizeListener { void size(long bytes); }

    /**
     * What the phone itself says PocketLinux is using: the same total Android prints under
     * Settings -> Apps -> PocketLinux -> Storage. One question to the system, answered from the
     * filesystem's own accounting, instead of walking 200,000 files of Ubuntu -- and it counts
     * allocated blocks, which a walk over file lengths cannot see. An app needs no permission
     * to ask about itself. -1 when the phone will not answer, and the caller walks instead.
     */
    static long androidReportedSize(Context context) {
        try {
            android.app.usage.StorageStatsManager stats = (android.app.usage.StorageStatsManager)
                    context.getSystemService(Context.STORAGE_STATS_SERVICE);
            android.os.storage.StorageManager storage = (android.os.storage.StorageManager)
                    context.getSystemService(Context.STORAGE_SERVICE);
            if (stats == null || storage == null) return -1L;
            java.util.UUID uuid = storage.getUuidForPath(context.getFilesDir());
            android.app.usage.StorageStats result = stats.queryStatsForPackage(
                    uuid, context.getPackageName(), android.os.Process.myUserHandle());
            return result.getAppBytes() + result.getDataBytes();
        } catch (Throwable error) {
            return -1L;   // any phone that will not answer keeps the old measurement
        }
    }

    static long directorySize(File root) {
        return Trees.size(root);
    }

    static void deleteTree(File root) throws IOException {
        Trees.delete(root);
    }
}
