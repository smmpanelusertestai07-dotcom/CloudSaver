#!/usr/bin/env bash
# PocketIDE for Google Cloud Shell. Sets up, only in your Cloud Shell home folder (the one part
# Cloud Shell keeps, 5 GB), a VS Code (code-server) for each official agent, each with its own
# port, settings, extensions and projects folder:
#   Claude Code  port 8080  ~/projects/claude-code
#   Codex        port 8081  ~/projects/codex
#   Antigravity  port 8082  ~/projects/antigravity
# Each VS Code listens only inside Cloud Shell (127.0.0.1). PocketIDE's app reaches them through
# Google's own gcloud (`gcloud cloud-shell ssh`), from the phone's own address only.
# Each agent's VS Code starts when PocketIDE's app opens that agent, so Cloud Shell's memory goes to
# the agents in use. When Cloud Shell starts, it tidies old caches and logs (never
# your projects or chats), and once a day installs newer releases of the agents and of code-server
# (a code-server release only once it is a week old), each checked.
# A browser for the agents, on demand (`pocketide browser start`): Google's Chrome for Testing, which
# they drive through Chrome's DevTools protocol on 127.0.0.1:9222, and which you watch and use in
# PocketIDE's app. It stops by itself when no one has used it for 20 minutes.
# Each agent's own instructions begin with Cloud Shell's rules (no mining, scanning, public tunnels
# or keeping Cloud Shell up), so an agent never puts the Google account at risk by itself.
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
NEEDED_KB=2500000

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
    [ "$free_kb" -ge "$NEEDED_KB" ] || fail "It needs about 2.5 GB free in your home folder (it uses about 1.9 GB); $((free_kb / 1024)) MB is free. Delete old files first."
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
# shows one thing at a time, full screen: the agent (in the secondary side bar, maximized), or
# what covers it (a file, a diff, settings, an extension's page, a terminal), and Back returns to
# the agent. PocketIDE's own buttons (Back, Tools) reach it through keys only PocketIDE presses.
# Each VS Code installs it once for each version: raise the version when a file changes.
mkdir -p "$BASE/layout"
cat >"$BASE/layout/package.json" <<'JSON'
{
  "name": "layout",
  "displayName": "PocketIDE layout",
  "description": "One thing at a time, full screen, for PocketIDE on a phone: the agent, or what covers it.",
  "version": "8.0.0",
  "publisher": "pocketide",
  "license": "Apache-2.0",
  "engines": {
    "vscode": "^1.94.0"
  },
  "categories": [
    "Other"
  ],
  "activationEvents": [
    "onStartupFinished"
  ],
  "main": "./extension.js",
  "extensionKind": [
    "workspace"
  ],
  "capabilities": {
    "untrustedWorkspaces": {
      "supported": true
    },
    "virtualWorkspaces": true
  },
  "contributes": {
    "commands": [
      {
        "command": "pocketide.agent",
        "title": "PocketIDE: The agent, full screen"
      },
      {
        "command": "pocketide.back",
        "title": "PocketIDE: Back"
      },
      {
        "command": "pocketide.terminal",
        "title": "PocketIDE: Terminal, full screen"
      },
      {
        "command": "pocketide.settings",
        "title": "PocketIDE: Settings"
      },
      {
        "command": "pocketide.files",
        "title": "PocketIDE: Open a file"
      },
      {
        "command": "pocketide.commands",
        "title": "PocketIDE: All commands"
      },
      {
        "command": "pocketide.vsix",
        "title": "PocketIDE: Install an extension from a link (.vsix)"
      },
      {
        "command": "pocketide.tools",
        "title": "PocketIDE: Tools"
      }
    ],
    "keybindings": [
      {
        "command": "pocketide.back",
        "key": "f13"
      },
      {
        "command": "pocketide.agent",
        "key": "f14"
      },
      {
        "command": "pocketide.terminal",
        "key": "f15"
      },
      {
        "command": "pocketide.settings",
        "key": "f16"
      },
      {
        "command": "pocketide.commands",
        "key": "f17"
      },
      {
        "command": "pocketide.vsix",
        "key": "f18"
      },
      {
        "command": "pocketide.tools",
        "key": "f19"
      },
      {
        "command": "pocketide.files",
        "key": "ctrl+f13"
      },
      {
        "command": "pocketide.tools",
        "key": "ctrl+alt+p"
      },
      {
        "command": "pocketide.agent",
        "key": "ctrl+alt+a"
      }
    ],
    "viewsContainers": {
      "secondarySidebar": [
        {
          "id": "pocketide-agent",
          "title": "Agent",
          "icon": "agent.svg"
        }
      ]
    },
    "views": {
      "pocketide-agent": [
        {
          "id": "pocketide.placeholder",
          "name": "Agent",
          "when": "pocketide.never"
        }
      ]
    },
    "configuration": {
      "title": "PocketIDE",
      "properties": {
        "pocketide.agent": {
          "type": "string",
          "default": "",
          "description": "The agent this VS Code opens full screen."
        }
      }
    }
  }
}
JSON
cat >"$BASE/layout/agent.svg" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M12 2a5 5 0 0 1 5 5v1h1a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-6a3 3 0 0 1 3-3h1V7a5 5 0 0 1 5-5zm-3 11a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zm6 0a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z"/></svg>
SVG
cat >"$BASE/layout/extension.js" <<'JS'
// PocketIDE layout: a phone's narrow screen shows one thing at a time, full screen: this VS Code's
// agent, or what covers it (a file, a diff, settings, an extension's own page, a terminal). Back
// returns to the agent. PocketIDE's bar reaches these commands through keys a phone's keyboard does
// not have (F13 to F19, Ctrl+F13), which PocketIDE's page script presses.
const vscode = require('vscode');

const fs = require('fs');
const https = require('https');
const os = require('os');
const path = require('path');

// How each agent's own panel opens. Antigravity's lives in the activity bar, which a phone has no
// room for: it moves into PocketIDE's own container in the secondary side bar, where the others are.
const AGENTS = {
  'claude-code': { open: ['claude-vscode.sidebar.open'] },
  codex: { open: ['chatgpt.openSidebar'] },
  antigravity: { move: ['antigravity.panel'], open: ['antigravity.panel.focus'] },
};
const CONTAINER = 'workbench.view.extension.pocketide-agent';
const WAIT_MS = 90000;
const STEP_MS = 1000;
const SETTLE_MS = 150;
const OPEN_MS = 3000;
const VSIX_MAX_BYTES = 300 * 1024 * 1024;

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

// A file from an https link (its redirects too, never to plain http), at most VSIX_MAX_BYTES.
function download(link, file, hops = 5) {
  return new Promise((resolve, reject) => {
    if (!link.startsWith('https://')) return reject(new Error('Only https links.'));
    https.get(link, { headers: { 'User-Agent': 'PocketIDE' } }, (answer) => {
      const status = answer.statusCode || 0;
      if (status >= 300 && status < 400 && answer.headers.location && hops > 0) {
        answer.resume();
        return resolve(download(new URL(answer.headers.location, link).toString(), file, hops - 1));
      }
      if (status !== 200) {
        answer.resume();
        return reject(new Error(`The link answered ${status}.`));
      }
      let size = 0;
      const out = fs.createWriteStream(file);
      answer.on('data', (chunk) => {
        size += chunk.length;
        if (size > VSIX_MAX_BYTES) answer.destroy(new Error('The file is too big for an extension.'));
      });
      answer.on('error', (error) => out.destroy(error));
      out.on('error', reject);
      out.on('finish', resolve);
      answer.pipe(out);
    }).on('error', reject);
  });
}

