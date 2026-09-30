#!/usr/bin/env bash
# PocketIDE for Google Cloud Shell. Sets up, only in your Cloud Shell home folder (the one part
# Cloud Shell keeps, 5 GB), a VS Code (code-server) for each official agent, each with its own
# port, settings, extensions and projects folder:
#   Claude Code  port 8080  ~/projects/claude-code
#   Codex        port 8081  ~/projects/codex
#   Antigravity  port 8082  ~/projects/antigravity
# From then on Cloud Shell starts them by itself when it starts, tidies old caches, logs and
# 30-day-old Codex chats (never your projects), and once a day installs newer releases of the
# agents and of code-server (a code-server release only once it is a week old), each checked.
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

# 1. VS Code for the web (code-server), used only when its SHA-256 matches the pinned one. A newer
# one the daily update installed stays.
installed_cs=$(basename "$(readlink "$CS_DIR/current" 2>/dev/null || echo none)")
if [ -x "$CS_DIR/current/bin/code-server" ] &&
    [ "$(printf '%s\n%s\n' "$CODE_SERVER_VERSION" "$installed_cs" | sort -V | head -n 1)" = "$CODE_SERVER_VERSION" ]; then
    echo "code-server $installed_cs is installed."
elif [ ! -x "$CS_DIR/$CODE_SERVER_VERSION/bin/code-server" ]; then
    free_kb=$(df -Pk "$HOME" | awk 'NR == 2 { print $4 }')
    [ "$free_kb" -ge "$NEEDED_KB" ] || fail "It needs about 2 GB free in your home folder (it uses about 1.6 GB); $((free_kb / 1024)) MB is free. Delete old files first."
    say "Downloading VS Code (code-server $CODE_SERVER_VERSION)..."
    tmp=$(mktemp -d)
    curl -fL --retry 3 -o "$tmp/code-server.tar.gz" "$CODE_SERVER_URL"
    echo "$CODE_SERVER_SHA256  $tmp/code-server.tar.gz" | sha256sum -c --quiet - || fail "code-server's download did not match its checksum."
    mkdir -p "$CS_DIR/$CODE_SERVER_VERSION"
    tar -xzf "$tmp/code-server.tar.gz" -C "$CS_DIR/$CODE_SERVER_VERSION" --strip-components=1
    rm -rf "$tmp"
    ln -sfn "$CS_DIR/$CODE_SERVER_VERSION" "$CS_DIR/current"
else
    ln -sfn "$CS_DIR/$CODE_SERVER_VERSION" "$CS_DIR/current"
fi

# 2. PocketIDE's layout extension, which each agent's VS Code gets: on a phone's narrow screen it
# opens that VS Code's agent full screen. Claude Code and Codex open in the secondary side bar,
# maximized; tap its restore button at the top to see the files and the editor again. Antigravity's
# own panel cannot open through Cloud Shell's Web Preview (its local server answers only to
# localhost), so Antigravity opens as its command line, agy, in a maximized terminal.
# Each VS Code installs it once for each version: raise the version when either file changes.
mkdir -p "$BASE/layout"
cat >"$BASE/layout/package.json" <<'JSON'
{
  "name": "layout",
  "displayName": "PocketIDE layout",
  "description": "Opens this VS Code's agent full screen, as a phone needs it.",
  "version": "6.0.1",
  "publisher": "pocketide",
  "license": "Apache-2.0",
  "engines": { "vscode": "^1.94.0" },
  "categories": ["Other"],
  "activationEvents": ["onStartupFinished"],
  "main": "./extension.js",
  "extensionKind": ["workspace"],
  "capabilities": { "untrustedWorkspaces": { "supported": true }, "virtualWorkspaces": true },
  "contributes": {
    "configuration": {
      "title": "PocketIDE",
      "properties": {
        "pocketide.agent": { "type": "string", "default": "", "description": "The agent this VS Code opens full screen." }
      }
    }
  }
}
JSON
cat >"$BASE/layout/extension.js" <<'JS'
// PocketIDE layout: opens this VS Code's agent full screen, as a phone's narrow screen needs it.
const vscode = require('vscode');

const fs = require('fs');
const os = require('os');
const path = require('path');

// How each agent opens. Antigravity's panel loads Google's local server through code-server's
// path proxy, whose pages that server refuses; its command line works, so it opens in a terminal
// (the settings keep the panel maximized).
const AGENTS = {
  'claude-code': { open: ['claude-vscode.sidebar.open'] },
  codex: { open: ['chatgpt.openSidebar'] },
  antigravity: { terminal: path.join(os.homedir(), '.gemini', 'bin', 'agy'), name: 'Antigravity' },
};
const WAIT_MS = 90000;
const STEP_MS = 1000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const run = (command, ...args) => Promise.resolve(vscode.commands.executeCommand(command, ...args)).then(() => true, () => false);

