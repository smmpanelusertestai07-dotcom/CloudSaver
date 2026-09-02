package com.pocketdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    /** 120 dpi reads like a small PC screen; 168 filled the display with a few huge windows. */
    static final int DEFAULT_UI_SCALE = 120;
    /** Long side of the desktop framebuffer; keeps memory sane on a 4 GB phone. */
    static final int GEOMETRY_CAP = 1600;

    static final String UBUNTU_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz";
    /** Failover order for the base archive. Every mirror is checked against UBUNTU_SHA256. */
    static final String[] UBUNTU_MIRRORS = {
            UBUNTU_URL,
            "https://mirror.us.leaseweb.net/ubuntu-cdimage/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
    };
    static final String UBUNTU_SHA256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2";
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

    /**
     * Stand-ins for the /proc entries Android will not let a normal app read.
     *
     * Each is bound over the path it replaces. The values are ordinary, unremarkable ones: the
     * point is only that the file exists and parses, so software that reads it carries on
     * instead of failing and then guessing.
     */
    private static Map<String, String> fakeProcFiles(Context context) throws IOException {
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("/proc/loadavg", "0.32 0.28 0.24 1/512 4096\n");
        contents.put("/proc/uptime", "1234.56 4321.00\n");
        contents.put("/proc/version",
                "Linux version 6.2.1 (pocketdesk@localhost) (gcc 13.2.0) #1 SMP PREEMPT\n");
        contents.put("/proc/sys/kernel/cap_last_cap", "40\n");
        // Chromium's file watcher reads this one and logs an error for every process without it.
        contents.put("/proc/sys/fs/inotify/max_user_watches", "524288\n");
        contents.put("/proc/stat", statContents());
        contents.put("/proc/vmstat", vmstatContents());

        File directory = new File(context.getFilesDir(), "proc-fakes");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create the /proc stand-in directory");
        }
        Map<String, String> binds = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            String name = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
            File file = new File(directory, name);
            if (!file.exists() || file.length() == 0) {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(entry.getValue().getBytes("UTF-8"));
                }
            }
            binds.put(entry.getKey(), file.getAbsolutePath());
        }
        return binds;
    }

    private static String statContents() {
        StringBuilder stat = new StringBuilder("cpu  100000 0 50000 900000 0 0 0 0 0 0\n");
        for (int cpu = 0; cpu < 8; cpu++) {
            stat.append("cpu").append(cpu).append(" 12500 0 6250 112500 0 0 0 0 0 0\n");
        }
        stat.append("intr 0\nctxt 100000\nbtime 1700000000\nprocesses 4096\n")
                .append("procs_running 1\nprocs_blocked 0\nsoftirq 0\n");
        return stat.toString();
    }

    private static String vmstatContents() {
        String[] keys = {"nr_free_pages", "nr_zone_inactive_anon", "nr_zone_active_anon",
                "nr_zone_inactive_file", "nr_zone_active_file", "nr_dirty", "nr_writeback",
                "pgpgin", "pgpgout", "pswpin", "pswpout", "pgfault", "pgmajfault"};
        StringBuilder vmstat = new StringBuilder();
        for (String key : keys) vmstat.append(key).append(" 0\n");
        return vmstat.toString();
    }

    static Process startContainer(Context context, String command) throws IOException {
        File root = rootfs(context);
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
        args.add("/bin/bash");
        args.add("-lc");
        args.add(command);

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        builder.environment().put("PROOT_TMP_DIR", new File(context.getFilesDir(), "usr/tmp").getAbsolutePath());
        builder.environment().put("PROOT_LOADER", new File(nativeDirectory, "libproot-loader.so").getAbsolutePath());
        builder.environment().put("PROOT_NO_SECCOMP", "1");
        builder.environment().put("PROOT_NO_MOUNTINFO", "1");
        builder.environment().put("LD_LIBRARY_PATH", nativeDirectory.getAbsolutePath());
        return builder.start();
    }

    static int runContainer(Context context, String command, OutputListener listener)
            throws IOException, InterruptedException {
        Process process = startContainer(context, command);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (listener != null) listener.line(line);
                if (Thread.currentThread().isInterrupted()) {
                    process.destroy();
                    throw new InterruptedException("Interrupted");
                }
            }
        }
        return process.waitFor();
    }

    static String bootstrapCommand() {
        return "set -eu; "
                + "export DEBIAN_FRONTEND=noninteractive; "
                + "rm -f /etc/resolv.conf; printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > /etc/resolv.conf; "
                + "printf '127.0.0.1 localhost\\n::1 localhost\\n' > /etc/hosts; "
                + "printf 'Acquire::Retries \"5\";\nAcquire::http::Timeout \"40\";\nAcquire::https::Timeout \"40\";\n' > /etc/apt/apt.conf.d/99pocketdesk; "
                + "apt-get update; "
                + "apt-get install -y --no-install-recommends "
                + "tigervnc-standalone-server openbox lxterminal pcmanfm tint2 dbus-x11 "
                + "x11-xserver-utils x11-utils xfonts-base fonts-dejavu-core ca-certificates curl gnupg git nano sudo "
                // Without an icon and cursor theme every launcher is a generic diamond and the
                // pointer stays the old X11 cross instead of an arrow.
                + "xdg-utils adwaita-icon-theme dmz-cursor-theme tzdata "
                // adwaita only Recommends this, and we install without recommends -- so without
                // naming it every SVG icon in the theme falls back to a generic diamond.
                + "librsvg2-common "
                // Window controls the desktop offers: minimise all, close all, list what is open.
                + "wmctrl xdotool "
                // On-screen toasts and dialogs: an app that fails to start has to be able to say so.
                + "dunst libnotify-bin zenity xdotool; "
                // A desktop clock is only useful in the user's own time.
                + "ln -sf /usr/share/zoneinfo/Asia/Kolkata /etc/localtime; "
                + "echo 'Asia/Kolkata' > /etc/timezone; "
                + "id coder >/dev/null 2>&1 || useradd -m -s /bin/bash coder; "
                + "printf 'coder ALL=(ALL) NOPASSWD:ALL\\n' > /etc/sudoers.d/coder; chmod 0440 /etc/sudoers.d/coder; "
                + "mkdir -p /home/coder/Desktop /home/coder/.config /home/coder/Projects "
                + "/home/coder/Downloads /usr/share/backgrounds; "
                // A computer with no browser is not much of a computer. GNOME Web is the one
                // that opens in a couple of seconds on a phone; Firefox is a separate choice in
                // the app list for anyone who wants it.
                + "apt-get install -y --no-install-recommends epiphany-browser || true; "
                // GNOME Web's start page renders live thumbnails, which is the slowest possible
                // first thing to draw on a phone -- and what put "Page Unresponsive" on screen.
                + "mkdir -p /usr/share/glib-2.0/schemas; "
                + "printf '[org.gnome.Epiphany]\\nhomepage-url=\\047about:blank\\047\\n' "
                + "> /usr/share/glib-2.0/schemas/99_pocketdesk.gschema.override; "
                + "glib-compile-schemas /usr/share/glib-2.0/schemas >/dev/null 2>&1 || true; "
                + "chown -R coder:coder /home/coder; "
                + "apt-get clean; rm -rf /var/lib/apt/lists/*";
    }

    static void writeDesktopScripts(Context context) throws IOException, ErrnoException {
        copyAsset(context, "pocketdesk-desktop.sh", "usr/local/bin/pocketdesk-desktop");
        copyAsset(context, "pocketdesk-menu.sh", "usr/local/bin/pocketdesk-menu");
        copyAsset(context, "pocketdesk-open.sh", "usr/local/bin/pocketdesk-open");
        copyAsset(context, "pocketdesk-windows.sh", "usr/local/bin/pocketdesk-windows");
        copyAsset(context, "wallpaper.png", "usr/share/backgrounds/pocketdesk.png");
        // Antigravity ships as a tarball with no packaged icon, so it borrows Google's own.
        copyAsset(context, "antigravity.png", "usr/share/pixmaps/antigravity.png");
        // A folder that looks like a folder: the theme's file-manager mark reads as a grey box.
        copyAsset(context, "pocketdesk-files.png", "usr/share/pixmaps/pocketdesk-files.png");
    }

    /** The desktop scripts live as real shell files in assets, so they can be read and linted. */
    private static void copyAsset(Context context, String asset, String relativePath)
            throws IOException, ErrnoException {
        File target = new File(rootfs(context), relativePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent.getName());
        }
        try (InputStream input = context.getAssets().open(asset);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.getFD().sync();
        }
        Os.chmod(target.getAbsolutePath(), asset.endsWith(".sh") ? 0755 : 0644);
    }

    /** Rebuilds the desktop's menu, panel and icons from what is really installed. */
    static void refreshDesktopEntries(Context context) throws IOException, ErrnoException {
        copyAsset(context, "pocketdesk-menu.sh", "usr/local/bin/pocketdesk-menu");
        copyAsset(context, "pocketdesk-open.sh", "usr/local/bin/pocketdesk-open");
        copyAsset(context, "pocketdesk-windows.sh", "usr/local/bin/pocketdesk-windows");
    }

    static boolean isAppInstalled(Context context, LinuxApps.App app) {
        return new File(rootfs(context), app.marker.substring(1)).exists();
    }

    static final String KEY_SHARE_DOWNLOADS = "share_downloads";
    static final String KEY_APP_LOCK = "app_lock";

    static String startDesktopCommand(int width, int height, int dpi) {
        return startDesktopCommand(width, height, dpi, true);
    }

    static String startDesktopCommand(int width, int height, int dpi, boolean shareDownloads) {
        int safeWidth = even(Math.max(800, Math.min(width, 1920)));
        int safeHeight = even(Math.max(480, Math.min(height, 1200)));
        int safeDpi = Math.max(96, Math.min(dpi, 240));
        return "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1; "
                + "mkdir -p /tmp/.X11-unix; chmod 1777 /tmp /tmp/.X11-unix; "
                // Android hands the container supplementary GIDs that Ubuntu has no names for.
                // Naming them stops every login shell printing "groups: cannot find name for group ID".
                + "for gid in $(id -G 2>/dev/null); do "
                + "getent group \"$gid\" >/dev/null 2>&1 || echo \"android$gid:x:$gid:\" >> /etc/group; done; "
                + "chown -R coder:coder /home/coder 2>/dev/null || true; "
                + "export POCKETDESK_SHARE_DOWNLOADS=" + (shareDownloads ? 1 : 0) + "; "
                + "exec su - coder -c 'POCKETDESK_SHARE_DOWNLOADS=" + (shareDownloads ? 1 : 0)
                + " /usr/local/bin/pocketdesk-desktop "
                + safeWidth + "x" + safeHeight + " " + safeDpi + "'";
    }

    private static int even(int value) {
        return value - (value % 2);
    }

    private static String desktopScript() {
        return "#!/bin/bash\n"
                + "set -u\n"
                + "GEOMETRY=${1:-1280x720}\n"
                + "DPI=${2:-160}\n"
                + "export HOME=/home/coder USER=coder LOGNAME=coder DISPLAY=:1 LANG=C.UTF-8\n"
                + "cd \"$HOME\"\n"
                + "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1\n"
                // A real DPI is what makes text large without blurring it: the desktop renders at
                // the phone's own pixel count, and only the type and controls grow.
                + "printf 'Xft.dpi: %s\\nXft.antialias: true\\nXft.hinting: true\\n"
                + "Xft.hintstyle: hintslight\\nXft.rgba: rgb\\n' \"$DPI\" > \"$HOME/.Xresources\"\n"
                + "mkdir -p \"$HOME/.config/gtk-3.0\" \"$HOME/.config/lxterminal\" \"$HOME/.config/tint2\"\n"
                + "printf '[Settings]\\ngtk-font-name=Sans 11\\ngtk-application-prefer-dark-theme=1\\n"
                + "gtk-xft-dpi=%s\\n' \"$((DPI * 1024))\" > \"$HOME/.config/gtk-3.0/settings.ini\"\n"
                + "printf '[general]\\nfontname=Monospace 12\\nscrollback=4000\\n"
                + "bgcolor=rgb(23,26,38)\\nfgcolor=rgb(226,232,245)\\ngeometry_columns=100\\n"
                + "geometry_rows=28\\nhidescrollbar=false\\n' > \"$HOME/.config/lxterminal/lxterminal.conf\"\n"
                + "printf 'panel_items = LTSC\\npanel_size = 100%% 44\\ntaskbar_name = 0\\n"
                + "task_font = Sans 11\\nclock_font_line1 = Sans 11\\nlauncher_icon_size = 28\\n"
                + "task_maximum_size = 220 40\\n' > \"$HOME/.config/tint2/tint2rc\"\n"
                + "/usr/bin/Xtigervnc :1 -rfbport 5901 -localhost yes -SecurityTypes None -ac -AlwaysShared "
                + "-geometry \"$GEOMETRY\" -depth 24 -dpi \"$DPI\" -desktop 'PocketDesk' &\n"
                + "VNC_PID=$!\n"
                + "for n in 1 2 3 4 5 6 7 8; do [ -S /tmp/.X11-unix/X1 ] && break; sleep 0.5; done\n"
                + "xrdb -merge \"$HOME/.Xresources\" >/dev/null 2>&1 || true\n"
                + "eval \"$(dbus-launch --sh-syntax)\"\n"
                + "openbox-session >/tmp/pocketdesk-openbox.log 2>&1 &\n"
                + "tint2 >/tmp/pocketdesk-tint2.log 2>&1 &\n"
                + "pcmanfm --desktop --profile LXDE >/tmp/pocketdesk-pcmanfm.log 2>&1 &\n"
                + "lxterminal >/tmp/pocketdesk-terminal.log 2>&1 &\n"
                + "wait \"$VNC_PID\"\n";
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
    static long directorySize(File root) {
        return Trees.size(root);
    }

    static void deleteTree(File root) throws IOException {
        Trees.delete(root);
    }
}