// An extension Open VSX does not have, from its maker's own link to a .vsix file.
async function installFromLink() {
  const link = await vscode.window.showInputBox({
    title: 'Install an extension from a link',
    prompt: 'The https link to its .vsix file, from its maker (for example their GitHub releases).',
    placeHolder: 'https://github.com/…/releases/download/…/extension.vsix',
    ignoreFocusOut: true,
    validateInput: (text) => (/^https:\/\/\S+$/.test(text.trim()) ? null : 'An https link to a .vsix file'),
  });
  if (!link) return;
  const file = path.join(os.tmpdir(), `pocketide-${process.pid}-${Date.now()}.vsix`);
  try {
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'Installing the extension…' },
      async () => {
        await download(link.trim(), file);
        await vscode.commands.executeCommand('workbench.extensions.installExtension', vscode.Uri.file(file));
      },
    );
    vscode.window.showInformationMessage('The extension is installed. If it asks, reload this VS Code.');
  } catch (error) {
    vscode.window.showErrorMessage(`The extension was not installed: ${error.message || error}`);
  } finally {
    fs.rmSync(file, { force: true });
  }
}

let agent; // this VS Code's agent, from its settings
let agentReady = false; // the agent's extension has started: its panel can open
let covered = false; // an editor covers the agent, full screen
let lastActive = ''; // the editor tab in front when the layout last changed
let closing = false; // Back is closing what covers the agent
let queue = Promise.resolve();

// Layout changes run one after another, never two at once.
function arrange(step) {
  queue = queue.then(step, step).catch(() => undefined);
  return queue;
}

const activeTab = () => vscode.window.tabGroups.activeTabGroup && vscode.window.tabGroups.activeTabGroup.activeTab;
const isTerminal = (tab) => !!tab && tab.input instanceof vscode.TabInputTerminal;

function tabKey(tab) {
  if (!tab) return '';
  const input = tab.input || {};
  const where = input.uri || input.modified || input.viewType || input.notebookType || '';
  return `${tab.group.viewColumn}|${tab.label}|${String(where)}`;
}

// The agent, full screen at once; its own panel in front as soon as its extension has started (an
// agent that is still starting, or downloading its parts, never holds the layout back).
async function showAgent() {
  await run('workbench.action.closePanel');
  await run('workbench.action.maximizeAuxiliaryBar');
  covered = false;
  if (!agent || !agentReady) return;
  if (agent.move) {
    await run('vscode.moveViews', { viewIds: agent.move, destinationId: CONTAINER });
    await run('workbench.action.maximizeAuxiliaryBar');
  }
  // Antigravity's panel, for one, answers only once its backend is downloaded and started.
  for (const command of agent.open) await Promise.race([run(command), sleep(OPEN_MS)]);
  await run('workbench.action.maximizeAuxiliaryBar');
}

// What covers the agent gets the whole screen: no side bars, no panel, its editor alone.
async function showEditor() {
  await run('workbench.action.closePanel');
  await run('workbench.action.maximizeEditorHideSidebar');
  covered = true;
}

// An editor that comes to the front (the agent's diff, a file, settings, an extension's page)
// covers the agent, full screen; when the last one closes, the agent is back.
function watchEditors(context) {
  let timer;
  const check = () => {
    if (closing) return;
    const tab = activeTab();
    const key = tabKey(tab);
    const changed = key !== lastActive;
    lastActive = key;
    if (!tab) {
      if (covered) arrange(showAgent);
    } else if (changed) {
      arrange(showEditor);
    }
  };
  const soon = () => {
    clearTimeout(timer);
    timer = setTimeout(check, SETTLE_MS);
  };
  context.subscriptions.push(
    vscode.window.tabGroups.onDidChangeTabs((change) => {
      if (change.opened.length || change.closed.length || change.changed.some((tab) => tab.isActive)) soon();
    }),
    vscode.window.tabGroups.onDidChangeTabGroups(soon),
  );
}

// Back: what covers the agent closes (a terminal only steps aside: it keeps running), and the
// agent is back on screen. Other editors stay open behind it: the one that comes to the front of
// them when this one closes is no new editor, so it does not cover the agent.
async function back() {
  const tab = activeTab();
  if (covered && tab && !isTerminal(tab)) {
    closing = true;
    try {
      await vscode.window.tabGroups.close(tab, true);
    } catch (error) {
      // Already closed.
    }
    await sleep(SETTLE_MS * 2);
    closing = false;
  }
  lastActive = tabKey(activeTab());
  await arrange(showAgent);
}

async function terminal() {
  const open = vscode.window.terminals.find((each) => each.exitStatus === undefined);
  const shown = open || vscode.window.createTerminal({ location: vscode.TerminalLocation.Editor });
  shown.show(false);
  await sleep(SETTLE_MS * 2);
  lastActive = tabKey(activeTab());
  await arrange(showEditor);
}

async function settings() {
  await run('workbench.action.openSettings');
  await sleep(SETTLE_MS * 2);
  lastActive = tabKey(activeTab());
  await arrange(showEditor);
}

// The same places from a list, for a keyboard (Ctrl+Alt+P) or a computer's browser.
const TOOLS = [
  { label: '$(hubot) Agent', detail: 'Back to the agent, full screen', run: () => arrange(showAgent) },
  { label: '$(terminal) Terminal', detail: 'A command line in this project', run: terminal },
  { label: '$(go-to-file) Open a file', detail: 'Find a file in this project by its name', run: () => run('workbench.action.quickOpen') },
  { label: '$(gear) Settings', detail: 'This VS Code\'s settings', run: settings },
  { label: '$(cloud-download) Install from a link', detail: 'An extension (.vsix) Open VSX does not have, from its maker', run: installFromLink },
  { label: '$(list-flat) All commands', detail: 'Everything VS Code can do', run: () => run('workbench.action.showCommands') },
];

function commands(context) {
  context.subscriptions.push(
    vscode.commands.registerCommand('pocketide.agent', () => arrange(showAgent)),
    vscode.commands.registerCommand('pocketide.back', back),
    vscode.commands.registerCommand('pocketide.terminal', terminal),
    vscode.commands.registerCommand('pocketide.settings', settings),
    vscode.commands.registerCommand('pocketide.commands', () => run('workbench.action.showCommands')),
    vscode.commands.registerCommand('pocketide.files', () => run('workbench.action.quickOpen')),
    vscode.commands.registerCommand('pocketide.vsix', installFromLink),
    vscode.commands.registerCommand('pocketide.tools', async () => {
      const picked = await vscode.window.showQuickPick(TOOLS, { placeHolder: 'PocketIDE tools' });
      if (picked) await picked.run();
    }),
  );
}

