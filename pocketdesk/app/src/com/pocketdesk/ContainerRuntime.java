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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ContainerRuntime {
    static final String PREFS = "pocketdesk_preferences";
    static final String KEY_WIFI_ONLY = "wifi_only";
    static final String KEY_THERMAL_GUARD = "thermal_guard";
    static final String KEY_SESSION_MINUTES = "session_minutes";
    static final String KEY_ORIENTATION = "orientation";
    static final String KEY_THEME = "theme";
    static final String KEY_POLICY_V2 = "balanced_policy_v2";
    static final String KEY_PERMISSION_INTRO = "permission_intro_v1";
    static final String KEY_CRASH_SEEN = "crash_seen_at";
    static final String KEY_DESKTOP_INSTALLED = "desktop_installed";
    static final String KEY_UI_SCALE = "ui_scale_dpi";
    static final int DEFAULT_UI_SCALE = 168;
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
                + "x11-xserver-utils xfonts-base fonts-dejavu-core ca-certificates curl git nano sudo xdg-utils; "
                + "id coder >/dev/null 2>&1 || useradd -m -s /bin/bash coder; "
                + "printf 'coder ALL=(ALL) NOPASSWD:ALL\\n' > /etc/sudoers.d/coder; chmod 0440 /etc/sudoers.d/coder; "
                + "mkdir -p /home/coder/Desktop /home/coder/.config; chown -R coder:coder /home/coder; "
                + "apt-get clean; rm -rf /var/lib/apt/lists/*";
    }

    static void writeDesktopScripts(Context context) throws IOException, ErrnoException {
        copyAsset(context, "pocketdesk-desktop.sh", "usr/local/bin/pocketdesk-desktop");
        copyAsset(context, "pocketdesk-menu.sh", "usr/local/bin/pocketdesk-menu");
        writeShortcut(context, "Terminal", "utilities-terminal", "lxterminal");
        writeShortcut(context, "Files", "system-file-manager", "pcmanfm /home/coder/Shared");
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
        Os.chmod(target.getAbsolutePath(), 0755);
    }

    /** A desktop icon plus a launcher wrapper, used for every app in the catalog. */
    static void writeAppShortcut(Context context, LinuxApps.App app) throws IOException, ErrnoException {
        File root = rootfs(context);
        String launcher = "/usr/local/bin/pocketdesk-" + app.id;
        writeExecutable(new File(root, launcher.substring(1)), LinuxApps.launcherScript(app.marker));
        writeShortcut(context, app.name, app.id, launcher);
    }

    private static void writeShortcut(Context context, String name, String icon, String exec)
            throws IOException, ErrnoException {
        File file = new File(rootfs(context), "home/coder/Desktop/" + name + ".desktop");
        writeText(file, "[Desktop Entry]\nType=Application\nName=" + name
                + "\nIcon=" + icon + "\nExec=" + exec + "\nTerminal=false\nCategories=Development;Utility;\n");
        Os.chmod(file.getAbsolutePath(), 0755);
        // The same entry in the system menu, so it is reachable even without desktop icons.
        File shared = new File(rootfs(context), "usr/share/applications/pocketdesk-"
                + name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + ".desktop");
        writeText(shared, "[Desktop Entry]\nType=Application\nName=" + name
                + "\nIcon=" + icon + "\nExec=" + exec + "\nTerminal=false\nCategories=Development;Utility;\n");
    }

    static boolean isAppInstalled(Context context, LinuxApps.App app) {
        return new File(rootfs(context), app.marker.substring(1)).exists();
    }

    static String startDesktopCommand(int width, int height, int dpi) {
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
                + "exec su - coder -c '/usr/local/bin/pocketdesk-desktop "
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
