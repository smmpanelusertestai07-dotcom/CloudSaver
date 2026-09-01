package com.pocketdesk;

/**
 * The desktop apps PocketDesk can install into the container.
 *
 * Every entry installs the newest build each time it runs, so "install" and "update" are the same
 * action: apt repositories upgrade in place, and the direct downloads all resolve a "latest" URL
 * rather than a pinned version.
 */
final class LinuxApps {

    static final class App {
        final String id;
        final String name;
        final String summary;
        final int iconRes;
        final String approximateSize;
        /** Free space the install genuinely needs, package plus unpacked files. */
        final long needsBytes;
        /** Honest wall-clock expectation shown while installing, e.g. "5-15 min". */
        final String typicalTime;
        /** Shown before install when the app has a real limitation on this setup. */
        final String caution;
        final String marker;        // file that exists once installed
        private final String command;

        App(String id, String name, String summary, int iconRes, String approximateSize,
            long needsBytes, String typicalTime, String caution, String marker, String command) {
            this.id = id;
            this.name = name;
            this.summary = summary;
            this.iconRes = iconRes;
            this.approximateSize = approximateSize;
            this.needsBytes = needsBytes;
            this.typicalTime = typicalTime;
            this.caution = caution;
            this.marker = marker;
            this.command = command;
        }

        String installCommand() {
            return "set -eu; export DEBIAN_FRONTEND=noninteractive; "
                    + "printf 'Acquire::Retries \"5\";\\n' > /etc/apt/apt.conf.d/99pocketdesk-retry; "
                    + command
                    + "; apt-get clean; rm -rf /var/lib/apt/lists/*";
        }
    }

    private LinuxApps() {}

    private static final String LATEST_CHATGPT =
            "https://persistent.oaistatic.com/codex-app-prod/linux/deb/latest/chatgpt_arm64.deb";
    private static final String CLAUDE_KEY = "https://downloads.claude.ai/claude-desktop/key.asc";
    private static final String CLAUDE_REPO = "https://downloads.claude.ai/claude-desktop/apt/stable";
    /** Published by Anthropic in the Claude Desktop Linux install guide. */
    private static final String CLAUDE_FINGERPRINT = "31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE";
    private static final String VSCODE_LATEST =
            "https://update.code.visualstudio.com/latest/linux-deb-arm64/stable";
    private static final String ANTIGRAVITY_PAGE = "https://antigravity.google/download";
    // Ubuntu's own "firefox" package is a snap shim that cannot work inside a container, so the
    // real arm64 .deb comes from Mozilla's repository instead.
    private static final String MOZILLA_KEY = "https://packages.mozilla.org/apt/repo-signing-key.gpg";
    private static final String MOZILLA_REPO = "https://packages.mozilla.org/apt";

    private static final long GB = 1024L * 1024L * 1024L;