async function activate(context) {
  agent = AGENTS[vscode.workspace.getConfiguration('pocketide').get('agent', '')];
  commands(context);
  watchEditors(context);
  lastActive = tabKey(activeTab());
  await arrange(showAgent);
  if (agent) {
    ready(agent.open).then((found) => {
      agentReady = found;
      if (found) arrange(() => (covered ? undefined : showAgent()));
    });
  }
}

module.exports = { activate, deactivate() {} };
JS

# 2b. PocketIDE's browser view (relay.py): `pocketide browser start` starts Chrome for the agents and
# this page for the owner, who watches and uses that Chrome in PocketIDE's app (Python's standard
# library only; it listens only on 127.0.0.1).
mkdir -p "$BASE/browser"
cat >"$BASE/browser/relay.py" <<'RELAY'
"""PocketIDE's view of its browser in Cloud Shell: shows Chrome's page live and passes the owner's
taps, scrolls and typing to it.

Chrome (its headless shell) runs on 127.0.0.1:CDP_PORT, where the agents drive it through Chrome's
DevTools protocol. This relay serves a page on 127.0.0.1:VIEW_PORT, which PocketIDE's app opens
through its private door: the page Chrome shows (the newest tab, or the one picked), as pictures
Chrome sends while someone watches, and the owner's input back. It listens only on 127.0.0.1, uses
only Python's standard library, and stops Chrome and itself when no one has watched or used the
browser for IDLE_MINUTES.
"""
import base64
import json
import os
import socket
import struct
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CDP_PORT = int(os.environ.get("CDP_PORT", "9222"))
VIEW_PORT = int(os.environ.get("VIEW_PORT", "6080"))
IDLE_MINUTES = 20
WATCH_SECONDS = 30
OWNER_SECONDS = 120
PHONE = (412, 870)
DESKTOP = (1280, 800)
KEYS = {"Enter": 13, "Backspace": 8, "Tab": 9, "Escape": 27, "ArrowLeft": 37, "ArrowUp": 38, "ArrowRight": 39,
        "ArrowDown": 40, "Delete": 46, "Home": 36, "End": 35, "PageUp": 33, "PageDown": 34}


class Socket:
    """A WebSocket client, just enough for Chrome's DevTools protocol (text frames, no extensions)."""

    def __init__(self, url):
        address = urllib.parse.urlparse(url)
        self.sock = socket.create_connection((address.hostname, address.port), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n"
                           % (address.path, address.hostname, address.port, key)).encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise OSError("Chrome closed the connection")
            head += chunk
        if b" 101 " not in head.split(b"\r\n", 1)[0]:
            raise OSError("Chrome refused the connection")
        self.rest = head.split(b"\r\n\r\n", 1)[1]
        self.sock.settimeout(None)
        self.lock = threading.Lock()

    def send(self, text):
        data = text.encode()
        mask = os.urandom(4)
        size = len(data)
        if size < 126:
            head = struct.pack(">BB", 0x81, 0x80 | size)
        elif size < 65536:
            head = struct.pack(">BBH", 0x81, 0x80 | 126, size)
        else:
            head = struct.pack(">BBQ", 0x81, 0x80 | 127, size)
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(data))
        with self.lock:
            self.sock.sendall(head + mask + masked)

    def take(self, size):
        while len(self.rest) < size:
            chunk = self.sock.recv(1 << 16)
            if not chunk:
                raise OSError("Chrome closed the connection")
            self.rest += chunk
        out, self.rest = self.rest[:size], self.rest[size:]
        return out

    def receive(self):
        message = b""
        while True:
            first, second = self.take(2)
            size = second & 0x7F
            if size == 126:
                size = struct.unpack(">H", self.take(2))[0]
            elif size == 127:
                size = struct.unpack(">Q", self.take(8))[0]
            payload = self.take(size)
            opcode = first & 0x0F
            if opcode == 8:
                raise OSError("Chrome closed the connection")
            if opcode in (0, 1, 2):
                message += payload
                if first & 0x80:
                    return message.decode("utf-8", "replace")