// The agent's extension registers its commands once it has started: wait for them.
async function ready(names) {
  for (let waited = 0; waited < WAIT_MS; waited += STEP_MS) {
    const known = new Set(await vscode.commands.getCommands(true));
    if (names.every((name) => known.has(name))) return true;
    await sleep(STEP_MS);
  }
  return false;
}

// Antigravity's extension fetches agy the first time it starts: wait for it.
async function fetched(file) {
  for (let waited = 0; waited < WAIT_MS; waited += STEP_MS) {
    if (fs.existsSync(file)) return true;
    await sleep(STEP_MS);
  }
  return false;
}

async function activate() {
  const agent = AGENTS[vscode.workspace.getConfiguration('pocketide').get('agent', '')];
  if (!agent) return;
  if (agent.terminal) {
    // A terminal VS Code kept from the last time (agy still running in it) is shown, not doubled.
    const kept = vscode.window.terminals.find((terminal) => terminal.name === agent.name);
    if (kept) {
      kept.show();
    } else if (await fetched(agent.terminal)) {
      const terminal = vscode.window.createTerminal({ name: agent.name });
      terminal.show();
      terminal.sendText(agent.terminal);
    }
    await run('workbench.action.closeSidebar');
    return;
  }
  if (!(await ready(agent.open))) return;
  for (const command of agent.open) await run(command);
  await run('workbench.action.maximizeAuxiliaryBar');
}

module.exports = { activate, deactivate() {} };
JS

# 3. `pocketide`: starts each agent's VS Code that is not running, installs or updates the agents
# (`pocketide update`), and, when Cloud Shell starts (`pocketide boot`), tidies and updates too.
cat >"$BIN/pocketide" <<'LAUNCHER'
#!/usr/bin/env bash
# PocketIDE's launcher in Google Cloud Shell; see ~/pocketide-cloudshell.sh.
set -uo pipefail
AGENTS="claude-code:8080:anthropic/claude-code codex:8081:openai/chatgpt antigravity:8082:google/google-antigravity"
BASE="$HOME/.pocketide"
CODE="$BASE/code-server/current/bin/code-server"

settings() { # $1: the agent; the settings its VS Code starts with: phone screen, no telemetry, its agent full screen
    python3 - "$1" <<'PY'
import json, sys
agent = sys.argv[1]
print(json.dumps({
    "workbench.startupEditor": "none",
    "workbench.tips.enabled": False,
    "workbench.reduceMotion": "on",
    "workbench.secondarySideBar.defaultVisibility": "hidden" if agent == "antigravity" else "maximized",
    "workbench.panel.opensMaximized": "always" if agent == "antigravity" else "preserve",
    "window.autoDetectColorScheme": True,
    "breadcrumbs.enabled": False,
    "editor.minimap.enabled": False,
    "editor.wordWrap": "on",
    "editor.fontSize": 14,
    "terminal.integrated.fontSize": 14,
    "terminal.integrated.gpuAcceleration": "off",
    "files.autoSave": "afterDelay",
    "extensions.ignoreRecommendations": True,
    "update.mode": "none",
    "telemetry.telemetryLevel": "off",
    "chat.disableAIFeatures": True,
    "task.allowAutomaticTasks": "off",
    "pocketide.agent": agent,
    "claudeCode.preferredLocation": "sidebar",
    "claudeCode.useCtrlEnterToSend": True,
    "chatgpt.openOnStartup": agent == "codex",
    "chatgpt.composerEnterBehavior": "cmdAlways",
}, indent=2))
PY
}

layout() { # $1: an agent's VS Code folder: PocketIDE's layout extension in it, once for each version
    version=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["version"])' "$BASE/layout/package.json" 2>/dev/null) || return 0
    ls -d "$1/extensions/pocketide.layout-$version"* >/dev/null 2>&1 && return 0
    vsix="$BASE/pocketide.layout-$version.vsix"
    python3 - "$BASE/layout" "$vsix" "$version" <<'PY'
import os, sys, zipfile
source, out, version = sys.argv[1:4]
types = ('<?xml version="1.0" encoding="utf-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
         '<Default Extension=".json" ContentType="application/json"/><Default Extension=".js" ContentType="application/javascript"/>'
         '<Default Extension=".vsixmanifest" ContentType="text/xml"/></Types>')
