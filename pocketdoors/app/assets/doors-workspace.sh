#!/bin/bash
# One workspace, and one way into each agent.
#
# This replaced three separate scripts that each opened a different kind of "door". Three doors
# meant a menu, and a menu is a question the owner has to answer before they can start working --
# when for each maker exactly one route is the best their own publishing supports. Offering the
# others was offering worse choices politely.
#
# So: code-server runs here and is the screen for all three. It brings a file tree, a terminal, a
# diff view and git, which two of the three agents would otherwise have no way to show on a phone.
# Inside it, each agent is reached the one way its maker supports best, and where the maker
# publishes a phone surface of their own it is switched on automatically rather than offered.
#
#   doors-workspace.sh install <claude|codex|antigravity>
#   doors-workspace.sh login   <antigravity>
#   doors-workspace.sh start   <claude|codex|antigravity>
#   doors-workspace.sh stop
set -eu

STATE=/var/lib/doors
WORK=/root/work
PORT=8391
BASE=/opt/doors/code-server
LOG="$STATE/workspace.log"

# ---------------------------------------------------------------------- code-server

VERSION=4.137.0
ARCHIVE="code-server-${VERSION}-linux-arm64.tar.gz"
URL="https://github.com/coder/code-server/releases/download/v${VERSION}/${ARCHIVE}"

# What a working installation must contain. code-server's launcher runs `lib/node <root>`, and
# node resolves <root> through package.json's "main", which is out/node/entry.js. If any of the
# three is missing the launcher dies with MODULE_NOT_FOUND and an empty require stack -- which is
# what a half-finished download looks like from the far end, and reads like nothing at all.
NEEDED="package.json out/node/entry.js lib/node"

# ---------------------------------------------------------------------- the agents

# Extension identifiers, exactly as their makers own them on Open VSX. Google publish no
# extension there, so Antigravity is reached through its own CLI in the workspace's terminal.
EXT_codex="openai.chatgpt"
EXT_claude="Anthropic.claude-code"

CLAUDE_PACKAGE="@anthropic-ai/claude-code"
# 2.1.203, not 2.1.52. Remote Control itself arrived in 2.1.52, but the one thing this whole
# design rests on -- remoteControlAtStartup honoured without anybody opening a menu -- is
# documented as needing 2.1.203 or later. An older build would read the file, ignore the key,
# and the owner would be told their session was on their phone when it was not.
CLAUDE_MIN="2.1.203"

AGY_BASE=/opt/doors/antigravity
AGY_MANIFEST="https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"
AGY_FLOOR_URL="https://storage.googleapis.com/antigravity-public/antigravity-cli/1.2.2-6061403484848128/linux-arm/cli_linux_arm64.tar.gz"
AGY_FLOOR_SHA512="a1645a30f36b767c7534c2f6a53e99a9bfade993267efcca715f7a45d797d47d6561df787e9d4a51a3bdfc9be855d49d23fa3f4b91b2c661fd17314050836048"

# Four variables, each of which switches Remote Control off without saying so. Anthropic's own
# requirements page names them: they disable the feature-flag evaluation that Remote Control
# depends on, so the session starts, works, and simply never appears on the phone. Nothing in
# this app sets them, but a workspace is a real Ubuntu and anything could, so they are cleared
# here rather than hoped about.
unset DISABLE_TELEMETRY DO_NOT_TRACK CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC DISABLE_GROWTHBOOK
# Remote Control is refused when the API is pointed anywhere but Anthropic's own host.
unset ANTHROPIC_BASE_URL