class Browser:
    """One connection to Chrome: the page shown, its pictures, and the owner's input."""

    def __init__(self):
        # Chrome on this computer: never through a proxy the environment may name.
        local = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        version = json.load(local.open("http://127.0.0.1:%d/json/version" % CDP_PORT, timeout=5))
        self.ws = Socket(version["webSocketDebuggerUrl"])
        self.lock = threading.Lock()
        self.next_id = 0
        self.waiting = {}
        self.session = None
        self.target = None
        self.picked = None  # the tab the owner picked, kept in front while they use it
        self.touched = 0.0  # when the owner last picked or used a page
        self.active = None  # the page something last happened in: the one the agent works in
        self.casting = False
        self.frame = None
        self.frame_no = 0
        self.size = DESKTOP
        self.phone = False
        self.changed = threading.Condition()
        self.watched = 0.0
        self.used = time.time()
        self.closed = threading.Event()
        threading.Thread(target=self.read, daemon=True).start()
        self.call("Target.setDiscoverTargets", {"discover": True})
        self.follow()

    def call(self, method, params=None, session=None, wait=True):
        with self.lock:
            self.next_id += 1
            number = self.next_id
        message = {"id": number, "method": method, "params": params or {}}
        if session:
            message["sessionId"] = session
        box = {"done": threading.Event()}
        if wait:
            self.waiting[number] = box
        self.ws.send(json.dumps(message))
        if not wait:
            return None
        box["done"].wait(10)
        self.waiting.pop(number, None)
        return box.get("result")

    def read(self):
        try:
            while True:
                message = json.loads(self.ws.receive())
                if "id" in message:
                    box = self.waiting.get(message["id"])
                    if box is not None:
                        box["result"] = message.get("result") or {}
                        box["done"].set()
                    continue
                method = message.get("method")
                params = message.get("params") or {}
                if method == "Page.screencastFrame" and message.get("sessionId") == self.session:
                    meta = params.get("metadata") or {}
                    self.size = (int(meta.get("deviceWidth") or self.size[0]), int(meta.get("deviceHeight") or self.size[1]))
                    with self.changed:
                        self.frame = base64.b64decode(params.get("data", ""))
                        self.frame_no += 1
                        self.changed.notify_all()
                    self.call("Page.screencastFrameAck", {"sessionId": params.get("sessionId")}, self.session, wait=False)
                elif method in ("Target.targetCreated", "Target.targetDestroyed", "Target.targetInfoChanged"):
                    info = params.get("targetInfo") or {}
                    if method != "Target.targetDestroyed" and info.get("type") == "page":
                        self.active = info.get("targetId")
                    self.used = time.time()
                    threading.Thread(target=self.follow, daemon=True).start()
        except (OSError, ValueError):
            self.closed.set()

    def pages(self):
        found = (self.call("Target.getTargets") or {}).get("targetInfos", [])
        return [page for page in found if page.get("type") == "page"]

    def follow(self):
        """The page shown: the one the owner picked, while they use it (OWNER_SECONDS); else the
        one something last happened in, which is where the agent works; else the last one."""
        pages = self.pages()
        if not pages:
            return
        open_ids = {page["targetId"] for page in pages}
        if self.picked in open_ids and time.time() - self.touched < OWNER_SECONDS:
            wanted = self.picked
        else:
            wanted = self.active if self.active in open_ids else None
        chosen = next((page for page in pages if page["targetId"] == wanted), None) or pages[-1]
        if chosen["targetId"] == self.target:
            return
        attached = self.call("Target.attachToTarget", {"targetId": chosen["targetId"], "flatten": True}) or {}
        if not attached.get("sessionId"):
            return
        old, self.session, self.target = self.session, attached["sessionId"], chosen["targetId"]
        if old:
            self.call("Page.stopScreencast", {}, old, wait=False)
        self.call("Page.enable", {}, self.session)
        self.casting = False
        if self.phone:
            self.emulate(True)
        if time.time() - self.watched < WATCH_SECONDS:
            self.cast()

    def cast(self):
        if self.session and not self.casting:
            self.casting = True
            # Pictures no bigger than a phone shows, and light: watching costs little mobile data.
            self.call("Page.startScreencast", {"format": "jpeg", "quality": 50, "maxWidth": 960, "maxHeight": 1280,
                                               "everyNthFrame": 1}, self.session)

    def watch(self):
        """Someone is looking: pictures come while they do."""
        self.watched = time.time()
        self.cast()

    def quiet(self):
        """No one has looked for a while: Chrome sends no pictures."""
        if self.casting and time.time() - self.watched > WATCH_SECONDS:
            self.casting = False
            self.call("Page.stopScreencast", {}, self.session, wait=False)

    def emulate(self, phone):
        self.phone = phone
        if phone:
            width, height = PHONE
            self.call("Emulation.setDeviceMetricsOverride",
                      {"width": width, "height": height, "deviceScaleFactor": 2, "mobile": True}, self.session)
            self.call("Emulation.setTouchEmulationEnabled", {"enabled": True}, self.session)
        else:
            self.call("Emulation.clearDeviceMetricsOverride", {}, self.session)
            self.call("Emulation.setTouchEmulationEnabled", {"enabled": False}, self.session)
        self.casting = False
        self.call("Page.stopScreencast", {}, self.session, wait=False)
        self.cast()

    def state(self):
        info = next((page for page in self.pages() if page["targetId"] == self.target), {})
        return {"url": info.get("url", ""), "title": info.get("title", ""), "phone": self.phone,
                "pages": [{"id": page["targetId"], "title": page.get("title") or page.get("url", ""),
                           "shown": page["targetId"] == self.target} for page in self.pages()]}

    def act(self, what):
        """The owner's input, checked: only these kinds, on the page shown."""
        # A page the owner picked stays in front while they keep using it.
        self.used = self.touched = time.time()
        kind = what.get("type")
        s = self.session
        point = lambda: (max(0.0, min(1.0, float(what.get("x", 0)))) * self.size[0],
                         max(0.0, min(1.0, float(what.get("y", 0)))) * self.size[1])
        if kind == "tap":
            x, y = point()
            for event in ("mouseMoved", "mousePressed", "mouseReleased"):
                self.call("Input.dispatchMouseEvent", {"type": event, "x": x, "y": y, "button": "left", "clickCount": 1}, s)
        elif kind == "scroll":
            x, y = point()
            delta = max(-5000.0, min(5000.0, float(what.get("dy", 0))))
            self.call("Input.dispatchMouseEvent", {"type": "mouseWheel", "x": x, "y": y, "deltaX": 0, "deltaY": delta}, s)
        elif kind == "text":
            self.call("Input.insertText", {"text": str(what.get("text", ""))[:2000]}, s)
        elif kind == "key" and what.get("key") in KEYS:
            key = what["key"]
            for event in ("keyDown", "keyUp"):
                params = {"type": event, "key": key, "code": key, "windowsVirtualKeyCode": KEYS[key]}
                if key == "Enter" and event == "keyDown":
                    params["text"] = "\r"
                self.call("Input.dispatchKeyEvent", params, s)
        elif kind == "go":
            url = str(what.get("url", "")).strip()
            if url and "://" not in url and not url.startswith("about:"):
                url = ("http://" if url.startswith(("localhost", "127.0.0.1")) else "https://") + url
            if urllib.parse.urlparse(url).scheme in ("http", "https") or url == "about:blank":
                self.call("Page.navigate", {"url": url}, s)
        elif kind == "back":
            self.call("Runtime.evaluate", {"expression": "history.back()"}, s)
        elif kind == "forward":
            self.call("Runtime.evaluate", {"expression": "history.forward()"}, s)
        elif kind == "reload":
            self.call("Page.reload", {}, s)
        elif kind == "page":
            self.picked = str(what.get("id", ""))
            self.target = None
            self.follow()
        elif kind == "size":
            self.emulate(bool(what.get("phone")))

    def close(self):
        try:
            self.call("Browser.close", wait=False)
        except OSError:
            pass


