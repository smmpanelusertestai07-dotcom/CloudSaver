#!/usr/bin/env bash
# PocketIDE for Google Cloud Shell. Sets up, only in your Cloud Shell home folder (the one part
# Cloud Shell keeps, 5 GB), each official agent on its own port, with its own projects folder:
#   Claude Code  port 8080  ~/projects/claude-code  in its own VS Code (code-server)
#   Codex        port 8081  ~/projects/codex        in its own VS Code (code-server)
#   Antigravity  port 8082  ~/projects/antigravity  Google's own Antigravity screen (agy)
# From then on Cloud Shell starts them by itself when it starts, tidies old caches, logs and
# 30-day-old Codex chats (never your projects), and once a day installs newer releases of the
# agents and of code-server (a code-server release only once it is a week old), each checked.
# A small bridge (reached only through Web Preview) shows Antigravity's screen, which Google's agy
# serves only to localhost, and lets an agent that signs in with a browser finish on a phone:
# PocketIDE hands it the sign-in page's return to localhost (port 8090).
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
# opens that VS Code's agent full screen, in the secondary side bar, maximized, and puts Tools
# (files, terminal, extensions...) and the keys a phone keyboard lacks in the status bar.
# Each VS Code installs it once for each version: raise the version when either file changes.
mkdir -p "$BASE/layout"
cat >"$BASE/layout/package.json" <<'JSON'
{
  "name": "layout",
  "displayName": "PocketIDE layout",
  "description": "Opens this VS Code's agent full screen, with the keys and tools a phone lacks.",
  "version": "6.1.0",
  "publisher": "pocketide",
  "license": "Apache-2.0",
  "engines": { "vscode": "^1.94.0" },
  "categories": ["Other"],
  "activationEvents": ["onStartupFinished"],
  "main": "./extension.js",
  "extensionKind": ["workspace"],
  "capabilities": { "untrustedWorkspaces": { "supported": true }, "virtualWorkspaces": true },
  "contributes": {
    "commands": [
      { "command": "pocketide.tools", "title": "PocketIDE: Tools" },
      { "command": "pocketide.keys", "title": "PocketIDE: Show or hide the keys" },
      { "command": "pocketide.agent", "title": "PocketIDE: The agent, full screen" },
      { "command": "pocketide.vsix", "title": "PocketIDE: Install an extension from a link (.vsix)" }
    ],
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
const https = require('https');
const os = require('os');
const path = require('path');

// The command that opens each agent's own panel.
const AGENTS = {
  'claude-code': { open: ['claude-vscode.sidebar.open'] },
  codex: { open: ['chatgpt.openSidebar'] },
};
const WAIT_MS = 90000;
const STEP_MS = 1000;
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

// Keys a phone keyboard lacks, for the terminal: a keyboard button in the status bar shows them,
// and hides them again.
const KEYS = [
  ['Esc', '\u001b'], ['Tab', '\t'], ['↑', '\u001b[A'], ['↓', '\u001b[B'],
  ['←', '\u001b[D'], ['→', '\u001b[C'], ['⏎', '\r'], ['^C', '\u0003'],
];

// Everything the hidden activity bar held, and the agent again.
const TOOLS = [
  { label: '$(hubot) Agent', detail: 'Back to the agent, full screen', command: 'pocketide.agent' },
  { label: '$(terminal) Terminal', detail: 'A command line in this project', command: 'workbench.action.terminal.focus', restore: true },
  { label: '$(files) Files', detail: 'This project\'s files', command: 'workbench.view.explorer', restore: true },
  { label: '$(search) Search', detail: 'Search the project', command: 'workbench.view.search', restore: true },
  { label: '$(source-control) Git', detail: 'Changes and commits', command: 'workbench.view.scm', restore: true },
  { label: '$(extensions) Extensions', detail: 'Add or update extensions from Open VSX', command: 'workbench.view.extensions', restore: true },
  { label: '$(cloud-download) Install from a link', detail: 'An extension (.vsix) Open VSX does not have, from its maker', command: 'pocketide.vsix' },
  { label: '$(gear) Settings', detail: 'This VS Code\'s settings', command: 'workbench.action.openSettings' },
  { label: '$(list-flat) All commands', detail: 'Everything VS Code can do', command: 'workbench.action.showCommands' },
];

async function openAgent(agent) {
  if (!(await ready(agent.open))) return;
  for (const command of agent.open) await run(command);
  await run('workbench.action.maximizeAuxiliaryBar');
}

function statusBar(context, agent) {
  const add = (text, tooltip, command, priority) => {
    const item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, priority);
    item.text = text;
    item.tooltip = tooltip;
    item.command = command;
    context.subscriptions.push(item);
    return item;
  };
  add('$(menu) Tools', 'PocketIDE tools: the agent, terminal, files, extensions', 'pocketide.tools', 2000).show();
  add('$(keyboard)', 'Keys: Esc, Tab, arrows, Enter, Ctrl+C', 'pocketide.keys', 1999).show();
  const keys = KEYS.map(([label, sequence], index) =>
    add(label, `Send ${label} to the terminal`, { title: label, command: 'pocketide.key', arguments: [sequence] }, 1998 - index));
  let shown = false;
  context.subscriptions.push(
    vscode.commands.registerCommand('pocketide.keys', () => {
      shown = !shown;
      keys.forEach((item) => (shown ? item.show() : item.hide()));
    }),
    vscode.commands.registerCommand('pocketide.key', (sequence) => {
      const terminal = vscode.window.activeTerminal;
      if (terminal) {
        terminal.sendText(sequence, false);
      } else {
        vscode.window.showInformationMessage('The keys go to a terminal: open one from Tools > Terminal.');
      }
    }),
    vscode.commands.registerCommand('pocketide.agent', () => (agent ? openAgent(agent) : undefined)),
    vscode.commands.registerCommand('pocketide.vsix', installFromLink),
    vscode.commands.registerCommand('pocketide.tools', async () => {
      const picked = await vscode.window.showQuickPick(TOOLS, { placeHolder: 'PocketIDE tools' });
      if (!picked) return;
      if (picked.restore) await run('workbench.action.restoreAuxiliaryBar');
      await run(picked.command);
    }),
  );
}

async function activate(context) {
  const agent = AGENTS[vscode.workspace.getConfiguration('pocketide').get('agent', '')];
  statusBar(context, agent);
  if (agent) await openAgent(agent);
}

module.exports = { activate, deactivate() {} };
JS

# PocketIDE's bridge: Antigravity's own screen, and the sign-in return (see its own words).
cat >"$BASE/bridge.py" <<'BRIDGE'
#!/usr/bin/env python3
"""PocketIDE's bridge in Cloud Shell, reached only through Cloud Shell's Web Preview, which only
the owner's Google account can open. It has two jobs.

Antigravity's screen (port 8082). Google's Antigravity program, agy, serves its own screen, but
only to http://localhost. The bridge passes each request on to it as localhost, and at /pocketide/
shows that screen with a bar that offers Google's sign-in page when agy asks to open it (agy would
open it in a browser on this computer, which has no screen).

Sign-in return (port 8090). An agent that signs in with a browser (Codex's Sign in with ChatGPT,
Antigravity's Continue with Google) waits for the sign-in page to return to http://localhost:PORT
here in Cloud Shell. On a phone that return lands on the phone, where nothing waits, so Chrome says
"localhost refused". PocketIDE opens this bridge with that same address instead, and the bridge
hands it to the agent waiting here, as localhost. Only GET, only this computer's ports 1024-65535.

Nothing it passes on is written to a log.
"""
import asyncio
import base64
import http.client
import http.server
import json
import os
import re
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RETURN_PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
SCREEN_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8082
AGY_PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 18082
PREFIX = "/pocketide/callback/"
PASSED = ("content-type", "content-length", "cache-control")
SIGNIN_FILE = os.path.join(HERE, "signin-url")
SIGNIN_FRESH_SECONDS = 600
RETRY_SECONDS = 10
AGY_HOST = b"localhost:%d" % AGY_PORT
DROPPED = (b"x-forwarded-for", b"x-forwarded-host", b"x-forwarded-proto", b"forwarded")
TO_LOCALHOST = re.compile(rb"^(location:[ \t]*)https?://(?:localhost|127\.0\.0\.1):%d" % AGY_PORT, re.I | re.M)

# Antigravity's screen in a frame, with the bar that opens Google's sign-in page on the phone:
# through PocketIDE, which finishes the sign-in by itself, or, without PocketIDE, in the browser.
PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="light dark"><title>Antigravity</title>
<style>
html,body{margin:0;height:100%;background:#fff}
@media (prefers-color-scheme:dark){html,body{background:#131314}}
iframe{display:block;width:100%;height:100%;border:0}
#bar{display:none;position:fixed;left:12px;right:12px;bottom:max(12px,env(safe-area-inset-bottom));
padding:14px 16px;border-radius:16px;background:#0b57d0;color:#fff;text-align:center;
text-decoration:none;font:600 16px/1.35 system-ui,sans-serif;box-shadow:0 6px 20px rgba(0,0,0,.35)}
#bar small{display:block;font-weight:400;font-size:13px;opacity:.9}
</style></head><body>
<iframe src="/" title="Antigravity" allow="clipboard-read; clipboard-write"></iframe>
<a id="bar" href="#">Continue signing in with Google<small>Opens Google's sign-in page</small></a>
<script>
const bar = document.getElementById('bar');
const seen = () => { try { return sessionStorage.getItem('pocketide.signin') || ''; } catch (e) { return ''; } };
async function check() {
  try {
    const { url, id } = await (await fetch('/pocketide/signin', { cache: 'no-store' })).json();
    if (!url || id === seen()) { bar.style.display = 'none'; return; }
    const back = encodeURIComponent(url);
    bar.href = 'intent://signin?u=' + back + '#Intent;scheme=pocketide;package=com.pocketide;S.browser_fallback_url=' + back + ';end';
    bar.onclick = () => { try { sessionStorage.setItem('pocketide.signin', id); } catch (e) {} bar.style.display = 'none'; };
    bar.style.display = 'block';
  } catch (e) {}
}
check();
setInterval(check, 1500);
</script></body></html>
"""

STARTING = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta http-equiv="refresh" content="3">
<meta name="viewport" content="width=device-width, initial-scale=1"><meta name="color-scheme" content="light dark">
<title>Antigravity</title></head>
<body style="font:16px/1.5 system-ui,sans-serif;padding:24px">
<p><b>Antigravity is starting in Cloud Shell.</b></p>
<p>This page tries again by itself every few seconds. If it stays here, run <code>pocketide update</code>
in Cloud Shell's terminal.</p></body></html>
"""


def decode(token):
    return base64.urlsafe_b64decode((token + "=" * (-len(token) % 4)).encode("ascii")).decode("utf-8")


def encode(text):
    return base64.urlsafe_b64encode(text.encode("utf-8")).decode("ascii").rstrip("=")


class Return(http.server.BaseHTTPRequestHandler):
    """The sign-in return: GET /pocketide/callback/<port>/<the address after localhost, base64url>."""

    server_version = "PocketIDE"
    sys_version = ""

    def do_GET(self):  # noqa: N802 (the name http.server calls)
        if self.path == "/pocketide/health":
            return self.reply(200, "ok")
        if not self.path.startswith(PREFIX):
            return self.reply(404, "This is PocketIDE's sign-in bridge; it only finishes sign-ins.")
        try:
            port_text, token = self.path[len(PREFIX):].split("/", 1)
            port = int(port_text)
            target = decode(token.split("?", 1)[0])
        except (ValueError, UnicodeDecodeError):
            return self.reply(400, "This sign-in link is not complete.")
        if not 1024 <= port <= 65535 or port == RETURN_PORT or not target.startswith("/") or target.startswith("//"):
            return self.reply(400, "This sign-in link is not complete.")
        try:
            upstream = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
            upstream.request("GET", target, headers={
                "Host": f"localhost:{port}",
                "User-Agent": self.headers.get("User-Agent", "PocketIDE"),
                "Accept": self.headers.get("Accept", "*/*"),
            })
            answer = upstream.getresponse()
            body = answer.read()
        except OSError:
            return self.reply(502, "Nothing waits for this sign-in in Cloud Shell any more. Start the sign-in again in the agent.")
        self.send_response(answer.status)
        for name, value in answer.getheaders():
            lowered = name.lower()
            if lowered == "location":
                self.send_header("Location", self.moved(value, port))
            elif lowered in PASSED:
                self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def moved(self, location, port):
        """A redirect back to the agent's own address stays on this bridge."""
        for origin in (f"http://localhost:{port}", f"http://127.0.0.1:{port}"):
            if location.startswith(origin + "/") or location == origin:
                location = location[len(origin):] or "/"
                break
        if location.startswith("/") and not location.startswith("//"):
            return PREFIX + f"{port}/" + encode(location)
        return location

    def reply(self, status, text):
        body = (text + "\n").encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):  # noqa: A002 (the name http.server uses)
        pass  # sign-in codes never go to a log


def returns():
    while True:
        try:
            http.server.ThreadingHTTPServer(("127.0.0.1", RETURN_PORT), Return).serve_forever()
        except OSError:
            time.sleep(RETRY_SECONDS)


def signin():
    """Google's sign-in page agy last asked to open, while it is fresh."""
    try:
        stat = os.stat(SIGNIN_FILE)
        if time.time() - stat.st_mtime > SIGNIN_FRESH_SECONDS:
            return {}
        with open(SIGNIN_FILE, encoding="utf-8") as handle:
            url = handle.read().strip()
    except OSError:
        return {}
    return {"url": url, "id": str(stat.st_mtime_ns)} if url.startswith("https://") else {}


def to_agy(head):
    """The request as agy takes it: from localhost, one request for each connection."""
    lines = head.split(b"\r\n")
    out, upgrade = [lines[0]], False
    for line in lines[1:]:
        if not line:
            continue
        name, _, value = line.partition(b":")
        key = name.strip().lower()
        if key == b"host":
            line = b"Host: " + AGY_HOST
        elif key == b"origin":
            line = b"Origin: http://" + AGY_HOST
        elif key == b"referer":
            line = b"Referer: " + re.sub(rb"^https?://[^/]+", b"http://" + AGY_HOST, value.strip())
        elif key == b"connection":
            if b"upgrade" not in value.lower():
                continue
            upgrade = True
        elif key in DROPPED:
            continue
        out.append(line)
    if not upgrade:
        out.append(b"Connection: close")
    return b"\r\n".join(out) + b"\r\n\r\n"


async def send(writer, status, kind, body):
    reason = {200: b"OK", 503: b"Service Unavailable"}.get(status, b"")
    writer.write(b"HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nCache-Control: no-store\r\n"
                 b"Connection: close\r\n\r\n" % (status, reason, kind.encode(), len(body)) + body)
    try:
        await writer.drain()
    finally:
        writer.close()


async def pipe(reader, writer, half):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            writer.write(data)
            await writer.drain()
        if half and writer.can_write_eof():
            writer.write_eof()
            return
    except (ConnectionError, OSError):
        pass
    writer.close()


async def screen(reader, writer):
    try:
        head = await reader.readuntil(b"\r\n\r\n")
        path = head.split(b" ", 2)[1].split(b"?", 1)[0]
    except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, ConnectionError, OSError, IndexError):
        writer.close()
        return
    if path in (b"/pocketide", b"/pocketide/"):
        return await send(writer, 200, "text/html; charset=utf-8", PAGE.encode("utf-8"))
    if path == b"/pocketide/signin":
        return await send(writer, 200, "application/json", json.dumps(signin()).encode("utf-8"))
    try:
        agy_reader, agy_writer = await asyncio.open_connection("127.0.0.1", AGY_PORT)
    except OSError:
        return await send(writer, 503, "text/html; charset=utf-8", STARTING.encode("utf-8"))
    agy_writer.write(to_agy(head))
    upload = asyncio.ensure_future(pipe(reader, agy_writer, half=True))
    try:
        answer = await agy_reader.readuntil(b"\r\n\r\n")
        writer.write(TO_LOCALHOST.sub(rb"\1", answer))
        await pipe(agy_reader, writer, half=False)
    except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, ConnectionError, OSError):
        writer.close()
    finally:
        upload.cancel()
        agy_writer.close()


