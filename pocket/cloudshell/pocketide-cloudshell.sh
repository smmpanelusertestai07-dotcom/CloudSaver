#!/usr/bin/env bash
# PocketIDE for Google Cloud Shell. Sets up, only in your Cloud Shell home folder (the one part
# Cloud Shell keeps, 5 GB), a VS Code (code-server) for each official agent, each with its own
# port, settings, extensions and projects folder:
#   Claude Code  port 8080  ~/projects/claude-code
#   Codex        port 8081  ~/projects/codex
#   Antigravity  port 8082  ~/projects/antigravity
# From then on Cloud Shell starts them by itself when it starts, tidies old caches, logs and
# 30-day-old Codex chats (never your projects), and checks the agents for updates once a day.
# Run it again at any time: it only adds what is missing.
#
# It does nothing to keep Cloud Shell awake. Cloud Shell is for interactive use: it stops about
# 40 minutes after you stop using it, a session lasts at most 12 hours, and the free quota is
# 50 hours a week. Staying within Google's rules keeps your Google account safe.
set -euo pipefail

CODE_SERVER_VERSION=4.139.1
CODE_SERVER_SHA256=53029be6c5781b7bca49b815fcc9a2a3fc111813ad8c9965b2c0f0d2985a0674
CODE_SERVER_URL="https://github.com/coder/code-server/releases/download/v$CODE_SERVER_VERSION/code-server-$CODE_SERVER_VERSION-linux-amd64.tar.gz"

BASE="$HOME/.pocketide"
CS_DIR="$BASE/code-server"
BIN="$HOME/.local/bin"
NEEDED_KB=2200000

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() {
    printf '\n\033[31mPocketIDE: %s\033[0m\n' "$*" >&2
    exit 1
}

[ "$(uname -m)" = x86_64 ] || fail "This is for Google Cloud Shell (an x86_64 Linux computer)."
mkdir -p "$BASE" "$BIN" "$HOME/projects"

# 1. VS Code for the web (code-server), used only when its SHA-256 matches the pinned one.
if [ ! -x "$CS_DIR/$CODE_SERVER_VERSION/bin/code-server" ]; then
    free_kb=$(df -Pk "$HOME" | awk 'NR == 2 { print $4 }')
    [ "$free_kb" -ge "$NEEDED_KB" ] || fail "It needs about 2 GB free in your home folder (it uses about 1.6 GB); $((free_kb / 1024)) MB is free. Delete old files first."
    say "Downloading VS Code (code-server $CODE_SERVER_VERSION)..."
    tmp=$(mktemp -d)
    curl -fL --retry 3 -o "$tmp/code-server.tar.gz" "$CODE_SERVER_URL"
    echo "$CODE_SERVER_SHA256  $tmp/code-server.tar.gz" | sha256sum -c --quiet - || fail "code-server's download did not match its checksum."
    mkdir -p "$CS_DIR/$CODE_SERVER_VERSION"
    tar -xzf "$tmp/code-server.tar.gz" -C "$CS_DIR/$CODE_SERVER_VERSION" --strip-components=1
    rm -rf "$tmp"
fi
ln -sfn "$CS_DIR/$CODE_SERVER_VERSION" "$CS_DIR/current"

# 2. `pocketide`: starts each agent's VS Code that is not running, installs or updates the agents
# (`pocketide update`), and, when Cloud Shell starts (`pocketide boot`), tidies and updates too.
cat >"$BIN/pocketide" <<'LAUNCHER'
#!/usr/bin/env bash
# PocketIDE's launcher in Google Cloud Shell; see ~/pocketide-cloudshell.sh.
set -uo pipefail
AGENTS="claude-code:8080:anthropic/claude-code codex:8081:openai/chatgpt antigravity:8082:google/google-antigravity"
BASE="$HOME/.pocketide"
CODE="$BASE/code-server/current/bin/code-server"