mkdir -p "$STATE" "$WORK"
say() { printf '%s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# Sign-in screens are interactive and refuse a bare pipe. `script` gives them a pty and is part
# of Ubuntu's base system, so they behave the way they do over SSH.
on_a_terminal() {
  command -v script >/dev/null 2>&1 || return 1
  TERM=xterm script --quiet --return --command "$1" /dev/null 2>&1 || true
}

# ---------------------------------------------------------------------- the editor

missing_part() {
  for part in $NEEDED; do
    [ -s "$BASE/$part" ] || { printf '%s\n' "$part"; return 0; }
  done
  return 1
}

install_server() {
  if [ -x "$BASE/bin/code-server" ] && ! missing_part >/dev/null; then
    return 0
  fi

  free_mb=$(df -Pm /opt 2>/dev/null | awk 'NR==2 {print $4}')
  if [ -n "${free_mb:-}" ] && [ "$free_mb" -lt 1200 ]; then
    fail "This needs about 1.2 GB free and the workspace has ${free_mb} MB. Free some space on the phone and try again."
  fi

  say "Downloading the editor… (about 220 MB, once)"
  tmp="$STATE/download"
  mkdir -p "$tmp"
  # Resumable, and with an hour to do it in. A fifteen-minute deadline is what cut this off
  # mid-file once, and what followed tried to unpack the part that had arrived.
  if ! curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
      --retry 5 --retry-delay 2 --continue-at - --max-time 3600 \
      "$URL" -o "$tmp/$ARCHIVE"; then
    fail "The editor could not be downloaded. What arrived is kept, so trying again resumes."
  fi

  # coder publish no checksum beside the release, so the digest of the first copy that ever
  # installed is written down and any later download that differs is refused. It is recorded only
  # after the tree has proved itself, so a truncated download never becomes the trusted baseline.
  digest=$(sha256sum "$tmp/$ARCHIVE" | cut -d' ' -f1)
  pinned="$STATE/code-server-${VERSION}.sha256"
  if [ -f "$pinned" ] && [ "$digest" != "$(cat "$pinned")" ]; then
    rm -f "$tmp/$ARCHIVE"
    fail "The editor download does not match the copy this phone installed before."
  fi

  say "Unpacking the editor… (6631 files, a few minutes)"
  rm -rf "$BASE"; mkdir -p "$BASE"
  # This archive does wrap its files in a directory, so the component is stripped here. The
  # Antigravity archive below is a single file and must not be stripped; they are not the same.
  if ! tar -xzf "$tmp/$ARCHIVE" -C "$BASE" --strip-components=1; then
    rm -f "$tmp/$ARCHIVE"
    fail "The editor archive would not unpack. The download was incomplete and has been discarded."
  fi

  part=$(missing_part) && {
    rm -f "$tmp/$ARCHIVE"
    fail "The editor unpacked without ${part}, so it cannot start. The download was incomplete."
  }

  if "$BASE/bin/code-server" --version >"$STATE/version.txt" 2>&1; then
    say "Editor ready: $(head -n 1 "$STATE/version.txt")"
  else
    say "The editor unpacked but would not run. Its own words:"
    head -n 5 "$STATE/version.txt" 2>/dev/null || true
    rm -f "$tmp/$ARCHIVE"
    fail "code-server ${VERSION} does not run in this workspace."
  fi

  printf '%s\n' "$digest" > "$pinned"
  rm -f "$tmp/$ARCHIVE"
}

# Open VSX publishes the size of every build. Asking costs one small request and turns a silent
# hour into a number someone can plan around.
extension_size() {
  publisher=${1%%.*}
  name=${1#*.}
  curl --fail --silent --location --proto '=https' --max-time 30 \
      "https://open-vsx.org/api/${publisher}/${name}/linux-arm64/latest" 2>/dev/null \
    | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin)["files"]["download"])
except Exception:
    pass' 2>/dev/null \
    | while read -r href; do
        [ -n "$href" ] || continue
        curl -sIL --max-time 30 "$href" 2>/dev/null \
          | awk 'tolower($1) == "content-length:" { print int($2 / 1048576) }' | tail -n 1
      done
}

install_extension() {
  id="$1"
  # Not 2>/dev/null. A server that cannot list its extensions cannot install one either, and
  # hiding the reason is what turned a clear failure into a stack trace further down.
  if "$BASE/bin/code-server" --list-extensions 2>&1 | grep -qi "^${id}$"; then
    return 0
  fi
  size=$(extension_size "$id")
  if [ -n "$size" ]; then
    say "Installing ${id} — ${size} MB, downloaded once."
    say "There is no progress line for this; Open VSX does not give one. It is not stuck."
  else
    say "Installing ${id}… (a few hundred megabytes, downloaded once)"
  fi
  "$BASE/bin/code-server" --install-extension "$id" \
    || fail "${id} could not be installed from Open VSX. Check the connection and try again."
}

