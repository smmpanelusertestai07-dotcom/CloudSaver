#!/bin/bash
# Door B: the publisher's own extension, in a VS Code server that runs here.
#
# Both OpenAI and Anthropic ship their agent's interface as a VS Code extension, and both
# publish a linux-arm64 build of it on Open VSX -- the registry code-server is allowed to use.
# So the interface on the phone is the one they wrote, updated by them, and the editor,
# terminal and diff around it are real rather than drawn by us.
#
#   doors-codeserver.sh install <codex|claude>
#   doors-codeserver.sh start   <codex|claude>
#   doors-codeserver.sh stop
#
# What runs on the phone is Node: the server, the extension host, and the agent. There is no
# X display, no Electron and no video stream, which is the whole reason this door exists.
set -eu

VERSION=4.137.0
ARCHIVE="code-server-${VERSION}-linux-arm64.tar.gz"
URL="https://github.com/coder/code-server/releases/download/v${VERSION}/${ARCHIVE}"
BASE=/opt/doors/code-server
STATE=/var/lib/doors
PORT=8391
PIDFILE="$STATE/code-server.pid"
LOG="$STATE/code-server.log"

# What a working installation must contain. code-server's launcher runs `lib/node <root>`, and
# node resolves <root> through package.json's "main", which is out/node/entry.js. If any of the
# three is missing the launcher dies with MODULE_NOT_FOUND and an empty require stack -- which
# is exactly what a half-finished download looks like from the far end, and reads like nothing
# at all. So they are checked by name, and named on failure.
NEEDED="package.json out/node/entry.js lib/node"

# Extension identifiers, exactly as their publishers own them on Open VSX.
EXT_codex="openai.chatgpt"
EXT_claude="anthropic.claude-code"

mkdir -p "$STATE"
say() { printf '%s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# --------------------------------------------------------------------------- install

# Everything node needs, present and non-empty. Returns the first missing path.
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

  # This archive is about 220 MB and unpacks to roughly three times that. Finding that out
  # after an hour of downloading, from a tar error nobody can read, is the worst way to learn it.
  free_mb=$(df -Pm /opt 2>/dev/null | awk 'NR==2 {print $4}')
  if [ -n "${free_mb:-}" ] && [ "$free_mb" -lt 1200 ]; then
    fail "This needs about 1.2 GB free and the workspace has ${free_mb} MB. Free some space on the phone and try again."
  fi

  say "Downloading code-server ${VERSION}… (about 220 MB)"
  tmp="$STATE/download"
  mkdir -p "$tmp"
  # Resumable, and with an hour to do it in. The previous build allowed fifteen minutes, which
  # a phone on mobile data cannot always meet; curl then stopped mid-file and what followed
  # tried to unpack the part that had arrived.
  if ! curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
      --retry 5 --retry-delay 2 --continue-at - --max-time 3600 \
      "$URL" -o "$tmp/$ARCHIVE"; then
    fail "code-server ${VERSION} could not be downloaded. The part that arrived is kept, so trying again resumes rather than restarts."
  fi

  # Integrity, honestly: coder publishes no checksum file beside the release, and their own
  # installer verifies nothing beyond HTTPS. This does one better -- the digest of the first
  # copy that ever installed is written down, and any later download that does not match it is
  # refused. It cannot vouch for the first copy; it can refuse a swapped second one. The digest
  # is only recorded once the unpacked tree has proved itself, so a truncated download can
  # never become the trusted baseline.
  digest=$(sha256sum "$tmp/$ARCHIVE" | cut -d' ' -f1)
  pinned="$STATE/code-server-${VERSION}.sha256"
  if [ -f "$pinned" ] && [ "$digest" != "$(cat "$pinned")" ]; then
    rm -f "$tmp/$ARCHIVE"
    fail "The code-server download does not match the copy this phone installed before."
  fi

  say "Unpacking code-server… (6631 files, this takes a few minutes)"
  rm -rf "$BASE"
  mkdir -p "$BASE"
  if ! tar -xzf "$tmp/$ARCHIVE" -C "$BASE" --strip-components=1; then
    rm -f "$tmp/$ARCHIVE"
    fail "The code-server archive would not unpack. The download was incomplete; it has been discarded so the next attempt starts clean."
  fi

  part=$(missing_part) && {
    rm -f "$tmp/$ARCHIVE"
    fail "code-server unpacked without ${part}, so it cannot start. The download was incomplete and has been discarded."
  }

  # It exists; now prove it runs. Its own version line is the shortest honest test there is.
  # Written to a file rather than read through a pipe, because a pipeline reports the exit
  # status of its last command and `head` succeeds however badly code-server failed.
  if "$BASE/bin/code-server" --version >"$STATE/version.txt" 2>&1; then
    say "code-server reports: $(head -n 1 "$STATE/version.txt")"
  else
    say "code-server unpacked but would not run. Its own words:"
    head -n 5 "$STATE/version.txt" 2>/dev/null || true
    rm -f "$tmp/$ARCHIVE"
    fail "code-server ${VERSION} does not run in this workspace."
  fi

  printf '%s\n' "$digest" > "$pinned"
  rm -f "$tmp/$ARCHIVE"
}