settings() { # the settings every agent's VS Code starts with: phone screen, no telemetry
    cat <<'JSON'
{
  "workbench.startupEditor": "none",
  "workbench.tips.enabled": false,
  "workbench.reduceMotion": "on",
  "window.autoDetectColorScheme": true,
  "breadcrumbs.enabled": false,
  "editor.minimap.enabled": false,
  "editor.wordWrap": "on",
  "editor.fontSize": 14,
  "terminal.integrated.fontSize": 14,
  "terminal.integrated.gpuAcceleration": "off",
  "files.autoSave": "afterDelay",
  "extensions.ignoreRecommendations": true,
  "update.mode": "none",
  "telemetry.telemetryLevel": "off",
  "chat.disableAIFeatures": true,
  "task.allowAutomaticTasks": "off",
  "claudeCode.preferredLocation": "sidebar",
  "claudeCode.useCtrlEnterToSend": true,
  "chatgpt.openOnStartup": false,
  "chatgpt.composerEnterBehavior": "cmdAlways"
}
JSON
}

pick() { # namespace name target -> "version download-url sha256-url" of the newest release
    python3 - "$1" "$2" "$3" <<'PY'
import json, sys, urllib.request
ns, name, target = sys.argv[1:4]
for offset in range(0, 800, 50):
    url = ("https://open-vsx.org/api/-/query?namespaceName=%s&extensionName=%s&targetPlatform=%s"
           "&includeAllVersions=true&size=50&offset=%d" % (ns, name, target, offset))
    page = json.load(urllib.request.urlopen(url, timeout=60)).get("extensions", [])
    for v in page:
        files = v.get("files", {})
        if (not v.get("preRelease") and v.get("verified") and v.get("downloadable", True)
                and v.get("targetPlatform") == target and "download" in files and "sha256" in files):
            print(v["version"], files["download"], files["sha256"])
            sys.exit(0)
    if len(page) < 50:
        break
sys.exit(1)
PY
}

# The newest release (never a pre-release) of each agent from its verified publisher on Open VSX,
# for this computer or every platform, checked against the SHA-256 Open VSX publishes, each into
# its own VS Code.
update() {
    missing=""
    for entry in $AGENTS; do
        key=${entry%%:*} rest=${entry#*:}
        agent=${rest#*:} ns=${agent%/*} name=${agent#*/}
        data="$BASE/vscode/$key"
        mkdir -p "$data/Machine" "$data/extensions" "$HOME/projects/$key"
        settings >"$data/Machine/settings.json"
        choice=$(pick "$ns" "$name" linux-x64 || pick "$ns" "$name" universal) || { echo "Open VSX has no release of $ns.$name now." >&2; continue; }
        read -r version download sha <<<"$choice"
        if ls -d "$data/extensions/$ns.$name-$version"* >/dev/null 2>&1; then
            echo "$ns.$name $version is installed."
            continue
        fi
        if [ "$(df -Pk "$HOME" | awk 'NR == 2 { print $4 }')" -lt 1000000 ]; then
            echo "Less than 1 GB is free in your home folder: $ns.$name was not installed." >&2
            missing="$missing $name"
            continue
        fi
        echo "Installing $ns.$name $version..."
        vsix="$BASE/$ns.$name-$version.vsix"
        if ! curl -fsSL --retry 3 -o "$vsix" "$download" ||
            ! echo "$(curl -fsSL "$sha" | awk '{ print $1 }')  $vsix" | sha256sum -c --quiet -; then
            echo "$ns.$name's download did not match the checksum Open VSX publishes; it was not installed." >&2
            missing="$missing $name"
        elif ! "$CODE" --user-data-dir "$data" --extensions-dir "$data/extensions" --install-extension "$vsix" --force; then
            missing="$missing $name"
        fi
        rm -f "$vsix"
    done
    # Codex's command line comes with its extension: `codex login --device-auth` signs in here.
    codex=$(find "$BASE/vscode/codex/extensions" -path '*openai.chatgpt*' -type f -name codex -perm -u+x 2>/dev/null | head -n 1)
    [ -n "$codex" ] && ln -sfn "$codex" "$HOME/.local/bin/codex"
    if [ -n "$missing" ]; then
        echo "Not installed:$missing. Run: pocketide update" >&2
        return 1
    fi
    date +%s >"$BASE/updated"
}

start() { # each agent's VS Code that is not running, on its own port
    for entry in $AGENTS; do
        key=${entry%%:*} rest=${entry#*:} port=${rest%%:*}
        data="$BASE/vscode/$key"
        [ -d "$data" ] || continue
        pgrep -u "$(id -u)" -f "code-server.*127.0.0.1:$port" >/dev/null && continue
        nohup "$CODE" --bind-addr "127.0.0.1:$port" --auth none --disable-telemetry --disable-update-check \
            --disable-workspace-trust --disable-getting-started-override \
            --user-data-dir "$data" --extensions-dir "$data/extensions" "$HOME/projects/$key" \
            >"$data/code-server.log" 2>&1 &
    done
}

tidy() { # old caches, logs and 30-day-old Codex chats; never projects, never what is in use
    find "$HOME/.cache" -type f -atime +14 -delete 2>/dev/null
    find "$HOME/.npm/_cacache" -type f -mtime +30 -delete 2>/dev/null
    find "$HOME/.codex/sessions" -type f -name '*.jsonl' -mtime +30 -delete 2>/dev/null
    find "$BASE/vscode" -path '*/logs/*' -type f -mtime +7 -delete 2>/dev/null
    find "$HOME/.local/share/Trash" -mindepth 1 -mtime +30 -delete 2>/dev/null
    return 0
}

case "${1:-}" in
update) update && start ;;
boot)
    tidy
    start
    last=$(cat "$BASE/updated" 2>/dev/null || echo 0)
    [ $(($(date +%s) - last)) -lt 86400 ] || update
    ;;
--quiet) start ;;
*)
    start
    echo "PocketIDE: Claude Code on port 8080, Codex on 8081, Antigravity on 8082 (Web Preview)."
    ;;