async def screens():
    while True:
        try:
            server = await asyncio.start_server(screen, "127.0.0.1", SCREEN_PORT, limit=1 << 16)
        except OSError:
            await asyncio.sleep(RETRY_SECONDS)
            continue
        async with server:
            await server.serve_forever()


if __name__ == "__main__":
    threading.Thread(target=returns, daemon=True).start()
    asyncio.run(screens())
BRIDGE

# Antigravity opens its sign-in page with the computer's browser; Cloud Shell has none, so for
# Antigravity these keep the page for its screen's bar, which opens it on the phone.
mkdir -p "$BASE/bin"
cat >"$BASE/bin/xdg-open" <<'OPEN'
#!/bin/sh
case "${1:-}" in
https://*)
    umask 077
    printf '%s\n' "$1" >"$HOME/.pocketide/signin-url.new" && mv -f "$HOME/.pocketide/signin-url.new" "$HOME/.pocketide/signin-url"
    ;;
esac
exit 0
OPEN
chmod +x "$BASE/bin/xdg-open"
for name in x-www-browser www-browser sensible-browser; do ln -sfn xdg-open "$BASE/bin/$name"; done

# 3. `pocketide`: starts each agent that is not running, installs or updates the agents
# (`pocketide update`), and, when Cloud Shell starts (`pocketide boot`), tidies and updates too.
cat >"$BIN/pocketide" <<'LAUNCHER'
#!/usr/bin/env bash
# PocketIDE's launcher in Google Cloud Shell; see ~/pocketide-cloudshell.sh.
set -uo pipefail
AGENTS="claude-code:8080:anthropic/claude-code codex:8081:openai/chatgpt"
BASE="$HOME/.pocketide"
CODE="$BASE/code-server/current/bin/code-server"
AGY="$HOME/.gemini/bin/agy"
AGY_PORT=18082

