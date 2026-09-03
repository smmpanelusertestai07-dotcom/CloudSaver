package com.pocketdesk;

/**
 * The desktop apps PocketDesk can install into the container.
 *
 * Every entry installs the newest build each time it runs, so "install" and "update" are the same
 * action: apt repositories upgrade in place, and the direct downloads all resolve a "latest" URL
 * rather than a pinned version. Every app comes from its publisher's own apt repository or
 * download endpoint, never from a third party.
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
        /**
         * True when the package comes from an apt repository, where apt refuses anything whose
         * signature does not match the publisher's key. False for the two that publish a plain
         * .deb download instead -- their protection is HTTPS to the publisher's own domain, and
         * the app must say exactly that rather than promise a signature check it does not do.
         */
        final boolean repoSigned;

        App(String id, String name, String summary, int iconRes, int logoRes, String approximateSize,
            long needsBytes, String typicalTime, String caution, String marker, String command,
            String uninstall, boolean repoSigned) {
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
            this.repoSigned = repoSigned;
        }

        /** What the row should show: the real logo when there is one, the line-art otherwise. */
        int displayIcon() {
            return logoRes != 0 ? logoRes : iconRes;
        }

        boolean removable() { return uninstall != null; }

        String installCommand() {
            // pd_repair first: an install interrupted by a stop leaves dpkg half-applied, and
            // every later apt command fails until it is finished.
            return "set -eu; " + APT_HELPERS + "pd_repair; "
                    + command
                    // The .deb files go; the package lists stay. Removing the lists here is
                    // what made the NEXT install fetch 40 MB of index before it could start.
                    + "; apt-get clean";
        }

        String uninstallCommand() {
            return "set -eu; " + APT_HELPERS + "pd_repair; "
                    + (uninstall == null ? "true" : uninstall)
                    + "; apt-get -y autoremove 2>/dev/null || true; apt-get clean";
        }
    }

    private LinuxApps() {}

    /**
     * The shell helpers every package step uses.
     *
     * pd_repair finishes an install that a stop, a low battery or Android ending the app left
     * half-applied -- dpkg refuses every later command until that is done, which is why a
     * second set-up used to fail immediately with "exited with code 1".
     * pd_update and pd_step retry a step that failed on a flaky mobile connection, and pd_step
     * records each finished step under /var/lib/pocketdesk/stage, so a set-up that was stopped
     * continues from where it stopped instead of starting over.
     */
    static final String APT_HELPERS =
            "export DEBIAN_FRONTEND=noninteractive; "
            // POCKETDESK_TEST_ROOT is empty on the phone, so these are the real paths; the test
            // suite sets it to a temporary folder and runs these very functions.
            + "PD_ROOT=\"${POCKETDESK_TEST_ROOT:-}\"; PD_STATE=\"$PD_ROOT/var/lib/pocketdesk\"; "
            + "mkdir -p \"$PD_STATE/stage\" \"$PD_ROOT/etc/apt/apt.conf.d\" \"$PD_ROOT/etc/dpkg/dpkg.cfg.d\"; "
            + "printf 'Acquire::Retries \"5\";\nAcquire::http::Timeout \"40\";\n"
            + "Acquire::https::Timeout \"40\";\nAcquire::Languages \"none\";\n"
            // A phone network that advertises IPv6 it cannot route made every fetch wait for the
            // v6 attempt to time out first; apt has its own switch for this, separate from
            // /etc/gai.conf. And carrier proxies mangle pipelined requests, which apt sees as a
            // hash mismatch and answers by downloading the same package again -- the exact way
            // mobile data was being spent twice. One request at a time is slightly slower per
            // package and very much cheaper overall.
            + "Acquire::ForceIPv4 \"true\";\nAcquire::http::Pipeline-Depth \"0\";\n"
            + "APT::Install-Suggests \"false\";\nquiet \"1\";\n"
            + "Dpkg::Options {\"--force-confdef\";\"--force-confold\";};\n' "
            + "> \"$PD_ROOT/etc/apt/apt.conf.d/99pocketdesk\"; "
            // Phone storage is slow, and dpkg's fsync after every file was most of the wait.
            // force-unsafe-io is what container images use for the same reason; an install cut
            // off mid-way is repaired by pd_repair rather than by the filesystem. Changelogs and
            // info files are still not unpacked, and every package's copyright file is kept,
            // because the licences must stay. Man pages and groff's macros ARE unpacked: they
            // cost about 3 MB across the whole computer, and man-db is installed.
            + "printf 'force-unsafe-io\npath-exclude=/usr/share/doc/*\n"
            + "path-include=/usr/share/doc/*/copyright\npath-exclude=/usr/share/info/*\n' "
            + "> \"$PD_ROOT/etc/dpkg/dpkg.cfg.d/99pocketdesk\"; "
            // man-db's postinst normally builds its index with mandb, and under PRoot's traced
            // syscalls that is minutes. man <page> works without an index; only apropos and
            // man -k need one, and "sudo mandb" builds it whenever the owner wants it.
            + "echo 'man-db man-db/auto-update boolean false' | debconf-set-selections 2>/dev/null || true; "
            + "pd_repair() { dpkg --configure -a >/dev/null 2>&1 || true; "
            + "apt-get -y -f install >/dev/null 2>&1 || true; }; "
            // Fetching the package lists costs about 40 MB of mobile data, and it used to run
            // again at the start of every single install. Lists that are only hours old describe
            // the same packages, so a fresh set is reused and the download is skipped entirely.
            // "pd_update force" always fetches, for the basics update that is meant to find new
            // versions. POCKETDESK_LIST_HOURS makes the window testable.
            + "pd_fresh() { pd_h=\"${POCKETDESK_LIST_HOURS:-12}\"; "
            + "[ -f \"$PD_ROOT/var/lib/apt/lists/lock\" ] || return 1; "
            + "pd_n=$(find \"$PD_ROOT/var/lib/apt/lists\" -maxdepth 1 -name '*_Packages*' "
            + "-newermt \"-$pd_h hours\" 2>/dev/null | wc -l); "
            + "[ \"${pd_n:-0}\" -gt 0 ]; }; "
            + "pd_update() { "
            + "if [ \"${1:-}\" != force ] && pd_fresh; then "
            + "echo 'PocketDesk: the package list is up to date; nothing to download'; return 0; fi; "
            + "pd_u=1; while [ $pd_u -le 3 ]; do "
            + "apt-get update 2>\"$PD_STATE/apt-update.err\" && return 0; "
            + "echo \"PocketDesk: package list attempt $pd_u did not finish\"; "
            // One unreachable vendor repository fails the whole update, and then every install
            // and the basics update after it -- for as long as the container exists. From the
            // second attempt, a source named in apt's own error is set aside so the computer
            // keeps working with the ones that do answer.
            + "if [ $pd_u -ge 2 ]; then for pd_l in /etc/apt/sources.list.d/*.list; do "
            + "[ -f \"$pd_l\" ] || continue; "
            + "pd_url=$(awk '{for (i=1; i<=NF; i++) if ($i ~ /^https?:/) { print $i; exit }}' \"$pd_l\"); "
            + "[ -n \"$pd_url\" ] || continue; "
            + "if grep -qF \"$pd_url\" \"$PD_STATE/apt-update.err\" 2>/dev/null; then "
            + "echo \"PocketDesk: $(basename \\\"$pd_l\\\") is not answering and was set aside\"; "
            + "mv \"$pd_l\" \"$pd_l.unreachable\"; fi; done; fi; "
            + "sleep \"${POCKETDESK_RETRY_SLEEP:-5}\"; pd_u=$((pd_u+1)); done; return 1; }; "
            // A repository is written, proved, and rolled back if it does not answer: an
            // unproven source must never be left behind to break every later install.
            + "pd_repo() { pd_f=\"/etc/apt/sources.list.d/$1\"; printf '%s\n' \"$2\" > \"$pd_f\"; "
            + "if pd_update; then return 0; fi; "
            + "echo \"PocketDesk: the $1 repository did not answer; removing it again\"; "
            + "rm -f \"$pd_f\"; pd_update || true; return 1; }; "
            + "pd_step() { pd_stage=$1; shift; "
            + "if [ -f \"$PD_STATE/stage/$pd_stage\" ]; then echo \"PocketDesk: $pd_stage is already done\"; return 0; fi; "
            + "pd_try=1; while [ $pd_try -le 3 ]; do "
            + "if apt-get install -y --no-install-recommends \"$@\"; then : > \"$PD_STATE/stage/$pd_stage\"; return 0; fi; "
            + "echo \"PocketDesk: $pd_stage attempt $pd_try did not finish, repairing and trying again\"; "
            + "pd_repair; pd_update || true; sleep \"${POCKETDESK_RETRY_SLEEP:-5}\"; "
            + "pd_try=$((pd_try+1)); done; return 1; }; ";

    /**
     * The container's time zone, taken from the phone at every start rather than fixed at
     * build time: a desktop whose clock is hours out dates every file and commit wrongly.
     */
    static final String PD_TIMEZONE =
            "pd_tz=\"${POCKETDESK_TZ:-}\"; "
            + "if [ -n \"$pd_tz\" ] && [ -f \"/usr/share/zoneinfo/$pd_tz\" ]; then "
            + "ln -sf \"/usr/share/zoneinfo/$pd_tz\" /etc/localtime; printf '%s\\n' \"$pd_tz\" > /etc/timezone; "
            + "elif [ ! -e /etc/localtime ]; then ln -sf /usr/share/zoneinfo/UTC /etc/localtime; fi; ";

    /** Where the container records which app version last brought its basics up to date. */
    static final String BASICS_VERSION_FILE = "var/lib/pocketdesk/basics-version";

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
     *
     * Protection is enforced, not left to a setting: Safe Browsing runs at its Enhanced level
     * (the same Google service that protects Chrome on the phone), dangerous downloads are
     * blocked, and a malware or phishing warning cannot be clicked through.
     */
    static final String CHROME_INSTALL =
            "pd_update || exit 11; apt-get install -y --no-install-recommends curl gnupg ca-certificates; "
            + fetchKey(CHROME_KEY, "/etc/apt/keyrings/google-chrome.gpg")
            + "pd_repo google-chrome.list 'deb [arch=arm64 signed-by=/etc/apt/keyrings/google-chrome.gpg] "
            + CHROME_REPO + " stable main' || exit 15; "
            + "apt-get install -y --no-install-recommends google-chrome-stable; "
            + "printf 'repo_add_once=\"false\"\nrepo_reenable_on_distupgrade=\"false\"\n' > /etc/default/google-chrome; "
            + "echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/google-chrome.gpg] " + CHROME_REPO
            + " stable main' > /etc/apt/sources.list.d/google-chrome.list; "
            + "mkdir -p /etc/opt/chrome/policies/managed; "
            + "printf '{\"HomepageLocation\": \"about:blank\", \"HomepageIsNewTabPage\": false, "
            + "\"NewTabPageLocation\": \"about:blank\", \"RestoreOnStartup\": 5, "
            + "\"BackgroundModeEnabled\": false, \"DefaultBrowserSettingEnabled\": false, "
            + "\"MetricsReportingEnabled\": false, \"HardwareAccelerationModeEnabled\": false, "
            + "\"PromotionalTabsEnabled\": false, \"SafeBrowsingProtectionLevel\": 2, "
            + "\"SafeBrowsingProceedAnywayDisabled\": true, \"SafeBrowsingExtendedReportingEnabled\": false, "
            + "\"DownloadRestrictions\": 1, \"AdvancedProtectionAllowed\": true}\n' "
            + "> /etc/opt/chrome/policies/managed/pocketdesk.json; "
            // The old built-in browser goes once Chrome is here: one browser, not two.
            + "apt-get remove -y epiphany-browser >/dev/null 2>&1 || true";

    /** The desktop and its tools: what set-up installs, and what the Settings row refreshes. */
    static final String DESKTOP_PACKAGES =
            "curl gnupg ca-certificates adwaita-icon-theme dmz-cursor-theme tzdata "
            + "gnome-themes-extra-data fonts-noto-color-emoji fonts-noto-core "
            + "locales bash-completion lsb-release "
            + "xdg-utils x11-xserver-utils x11-utils dbus-x11 "
            + "dunst libnotify-bin zenity xdotool wmctrl desktop-file-utils librsvg2-common "
            + "lxterminal pcmanfm libfm-modules tint2 pulseaudio pulseaudio-utils "
            + "less file unzip zip wget";
    /**
     * The everyday programs a computer is expected to have. Their step is allowed to fail: not
     * one of them is the desktop, and none of them runs in the background -- each costs memory
     * only while its own window is open. About 52 MB installed, all told.
     */
    static final String TOOL_PACKAGES =
            "mousepad xarchiver 7zip gpicview galculator lxtask lxappearance pavucontrol "
            + "scrot xclip xsel ripgrep man-db manpages tmux "
            // The words on the screen, for PocketDesk's own Appshot: an agent gets the text of a
            // window as well as its picture. About 35 MB with the English data, and it only runs
            // when an agent actually asks for a reading.
            + "tesseract-ocr tesseract-ocr-eng";
    /** The developer tools an agentic development environment needs from the first minute. */
    static final String DEVELOPER_PACKAGES =
            "build-essential pkg-config python3 python3-pip python3-venv python3-dev nodejs npm "
            + "git git-lfs openssh-client jq htop tree vim nano rsync sqlite3";

    static final App[] CATALOG = {
            // New installs get all of this during setup. This row is how a container built by an
            // earlier version catches up without being rebuilt. Not removable: it is the computer.
            // Not on the Apps tab: set-up installs all of this. Settings -> Storage -> Update the
            // computer's basics runs it again, for a computer built by an earlier version.
            new App("basics", "Computer basics",
                    "The desktop, sound, Google Chrome and the developer tools, plus Ubuntu's "
                            + "security updates.",
                    R.drawable.ic_desktop, 0, "about 550 MB", 3 * GB,
                    "15–45 min", null,
                    "/usr/bin/gcc",
                    // Run again from the top: the finished-step marks are cleared first, so every
                    // part is re-checked and anything the publisher has updated is fetched.
                    "rm -f \"$PD_STATE/stage/\"* 2>/dev/null || true; "
                            + "pd_update || exit 11; "
                            + "pd_step core " + DESKTOP_PACKAGES + " || exit 12; "
                            + "pd_step tools " + TOOL_PACKAGES + " || true; "
                            + "pd_step devtools " + DEVELOPER_PACKAGES + " || exit 14; "
                            // Ubuntu's own security updates for everything already installed.
                            + "apt-get -y upgrade || pd_repair; "
                            + "printf 'precedence ::ffff:0:0/96  100\\n' > /etc/gai.conf; "
                            + PD_TIMEZONE
                            + "( " + CHROME_INSTALL + " ) || true; "
                            + "[ -x /usr/bin/google-chrome-stable ] && : > \"$PD_STATE/stage/chrome\" || true; "
                            + "printf '%s' \"${POCKETDESK_APP_VERSION:-unknown}\" > \"$PD_STATE/basics-version\"",
                    null, true),

            new App("chatgpt", "ChatGPT",
                    "AI assistant by OpenAI, with the Codex coding agent.",
                    R.drawable.ic_chat, R.drawable.logo_chatgpt,
                    "700 MB", 4 * GB, "10–25 min",
                    "OpenAI's Linux app is a public preview; that is OpenAI's current scope, and it "
                            + "grows with their updates. Your account's usage limits apply.",
                    "/usr/bin/chatgpt",
                    "pd_update || exit 11; "
                            + "if dpkg-query -W -f='${Status}' chatgpt 2>/dev/null | grep -q 'ok installed'; then "
                            + "apt-get install -y --only-upgrade chatgpt; else "
                            + "apt-get install -y --no-install-recommends curl ca-certificates; "
                            + "curl --fail --location --retry 3 '" + LATEST_CHATGPT + "' -o /tmp/chatgpt.deb; "
                            + "apt-get install -y /tmp/chatgpt.deb; rm -f /tmp/chatgpt.deb; fi",
                    "apt-get remove -y chatgpt", false),

            new App("claude", "Claude Desktop",
                    "AI assistant by Anthropic, with the Claude Code coding agent.",
                    R.drawable.ic_terminal, R.drawable.logo_claude, "600 MB", 3 * GB,
                    "10–20 min",
                    "Anthropic's Linux app is in beta. Cowork's local virtual machine needs hardware "
                            + "virtualisation that a phone does not give apps, so that tab stays "
                            + "unavailable here. Chat and Claude Code work.",
                    "/usr/bin/claude-desktop",
                    // libglib2.0-bin satisfies claude-desktop's "kde-cli-tools | ... | gvfs" choice with
                    // one 200 KB package; without it apt takes the first name on that list and pulls in
                    // 149 KDE and Qt5 packages, about 154 MB, onto a phone.
                    "pd_update || exit 11; apt-get install -y --no-install-recommends curl gnupg ca-certificates libglib2.0-bin; "
                            + "curl -fsSLo /usr/share/keyrings/claude-desktop-archive-keyring.asc '" + CLAUDE_KEY + "'; "
                            + "gpg --show-keys --with-colons /usr/share/keyrings/claude-desktop-archive-keyring.asc "
                            + "| grep -q '" + CLAUDE_FINGERPRINT + "' "
                            + "|| { echo 'Claude signing key did not match the published fingerprint'; exit 1; }; "
                            + "pd_repo claude-desktop.list 'deb [arch=arm64 signed-by=/usr/share/keyrings/claude-desktop-archive-keyring.asc] "
                            + CLAUDE_REPO + " stable main' || exit 15; "
                            + "apt-get install -y --no-install-recommends claude-desktop",
                    "apt-get remove -y claude-desktop; rm -f /etc/apt/sources.list.d/claude-desktop.list", true),

            new App("cursor", "Cursor",
                    "The AI code editor, by Anysphere.",
                    R.drawable.ic_terminal, R.drawable.logo_cursor, "700 MB", 2500 * MB,
                    "5–15 min",
                    "A large editor. Expect it to take a while to open the first time.",
                    "/usr/share/cursor/cursor",
                    "pd_update || exit 11; apt-get install -y --no-install-recommends curl ca-certificates; "
                            + "url=$(curl -fsSL 'https://api2.cursor.sh/updates/api/download/stable/linux-arm64/cursor' "
                            + "| grep -oE 'https://[^\"]*arm64[^\"]*\\.deb' | head -n 1); "
                            + "[ -n \"$url\" ] || { echo 'Could not find the Linux ARM64 build on cursor.com'; exit 1; }; "
                            + "curl --fail --location --retry 3 \"$url\" -o /tmp/cursor.deb; "
                            + "apt-get install -y /tmp/cursor.deb; rm -f /tmp/cursor.deb",
                    "apt-get remove -y cursor", false),

            new App("antigravity", "Antigravity",
                    "Google's agentic development platform: AI agents plan, write, run and test software.",
                    R.drawable.ic_desktop, R.drawable.logo_antigravity, "230 MB", 3 * GB,
                    "5–20 min",
                    "Installed from Google's own apt repository, so a tap on this row updates it in place.",
                    "/usr/share/applications/antigravity.desktop",
                    "pd_update || exit 11; apt-get install -y --no-install-recommends curl gnupg ca-certificates "
                            + "libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 "
                            + "libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2t64 libgtk-3-0; "
                            + fetchKey(ANTIGRAVITY_KEY, "/etc/apt/keyrings/antigravity-repo-key.gpg")
                            + "pd_repo antigravity.list 'deb [arch=arm64 signed-by=/etc/apt/keyrings/antigravity-repo-key.gpg] "
                            + ANTIGRAVITY_REPO + " antigravity-debian main' || exit 15; "
                            + "apt-get install -y --no-install-recommends antigravity; "
                            + "if [ ! -f /usr/share/applications/antigravity.desktop ]; then "
                            + "bin=$(command -v antigravity || find /usr/share/antigravity /opt/antigravity -maxdepth 2 -type f -name 'antigravity' 2>/dev/null | head -n 1); "
                            + "[ -n \"$bin\" ] || { echo 'Antigravity installed but no runnable binary was found'; exit 1; }; "
                            + "printf '[Desktop Entry]\\nName=Antigravity\\nComment=Google agentic development platform\\n"
                            + "Exec=%s %%U\\nIcon=antigravity\\nType=Application\\nTerminal=false\\n"
                            + "StartupNotify=true\\nCategories=Development;\\n' \"$bin\" "
                            + "> /usr/share/applications/antigravity.desktop; fi",
                    "apt-get remove -y antigravity; rm -f /etc/apt/sources.list.d/antigravity.list", true),

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