esac
LAUNCHER
chmod +x "$BIN/pocketide"

# 3. The agents, each in its own VS Code.
say "Installing the three agents, each in its own VS Code..."
installed=yes
"$BIN/pocketide" update || installed=no

# 4. Cloud Shell runs ~/.customize_environment as root each time it starts: PocketIDE's part
# starts the agents' VS Code as you, before you open anything. A terminal starts them too.
if ! grep -q 'PocketIDE' "$HOME/.customize_environment" 2>/dev/null; then
    [ -f "$HOME/.customize_environment" ] || printf '#!/bin/sh\n' >"$HOME/.customize_environment"
    cat >>"$HOME/.customize_environment" <<CUSTOM

# PocketIDE: each agent's VS Code, started as $(id -un) when Cloud Shell starts.
sudo -u $(id -un) -H bash -c '$BIN/pocketide boot' >/tmp/pocketide-boot.log 2>&1 &
CUSTOM
    chmod +x "$HOME/.customize_environment"
fi
grep -q 'PocketIDE: agents' "$HOME/.bashrc" 2>/dev/null || cat >>"$HOME/.bashrc" <<'RC'

# PocketIDE: agents' VS Code (ports 8080-8082), started if Cloud Shell has not yet.
case $- in *i*) [ -x "$HOME/.local/bin/pocketide" ] && "$HOME/.local/bin/pocketide" --quiet ;; esac
RC

if [ "$installed" = no ]; then
    fail "Not every agent installed (see above). Free some space if asked, then run: pocketide update"
fi
say "Done. Go back to PocketIDE and tap Setup is done."
cat <<'NEXT'
In PocketIDE, tap an agent: its own VS Code opens. Sign in to each agent once:
  Claude Code  - its panel's Sign in: open the link, sign in, paste the code back.
  Codex        - first turn on device code sign-in in ChatGPT (Settings > Security), then run
                 `codex login --device-auth` here and enter the code it shows.
  Antigravity  - its panel's Sign in, or run `agy` here: open the link, paste the code back.
Your files:    projects in ~/projects/claude-code, ~/projects/codex and ~/projects/antigravity;
               chats and sign-ins in ~/.claude, ~/.codex and ~/.gemini.
NEXT