manifest = f"""<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">
  <Metadata>
    <Identity Language="en-US" Id="layout" Version="{version}" Publisher="pocketide" />
    <DisplayName>PocketIDE layout</DisplayName>
    <Description xml:space="preserve">Opens this VS Code's agent full screen, as a phone needs it.</Description>
    <Categories>Other</Categories>
    <Properties>
      <Property Id="Microsoft.VisualStudio.Code.Engine" Value="^1.94.0" />
      <Property Id="Microsoft.VisualStudio.Code.ExtensionKind" Value="workspace" />
    </Properties>
  </Metadata>
  <Installation><InstallationTarget Id="Microsoft.VisualStudio.Code" /></Installation>
  <Dependencies />
  <Assets><Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true" /></Assets>
</PackageManifest>
"""
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as package:
    package.writestr("[Content_Types].xml", types)
    package.writestr("extension.vsixmanifest", manifest)
    for name in ("package.json", "extension.js"):
        package.write(os.path.join(source, name), "extension/" + name)
PY
    "$CODE" --user-data-dir "$1" --extensions-dir "$1/extensions" --install-extension "$vsix" --force >/dev/null ||
        echo "PocketIDE's layout extension was not installed in $1." >&2
    rm -f "$vsix"
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

# The newest code-server release that is at least a week old, when GitHub publishes its SHA-256
# and the download matches it. It runs from the next start; the one running now, and the one
# before it, stay, older ones go.
code_server_update() {
    python3 - "$BASE/code-server" <<'PY'
import datetime, hashlib, json, os, shutil, subprocess, sys, tempfile, urllib.request


def key(text):
    return tuple(int(part) for part in text.split("."))


def main(base):
    request = urllib.request.Request("https://api.github.com/repos/coder/code-server/releases/latest",
                                     headers={"Accept": "application/vnd.github+json"})
    release = json.load(urllib.request.urlopen(request, timeout=60))
    version = str(release.get("tag_name", "")).lstrip("v")
    if release.get("draft") or release.get("prerelease") or not version.replace(".", "").isdigit():
        return
    current = os.path.realpath(os.path.join(base, "current"))
    target = os.path.join(base, version)
    if os.path.realpath(target) == current:
        print(f"code-server {version} is installed.")
        return
    try:
        if key(version) <= key(os.path.basename(current)):
            return
    except ValueError:
        pass
    published = datetime.datetime.fromisoformat(release["published_at"].replace("Z", "+00:00"))
    if datetime.datetime.now(datetime.timezone.utc) - published < datetime.timedelta(days=7):
        print(f"code-server {version} is not a week old yet; it waits.")
        return
    name = f"code-server-{version}-linux-amd64.tar.gz"
    asset = next((a for a in release.get("assets", []) if a.get("name") == name), None)
    digest = str((asset or {}).get("digest") or "")
    if not digest.startswith("sha256:"):
        print(f"GitHub publishes no SHA-256 for {name}; code-server stays as it is.")
        return
    if not os.path.isdir(os.path.join(target, "bin")):
        download(base, version, name, asset["browser_download_url"], digest[len("sha256:"):])
    link = os.path.join(base, "current.new")
    if os.path.lexists(link):
        os.remove(link)
    os.symlink(target, link)
    os.replace(link, os.path.join(base, "current"))
    for old in os.listdir(base):
        path = os.path.join(base, old)
        if old not in (version, os.path.basename(current)) and os.path.isdir(path) and not os.path.islink(path):
            shutil.rmtree(path, ignore_errors=True)
    print(f"code-server {version} is installed; it runs from the next start of Cloud Shell.")


def download(base, version, name, url, sha256):
    if shutil.disk_usage(base).free < 1_000_000_000:
        sys.exit("Less than 1 GB is free in your home folder: code-server was not updated.")
    print(f"Updating code-server to {version}...")
    with tempfile.TemporaryDirectory(dir=base) as work:
        archive = os.path.join(work, name)
        sha = hashlib.sha256()
        with urllib.request.urlopen(url, timeout=600) as response, open(archive, "wb") as out:
            for chunk in iter(lambda: response.read(1 << 20), b""):
                sha.update(chunk)
                out.write(chunk)
        if sha.hexdigest() != sha256:
            sys.exit(f"{name} did not match the SHA-256 GitHub publishes; code-server stays as it is.")
        unpacked = os.path.join(work, version)
        os.makedirs(unpacked)
        subprocess.run(["tar", "-xzf", archive, "-C", unpacked, "--strip-components=1"], check=True)
        os.replace(unpacked, os.path.join(base, version))


try:
    main(sys.argv[1])
except Exception as error:  # the network, GitHub or the disk; tried again tomorrow
    sys.exit(f"code-server was not updated: {error}")
PY
}