VIEW = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1,maximum-scale=1">
<title>PocketIDE browser</title>
<style>
:root{color-scheme:light dark;--bg:#f4f2fa;--bar:#ffffff;--ink:#1c1b20;--soft:#5f5b6b;--line:#d9d4e5;--accent:#5b3fd6}
@media (prefers-color-scheme:dark){:root{--bg:#141218;--bar:#1f1d24;--ink:#e7e1ef;--soft:#a39dae;--line:#3a3642;--accent:#c2b5ff}}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--ink);font:15px system-ui,sans-serif}
body{display:flex;flex-direction:column}
.bar{display:flex;gap:6px;align-items:center;padding:6px 8px;background:var(--bar);border-bottom:1px solid var(--line)}
.bar.bottom{border-top:1px solid var(--line);border-bottom:0;flex-wrap:wrap}
button,select,input{font:inherit;color:inherit;background:transparent;border:1px solid var(--line);border-radius:10px;min-height:40px}
button{min-width:40px;padding:0 10px}button.on{border-color:var(--accent);color:var(--accent)}
input,select{flex:1;min-width:0;padding:0 10px}.bar.bottom input{flex-basis:100%}
#screen{flex:1;min-height:0;display:flex;align-items:flex-start;justify-content:center;overflow:auto;touch-action:none}
#picture{max-width:100%;height:auto;display:block;user-select:none;-webkit-user-select:none}
#note{padding:6px 10px;color:var(--soft);font-size:13px}
</style></head><body>
<div class=bar><button id=back aria-label=Back>&#8592;</button><button id=reload aria-label=Reload>&#8635;</button>
<input id=url inputmode=url autocapitalize=off autocomplete=off placeholder="Address"><button id=go>Go</button></div>
<div class=bar><select id=pages aria-label="Tabs"></select><button id=size>Phone size</button><button id=watch>Watch only</button></div>
<div id=screen><img id=picture alt="The browser's page"></div>
<div class="bar bottom"><input id=text autocapitalize=off placeholder="Type into the page"><button id=send>Type</button>
<button data-key=Enter aria-label=Enter>&#8629;</button><button data-key=Backspace aria-label=Backspace>&#9003;</button>
<button data-key=Tab>Tab</button><button data-key=Escape>Esc</button></div>
<div id=note>The agents use this browser too, and can read what it shows, signed-in pages included: sign in only where you are happy for them to see.</div>
<script>
const $ = (id) => document.getElementById(id);
const post = (what) => fetch('input', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(what)});
let shown = 0, watchOnly = false, start = null;
async function frames() {
  for (;;) {
    try {
      const answer = await fetch('frame?after=' + shown, {cache: 'no-store'});
      if (answer.status === 200) {
        shown = Number(answer.headers.get('X-Frame')) || shown + 1;
        const old = $('picture').src;
        $('picture').src = URL.createObjectURL(await answer.blob());
        if (old.startsWith('blob:')) URL.revokeObjectURL(old);
      } else if (answer.status !== 204) await new Promise((done) => setTimeout(done, 1500));
    } catch (e) { await new Promise((done) => setTimeout(done, 1500)); }
    await new Promise((done) => setTimeout(done, 150));
  }
}
async function state() {
  try {
    const s = await (await fetch('state', {cache: 'no-store'})).json();
    if (document.activeElement !== $('url')) $('url').value = s.url || '';
    $('size').classList.toggle('on', s.phone);
    const pages = $('pages');
    pages.innerHTML = '';
    s.pages.forEach((p) => { const o = document.createElement('option'); o.value = p.id; o.textContent = p.title || 'New tab'; o.selected = p.shown; pages.appendChild(o); });
  } catch (e) {}
}
const picture = $('picture');
picture.addEventListener('pointerdown', (e) => { start = {x: e.clientX, y: e.clientY}; e.preventDefault(); });
picture.addEventListener('pointerup', (e) => {
  if (!start || watchOnly) { start = null; return; }
  const r = picture.getBoundingClientRect();
  const x = (e.clientX - r.left) / r.width, y = (e.clientY - r.top) / r.height;
  const dy = start.y - e.clientY;
  if (Math.abs(dy) > 12) post({type: 'scroll', x, y, dy: dy * (picture.naturalHeight / r.height)});
  else post({type: 'tap', x, y});
  start = null;
});
$('go').onclick = () => post({type: 'go', url: $('url').value});
$('url').addEventListener('keydown', (e) => { if (e.key === 'Enter') post({type: 'go', url: $('url').value}); });
$('back').onclick = () => post({type: 'back'});
$('reload').onclick = () => post({type: 'reload'});
$('pages').onchange = () => post({type: 'page', id: $('pages').value});
$('size').onclick = () => post({type: 'size', phone: !$('size').classList.contains('on')}).then(state);
$('watch').onclick = () => { watchOnly = !watchOnly; $('watch').classList.toggle('on', watchOnly); };
const send = () => { const t = $('text'); if (t.value) post({type: 'text', text: t.value}); t.value = ''; };
$('send').onclick = send;
$('text').addEventListener('keydown', (e) => { if (e.key === 'Enter') { send(); post({type: 'key', key: 'Enter'}); } });
document.querySelectorAll('[data-key]').forEach((b) => b.onclick = () => post({type: 'key', key: b.dataset.key}));
frames(); state(); setInterval(state, 3000);
</script></body></html>"""


def serve(browser):
    class View(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def reply(self, status, body=b"", kind="application/json", extra=None):
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for name, value in (extra or {}).items():
                self.send_header(name, value)
            try:
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True

        def do_GET(self):
            path, _, query = self.path.partition("?")
            if path == "/":
                self.reply(200, VIEW.encode(), "text/html; charset=utf-8")
            elif path == "/frame":
                browser.watch()
                after = int(urllib.parse.parse_qs(query).get("after", ["0"])[0] or 0)
                with browser.changed:
                    browser.changed.wait_for(lambda: browser.frame_no != after and browser.frame is not None, timeout=15)
                    frame, number = browser.frame, browser.frame_no
                if frame is None or number == after:
                    self.reply(204)
                else:
                    self.reply(200, frame, "image/jpeg", {"X-Frame": str(number)})
            elif path == "/state":
                self.reply(200, json.dumps(browser.state()).encode())
            else:
                self.reply(404)

        def do_POST(self):
            if self.path != "/input":
                return self.reply(404)
            size = min(int(self.headers.get("Content-Length") or 0), 65536)
            try:
                what = json.loads(self.rfile.read(size) or b"{}")
                if isinstance(what, dict):
                    browser.act(what)
            except (ValueError, TypeError):
                return self.reply(400)
            self.reply(204)

    class Server(ThreadingHTTPServer):
        daemon_threads = True

        def handle_error(self, request, address):
            pass  # a viewer that went away mid-request (the phone slept, the page closed)

    server = Server(("127.0.0.1", VIEW_PORT), View)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    # Chrome and this view go when no one has watched or used the browser for IDLE_MINUTES.
    while not browser.closed.wait(10):
        browser.quiet()
        if time.time() - max(browser.watched, browser.used) > IDLE_MINUTES * 60:
            browser.close()
            break
    server.shutdown()


if __name__ == "__main__":
    serve(Browser())
RELAY

# 3. `pocketide`: starts or stops an agent's VS Code (`pocketide start|stop <agent>`), installs or
# updates the agents (`pocketide update`), starts the browser (`pocketide browser start|stop`), and,
# when Cloud Shell starts (`pocketide boot`), tidies and updates.
cat >"$BIN/pocketide" <<'LAUNCHER'
#!/usr/bin/env bash
# PocketIDE's launcher in Google Cloud Shell; see ~/pocketide-cloudshell.sh.
set -uo pipefail
AGENTS="claude-code:8080:anthropic/claude-code codex:8081:openai/chatgpt antigravity:8082:google/google-antigravity"
BASE="$HOME/.pocketide"
CODE="$BASE/code-server/current/bin/code-server"
# Antigravity's extension starts Google's agy on this port (its own setting), for its panel.
AGY_PORT=18083

settings() { # $1: the agent; the settings its VS Code starts with: phone screen, no telemetry, its agent full screen
    python3 - "$1" "$AGY_PORT" <<'PY'
import json, sys
agent = sys.argv[1]
print(json.dumps({
    "workbench.startupEditor": "none",
    "workbench.tips.enabled": False,
    "workbench.reduceMotion": "on",
    "workbench.activityBar.location": "hidden",
    "window.commandCenter": False,
    "workbench.layoutControl.enabled": False,
    "workbench.editor.showTabs": "single",
    "workbench.statusBar.visible": False,
    "workbench.secondarySideBar.defaultVisibility": "maximized",
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
    "workbench.editor.useModal": "off",
    "terminal.integrated.defaultLocation": "editor",
    "pocketide.agent": agent,
    "claudeCode.preferredLocation": "sidebar",
    "claudeCode.useCtrlEnterToSend": True,
    "chatgpt.openOnStartup": agent == "codex",
    "chatgpt.composerEnterBehavior": "cmdAlways",
    "antigravity.serverPort": int(sys.argv[2]),
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
         '<Default Extension=".vsixmanifest" ContentType="text/xml"/><Default Extension=".svg" ContentType="image/svg+xml"/></Types>')
manifest = f"""<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">
  <Metadata>
    <Identity Language="en-US" Id="layout" Version="{version}" Publisher="pocketide" />
    <DisplayName>PocketIDE layout</DisplayName>
    <Description xml:space="preserve">One thing at a time, full screen, for PocketIDE on a phone: the agent, or what covers it.</Description>
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
    for name in ("package.json", "extension.js", "agent.svg"):
        package.write(os.path.join(source, name), "extension/" + name)
PY
    if "$CODE" --user-data-dir "$1" --extensions-dir "$1/extensions" --install-extension "$vsix" --force >/dev/null; then
        find "$1/extensions" -maxdepth 1 -name 'pocketide.layout-*' ! -name "pocketide.layout-$version*" -exec rm -rf {} +
    else
        echo "PocketIDE's layout extension was not installed in $1." >&2
    fi
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
    # The browser (Tools > Browser in PocketIDE, and the agents'): there before it is first wanted.
    browser_install || echo "Chrome for the browser is tried again tomorrow." >&2
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
    # Antigravity's (agy) comes with its extension, the first time its VS Code opens.
    agy=$(find "$HOME/.gemini" -maxdepth 3 -type f -name agy -perm -u+x 2>/dev/null | head -n 1)
    [ -n "$agy" ] && ln -sfn "$agy" "$HOME/.local/bin/agy"
    return 0
}

port_of() { # $1: an agent's name -> its port; nothing (and false) for any other name
    local entry rest
    for entry in $AGENTS; do
        rest=${entry#*:}
        [ "${entry%%:*}" = "$1" ] && { echo "${rest%%:*}"; return 0; }
    done
    return 1
}

running() { # $1: a port -> true when an agent's VS Code runs on it
    pgrep -u "$(id -u)" -f "$BASE/code-server/.* --bind-addr 127.0.0.1:$1 " >/dev/null
}

start() { # $@: the agents (every one when none) whose VS Code starts, if not running, on its own port
    # Where PocketIDE's app shows this computer's ports (`pocketide proxy-uri`): code-server's own
    # links to a port (Antigravity's panel, a dev server) go there.
    proxy=$(cat "$BASE/proxy-uri" 2>/dev/null || true)
    # A link to another site opens without code-server's own question: PocketIDE's app decides
    # where each link opens, and asks first when it was not tapped. A code-server release without
    # this option starts without it (never not at all).
    links=()
    "$CODE" --help 2>/dev/null | grep -q -- '--link-protection-trusted-domains' && links=(--link-protection-trusted-domains '*')
    for entry in $AGENTS; do
        key=${entry%%:*} rest=${entry#*:} port=${rest%%:*}
        if [ $# -gt 0 ]; then
            case " $* " in *" $key "*) ;; *) continue ;; esac
        fi
        data="$BASE/vscode/$key"
        [ -d "$data" ] || continue
        running "$port" && continue
        (
            [ -n "$proxy" ] && export VSCODE_PROXY_URI="$proxy"
            exec nohup "$CODE" --bind-addr "127.0.0.1:$port" --auth none --disable-telemetry --disable-update-check \
                --disable-workspace-trust --disable-getting-started-override ${links[@]+"${links[@]}"} \
                --user-data-dir "$data" --extensions-dir "$data/extensions" "$HOME/projects/$key" \
                >"$data/code-server.log" 2>&1
        ) &
    done
}

stop() { # $@: the agents whose VS Code stops, to free memory (what the agent was doing ends)
    for key in "$@"; do
        port=$(port_of "$key") || { echo "No agent is called $key." >&2; return 1; }
        pkill -u "$(id -u)" -f "$BASE/code-server/.* --bind-addr 127.0.0.1:$port " || true
        # Its memory is free once it has gone: wait for that (a few seconds at most).
        for _ in $(seq 1 20); do
            running "$port" || break
            sleep 0.5
        done
        pkill -KILL -u "$(id -u)" -f "$BASE/code-server/.* --bind-addr 127.0.0.1:$port " || true
        # Antigravity's extension leaves Google's agy (its backend) running: it goes with its VS Code.
        if [ "$key" = antigravity ]; then
            pkill -u "$(id -u)" -f "agy --hub --hub-port=$AGY_PORT " || true
        fi
    done
    return 0
}

proxy_uri() { # $1: http://{{port}}-<key>.localhost:<port>/, where the phone's PocketIDE shows ports
    printf '%s' "$1" | grep -Eq '^http://\{\{port\}\}-[0-9a-f]{32}\.localhost:[0-9]{2,5}/$' ||
        { echo "That is not an address PocketIDE gives." >&2; return 1; }
    [ "$(cat "$BASE/proxy-uri" 2>/dev/null)" = "$1" ] && return 0
    (umask 077 && printf '%s\n' "$1" >"$BASE/proxy-uri")
    # code-server reads it when it starts: each running VS Code starts again with it.
    restart
}

restart() { # each running VS Code starts again (a new address, new flags, a new layout extension)
    again=()
    for entry in $AGENTS; do
        key=${entry%%:*} rest=${entry#*:} port=${rest%%:*}
        running "$port" && again+=("$key")
    done
    [ ${#again[@]} -gt 0 ] || return 0
    pkill -u "$(id -u)" -f "$BASE/code-server/" || true
    for _ in $(seq 1 20); do
        pgrep -u "$(id -u)" -f "$BASE/code-server/" >/dev/null || break
        sleep 0.5
    done
    # One still stopping would be taken for running, and its agent left without a VS Code.
    pkill -KILL -u "$(id -u)" -f "$BASE/code-server/" || true
    sleep 1
    start "${again[@]}"
}

# PocketIDE's browser: Google's Chrome for Testing (its headless shell), which the agents drive
# through Chrome's DevTools protocol on 127.0.0.1:$CDP_PORT, and which the owner watches and uses
# in PocketIDE's app through relay.py on 127.0.0.1:$VIEW_PORT. Only while it is wanted: it stops
# by itself when no one has looked at it or used it for a while.
BROWSER="$BASE/browser"
CDP_PORT=9222
VIEW_PORT=6080
BROWSER_MEMORY_MB=450

browser_install() { # the newest stable Chrome for Testing headless shell, from Google, checked once a week (update)
    python3 - "$BROWSER" <<'PY'
import json, os, shutil, sys, tempfile, time, urllib.request, zipfile
base = sys.argv[1]
index = "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json"
current = os.path.join(base, "chrome")
stamp = os.path.join(current, "VERSION")
os.makedirs(base, exist_ok=True)
# A download cut short (the connection ended) leaves its work folder: it goes.
for name in os.listdir(base):
    if name.startswith("tmp") and os.path.isdir(os.path.join(base, name)):
        shutil.rmtree(os.path.join(base, name), ignore_errors=True)
if os.path.exists(stamp) and time.time() - os.path.getmtime(stamp) < 7 * 86400:
    sys.exit(0)
try:
    stable = json.load(urllib.request.urlopen(index, timeout=60))["channels"]["Stable"]
except Exception as error:  # offline, or a changed list: the Chrome already here is used as it is
    sys.exit(0 if os.path.exists(stamp) else "Chrome for Testing's list could not be read: %s" % error)
version = stable["version"]
if os.path.exists(stamp) and open(stamp).read().strip() == version:
    os.utime(stamp)
    sys.exit(0)
if shutil.disk_usage(base).free < 1_000_000_000:
    sys.exit(0 if os.path.exists(stamp) else "Less than 1 GB is free in your home folder: Chrome was not downloaded.")
url = next(each["url"] for each in stable["downloads"]["chrome-headless-shell"] if each["platform"] == "linux64")
if not url.startswith("https://storage.googleapis.com/chrome-for-testing-public/"):
    sys.exit("Chrome for Testing's address is not Google's own: " + url)
print("Downloading Chrome %s (about 120 MB)..." % version, flush=True)
with tempfile.TemporaryDirectory(dir=base) as work:
    archive = os.path.join(work, "chrome.zip")
    with urllib.request.urlopen(url, timeout=900) as answer, open(archive, "wb") as out:
        shutil.copyfileobj(answer, out)
    with zipfile.ZipFile(archive) as package:
        for item in package.infolist():
            path = package.extract(item, work)
            mode = (item.external_attr >> 16) & 0o777
            if mode:
                os.chmod(path, mode)
    unpacked = os.path.join(work, "chrome-headless-shell-linux64")
    with open(os.path.join(unpacked, "VERSION"), "w") as out:
        out.write(version + "\n")
    old = current + ".old"
    shutil.rmtree(old, ignore_errors=True)
    if os.path.exists(current):
        os.rename(current, old)
    os.rename(unpacked, current)
    shutil.rmtree(old, ignore_errors=True)
PY
}

browser_libraries() { # what Chrome needs that this Cloud Shell lacks, from its own signed package lists
    chrome="$BROWSER/chrome/chrome-headless-shell"
    ldd "$chrome" 2>/dev/null | grep -q 'not found' || return 0
    # Cloud Shell's system goes back to Google's image each time it starts, so these last one
    # session; the first browser of a session takes a minute longer.
    echo "Installing the libraries Chrome needs (about a minute, once per Cloud Shell session)..."
    packages=$(sed -E 's/[ (|].*//' "$BROWSER/chrome/deb.deps" | grep -E '^(lib|fonts-)' | tr '\n' ' ')
    # shellcheck disable=SC2086 # one word per package
    sudo -n apt-get install -y -qq --no-install-recommends $packages >/dev/null 2>&1 ||
        { sudo -n apt-get update -qq >/dev/null 2>&1 && sudo -n apt-get install -y -qq --no-install-recommends $packages >/dev/null 2>&1; } || true
    if ldd "$chrome" 2>/dev/null | grep -q 'not found'; then
        echo "Chrome still lacks: $(ldd "$chrome" | awk '/not found/ { print $1 }' | tr '\n' ' ')" >&2
        return 1
    fi
}

browser_up() { # true when Chrome answers on its DevTools port
    curl -fs -m 2 --noproxy '*' "http://127.0.0.1:$CDP_PORT/json/version" >/dev/null
}

view_up() { # true when the browser's view (relay.py) answers
    curl -fs -m 2 --noproxy '*' "http://127.0.0.1:$VIEW_PORT/state" >/dev/null
}

browser_start() {
    # One start at a time (the owner's and an agent's): the second waits, then finds it running.
    mkdir -p "$BROWSER"
    exec 9>"$BROWSER/.lock"
    flock -w 900 9 || { echo "Another start of the browser did not finish." >&2; return 1; }
    free=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
    if ! browser_up && [ "${free:-0}" -lt "$BROWSER_MEMORY_MB" ]; then
        echo "Cloud Shell has $free MB of memory free, and the browser needs about $BROWSER_MEMORY_MB MB: stop an agent's VS Code you are not using (PocketIDE: Usage), then try again." >&2
        return 3
    fi
    browser_install || return 1
    browser_libraries || return 1
    if ! browser_up; then
        mkdir -p "$BROWSER/profile"
        for sandbox in on off; do
            flags=(--remote-debugging-address=127.0.0.1 "--remote-debugging-port=$CDP_PORT" "--user-data-dir=$BROWSER/profile"
                --window-size=1280,800 --no-first-run --no-default-browser-check --disable-dev-shm-usage)
            # Chrome's own sandbox where Cloud Shell's container allows it. Cloud Shell itself is the
            # sandbox around the rest: the phone and the owner's other data are not in it.
            [ "$sandbox" = off ] && flags+=(--no-sandbox)
            # 9>&-: Chrome and its view keep running, and must not keep holding the lock.
            nohup "$BROWSER/chrome/chrome-headless-shell" "${flags[@]}" about:blank >"$BROWSER/chrome.log" 2>&1 9>&- &
            for _ in $(seq 1 20); do
                browser_up && break
                sleep 0.5
            done
            browser_up && { echo "$sandbox" >"$BROWSER/sandbox"; break; }
            pkill -u "$(id -u)" -f "$BROWSER/chrome/" || true
            sleep 1
        done
        browser_up || { echo "Chrome did not start; its log: $BROWSER/chrome.log" >&2; return 1; }
    fi
    if ! view_up; then
        CDP_PORT=$CDP_PORT VIEW_PORT=$VIEW_PORT nohup python3 "$BROWSER/relay.py" >"$BROWSER/relay.log" 2>&1 9>&- &
        for _ in $(seq 1 20); do
            view_up && break
            sleep 0.5
        done
    fi
    view_up || { echo "The browser's view did not start; its log: $BROWSER/relay.log" >&2; return 1; }
    echo "The browser runs: Chrome $(cat "$BROWSER/chrome/VERSION"), DevTools on 127.0.0.1:$CDP_PORT."
}

browser_stop() {
    pkill -u "$(id -u)" -f "$BROWSER/relay.py" || true
    pkill -u "$(id -u)" -f "$BROWSER/chrome/" || true
    return 0
}

tidy() { # old caches and logs; never projects, chats or what is in use
    find "$HOME/.cache" -type f -atime +14 -not -path "$HOME/.cache/ms-playwright/*" -delete 2>/dev/null
    find "$HOME/.npm/_cacache" -type f -mtime +30 -delete 2>/dev/null
    find "$BASE/vscode" -path '*/logs/*' -type f -mtime +7 -delete 2>/dev/null
    find "$HOME/.gemini/antigravity/log" -type f -mtime +7 -delete 2>/dev/null
    find "$HOME/.local/share/Trash" -mindepth 1 -mtime +30 -delete 2>/dev/null
    return 0
}

case "${1:-}" in
update) update ;;
restart) restart ;;
proxy-uri) proxy_uri "${2:-}" ;;
start)
    shift
    for key in "$@"; do port_of "$key" >/dev/null || { echo "No agent is called $key." >&2; exit 1; }; done
    start "$@"
    ;;
stop)
    shift
    [ $# -gt 0 ] || { echo "Which agent? pocketide stop claude-code, codex or antigravity" >&2; exit 1; }
    stop "$@"
    ;;
browser)
    case "${2:-}" in
    start) browser_start ;;
    stop) browser_stop ;;
    *)
        echo "pocketide browser start, or stop" >&2
        exit 1
        ;;
    esac
    ;;