install_extension() {
  agent="$1"
  eval "id=\${EXT_${agent}:-}"
  [ -n "$id" ] || fail "Unknown agent: $agent"
  # Not 2>/dev/null. A server that cannot list its extensions cannot install one either, and
  # hiding the reason here is what turned a clear failure into a stack trace further down.
  if "$BASE/bin/code-server" --list-extensions 2>&1 | grep -qi "^${id}$"; then
    say "${id} is already installed."
    return 0
  fi
  say "Installing ${id} from Open VSX…"
  # code-server resolves this against Open VSX, which is the registry its licence allows and
  # the one both publishers put their official builds on.
  "$BASE/bin/code-server" --install-extension "$id" \
    || fail "${id} could not be installed from Open VSX. Check the connection and try again."
}

# --------------------------------------------------------------------------- run

running() {
  [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

start_server() {
  agent="$1"
  install_server
  install_extension "$agent"

  if running; then
    say "code-server is already running on ${PORT}."
    say "READY http://127.0.0.1:${PORT}/"
    return 0
  fi

  mkdir -p /root/work
  # Bound to the loopback address on purpose: the only thing that should ever reach this
  # server is the app on the same phone. No password, because no one else can connect, and a
  # password prompt on every start would be a lock with no door.
  say "Starting code-server on 127.0.0.1:${PORT}…"
  nohup "$BASE/bin/code-server" \
    --bind-addr "127.0.0.1:${PORT}" \
    --auth none \
    --disable-telemetry \
    --disable-update-check \
    --disable-workspace-trust \
    --app-name "PocketAgent" \
    --welcome-text "Your workspace is on this phone." \
    /root/work >>"$LOG" 2>&1 &
  printf '%s\n' "$!" > "$PIDFILE"

  # Wait for it to answer before telling the phone to load the page; a WebView that arrives
  # first shows a connection error and the owner has to guess whether to retry.
  for _ in $(seq 1 90); do
    if curl --fail --silent --max-time 2 "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1 \
       || curl --fail --silent --max-time 2 -o /dev/null "http://127.0.0.1:${PORT}/"; then
      say "READY http://127.0.0.1:${PORT}/"
      return 0
    fi
    if ! running; then
      say "code-server stopped while starting. Its own words:"
      tail -n 20 "$LOG" 2>/dev/null || true
      fail "code-server did not stay running."
    fi
    sleep 1
  done
  say "code-server did not answer in time. Its own words:"
  tail -n 20 "$LOG" 2>/dev/null || true
  fail "code-server did not answer on ${PORT}."
}

stop_server() {
  if running; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    sleep 1
    kill -9 "$(cat "$PIDFILE")" 2>/dev/null || true
  fi
  rm -f "$PIDFILE"
  say "Stopped."
}

case "${1:-}" in
  install) install_server; install_extension "${2:-codex}" ;;
  start)   start_server "${2:-codex}" ;;
  stop)    stop_server ;;
  status)  running && say "running" || say "stopped" ;;
  *)       fail "Usage: doors-codeserver.sh install|start|stop|status [codex|claude]" ;;
esac