# The newest release (never a pre-release) of each agent from its verified publisher on Open VSX,
# for this computer or every platform, checked against the SHA-256 Open VSX publishes, each into
# its own VS Code.
update() {
    code_server_update || echo "code-server is tried again tomorrow." >&2
    missing=""
    for entry in $AGENTS; do
        key=${entry%%:*} rest=${entry#*:}
        agent=${rest#*:} ns=${agent%/*} name=${agent#*/}
        data="$BASE/vscode/$key"
        mkdir -p "$data/Machine" "$data/extensions" "$HOME/projects/$key"
        settings "$key" >"$data/Machine/settings.json"
        layout "$data"
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
    links
    if [ -n "$missing" ]; then
        echo "Not installed:$missing. Run: pocketide update" >&2
        return 1
    fi
    date +%s >"$BASE/updated"
}

links() { # the agents' own command lines, where a terminal finds them
    # Codex's comes with its extension: `codex login --device-auth` signs in here.
    codex=$(find "$BASE/vscode/codex/extensions" -path '*openai.chatgpt*' -type f -name codex -perm -u+x 2>/dev/null | head -n 1)
    [ -n "$codex" ] && ln -sfn "$codex" "$HOME/.local/bin/codex"
    # Antigravity's (agy) is fetched by its extension the first time its VS Code opens.
    agy=$(find "$HOME/.gemini" -maxdepth 3 -type f -name agy -perm -u+x 2>/dev/null | head -n 1)
    [ -n "$agy" ] && ln -sfn "$agy" "$HOME/.local/bin/agy"
    return 0
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
    find "$HOME/.cache" -type f -atime +14 -not -path "$HOME/.cache/ms-playwright/*" -delete 2>/dev/null
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
--quiet)
    start
    links
    ;;
*)
    start
    echo "PocketIDE: Claude Code on port 8080, Codex on 8081, Antigravity on 8082 (Web Preview)."
    ;;
esac
LAUNCHER
chmod +x "$BIN/pocketide"

# The first PocketIDE script kept one VS Code for all three agents, on port 8080, in
# ~/.local/share/code-server. Each agent now has its own, so that one goes; the agents' chats and
# sign-ins (~/.claude, ~/.codex, ~/.gemini) and the projects stay.
OLD_DATA="$HOME/.local/share/code-server"
if grep -q 'chatgpt.openOnStartup' "$OLD_DATA/Machine/settings.json" 2>/dev/null; then
    if [ -n "${VSCODE_IPC_HOOK_CLI:-}" ]; then
        echo "The first PocketIDE VS Code stays for now: run this in Cloud Shell's own terminal to replace it."
    else
        pkill -u "$(id -u)" -f "code-server.*--user-data-dir $OLD_DATA" || true
        rm -rf "${OLD_DATA:?}"
        sed -i '/# PocketIDE: VS Code for Web Preview/,+1d' "$HOME/.bashrc" || true
        echo "The first PocketIDE VS Code (all agents in one) was replaced."
    fi
fi

# 4. The agents, each in its own VS Code.
say "Installing the three agents, each in its own VS Code..."
installed=yes
"$BIN/pocketide" update || installed=no

# 5. Cloud Shell runs ~/.customize_environment as root each time it starts: PocketIDE's part
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

# PocketIDE: agents' VS Code (ports 8080-8082), started if Cloud Shell has not yet; pocketide,
# codex and agy on the PATH.
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$HOME/.local/bin:$PATH" ;; esac
case $- in *i*) [ -x "$HOME/.local/bin/pocketide" ] && "$HOME/.local/bin/pocketide" --quiet ;; esac
RC

if [ "$installed" = no ]; then
    fail "Not every agent installed (see above). Free some space if asked, then run: ~/.local/bin/pocketide update"
fi
say "Done. Go back to PocketIDE and tap Set-up is done."
cat <<'NEXT'
In PocketIDE, tap an agent: its own VS Code opens, the agent full screen. Sign in once:
  Claude Code  - its panel's Sign in: open the link, sign in, paste the code back.
  Codex        - first turn on device code sign-in in ChatGPT (Settings > Security), then in
                 Codex's VS Code open the Terminal, run `codex login --device-auth` and enter
                 the code it shows.
  Antigravity  - it opens as its command line (agy): pick Google OAuth, open the link it
                 shows, sign in, and paste the code back.
Your files:    projects in ~/projects/claude-code, ~/projects/codex and ~/projects/antigravity;
               chats and sign-ins in ~/.claude, ~/.codex and ~/.gemini.
NEXT