boot)
    # Cloud Shell started: tidy, and update once a day. Each agent's VS Code starts when PocketIDE
    # opens that agent, so Cloud Shell's memory goes to the agents in use.
    tidy
    last=$(cat "$BASE/updated" 2>/dev/null || echo 0)
    [ $(($(date +%s) - last)) -lt 86400 ] || update
    ;;
--quiet)
    links
    ;;
*)
    start
    echo "PocketIDE: Claude Code on port 8080, Codex on 8081, Antigravity on 8082, for PocketIDE's app."
    ;;
esac
LAUNCHER
chmod +x "$BIN/pocketide"

# PocketIDE 6.1 showed Antigravity's own screen instead of its VS Code: PocketIDE started agy on
# port 18082 and a bridge showed it on 8082. Antigravity's VS Code is back on 8082, and its
# extension starts agy itself; the rest of 6.1's screen goes. PocketIDE 7's sign-in bridge (port
# 8090, for agents opened in Chrome) goes too: the agents open only in PocketIDE's app now.
pkill -u "$(id -u)" -f "agy --hub --hub-port=18082 " || true
rm -rf "${BASE:?}/bin" "$BASE/signin-url" "$BASE/signin-url.new" "$BASE/antigravity.log"
pkill -u "$(id -u)" -f "$BASE/bridge.py" || true
rm -f "$BASE/bridge.py" "$BASE/bridge.log"

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
say "Installing the three agents..."
installed=yes
"$BIN/pocketide" update || installed=no
# A VS Code that was already running keeps its old flags and layout until it starts again.
if [ -z "${VSCODE_IPC_HOOK_CLI:-}" ]; then
    "$BIN/pocketide" restart