    static final App[] CATALOG = {
            // New installs get all of this during setup. This row is how a container built by an
            // earlier version catches up without being rebuilt.
            new App("essentials", "Desktop essentials",
                    "Firefox, icon theme, arrow cursor and Indian time.",
                    R.drawable.ic_network, "about 350 MB", 1 * GB, "3\u201310 min", null,
                    "/usr/bin/firefox",
                    "apt-get update; apt-get install -y --no-install-recommends "
                            + "curl gnupg ca-certificates adwaita-icon-theme dmz-cursor-theme tzdata "
                            + "xdg-utils x11-xserver-utils; "
                            + "ln -sf /usr/share/zoneinfo/Asia/Kolkata /etc/localtime; "
                            + "echo 'Asia/Kolkata' > /etc/timezone; "
                            + "install -d -m 0755 /etc/apt/keyrings; "
                            + "curl -fsSL '" + MOZILLA_KEY + "' -o /etc/apt/keyrings/packages.mozilla.org.asc; "
                            + "echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] "
                            + MOZILLA_REPO + " mozilla main' > /etc/apt/sources.list.d/mozilla.list; "
                            + "printf 'Package: *\\nPin: origin packages.mozilla.org\\nPin-Priority: 1000\\n' "
                            + "> /etc/apt/preferences.d/mozilla; "
                            + "apt-get update; apt-get install -y --no-install-recommends firefox"),

            new App("chatgpt", "ChatGPT", "OpenAI's desktop app. Includes Codex.",
                    R.drawable.ic_chat, "about 700 MB", 2500L * 1024 * 1024, "5\u201315 min",
                    "Computer Use is not offered on Linux. Your account's usage limits still apply.",
                    "/usr/bin/chatgpt",
                    "apt-get update; "
                            + "curl --fail --location --retry 3 '" + LATEST_CHATGPT + "' -o /tmp/chatgpt.deb; "
                            + "apt-get install -y --no-install-recommends /tmp/chatgpt.deb; rm -f /tmp/chatgpt.deb"),

            new App("claude", "Claude Desktop", "Anthropic's desktop app. Includes Claude Code.",
                    R.drawable.ic_terminal, "about 600 MB", 2500L * 1024 * 1024, "5\u201315 min",
                    "Linux support is in beta. Cowork needs hardware virtualisation, which a phone "
                            + "container cannot provide, so that tab stays unavailable.",
                    "/usr/bin/claude-desktop",
                    "apt-get update; apt-get install -y --no-install-recommends curl gnupg ca-certificates; "
                            + "curl -fsSLo /usr/share/keyrings/claude-desktop-archive-keyring.asc '" + CLAUDE_KEY + "'; "
                            // Refuse the key unless it is the fingerprint Anthropic publishes.
                            + "gpg --show-keys --with-colons /usr/share/keyrings/claude-desktop-archive-keyring.asc "
                            + "| grep -q '" + CLAUDE_FINGERPRINT + "' "
                            + "|| { echo 'Claude signing key did not match the published fingerprint'; exit 1; }; "
                            + "echo 'deb [arch=arm64 signed-by=/usr/share/keyrings/claude-desktop-archive-keyring.asc] "
                            + CLAUDE_REPO + " stable main' > /etc/apt/sources.list.d/claude-desktop.list; "
                            + "apt-get update; apt-get install -y --no-install-recommends claude-desktop"),

            new App("antigravity", "Antigravity", "Google's agent-first IDE.",
                    R.drawable.ic_desktop, "about 800 MB", 3 * GB, "5\u201320 min",
                    "Google ships Antigravity as a tarball, so it updates when you run this again.",
                    "/opt/antigravity/antigravity",
                    "apt-get update; apt-get install -y --no-install-recommends curl ca-certificates "
                            + "libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 "
                            + "libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2t64 libgtk-3-0; "
                            // The download page names the current build; never pin a version here.
                            + "url=$(curl -fsSL '" + ANTIGRAVITY_PAGE + "' "
                            + "| grep -oE 'https://[^\"]*linux-arm[^\"]*Antigravity\\.tar\\.gz' | head -n 1); "
                            + "[ -n \"$url\" ] || { echo 'Could not find the Linux ARM64 download on antigravity.google'; exit 1; }; "
                            + "curl --fail --location --retry 3 \"$url\" -o /tmp/antigravity.tar.gz; "
                            + "rm -rf /opt/antigravity; mkdir -p /opt/antigravity; "
                            + "tar -xzf /tmp/antigravity.tar.gz -C /opt/antigravity --strip-components=1; "
                            + "rm -f /tmp/antigravity.tar.gz; "
                            + "bin=$(find /opt/antigravity -maxdepth 2 -type f -name 'antigravity*' -perm -u+x | head -n 1); "
                            + "[ -n \"$bin\" ] || { echo 'The download did not contain a runnable Antigravity binary'; exit 1; }; "
                            + "ln -sf \"$bin\" /opt/antigravity/antigravity; "
                            + "chmod -R a+rX /opt/antigravity"),

            new App("vscode", "VS Code", "Microsoft's editor, ARM64 build.",
                    R.drawable.ic_terminal, "about 400 MB", 1500L * 1024 * 1024, "3\u201310 min", null,
                    "/usr/bin/code",
                    "apt-get update; apt-get install -y --no-install-recommends curl ca-certificates; "
                            + "curl --fail --location --retry 3 '" + VSCODE_LATEST + "' -o /tmp/code.deb; "
                            + "apt-get install -y --no-install-recommends /tmp/code.deb; rm -f /tmp/code.deb"),

            new App("devtools", "Developer tools", "Node.js, Python, pip and a compiler.",
                    R.drawable.ic_install, "about 500 MB", 1500L * 1024 * 1024, "3\u201310 min", null,
                    "/usr/bin/node",
                    "apt-get update; apt-get install -y --no-install-recommends "
                            + "nodejs npm python3 python3-pip python3-venv build-essential"),
    };

    static App byId(String id) {
        for (App app : CATALOG) {
            if (app.id.equals(id)) return app;
        }
        return null;
    }

    /** Electron apps cannot use their own sandbox under PRoot, so they are launched without it. */
    static String launcherScript(String executable) {
        String name = executable.substring(executable.lastIndexOf('/') + 1);
        return "#!/bin/bash\n"
                + "export HOME=/home/coder USER=coder LOGNAME=coder DISPLAY=:1 LANG=C.UTF-8\n"
                + "export LIBGL_ALWAYS_SOFTWARE=1 ELECTRON_DISABLE_SECURITY_WARNINGS=1\n"
                + "for candidate in \"" + executable + "\" \"/usr/bin/" + name + "\" "
                + "\"/opt/" + name + "/" + name + "\" \"$(command -v " + name + " 2>/dev/null)\"; do\n"
                + "  [ -n \"$candidate\" ] && [ -x \"$candidate\" ] || continue\n"
                + "  exec \"$candidate\" --no-sandbox --disable-gpu --disable-dev-shm-usage \"$@\"\n"
                + "done\n"
                + "lxterminal -e bash -lc 'echo \"" + name + " is not installed yet.\"; read -r -p \"Press Enter\"'\n";
    }
}
