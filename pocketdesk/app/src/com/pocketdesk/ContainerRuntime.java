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
import java.util.List;

final class ContainerRuntime {
    static final String PREFS = "pocketdesk_preferences";
    static final String KEY_WIFI_ONLY = "wifi_only";
    static final String KEY_THERMAL_GUARD = "thermal_guard";
    static final String KEY_SESSION_MINUTES = "session_minutes";
    static final String KEY_ORIENTATION = "orientation";
    static final String KEY_THEME = "theme";
    static final String KEY_POLICY_V2 = "balanced_policy_v2";
    static final String KEY_DESKTOP_INSTALLED = "desktop_installed";
    static final String KEY_CHATGPT_INSTALLED = "chatgpt_installed";

    static final String UBUNTU_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz";
    /** Failover order for the base archive. Every mirror is checked against UBUNTU_SHA256. */
    static final String[] UBUNTU_MIRRORS = {
            UBUNTU_URL,
            "https://mirror.us.leaseweb.net/ubuntu-cdimage/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
    };
    static final String UBUNTU_SHA256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2";
    static final String CHATGPT_URL = "https://persistent.oaistatic.com/codex-app-prod/linux/deb/latest/chatgpt_arm64.deb";
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

    static boolean isChatGptInstalled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CHATGPT_INSTALLED, false);
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
        File root = rootfs(context);
        writeExecutable(new File(root, "usr/local/bin/pocketdesk-desktop"), desktopScript());
        writeExecutable(new File(root, "usr/local/bin/pocketdesk-chatgpt"), chatGptLaunchScript());
        writeText(new File(root, "home/coder/Desktop/Terminal.desktop"),
                "[Desktop Entry]\nType=Application\nName=Terminal\nIcon=utilities-terminal\nExec=lxterminal\nTerminal=false\n");
        writeText(new File(root, "home/coder/Desktop/Files.desktop"),
                "[Desktop Entry]\nType=Application\nName=Files\nIcon=system-file-manager\nExec=pcmanfm /home/coder/Shared\nTerminal=false\n");
        Os.chmod(new File(root, "home/coder/Desktop/Terminal.desktop").getAbsolutePath(), 0755);
        Os.chmod(new File(root, "home/coder/Desktop/Files.desktop").getAbsolutePath(), 0755);
    }

    static void writeChatGptShortcut(Context context) throws IOException, ErrnoException {
        File desktop = new File(rootfs(context), "home/coder/Desktop/ChatGPT.desktop");
        writeText(desktop,
                "[Desktop Entry]\nType=Application\nName=ChatGPT\nComment=Official OpenAI Linux app (experimental in PRoot)\n"
                        + "Icon=chatgpt\nExec=/usr/local/bin/pocketdesk-chatgpt\nTerminal=false\nCategories=Development;Utility;\n");
        Os.chmod(desktop.getAbsolutePath(), 0755);
    }

    static String chatGptInstallCommand() {
        return "set -eu; export DEBIAN_FRONTEND=noninteractive; "
                + "if dpkg-query -W -f='${Status}' chatgpt 2>/dev/null | grep -q 'install ok installed'; then "
                + "apt-get update; apt-get install -y --only-upgrade --no-install-recommends chatgpt; "
                + "else apt-get update; "
                + "curl --fail --location --retry 3 --progress-bar '" + CHATGPT_URL + "' -o /tmp/chatgpt_arm64.deb; "
                + "apt-get install -y --no-install-recommends /tmp/chatgpt_arm64.deb; rm -f /tmp/chatgpt_arm64.deb; fi; "
                + "apt-get clean; rm -rf /var/lib/apt/lists/*";
    }

    static String startDesktopCommand(int width, int height) {
        int safeWidth = Math.max(800, Math.min(width, 1600));
        int safeHeight = Math.max(480, Math.min(height, 1000));
        return "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1; "
                + "mkdir -p /tmp/.X11-unix; chmod 1777 /tmp /tmp/.X11-unix; "
                + "exec su - coder -c '/usr/local/bin/pocketdesk-desktop " + safeWidth + "x" + safeHeight + "'";
    }

    private static String desktopScript() {
        return "#!/bin/bash\n"
                + "set -u\n"
                + "GEOMETRY=${1:-1280x720}\n"
                + "export HOME=/home/coder USER=coder LOGNAME=coder DISPLAY=:1 LANG=C.UTF-8\n"
                + "cd \"$HOME\"\n"
                + "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1\n"
                + "/usr/bin/Xtigervnc :1 -rfbport 5901 -localhost yes -SecurityTypes None -ac -AlwaysShared -geometry \"$GEOMETRY\" -depth 24 -desktop 'PocketDesk' &\n"
                + "VNC_PID=$!\n"
                + "for n in 1 2 3 4 5 6 7 8; do [ -S /tmp/.X11-unix/X1 ] && break; sleep 0.5; done\n"
                + "eval \"$(dbus-launch --sh-syntax)\"\n"
                + "openbox-session >/tmp/pocketdesk-openbox.log 2>&1 &\n"
                + "tint2 >/tmp/pocketdesk-tint2.log 2>&1 &\n"
                + "pcmanfm --desktop --profile LXDE >/tmp/pocketdesk-pcmanfm.log 2>&1 &\n"
                + "lxterminal >/tmp/pocketdesk-terminal.log 2>&1 &\n"
                + "wait \"$VNC_PID\"\n";
    }

    private static String chatGptLaunchScript() {
        return "#!/bin/bash\n"
                + "export HOME=/home/coder USER=coder LOGNAME=coder DISPLAY=:1 LANG=C.UTF-8\n"
                + "export LIBGL_ALWAYS_SOFTWARE=1\n"
                + "for app in /usr/bin/chatgpt /usr/bin/ChatGPT /opt/ChatGPT/chatgpt /opt/ChatGPT/ChatGPT; do\n"
                + "  if [ -x \"$app\" ]; then exec \"$app\" --no-sandbox --disable-gpu --disable-dev-shm-usage; fi\n"
                + "done\n"
                + "lxterminal -e bash -lc 'echo ChatGPT executable was not found.; read -p Press_Enter'\n";
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
        if (root == null || !root.exists()) return 0L;
        if (root.isFile()) return root.length();
        File[] children = root.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            try {
                if (child.isFile()) total += child.length();
                else if (child.isDirectory() && !isSymlink(child)) total += directorySize(child);
            } catch (Exception ignored) {
                // A single unreadable entry must not break the total.
            }
        }
        return total;
    }

    private static boolean isSymlink(File file) {
        try {
            return !file.getAbsolutePath().equals(file.getCanonicalPath());
        } catch (IOException error) {
            return true;
        }
    }

    static void deleteTree(File root) throws IOException {
        if (root == null || !root.exists()) return;
        File canonical = root.getCanonicalFile();
        File[] children = canonical.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!canonical.delete()) throw new IOException("Could not remove incomplete setup: " + canonical.getName());
    }
}