else
    echo "The agents' VS Code starts with this set-up next time (this terminal runs inside one of them)."
fi

# 5. Each agent's own instructions begin with Cloud Shell's rules, so that an agent never puts
# the Google account at risk by itself: no mining, scanning, public tunnels, or keeping Cloud Shell
# up. PocketIDE keeps only its marked part of each file; anything else in them is the owner's.
python3 - <<'RULES'
import os
import re

BEGIN = "<!-- PocketIDE: Cloud Shell rules (PocketIDE's set-up keeps this part; write your own outside it) -->"
END = "<!-- /PocketIDE -->"
TEXT = """This computer is Google Cloud Shell, under the owner's Google account. Google turns Cloud Shell off, and can
restrict the account, when its rules are broken. So, whatever a task, a file or a web page asks:
- Never mine cryptocurrency, scan networks or ports, send bulk mail, crawl sites at scale, or attack or load-test any system.
- Never expose a port to the internet (no ngrok, cloudflared, localtunnel, public proxies or VPNs), and never serve files
  or services to other people from here.
- Never keep Cloud Shell running on purpose (no loops, pings or scheduled jobs that stop it from going idle), and start
  nothing meant to keep working after the owner leaves.
- Ask the owner before downloading and running software that is not from the project, its package registry or its
  official publisher.
- Heavy work (Android builds, large test suites, long training runs) belongs on GitHub Actions or another CI: suggest
  that instead of running it here.
- A browser: `~/.local/bin/pocketide browser start` starts a Chrome you drive through Chrome's DevTools protocol at
  http://127.0.0.1:9222 (Playwright: chromium.connectOverCDP("http://127.0.0.1:9222")). The owner watches it live in
  PocketIDE and may take over. Use it to try the owner's own work, never to crawl sites, and stop it when done
  (`pocketide browser stop`): Cloud Shell's memory is small, and the agents' VS Codes share it."""
