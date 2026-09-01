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
        /** Line-art fallback, used when the app has no brand mark of its own. */
        final int iconRes;
        /** The app's real logo, taken from the vendor's own package. Zero when there is none. */
        final int logoRes;
        final String approximateSize;
        /** Free space the install genuinely needs, package plus unpacked files. */
        final long needsBytes;
        /** Honest wall-clock expectation shown while installing, e.g. "5-15 min". */
        final String typicalTime;
        /** Shown before install when the app has a real limitation on this setup. */
        final String caution;
        final String marker;        // file that exists once installed
        private final String command;

        App(String id, String name, String summary, int iconRes, int logoRes, String approximateSize,
            long needsBytes, String typicalTime, String caution, String marker, String command) {
            this.id = id;
            this.name = name;
            this.summary = summary;
            this.iconRes = iconRes;
            this.logoRes = logoRes;
            this.approximateSize = approximateSize;
            this.needsBytes = needsBytes;
            this.typicalTime = typicalTime;
            this.caution = caution;
            this.marker = marker;
            this.command = command;
        }

        /** What the row should show: the real logo when there is one, the line-art otherwise. */
        int displayIcon() {
            return logoRes != 0 ? logoRes : iconRes;
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
    private static final long MB = 1024L * 1024L;

    static final App[] CATALOG = {
            // New installs get all of this during setup. This row is how a container built by an
            // earlier version catches up without being rebuilt.
            new App("essentials", "Browser and desktop basics",
                    "Browser, real icons, arrow pointer, Indian time, window controls and messages.",
                    R.drawable.ic_network, R.drawable.logo_web, "about 150 MB", 700 * MB,
                    "2–6 min", null,
                    "/usr/bin/epiphany",
                    "apt-get update; apt-get install -y --no-install-recommends "
                            + "curl gnupg ca-certificates adwaita-icon-theme dmz-cursor-theme tzdata "
                            + "xdg-utils x11-xserver-utils x11-utils dbus-x11 "
                            // An app that fails to open has to be able to say so on screen, and
                            // xdotool is how the launcher knows a window really appeared.
                            + "dunst libnotify-bin zenity xdotool wmctrl "
                            // Named explicitly: adwaita only Recommends it, and without it every
                            // SVG icon in the theme renders as a generic diamond.
                            + "librsvg2-common "
                            + "epiphany-browser; "
                            // GNOME Web's start page renders live thumbnails, the slowest possible
                            // first thing to draw here -- that is what showed "Page Unresponsive".
                            + "mkdir -p /usr/share/glib-2.0/schemas; "
                            + "printf '[org.gnome.Epiphany]\nhomepage-url=\047about:blank\047\n' "
                            + "> /usr/share/glib-2.0/schemas/99_pocketdesk.gschema.override; "
                            + "glib-compile-schemas /usr/share/glib-2.0/schemas >/dev/null 2>&1 || true; "
                            + "ln -sf /usr/share/zoneinfo/Asia/Kolkata /etc/localtime; "
                            + "echo 'Asia/Kolkata' > /etc/timezone"),

            new App("chatgpt", "ChatGPT", "Official desktop app by OpenAI. Includes Codex.",
                    R.drawable.ic_chat, R.drawable.logo_chatgpt,
                    "700 MB download, 1.3 GB installed", 4 * GB, "10–25 min",
                    "Computer Use is not offered on Linux. Your account's usage limits still apply.",
                    "/usr/bin/chatgpt",
                    // The package registers OpenAI's own apt repository, so once it is on the
                    // system an upgrade is the smaller and faster path than a fresh download.
                    "apt-get update; "
                            + "if dpkg-query -W -f='${Status}' chatgpt 2>/dev/null | grep -q 'ok installed'; then "
                            + "apt-get install -y --only-upgrade chatgpt; else "
                            + "apt-get install -y --no-install-recommends curl ca-certificates; "
                            + "curl --fail --location --retry 3 '" + LATEST_CHATGPT + "' -o /tmp/chatgpt.deb; "
                            + "apt-get install -y /tmp/chatgpt.deb; rm -f /tmp/chatgpt.deb; fi"),

            new App("claude", "Claude Desktop", "Official desktop app by Anthropic. Includes Claude Code.",
                    R.drawable.ic_terminal, R.drawable.logo_claude, "about 600 MB", 3 * GB,
                    "10–20 min",
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

            new App("cursor", "Cursor", "Official AI code editor by Anysphere.",
                    R.drawable.ic_terminal, R.drawable.logo_cursor, "about 700 MB", 2500 * MB,
                    "5\u201315 min",
                    "A large editor. Expect it to take a while to open the first time.",
                    "/usr/share/cursor/cursor",
                    "apt-get update; apt-get install -y --no-install-recommends curl ca-certificates; "
                            // Cursor's own endpoint always answers with the current build.
                            + "url=$(curl -fsSL 'https://api2.cursor.sh/updates/api/download/stable/linux-arm64/cursor' "
                            + "| grep -oE 'https://[^\"]*arm64[^\"]*\\.deb' | head -n 1); "
                            + "[ -n \"$url\" ] || { echo 'Could not find the Linux ARM64 build on cursor.com'; exit 1; }; "
                            + "curl --fail --location --retry 3 \"$url\" -o /tmp/cursor.deb; "
                            + "apt-get install -y /tmp/cursor.deb; rm -f /tmp/cursor.deb"),

            new App("antigravity", "Antigravity", "Official agent-first IDE by Google.",
                    R.drawable.ic_desktop, R.drawable.logo_antigravity, "about 800 MB", 3 * GB,
                    "5–20 min",
                    "Google ships Antigravity as a tarball, so it updates when you run this again.",
                    "/usr/share/applications/antigravity.desktop",
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
                            + "chmod -R a+rX /opt/antigravity; "
                            // A tarball registers nothing, so the desktop would never list it.
                            + "icon=$(find /opt/antigravity/resources -maxdepth 4 -name '*.png' -path '*linux*' | head -n 1); "
                            + "[ -n \"$icon\" ] && install -D -m 0644 \"$icon\" /usr/share/pixmaps/antigravity.png; "
                            + "printf '[Desktop Entry]\\nName=Antigravity\\nComment=Google agent-first IDE\\n"
                            + "Exec=/opt/antigravity/antigravity\\nIcon=antigravity\\nType=Application\\n"
                            + "Terminal=false\\nStartupNotify=true\\nCategories=Development;\\n' "
                            + "> /usr/share/applications/antigravity.desktop"),
    };

    static App byId(String id) {
        for (App app : CATALOG) {
            if (app.id.equals(id)) return app;
        }
        return null;
    }

}
