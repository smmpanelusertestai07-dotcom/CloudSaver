package com.pocketdesk;

/**
 * The desktop apps PocketDesk can install into the container.
 *
 * Every entry installs the newest build each time it runs, so "install" and "update" are the same
 * action: apt repositories upgrade in place, and the direct downloads all resolve a "latest" URL
 * rather than a pinned version. Every app comes from its maker's own apt repository or download
 * endpoint, never from a third party.
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
        /** How to remove it, or null for parts that come with the computer and are not removable. */
        private final String uninstall;

        App(String id, String name, String summary, int iconRes, int logoRes, String approximateSize,
            long needsBytes, String typicalTime, String caution, String marker, String command,
            String uninstall) {
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
            this.uninstall = uninstall;
        }

        /** What the row should show: the real logo when there is one, the line-art otherwise. */
        int displayIcon() {
            return logoRes != 0 ? logoRes : iconRes;
        }

        boolean removable() { return uninstall != null; }

        String installCommand() {
            return "set -eu; export DEBIAN_FRONTEND=noninteractive; "
                    + "printf 'Acquire::Retries \"5\";\\n' > /etc/apt/apt.conf.d/99pocketdesk-retry; "
                    + command
                    + "; apt-get clean; rm -rf /var/lib/apt/lists/*";
        }

        String uninstallCommand() {
            return "set -eu; export DEBIAN_FRONTEND=noninteractive; "
                    + (uninstall == null ? "true" : uninstall)
                    + "; apt-get -y autoremove 2>/dev/null || true; apt-get clean";
        }
    }

    private LinuxApps() {}

    private static final String LATEST_CHATGPT =
            "https://persistent.oaistatic.com/codex-app-prod/linux/deb/latest/chatgpt_arm64.deb";
    private static final String CLAUDE_KEY = "https://downloads.claude.ai/claude-desktop/key.asc";
    private static final String CLAUDE_REPO = "https://downloads.claude.ai/claude-desktop/apt/stable";
    /** Published by Anthropic in the Claude Desktop Linux install guide. */
    private static final String CLAUDE_FINGERPRINT = "31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE";
    /** Google's own apt repository for Antigravity; it publishes arm64 builds. */
    private static final String ANTIGRAVITY_KEY = "https://us-central1-apt.pkg.dev/doc/repo-signing-key.gpg";
    private static final String ANTIGRAVITY_REPO =
            "https://us-central1-apt.pkg.dev/projects/antigravity-auto-updater-dev";
    /** Google's own apt repository for Chrome; it has published arm64 builds since July 2026. */
    private static final String CHROME_KEY = "https://dl.google.com/linux/linux_signing_key.pub";
    private static final String CHROME_REPO = "https://dl.google.com/linux/chrome/deb/";

    private static final long GB = 1024L * 1024L * 1024L;
    private static final long MB = 1024L * 1024L;

    /** A signing key fetched into apt's keyring directory, armoured or binary, whichever it is. */
    private static String fetchKey(String url, String path) {
        return "mkdir -p /etc/apt/keyrings; curl -fsSLo /tmp/pocketdesk.key '" + url + "'; "
                + "if grep -q 'BEGIN PGP' /tmp/pocketdesk.key; then gpg --dearmor --yes -o '" + path
                + "' < /tmp/pocketdesk.key; else cp /tmp/pocketdesk.key '" + path + "'; fi; "
                + "chmod 0644 '" + path + "'; rm -f /tmp/pocketdesk.key; ";
    }

    /**
     * Google Chrome from Google's own repository: the computer's browser. Its package adds an
     * amd64-only repository line of its own on install; that line is replaced with the arm64
     * one and told not to come back. Policies: a blank start page, no background mode, no
     * "make me default" prompt (there is nothing else here), no GPU (there is none), no
     * metrics. Passwords and sign-in stay, in Chrome's basic store (no keyring here).
     */
    static final String CHROME_INSTALL =
            "apt-get update; apt-get install -y --no-install-recommends curl gnupg ca-certificates; "
            + fetchKey(CHROME_KEY, "/etc/apt/keyrings/google-chrome.gpg")
            + "echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/google-chrome.gpg] " + CHROME_REPO
            + " stable main' > /etc/apt/sources.list.d/google-chrome.list; "
            + "apt-get update; apt-get install -y --no-install-recommends google-chrome-stable; "
            + "printf 'repo_add_once=\"false\"\nrepo_reenable_on_distupgrade=\"false\"\n' > /etc/default/google-chrome; "
            + "echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/google-chrome.gpg] " + CHROME_REPO
            + " stable main' > /etc/apt/sources.list.d/google-chrome.list; "
            + "mkdir -p /etc/opt/chrome/policies/managed; "
            + "printf '{\"HomepageLocation\": \"about:blank\", \"HomepageIsNewTabPage\": false, "
            + "\"NewTabPageLocation\": \"about:blank\", \"RestoreOnStartup\": 5, "
            + "\"BackgroundModeEnabled\": false, \"DefaultBrowserSettingEnabled\": false, "
            + "\"MetricsReportingEnabled\": false, \"HardwareAccelerationModeEnabled\": false, "
            + "\"PromotionalTabsEnabled\": false}\n' > /etc/opt/chrome/policies/managed/pocketdesk.json; "
            // The old built-in browser goes once Chrome is here: one browser, not two.
            + "apt-get remove -y epiphany-browser >/dev/null 2>&1 || true";

    /** The desktop and its tools: what set-up installs, and what the Settings row refreshes. */
    static final String DESKTOP_PACKAGES =
            "curl gnupg ca-certificates adwaita-icon-theme dmz-cursor-theme tzdata "
            + "xdg-utils x11-xserver-utils x11-utils dbus-x11 "
            + "dunst libnotify-bin zenity xdotool wmctrl desktop-file-utils librsvg2-common "
            + "lxterminal pcmanfm tint2 pulseaudio pulseaudio-utils "
            + "less file unzip zip wget";
    /** The developer tools an agentic development environment needs from the first minute. */
    static final String DEVELOPER_PACKAGES =
            "build-essential pkg-config python3 python3-pip python3-venv nodejs npm "
            + "git git-lfs openssh-client jq htop tree vim nano rsync";

    static final App[] CATALOG = {
            // New installs get all of this during setup. This row is how a container built by an
            // earlier version catches up without being rebuilt. Not removable: it is the computer.
            // Not on the Apps tab: set-up installs all of this. Settings -> Storage -> Update the
            // computer's basics runs it again, for a computer built by an earlier version.
            new App("basics", "Desktop, Google Chrome and developer tools",
                    "The desktop, sound, Google Chrome and the developer tools, brought up to date.",
                    R.drawable.ic_desktop, 0, "about 700 MB", 3 * GB,
                    "10–30 min", null,
                    "/usr/bin/gcc",
                    "apt-get update; apt-get install -y --no-install-recommends " + DESKTOP_PACKAGES + "; "
                            + "apt-get install -y --no-install-recommends " + DEVELOPER_PACKAGES + "; "
                            + "printf 'precedence ::ffff:0:0/96  100\\n' > /etc/gai.conf; "
                            + "ln -sf /usr/share/zoneinfo/Asia/Kolkata /etc/localtime; "
                            + "echo 'Asia/Kolkata' > /etc/timezone; "
                            + "( " + CHROME_INSTALL + " ) || true",
                    null),

            new App("chatgpt", "ChatGPT",
                    "AI assistant by OpenAI, with the Codex coding agent.",
                    R.drawable.ic_chat, R.drawable.logo_chatgpt,
                    "700 MB", 4 * GB, "10–25 min",
                    "OpenAI's Linux app is a public preview; that is OpenAI's current scope, and it "
                            + "grows with their updates. Your account's usage limits apply.",
                    "/usr/bin/chatgpt",
                    "apt-get update; "
                            + "if dpkg-query -W -f='${Status}' chatgpt 2>/dev/null | grep -q 'ok installed'; then "
                            + "apt-get install -y --only-upgrade chatgpt; else "
                            + "apt-get install -y --no-install-recommends curl ca-certificates; "
                            + "curl --fail --location --retry 3 '" + LATEST_CHATGPT + "' -o /tmp/chatgpt.deb; "
                            + "apt-get install -y /tmp/chatgpt.deb; rm -f /tmp/chatgpt.deb; fi",
                    "apt-get remove -y chatgpt"),

            new App("claude", "Claude Desktop",
                    "AI assistant by Anthropic, with the Claude Code coding agent.",
                    R.drawable.ic_terminal, R.drawable.logo_claude, "600 MB", 3 * GB,
                    "10–20 min",
                    "Anthropic's Linux app is in beta. Cowork's local virtual machine needs hardware "
                            + "virtualisation that a phone does not give apps, so that tab stays "
                            + "unavailable here. Chat and Claude Code work.",
                    "/usr/bin/claude-desktop",
                    "apt-get update; apt-get install -y --no-install-recommends curl gnupg ca-certificates; "
                            + "curl -fsSLo /usr/share/keyrings/claude-desktop-archive-keyring.asc '" + CLAUDE_KEY + "'; "
                            + "gpg --show-keys --with-colons /usr/share/keyrings/claude-desktop-archive-keyring.asc "
                            + "| grep -q '" + CLAUDE_FINGERPRINT + "' "
                            + "|| { echo 'Claude signing key did not match the published fingerprint'; exit 1; }; "
                            + "echo 'deb [arch=arm64 signed-by=/usr/share/keyrings/claude-desktop-archive-keyring.asc] "
                            + CLAUDE_REPO + " stable main' > /etc/apt/sources.list.d/claude-desktop.list; "
                            + "apt-get update; apt-get install -y --no-install-recommends claude-desktop",
                    "apt-get remove -y claude-desktop; rm -f /etc/apt/sources.list.d/claude-desktop.list"),

            new App("cursor", "Cursor",
                    "The AI code editor, by Anysphere.",
                    R.drawable.ic_terminal, R.drawable.logo_cursor, "700 MB", 2500 * MB,
                    "5–15 min",
                    "A large editor. Expect it to take a while to open the first time.",
                    "/usr/share/cursor/cursor",
                    "apt-get update; apt-get install -y --no-install-recommends curl ca-certificates; "
                            + "url=$(curl -fsSL 'https://api2.cursor.sh/updates/api/download/stable/linux-arm64/cursor' "
                            + "| grep -oE 'https://[^\"]*arm64[^\"]*\\.deb' | head -n 1); "
                            + "[ -n \"$url\" ] || { echo 'Could not find the Linux ARM64 build on cursor.com'; exit 1; }; "
                            + "curl --fail --location --retry 3 \"$url\" -o /tmp/cursor.deb; "
                            + "apt-get install -y /tmp/cursor.deb; rm -f /tmp/cursor.deb",
                    "apt-get remove -y cursor"),

            new App("antigravity", "Antigravity",
                    "Google's agentic development platform: AI agents plan, write, run and test software.",
                    R.drawable.ic_desktop, R.drawable.logo_antigravity, "230 MB", 3 * GB,
                    "5–20 min",
                    "Installed from Google's own apt repository, so a tap on this row updates it in place.",
                    "/usr/share/applications/antigravity.desktop",
                    "apt-get update; apt-get install -y --no-install-recommends curl gnupg ca-certificates "
                            + "libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 "
                            + "libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2t64 libgtk-3-0; "
                            + fetchKey(ANTIGRAVITY_KEY, "/etc/apt/keyrings/antigravity-repo-key.gpg")
                            + "echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/antigravity-repo-key.gpg] "
                            + ANTIGRAVITY_REPO + " antigravity-debian main' > /etc/apt/sources.list.d/antigravity.list; "
                            + "apt-get update; apt-get install -y --no-install-recommends antigravity; "
                            + "if [ ! -f /usr/share/applications/antigravity.desktop ]; then "
                            + "bin=$(command -v antigravity || find /usr/share/antigravity /opt/antigravity -maxdepth 2 -type f -name 'antigravity' 2>/dev/null | head -n 1); "
                            + "[ -n \"$bin\" ] || { echo 'Antigravity installed but no runnable binary was found'; exit 1; }; "
                            + "printf '[Desktop Entry]\\nName=Antigravity\\nComment=Google agentic development platform\\n"
                            + "Exec=%s %%U\\nIcon=antigravity\\nType=Application\\nTerminal=false\\n"
                            + "StartupNotify=true\\nCategories=Development;\\n' \"$bin\" "
                            + "> /usr/share/applications/antigravity.desktop; fi",
                    "apt-get remove -y antigravity; rm -f /etc/apt/sources.list.d/antigravity.list"),

    };

    /** The four AI apps, as opposed to the computer's own parts (browser, tools, basics). */
    static boolean isAiApp(App app) {
        return "chatgpt".equals(app.id) || "claude".equals(app.id)
                || "cursor".equals(app.id) || "antigravity".equals(app.id);
    }

    static App byId(String id) {
        for (App app : CATALOG) {
            if (app.id.equals(id)) return app;
        }
        return null;
    }

}