settings() { # $1: the agent; the settings its VS Code starts with: phone screen, no telemetry, its agent full screen
    python3 - "$1" <<'PY'
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

# Google's Antigravity program, agy, as its own extension gets it: the release Google's update
# service names, from Google's storage, checked against the SHA-512 it gives, into ~/.gemini/bin.
# A newer one runs from the next start of Cloud Shell.
agy_update() {
    python3 - "$AGY" <<'PY'
import hashlib, json, os, shutil, subprocess, sys, tarfile, tempfile, urllib.request

MANIFEST = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_amd64.json"
STORAGE = "https://storage.googleapis.com/antigravity-public/"


def key(text):
    return tuple(int(part) for part in text.split("."))


def main(target):
    manifest = json.load(urllib.request.urlopen(MANIFEST, timeout=60))
    version, url, sha512 = str(manifest.get("version", "")), str(manifest.get("url", "")), str(manifest.get("sha512", ""))
    if not version.replace(".", "").isdigit() or not url.startswith(STORAGE) or len(sha512) != 128:
        sys.exit("Google's update service gave no usable Antigravity release; agy stays as it is.")
    try:
        installed = subprocess.run([target, "--version"], capture_output=True, text=True, timeout=60).stdout.strip()
        if key(installed) >= key(version):
            print(f"Antigravity (agy) {installed} is installed.")
            return
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    folder = os.path.dirname(target)
    os.makedirs(folder, exist_ok=True)
    if shutil.disk_usage(folder).free < 1_000_000_000:
        sys.exit("Less than 1 GB is free in your home folder: Antigravity (agy) was not installed.")
    print(f"Installing Antigravity (agy) {version}...")
    with tempfile.TemporaryDirectory(dir=folder) as work:
        archive = os.path.join(work, "agy.tar.gz")
        digest = hashlib.sha512()
        with urllib.request.urlopen(url, timeout=900) as response, open(archive, "wb") as out:
            for chunk in iter(lambda: response.read(1 << 20), b""):
                digest.update(chunk)
                out.write(chunk)
        if digest.hexdigest() != sha512:
            sys.exit("Antigravity's download did not match the SHA-512 Google gives; agy stays as it is.")
        staged = os.path.join(work, "agy")
        with tarfile.open(archive) as bundle:
            member = bundle.getmember("antigravity")
            if not member.isfile():
                sys.exit("Antigravity's download is not what it should be; agy stays as it is.")
            with bundle.extractfile(member) as source, open(staged, "wb") as out:
                shutil.copyfileobj(source, out)
        os.chmod(staged, 0o755)
        os.replace(staged, target)
    print(f"Antigravity (agy) {version} is installed.")


try:
    main(sys.argv[1])
except Exception as error:  # the network, Google or the disk; tried again tomorrow
    sys.exit(f"Antigravity (agy) was not updated: {error}")
PY
}

# The newest release (never a pre-release) of Claude Code and Codex from their verified publishers
# on Open VSX, for this computer or every platform, checked against the SHA-256 Open VSX
# publishes, each into its own VS Code; and Antigravity.
update() {
    code_server_update || echo "code-server is tried again tomorrow." >&2
    missing=""
    mkdir -p "$HOME/projects/antigravity"
    agy_update || missing="$missing agy"
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
    [ -x "$AGY" ] && ln -sfn "$AGY" "$HOME/.local/bin/agy"
    return 0
}

start() { # the bridge, Antigravity and each agent's VS Code that is not running, each on its own port
    if [ -f "$BASE/bridge.py" ] && ! pgrep -u "$(id -u)" -f "$BASE/bridge.py" >/dev/null; then
        nohup python3 "$BASE/bridge.py" >"$BASE/bridge.log" 2>&1 &
    fi
    # Antigravity's own screen: Google's agy, started the way its VS Code extension starts it; its
    # sign-in page goes to the bridge's bar (PocketIDE's xdg-open) instead of a browser here.
    if [ -x "$AGY" ] && ! pgrep -u "$(id -u)" -f "hub-port=$AGY_PORT" >/dev/null; then
        mkdir -p "$HOME/projects/antigravity"
        (cd "$HOME/projects/antigravity" &&
            AGY_ENABLE_HUB=1 BROWSER="$BASE/bin/xdg-open" PATH="$BASE/bin:$PATH" nohup "$AGY" --hub \
                --hub-port="$AGY_PORT" --app_data_dir=antigravity --csrf_token="$(cat /proc/sys/kernel/random/uuid)" \
                --add-dir="$HOME/projects/antigravity" >"$BASE/antigravity.log" 2>&1 &)
    fi
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
    find "$HOME/.gemini/antigravity/log" -type f -mtime +7 -delete 2>/dev/null
    find "$BASE" -maxdepth 1 -name 'signin-url' -mmin +60 -delete 2>/dev/null
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

# PocketIDE 6.0 ran Antigravity in its own VS Code on port 8082; Google's own Antigravity screen
# takes that port now. Its chats and sign-in (~/.gemini) and its projects stay. A bridge started
# by an older script restarts with the new one.
if [ -d "$BASE/vscode/antigravity" ]; then
    if [ -n "${VSCODE_IPC_HOOK_CLI:-}" ]; then
        echo "Antigravity's old VS Code stays for now: run this in Cloud Shell's own terminal to replace it."
    else
        pkill -u "$(id -u)" -f "code-server.*127.0.0.1:8082" || true
        rm -rf "${BASE:?}/vscode/antigravity"
        echo "Antigravity's VS Code was replaced by Antigravity's own screen."
    fi
fi
pkill -u "$(id -u)" -f "$BASE/bridge.py" || true

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

# 4. The agents: Claude Code and Codex each in its own VS Code, Antigravity on its own screen.
say "Installing the three agents..."
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
In PocketIDE, tap an agent: it opens full screen. Sign in once, in the agent itself:
  Claude Code  - its panel's Sign in.
  Codex        - Sign in with ChatGPT.
  Antigravity  - Continue with Google, then the blue bar: Continue signing in with Google.
If a sign-in page ends at "localhost refused to connect", tap PocketIDE's tools button at the
top of that page: PocketIDE hands the sign-in back to Cloud Shell and the agent is signed in.
In a VS Code, the status bar's Tools opens files, the terminal and extensions (also from a
.vsix link), and the keyboard button shows the keys a phone keyboard lacks.
Your files:    projects in ~/projects/claude-code, ~/projects/codex and ~/projects/antigravity;
               chats and sign-ins in ~/.claude, ~/.codex and ~/.gemini.
NEXT