# Settings that make a desktop editor usable on a 720-pixel screen. Written once; anything the
# owner changes afterwards is theirs and is never overwritten.
phone_defaults() {
  user_dir=/root/.local/share/code-server/User
  mkdir -p "$user_dir"
  [ -f "$user_dir/settings.json" ] && return 0
  cat > "$user_dir/settings.json" <<'JSON'
{
  "window.zoomLevel": -1,
  "workbench.activityBar.location": "top",
  "workbench.startupEditor": "none",
  "workbench.tips.enabled": false,
  "editor.wordWrap": "on",
  "editor.minimap.enabled": false,
  "editor.fontSize": 12,
  "editor.lineNumbers": "on",
  "terminal.integrated.fontSize": 12,
  "explorer.compactFolders": false,
  "chatgpt.openOnStartup": true
}
JSON
  say "Editor sized for this screen. Change anything in its own Settings; it will be kept."
}

# ---------------------------------------------------------------------- Anthropic

claude_path() { command -v claude 2>/dev/null && return 0; return 1; }

install_claude() {
  claude_path >/dev/null && return 0
  command -v npm >/dev/null 2>&1 || fail "Node is missing; set-up did not finish."
  say "Installing Claude Code…"
  npm install -g "$CLAUDE_PACKAGE" >>"$LOG" 2>&1 \
    || { tail -n 15 "$LOG" 2>/dev/null || true; fail "Claude Code could not be installed."; }
  claude_path >/dev/null || fail "Claude Code installed but is not on the path."
}

claude_signed_in() {
  cli=$(claude_path) || return 1
  "$cli" auth status 2>/dev/null | python3 -c 'import json,sys
try:
    sys.exit(0 if json.load(sys.stdin).get("loggedIn") else 1)
except Exception:
    sys.exit(1)' 2>/dev/null
}

# The whole reason there is no second option to choose. Anthropic document this exact key:
# remoteControlAtStartup in ~/.claude/settings.json turns Remote Control on for every session,
# so their phone app shows this session without anyone opening a menu or typing a command.
claude_remote_at_startup() {
  settings=/root/.claude/settings.json
  mkdir -p /root/.claude
  python3 - "$settings" <<'PY'
import json, sys, os
path = sys.argv[1]
data = {}
if os.path.exists(path):
    try:
        with open(path) as f:
            data = json.load(f)
    except Exception:
        data = {}
if data.get("remoteControlAtStartup") is True:
    raise SystemExit(0)
data["remoteControlAtStartup"] = True
with open(path, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
PY
}

claude_version_ok() {
  cli=$(claude_path) || return 0
  have=$("$cli" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n1 || true)
  [ -n "$have" ] || return 0
  lowest=$(printf '%s\n%s\n' "$have" "$CLAUDE_MIN" | sort -V | head -n1)
  [ "$lowest" = "$CLAUDE_MIN" ] \
    || fail "Claude Code $have is here; Remote Control needs $CLAUDE_MIN or later."
}

# ---------------------------------------------------------------------- Google

agy_path() {
  [ -x "$AGY_BASE/agy" ] && { printf '%s\n' "$AGY_BASE/agy"; return 0; }
  command -v agy 2>/dev/null && return 0
  return 1
}

install_agy() {
  agy_path >/dev/null && return 0
  url=""; sha512=""
  say "Checking Google's release manifest…"
  manifest=$(curl --fail --silent --location --proto '=https' --tlsv1.2 \
    --retry 2 --max-time 60 "$AGY_MANIFEST" 2>/dev/null || true)
  if [ -n "$manifest" ]; then
    url=$(printf '%s' "$manifest" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("url",""))' 2>/dev/null || true)
    sha512=$(printf '%s' "$manifest" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("sha512",""))' 2>/dev/null || true)
  fi
  # A manifest naming a download anywhere but Google's own storage is not one to follow.
  case "$url" in
    https://storage.googleapis.com/antigravity-public/*) : ;;
    *) url="$AGY_FLOOR_URL"; sha512="$AGY_FLOOR_SHA512" ;;
  esac

  say "Downloading the Antigravity CLI… (about 54 MB)"
  tmp=$(mktemp -d)
  curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
    --retry 5 --retry-delay 2 --continue-at - --max-time 3600 "$url" -o "$tmp/cli.tar.gz" \
    || { rm -rf "$tmp"; fail "The Antigravity CLI could not be downloaded."; }
  printf '%s  %s\n' "$sha512" "$tmp/cli.tar.gz" | sha512sum --check --status \
    || { rm -rf "$tmp"; fail "The Antigravity CLI did not match Google's published checksum."; }

  mkdir -p "$AGY_BASE"
  # Never --strip-components here. This archive holds exactly one entry, a file at the root, so
  # stripping one component strips its whole name: tar extracts nothing and still exits zero.
  tar -xzf "$tmp/cli.tar.gz" -C "$AGY_BASE" \
    || { rm -rf "$tmp"; fail "The Antigravity archive would not unpack."; }
  rm -rf "$tmp"
  if [ ! -f "$AGY_BASE/agy" ]; then
    found=$(find "$AGY_BASE" -maxdepth 4 -type f \( -name agy -o -name antigravity \) -print -quit 2>/dev/null || true)
    [ -n "$found" ] || fail "The archive did not contain the Antigravity binary."
    mv "$found" "$AGY_BASE/agy"
  fi
  chmod +x "$AGY_BASE/agy"
  [ -x "$AGY_BASE/agy" ] || fail "The Antigravity binary unpacked but cannot be run."
}

