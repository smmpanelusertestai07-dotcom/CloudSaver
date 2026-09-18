#!/usr/bin/env bash
# agy-up.sh - the Antigravity chat panel inside a browser Codespace (works from a phone).
#
# Why: the Antigravity extension shows its chat in an <iframe> that talks to a local
# server (the "hub") over a WebSocket. Through GitHub's port-forwarding proxy that
# WebSocket dies, so the panel never loads. The extension has an undocumented setting,
# "antigravity.serverUrl" (env ANTIGRAVITY_SERVER_URL works too): when it is set, the
# extension starts no hub of its own and loads the panel from that URL instead.
#
# So, on every codespace start (devcontainer.json -> postStartCommand), this script:
#   1. installs the agy binary if it is missing (the CLI and the hub are one binary)
#   2. opens a Cloudflare quick tunnel to a fixed local port (free, no account,
#      WebSocket works; the hostname is random and changes on every start)
#   3. starts the hub on that port with the same environment the extension gives it
#   4. writes the tunnel URL into .vscode/settings.json as antigravity.serverUrl and
#      bumps antigravity.serverPort, which makes the extension offer "Reload Window"
#      if it was already running (that port setting is unused once serverUrl is set)
#
# Run by hand any time:   bash .devcontainer/agy-up.sh      (restarts hub + tunnel)
# Logs:                   /tmp/agy-up/{hub,tunnel,install}.log
# Sign-in URL, if the panel does not open one:
#                         grep -o 'ANTIGRAVITY_OPEN_URL:.*' /tmp/agy-up/hub.log | tail -1
# Close the public URL:   pkill -f 'cloudflared tunnel'
#
# Keep in mind: while the tunnel runs, anyone who has the random URL can use this hub.
# Do not share it. Traffic passes through Cloudflare's edge (TLS ends there).

set -u
PORT=9000
LOG=/tmp/agy-up
WS="${CODESPACE_VSCODE_FOLDER:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
CF="$HOME/.local/bin/cloudflared"
mkdir -p "$LOG" "$HOME/.local/bin" "$WS/.vscode"

say() { printf '[agy-up] %s\n' "$*"; }
bg()  { if command -v setsid >/dev/null 2>&1; then setsid "$@"; else "$@"; fi; }

# 1. binary: the extension installs to ~/.gemini/bin/agy, the CLI installer to ~/.local/bin/agy
AGY=""
for c in "$HOME/.gemini/bin/agy" "$HOME/.local/bin/agy"; do
  if [ -x "$c" ]; then AGY="$c"; break; fi
done
if [ -z "$AGY" ]; then
  say "installing agy (one time)"
  curl -fsSL https://antigravity.google/cli/install.sh | bash >"$LOG/install.log" 2>&1 </dev/null || true
  [ -x "$HOME/.local/bin/agy" ] && AGY="$HOME/.local/bin/agy"
fi
if [ -z "$AGY" ]; then say "agy did not install; see $LOG/install.log"; exit 1; fi

# 2. cloudflared: one download, kept in ~/.local/bin
if [ ! -x "$CF" ]; then
  say "downloading cloudflared (one time)"
  if ! curl -fsSL -o "$CF" https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64; then
    say "cloudflared download failed"; exit 1
  fi
  chmod +x "$CF"
fi

# 3. tunnel first (its URL is the slow part); a fresh URL on every run
pkill -f "cloudflared tunnel --no-autoupdate" 2>/dev/null || true
: >"$LOG/tunnel.log"
# --http-host-header is required: the hub answers "Unauthorized Host (Localhost only)"
# unless the Host header names localhost, and Cloudflare forwards the public hostname.
bg nohup "$CF" tunnel --no-autoupdate --url "http://localhost:$PORT" \
  --http-host-header "localhost:$PORT" >"$LOG/tunnel.log" 2>&1 </dev/null &

# 4. hub: always our own, so it outlives window reloads. Kill every hub, including one the
# extension started on its own ephemeral port: they share one token store
# (--app_data_dir=antigravity), so two of them fight over the sign-in state.
pkill -f "agy --hub" 2>/dev/null || true
sleep 1
say "starting hub on :$PORT"
(
  cd "$WS" || exit 1
  export AGY_ENABLE_HUB=1 ANTIGRAVITY_VSCODE_HOST=1 ANTIGRAVITY_AUTH_SUCCESS_APP=vscode
  bg nohup "$AGY" --hub "--hub-port=$PORT" --app_data_dir=antigravity "--add-dir=$WS" >"$LOG/hub.log" 2>&1 </dev/null &
)

# 5. wait for the tunnel URL and for the hub to answer
URL=""
for _ in $(seq 1 90); do
  URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG/tunnel.log" | head -1)
  [ -n "$URL" ] && break
  sleep 1
done
if [ -z "$URL" ]; then say "no tunnel URL after 90 s; see $LOG/tunnel.log"; exit 1; fi
for _ in $(seq 1 60); do
  curl -fsS -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/" && break
  sleep 1
done
curl -fsS -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/" || say "hub not answering yet; see $LOG/hub.log"

# 6. point the extension at the tunnel (workspace settings; keeps every other key)
python3 - "$WS/.vscode/settings.json" "$URL" "$(( 10000 + $(date +%s) % 50000 ))" <<'PY'
import json, re, shutil, sys
path, url, port = sys.argv[1], sys.argv[2], int(sys.argv[3])
try:
    text = open(path, encoding="utf-8").read()
except FileNotFoundError:
    text = ""

def jsonc_to_json(s):
    # VS Code settings are JSONC: comments and trailing commas allowed. Strings are left untouched.
    n = len(s)

    def skip_blank(j):  # whitespace and comments
        while j < n:
            if s[j] in " \t\r\n":
                j += 1
            elif s.startswith("//", j):
                k = s.find("\n", j); j = n if k < 0 else k
            elif s.startswith("/*", j):
                k = s.find("*/", j + 2); j = n if k < 0 else k + 2
            else:
                break
        return j

    out, i, in_str = [], 0, False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(s[i + 1]); i += 2; continue
            if c == '"':
                in_str = False
            i += 1; continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        if s.startswith("//", i) or s.startswith("/*", i):
            i = skip_blank(i); continue
        if c == ",":
            j = skip_blank(i + 1)
            if j < n and s[j] in "}]":
                i += 1; continue  # trailing comma
        out.append(c); i += 1
    return "".join(out)

data = {}
if text.strip():
    try:
        data = json.loads(jsonc_to_json(text))
        if not isinstance(data, dict):
            raise ValueError("not an object")
    except Exception:
        shutil.copyfile(path, path + ".bak")
        print("[agy-up] could not parse settings.json; kept a copy at settings.json.bak")
        data = {}
data["antigravity.serverUrl"] = url
data["antigravity.serverPort"] = port
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY

say "Antigravity panel URL -> $URL"
say "written to $WS/.vscode/settings.json - if VS Code shows 'Reload Window', tap it"
