package com.pocketlinux;

/**
 * The desktop apps PocketLinux can install into the container.
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
            + "path-include=/usr/share/doc/*/copyright\npath-exclude=/usr/share/info/*\n"
            // The base image's own excludes file drops every man page; this line, read after it,
            // puts them back. Without it man-db and the manuals were fetched and thrown away.
            + "path-include=/usr/share/man/*\n' "
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
            // The age that matters is when THIS phone last fetched, which is only knowable from
            // a stamp written here: apt keeps the mirror's own Last-Modified on the index files,
            // so their date is the archive's publish date and says nothing about this phone.
            + "pd_fresh() { pd_h=\"${POCKETDESK_LIST_HOURS:-12}\"; "
            + "pd_at=$(cat \"$PD_STATE/apt-updated-at\" 2>/dev/null || true); "
            + "case \"$pd_at\" in '' | *[!0-9]*) return 1;; esac; "
            + "pd_age=$(( $(date +%s) - pd_at )); "
            + "[ \"$pd_age\" -ge 0 ] && [ \"$pd_age\" -lt $(( pd_h * 3600 )) ]; }; "
            + "pd_update() { "
            + "if [ \"${1:-}\" != force ] && pd_fresh; then "
            + "echo 'PocketLinux: the package list is up to date; nothing to download'; return 0; fi; "
            + "pd_u=1; while [ $pd_u -le 3 ]; do "
            + "if apt-get update 2>\"$PD_STATE/apt-update.err\"; then "
            + "date +%s > \"$PD_STATE/apt-updated-at\"; return 0; fi; "
            + "echo \"PocketLinux: package list attempt $pd_u did not finish\"; "
            // One unreachable vendor repository fails the whole update, and then every install
            // and the basics update after it -- for as long as the container exists. From the
            // second attempt, a source named in apt's own error is set aside so the computer
            // keeps working with the ones that do answer.
            + "if [ $pd_u -ge 2 ]; then for pd_l in \"$PD_ROOT\"/etc/apt/sources.list.d/*.list; do "
            + "[ -f \"$pd_l\" ] || continue; "
            + "pd_url=$(awk '{for (i=1; i<=NF; i++) if ($i ~ /^https?:/) { print $i; exit }}' \"$pd_l\"); "
            + "[ -n \"$pd_url\" ] || continue; "
            + "if grep -qF \"$pd_url\" \"$PD_STATE/apt-update.err\" 2>/dev/null; then "
            + "echo \"PocketLinux: $(basename \\\"$pd_l\\\") is not answering and was set aside\"; "
            + "mv \"$pd_l\" \"$pd_l.unreachable\"; fi; done; fi; "
            + "sleep \"${POCKETDESK_RETRY_SLEEP:-5}\"; pd_u=$((pd_u+1)); done; return 1; }; "
            // A repository is written, proved, and rolled back if it does not answer: an
            // unproven source must never be left behind to break every later install.
            + "pd_repo() { pd_f=\"$PD_ROOT/etc/apt/sources.list.d/$1\"; "
            + "mkdir -p \"$PD_ROOT/etc/apt/sources.list.d\"; printf '%s\n' \"$2\" > \"$pd_f\"; "
            // A source written a second ago is in no index that has been fetched, so its
            // packages stay invisible to apt until the lists are fetched again. Past the
            // freshness check, always: this is the one call that must not be cached.
            + "if pd_update force; then return 0; fi; "
            + "echo \"PocketLinux: the $1 repository did not answer; removing it again\"; "
            + "rm -f \"$pd_f\"; pd_update force || true; return 1; }; "
            // An app downloaded straight from its publisher, resumably.
            //
            // "curl -o /tmp/x.deb --retry 3" starts again from zero every time, and these files
            // are 200-700 MB on a phone that is usually on mobile data: a download that stopped
            // at 600 MB cost 600 MB and bought nothing. The part-file lives outside /tmp so it
            // survives a failed install and the next attempt continues from where it stopped.
            // A server that will not do ranges falls back to the whole file, which is only ever
            // what the old code did anyway.
            + "pd_fetch() { pd_dir=\"$PD_STATE/downloads\"; mkdir -p \"$pd_dir\"; "
            + "pd_out=\"$pd_dir/$2\"; "
            + "if curl --fail --location --retry 3 --retry-delay 5 -C - -o \"$pd_out\" \"$1\" "
            + "|| curl --fail --location --retry 3 --retry-delay 5 -o \"$pd_out\" \"$1\"; then "
            + "[ -s \"$pd_out\" ] && { printf '%s' \"$pd_out\"; return 0; }; fi; "
            + "echo 'PocketLinux: the download did not finish' >&2; return 1; }; "
            + "pd_step() { pd_stage=$1; shift; "
            + "if [ -f \"$PD_STATE/stage/$pd_stage\" ]; then echo \"PocketLinux: $pd_stage is already done\"; return 0; fi; "
            + "pd_try=1; while [ $pd_try -le 3 ]; do "
            + "if apt-get install -y --no-install-recommends \"$@\"; then : > \"$PD_STATE/stage/$pd_stage\"; return 0; fi; "
            + "echo \"PocketLinux: $pd_stage attempt $pd_try did not finish, repairing and trying again\"; "
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
     * Safe Browsing runs at its Enhanced level (the same Google service that protects Chrome on
     * the phone). Do not force the enterprise DownloadRestrictions policy: Chrome classifies
     * normal Linux package files such as .deb as dangerous file types and then reports the
     * owner's deliberate download as "Blocked by your organization". Safe Browsing still warns;
     * the owner keeps the final choice for a download they explicitly requested.
     */
    static final String CHROME_POLICY =
            "{\"HomepageLocation\": \"about:blank\", \"HomepageIsNewTabPage\": false, "
            + "\"NewTabPageLocation\": \"about:blank\", \"RestoreOnStartup\": 5, "
            + "\"BackgroundModeEnabled\": false, \"DefaultBrowserSettingEnabled\": false, "
            + "\"MetricsReportingEnabled\": false, \"HardwareAccelerationModeEnabled\": false, "
            + "\"PromotionalTabsEnabled\": false, \"SafeBrowsingProtectionLevel\": 2, "
            + "\"SafeBrowsingExtendedReportingEnabled\": false, \"AdvancedProtectionAllowed\": true}";

    static final String CHROME_INSTALL =
            "pd_update || exit 11; apt-get install -y --no-install-recommends curl gnupg ca-certificates; "
            + fetchKey(CHROME_KEY, "/etc/apt/keyrings/google-chrome.gpg")
            + "pd_repo google-chrome.list 'deb [arch=arm64 signed-by=/etc/apt/keyrings/google-chrome.gpg] "
            + CHROME_REPO + " stable main' || exit 15; "
            // The same three attempts every other step gets: 133 MB over mobile data had
            // exactly one, and apt resumes the partial file, so a retry is not a second 133 MB.
            + "pd_step chrome google-chrome-stable "
            + "|| echo \"PocketLinux: Google Chrome did not finish downloading this time\"; "
            + "printf 'repo_add_once=\"false\"\nrepo_reenable_on_distupgrade=\"false\"\n' > /etc/default/google-chrome; "
            + "echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/google-chrome.gpg] " + CHROME_REPO
            + " stable main' > /etc/apt/sources.list.d/google-chrome.list; "
            + "mkdir -p /etc/opt/chrome/policies/managed; "
            + "printf '%s\\n' '" + CHROME_POLICY + "' "
            + "> /etc/opt/chrome/policies/managed/pocketdesk.json; "
            // The old built-in browser goes once Chrome is here: one browser, not two.
            + "apt-get remove -y epiphany-browser >/dev/null 2>&1 || true";

    /** The desktop and its tools: what set-up installs, and what the Settings row refreshes. */
    static final String DESKTOP_PACKAGES =
            "curl gnupg ca-certificates adwaita-icon-theme dmz-cursor-theme tzdata "
            + "gnome-themes-extra-data fonts-noto-color-emoji fonts-noto-core "
            + "locales bash-completion lsb-release "
            + "xdg-utils x11-xserver-utils x11-utils dbus-x11 dbus-system-bus-common "
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
            + "scrot xclip xsel ripgrep man-db manpages tmux inotify-tools "
            // Electron's safeStorage keeps an app's sign-in token encrypted -- but only where
            // libsecret finds a keyring. With none, every Electron app on Linux falls back to
            // writing the token in plain text. This is what makes the four AI apps store their
            // sign-ins the way they do on a Mac.
            + "gnome-keyring libsecret-1-0 libsecret-tools "
            // The words on the screen, for PocketLinux's own Appshot: an agent gets the text of a
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
                            // This row exists to find what the publishers have changed, so it is
                            // the one place that must never reuse a list it fetched earlier.
                            + "pd_update force || exit 11; "
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

            // Mobile app development: the tools that really do work on an ARM64 phone, and none
            // that only pretend to. Every package here is in Ubuntu's own archive for arm64 --
            // no Google SDK download, because Google publishes no ARM64 Linux build-tools and a
            // half-installed SDK is worse than none.
            new App("mobiledev", "Mobile app development",
                    "Java 21, Gradle, adb, fastboot, aapt2 and scrcpy — and the pairing helper that "
                            + "lets this computer install and test an app on THIS phone, or on "
                            + "another one over Wi-Fi.",
                    R.drawable.ic_terminal, 0, "about 700 MB", 3 * GB,
                    "10–25 min", null,
                    "/usr/bin/adb",
                    "pd_update || exit 11; "
                            // aapt2 is the one piece Google publishes for Intel Linux and not for
                            // ARM64, and Android's build plugin fetches it from Maven -- so a
                            // perfectly good build failed on a processor the tool was never
                            // shipped for. Ubuntu builds its own from the same source; installing
                            // that and pointing Gradle at it is what lets an Android build finish
                            // on this phone at all.
                            + "apt-get install -y --no-install-recommends aapt2 >/dev/null 2>&1 || true; "
                            + "pd_step mobiledev openjdk-21-jdk-headless gradle adb fastboot aapt "
                            + "scrcpy android-sdk-libsparse-utils || exit 20; "
                            // Where Gradle and every Java tool look for a JDK. Written once, so
                            // an owner who changes it keeps their change.
                            + "if ! grep -q 'JAVA_HOME' /etc/profile.d/pocketdesk-java.sh 2>/dev/null; then "
                            + "mkdir -p /etc/profile.d; "
                            + "printf 'export JAVA_HOME=$(dirname $(dirname $(readlink -f "
                            + "$(command -v javac || command -v java))))\nexport PATH=\"$JAVA_HOME/bin:$PATH\"\n' "
                            + "> /etc/profile.d/pocketdesk-java.sh; fi; "
                            // Gradle on a 4 GB phone: the daemon is what runs it out of memory,
                            // and 1 GB is what is actually free once Android and the desktop have
                            // taken theirs.
                            + "mkdir -p /home/coder/.gradle; "
                            + "if [ ! -f /home/coder/.gradle/gradle.properties ]; then "
                            + "printf 'org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m\n"
                            + "org.gradle.daemon=false\norg.gradle.parallel=false\n"
                            + "org.gradle.caching=true\n' > /home/coder/.gradle/gradle.properties; fi; "
                            + "pd_aapt2=$(command -v aapt2 2>/dev/null); "
                            + "if [ -n \"$pd_aapt2\" ] && ! grep -q aapt2FromMavenOverride "
                            + "/home/coder/.gradle/gradle.properties 2>/dev/null; then "
                            + "printf 'android.aapt2FromMavenOverride=%s\n' \"$pd_aapt2\" "
                            + ">> /home/coder/.gradle/gradle.properties; fi; "
                            + "chown -R coder:coder /home/coder/.gradle 2>/dev/null || true; "
                            + "java -version 2>&1 | head -n 1; adb version 2>&1 | head -n 1; "
                            + "printf 'aapt2: %s\n' \"${pd_aapt2:-not installed}\"",
                    "apt-get remove -y --purge openjdk-21-jdk-headless gradle adb fastboot aapt "
                            + "scrcpy >/dev/null 2>&1 || true; "
                            + "rm -f /etc/profile.d/pocketdesk-java.sh \"$PD_STATE/stage/mobiledev\"; "
                            + "apt-get -y autoremove --purge >/dev/null 2>&1 || true",
                    false),

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
                            + "pd_deb=$(pd_fetch '" + LATEST_CHATGPT + "' chatgpt.deb) || exit 12; "
                            + "apt-get install -y \"$pd_deb\"; rm -f \"$pd_deb\"; fi",
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
                            + "pd_deb=$(pd_fetch \"$url\" cursor.deb) || exit 12; "
                            + "apt-get install -y \"$pd_deb\"; rm -f \"$pd_deb\"",
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