agy_signed_in() {
  [ -f "$STATE/antigravity.signed-in" ] && return 0
  for path in /root/.antigravity /root/.config/antigravity /root/.config/Antigravity; do
    if [ -d "$path" ] && find "$path" -type f -name '*.json' -print -quit 2>/dev/null | grep -q .; then
      : > "$STATE/antigravity.signed-in"; return 0
    fi
  done
  return 1
}

agy_daemon_up() { "$1" remote-control status 2>&1 | grep -qiE 'running|active|connected|online'; }

# ---------------------------------------------------------------------- prepare one agent

prepare() {
  case "$1" in
    claude)
      install_claude
      claude_version_ok
      # Not NEEDLOGIN. That word sends the app off to run a separate sign-in command, and there
      # is none here: Anthropic's extension signs in inside its own panel, in the editor that is
      # about to open. Saying NEEDLOGIN for an agent with no login command asked the app to run
      # an empty one.
      # Two ways in, and they are the same sign-in: the panel's button, or `claude` in the
      # editor's own terminal, which prints a link and takes a pasted code. The terminal one is
      # named because it needs no callback to come back into a web view, and a web view is the
      # one part of this that no publisher has promised will work.
      claude_signed_in \
        || say "Not signed in yet. Use the Sign in button in the Claude panel, or type claude in the editor's terminal and use /login."
      # Switched on before the editor starts, so the very first session is already on the phone.
      claude_remote_at_startup
      install_extension "$EXT_claude"
      ;;
    codex)
      install_extension "$EXT_codex"
      ;;
    antigravity)
      install_agy
      agy=$(agy_path)
      # Google's CLI reaches for the operating system's secure keyring -- on Linux, the Secret
      # Service over dbus. A workspace with no desktop session has neither, so the CLI may hold
      # a sign-in only for as long as it runs. Said out loud, because being asked to sign in
      # again with no explanation is worse than being told it might happen.
      if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ] && ! pgrep -x dbus-daemon >/dev/null 2>&1; then
        say "This workspace has no system keyring, so Google may ask you to sign in again."
      fi
      if ! agy_signed_in; then
        say "NEEDLOGIN Sign in to Google first."
        fail "Not signed in yet."
      fi
      # Started beside the editor, not instead of it: the dashboard and the terminal show the
      # same machine. Exit zero is not proof here -- Google register this with a service manager
      # and there is none in this workspace -- so its own status is what decides.
      say "Starting Google's Remote Control…"
      "$agy" remote-control start >>"$LOG" 2>&1 || true
      if agy_daemon_up "$agy"; then
        say "Antigravity is reachable from Google's dashboard."
      else
        say "Google's Remote Control did not report itself as running. Its own words:"
        "$agy" remote-control status 2>&1 | tail -n 6 || true
        say "The editor below still works; the dashboard will not show this machine."
      fi
      ;;
    *) fail "Unknown agent: $1" ;;
  esac
}