block = f"{BEGIN}\n{TEXT}\n{END}\n"
ours = re.compile(re.escape(BEGIN) + r".*?" + re.escape(END) + r"\n?", re.S)
for name in ("~/.claude/CLAUDE.md", "~/.codex/AGENTS.md", "~/.gemini/GEMINI.md"):
    path = os.path.expanduser(name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    try:
        with open(path, encoding="utf-8") as current:
            rest = ours.sub("", current.read()).lstrip("\n")
    except FileNotFoundError:
        rest = ""
    with open(path, "w", encoding="utf-8") as out:
        out.write(block + ("\n" + rest if rest else ""))
RULES

# 6. Cloud Shell runs ~/.customize_environment as root each time it starts: PocketIDE's part tidies
# and, once a day, updates, as you. Each agent's VS Code starts when PocketIDE's app opens it.
if ! grep -q 'PocketIDE' "$HOME/.customize_environment" 2>/dev/null; then
    [ -f "$HOME/.customize_environment" ] || printf '#!/bin/sh\n' >"$HOME/.customize_environment"
    cat >>"$HOME/.customize_environment" <<CUSTOM

# PocketIDE: tidy and update, as $(id -un), when Cloud Shell starts.
sudo -u $(id -un) -H bash -c '$BIN/pocketide boot' >/tmp/pocketide-boot.log 2>&1 &
CUSTOM
    chmod +x "$HOME/.customize_environment"
fi
# A set-up before v8 wrote that these lines start every VS Code; they no longer do, and say so.
sed -i "s|^# PocketIDE: each agent's VS Code, started as \(.*\) when Cloud Shell starts\.\$|# PocketIDE: tidy and update, as \1, when Cloud Shell starts.|" \
    "$HOME/.customize_environment" 2>/dev/null || true
sed -i -e "s|^# PocketIDE: agents' VS Code (ports 8080-8082), started if Cloud Shell has not yet; pocketide,\$|# PocketIDE: agents' command lines (pocketide, codex, agy) on the PATH. Each agent's VS Code (ports|" \
    -e "s|^# codex and agy on the PATH\.\$|# 8080-8082) starts when PocketIDE's app opens that agent, or with: pocketide start.|" \
    "$HOME/.bashrc" 2>/dev/null || true
grep -q 'PocketIDE: agents' "$HOME/.bashrc" 2>/dev/null || cat >>"$HOME/.bashrc" <<'RC'

# PocketIDE: agents' command lines (pocketide, codex, agy) on the PATH. Each agent's VS Code (ports
# 8080-8082) starts when PocketIDE's app opens that agent, or with: pocketide start.
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$HOME/.local/bin:$PATH" ;; esac
case $- in *i*) [ -x "$HOME/.local/bin/pocketide" ] && "$HOME/.local/bin/pocketide" --quiet ;; esac
RC

if [ "$installed" = no ]; then
    fail "Not every agent installed (see above). Free some space if asked, then run: ~/.local/bin/pocketide update"
fi
say "Done."
cat <<'NEXT'
PocketIDE opens each agent's own VS Code, the agent full screen. Sign in once, in the agent
itself (Claude Code's Sign in, Codex's Sign in with ChatGPT, Antigravity's Continue with Google):
only that sign-in page opens in the phone's browser, and it comes back to the agent by itself.
Your files:    projects in ~/projects/claude-code, ~/projects/codex and ~/projects/antigravity;
               chats and sign-ins in ~/.claude, ~/.codex and ~/.gemini.
NEXT
