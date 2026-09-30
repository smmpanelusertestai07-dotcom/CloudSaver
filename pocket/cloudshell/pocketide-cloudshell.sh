#!/usr/bin/env bash
# PocketIDE for Google Cloud Shell: VS Code (code-server) and the three official agents
# (Claude Code, Codex, Antigravity), installed only in your Cloud Shell home folder, the one
# part of Cloud Shell that is kept (5 GB). Run it again at any time to update.
#
# It does nothing to keep Cloud Shell awake. Cloud Shell is for interactive use: it stops about
# 40 minutes after you stop using it, a session lasts at most 12 hours, and the free quota is
# 50 hours a week. Staying within Google's rules keeps your Google account safe.
set -euo pipefail

CODE_SERVER_VERSION=4.139.1
CODE_SERVER_SHA256=53029be6c5781b7bca49b815fcc9a2a3fc111813ad8c9965b2c0f0d2985a0674
CODE_SERVER_URL="https://github.com/coder/code-server/releases/download/v$CODE_SERVER_VERSION/code-server-$CODE_SERVER_VERSION-linux-amd64.tar.gz"
AGENTS="anthropic/claude-code openai/chatgpt google/google-antigravity"
PORT=8080 # Cloud Shell's Web Preview port

BASE="$HOME/.pocketide"
CS_DIR="$BASE/code-server"
DATA="$HOME/.local/share/code-server"
EXT="$DATA/extensions"
BIN="$HOME/.local/bin"
NEEDED_KB=2200000

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() {
    printf '\n\033[31mPocketIDE: %s\033[0m\n' "$*" >&2
    exit 1
}

[ "$(uname -m)" = x86_64 ] || fail "This is for Google Cloud Shell (an x86_64 Linux computer)."
free_kb=$(df -Pk "$HOME" | awk 'NR == 2 { print $4 }')
[ "$free_kb" -ge "$NEEDED_KB" ] || fail "It needs about 2 GB free in your home folder (it uses about 1.6 GB); $((free_kb / 1024)) MB is free. Delete old files first."
mkdir -p "$BASE" "$BIN" "$EXT" "$DATA/Machine" "$HOME/projects"

# 1. VS Code for the web (code-server), used only when its SHA-256 matches the pinned one.
if [ ! -x "$CS_DIR/$CODE_SERVER_VERSION/bin/code-server" ]; then
    say "Downloading VS Code (code-server $CODE_SERVER_VERSION)..."
    tmp=$(mktemp -d)
    curl -fL --retry 3 -o "$tmp/code-server.tar.gz" "$CODE_SERVER_URL"
    echo "$CODE_SERVER_SHA256  $tmp/code-server.tar.gz" | sha256sum -c --quiet - || fail "code-server's download did not match its checksum."
    mkdir -p "$CS_DIR/$CODE_SERVER_VERSION"
    tar -xzf "$tmp/code-server.tar.gz" -C "$CS_DIR/$CODE_SERVER_VERSION" --strip-components=1
    rm -rf "$tmp"
fi
ln -sfn "$CS_DIR/$CODE_SERVER_VERSION" "$CS_DIR/current"
CODE="$CS_DIR/current/bin/code-server"

# 2. Settings for a phone screen: no telemetry, no VS Code AI chat next to the agents, no
# updates behind PocketIDE's back, Enter for a new line in the agents (their Send button sends).
cat > "$DATA/Machine/settings.json" <<'JSON'
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

# 3. The three official agents from Open VSX: the newest release (never a pre-release) from a
# verified publisher, built for this computer or for every platform, checked against the
# SHA-256 Open VSX publishes. code-server's own installer puts each one in place.
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
installed() { ls -d "$EXT/$1-$2"* >/dev/null 2>&1; }
for agent in $AGENTS; do
    ns=${agent%/*}
    name=${agent#*/}
    choice=$(pick "$ns" "$name" linux-x64 || pick "$ns" "$name" universal) || fail "Open VSX has no release of $ns.$name for this computer."
    read -r version download sha <<<"$choice"
    if installed "$ns.$name" "$version"; then
        echo "$ns.$name $version is installed."
        continue
    fi
    say "Installing $ns.$name $version..."
    vsix="$BASE/$ns.$name-$version.vsix"
    curl -fL --retry 3 -o "$vsix" "$download"
    expected=$(curl -fsSL "$sha" | awk '{ print $1 }')
    echo "$expected  $vsix" | sha256sum -c --quiet - || fail "$ns.$name's download did not match the checksum Open VSX publishes."
    EXTENSIONS_GALLERY='{}' "$CODE" --user-data-dir "$DATA" --extensions-dir "$EXT" --install-extension "$vsix" --force
    rm -f "$vsix"
done

# Codex's own command line comes with its extension: `codex login --device-auth` signs in here.
codex_bin=$(find "$EXT" -path '*openai.chatgpt*' -type f -name codex -perm -u+x 2>/dev/null | head -n 1 || true)
[ -n "$codex_bin" ] && ln -sfn "$codex_bin" "$BIN/codex"

# 4. `pocketide` starts VS Code for Web Preview when it is not running; Cloud Shell runs it each
# time a terminal opens, so VS Code is ready when you are. It keeps nothing else running.
cat > "$BIN/pocketide" <<LAUNCHER
#!/usr/bin/env bash
if ! pgrep -u "\$(id -u)" -f "code-server.*127.0.0.1:$PORT" >/dev/null; then
    nohup env EXTENSIONS_GALLERY='{}' "$CODE" --bind-addr 127.0.0.1:$PORT --auth none \\
        --disable-telemetry --disable-update-check --disable-workspace-trust --disable-getting-started-override \\
        --user-data-dir "$DATA" --extensions-dir "$EXT" "$HOME/projects" >"$BASE/code-server.log" 2>&1 &
fi
[ "\${1:-}" = --quiet ] || echo "VS Code is ready: tap Web Preview (top right) > Preview on port $PORT."
LAUNCHER
chmod +x "$BIN/pocketide"
grep -q 'PocketIDE: VS Code for Web Preview' "$HOME/.bashrc" 2>/dev/null || cat >>"$HOME/.bashrc" <<'RC'

# PocketIDE: VS Code for Web Preview (port 8080), started when Cloud Shell opens.
case $- in *i*) [ -x "$HOME/.local/bin/pocketide" ] && "$HOME/.local/bin/pocketide" --quiet ;; esac
RC
"$BIN/pocketide" --quiet

say "Done. VS Code and the three agents are ready."
cat <<'NEXT'
Open VS Code:  tap Web Preview (top right of Cloud Shell) > Preview on port 8080.
Sign in once:
  Claude Code  - its panel's Sign in: open the link, sign in, paste the code back.
  Codex        - first turn on device code sign-in in ChatGPT (Settings > Security), then run
                 `codex login --device-auth` here and enter the code it shows.
  Antigravity  - its panel's Sign in, or run `agy` here: open the link, paste the code back.
Your files:    projects in ~/projects; chats and sign-ins in ~/.claude, ~/.codex and ~/.gemini.
Cloud Shell keeps only this home folder (5 GB) and deletes it after 120 days without use.
Next time:     just open Cloud Shell; VS Code starts by itself.
NEXT