# ---------------------------------------------------------------------- run

start_workspace() {
  agent="${1:-claude}"
  install_server
  phone_defaults
  prepare "$agent"

  # A pid file left by an earlier session is always stale: proot runs with --kill-on-exit, so
  # nothing inside this workspace outlives the session that started it.
  rm -f "$STATE/code-server.pid"

  say "Starting the workspace…"
  nohup "$BASE/bin/code-server" \
    --bind-addr "127.0.0.1:${PORT}" \
    --auth none \
    --disable-telemetry \
    --disable-update-check \
    --disable-workspace-trust \
    --app-name "PocketAgent" \
    --welcome-text "Your workspace is on this phone." \
    "$WORK" >>"$LOG" 2>&1 &
  server=$!
  printf '%s\n' "$server" > "$STATE/code-server.pid"

  answered=false
  for _ in $(seq 1 90); do
    if curl --fail --silent --max-time 2 "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1 \
       || curl --fail --silent --max-time 2 -o /dev/null "http://127.0.0.1:${PORT}/"; then
      answered=true; break
    fi
    if ! kill -0 "$server" 2>/dev/null; then
      say "The workspace stopped while starting. Its own words:"
      tail -n 20 "$LOG" 2>/dev/null || true
      fail "The workspace did not stay running."
    fi
    sleep 1
  done
  [ "$answered" = true ] || {
    say "The workspace did not answer in time. Its own words:"
    tail -n 20 "$LOG" 2>/dev/null || true
    fail "The workspace did not answer on ${PORT}."
  }

  say "READY http://127.0.0.1:${PORT}/"

  # And then stay here, for as long as the workspace runs. proot is started with --kill-on-exit,
  # so a script that returned would kill what it had just started.
  wait "$server" 2>/dev/null || true
  rm -f "$STATE/code-server.pid"
  say "The workspace has stopped."
}

do_login() {
  case "${1:-}" in
    antigravity)
      install_agy
      agy=$(agy_path)
      if agy_signed_in; then say "Already signed in."; say "SIGNEDIN"; return 0; fi
      # Bare `agy`, no subcommand. The CLI's own instruction, in its own words: "Launch the CLI
      # without arguments to sign in." It is interactive, so it is given a terminal.
      say "ASK Sign in to Google. A link will appear; open it, then follow what it asks."
      if command -v script >/dev/null 2>&1; then
        on_a_terminal "$agy"
      else
        say "This workspace has no pty, so the sign-in screen may not draw. Its output follows."
        "$agy" 2>&1 || true
      fi
      if agy_signed_in; then say "Signed in."; say "SIGNEDIN"; else
        fail "Sign-in did not finish. Open Antigravity again and complete it."
      fi
      ;;
    *) fail "Only Antigravity signs in separately; the others sign in inside the editor." ;;
  esac
}

stop_workspace() {
  agy=$(agy_path 2>/dev/null || true)
  [ -n "$agy" ] && "$agy" remote-control stop >/dev/null 2>&1 || true
  if [ -f "$STATE/code-server.pid" ]; then
    kill "$(cat "$STATE/code-server.pid")" 2>/dev/null || true
    rm -f "$STATE/code-server.pid"
  fi
  say "Stopped."
}

case "${1:-}" in
  install) install_server; phone_defaults; prepare "${2:-claude}" ;;
  login)   do_login "${2:-}" ;;
  start)   start_workspace "${2:-claude}" ;;
  stop)    stop_workspace ;;
  status)  [ -f "$STATE/code-server.pid" ] && kill -0 "$(cat "$STATE/code-server.pid")" 2>/dev/null \
             && say "running" || say "stopped" ;;
  *)       fail "Usage: doors-workspace.sh install|login|start|stop|status [agent]" ;;
esac
