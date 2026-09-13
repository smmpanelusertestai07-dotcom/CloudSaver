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

# Extension identifiers, exactly as their publishers own them on Open VSX.
EXT_codex="openai.chatgpt"
EXT_claude="anthropic.claude-code"

mkdir -p "$STATE"
say() { printf '%s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# --------------------------------------------------------------------------- install

install_server() {
  [ -x "$BASE/bin/code-server" ] && return 0
  say "Downloading code-server ${VERSION}…"
  tmp=$(mktemp -d)
  curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
    --retry 3 --max-time 900 "$URL" -o "$tmp/$ARCHIVE" \
    || fail "code-server ${VERSION} could not be downloaded."

  # Integrity, honestly: coder publishes no checksum file beside the release, and their own
  # installer verifies nothing beyond HTTPS. This does one better -- the digest of the first
  # copy that ever installed is written down, and any later download that does not match it
  # is refused. It cannot vouch for the first copy; it can refuse a swapped second one.
  digest=$(sha256sum "$tmp/$ARCHIVE" | cut -d' ' -f1)
  pinned="$STATE/code-server-${VERSION}.sha256"
  if [ -f "$pinned" ]; then
    [ "$digest" = "$(cat "$pinned")" ] \
      || { rm -rf "$tmp"; fail "The code-server download does not match the copy this phone installed before."; }
  else
    printf '%s\n' "$digest" > "$pinned"
  fi

  say "Unpacking code-server…"
  mkdir -p "$BASE"
  tar -xzf "$tmp/$ARCHIVE" -C "$BASE" --strip-components=1
  rm -rf "$tmp"
  [ -x "$BASE/bin/code-server" ] || fail "code-server did not unpack correctly."
}

install_extension() {
  agent="$1"
  eval "id=\${EXT_${agent}:-}"
  [ -n "$id" ] || fail "Unknown agent: $agent"
  if "$BASE/bin/code-server" --list-extensions 2>/dev/null | grep -qi "^${id}$"; then
    say "${id} is already installed."
    return 0
  fi
  say "Installing ${id} from Open VSX…"
  # code-server resolves this against Open VSX, which is the registry its licence allows and
  # the one both publishers put their official builds on.
  "$BASE/bin/code-server" --install-extension "$id" \
    || fail "${id} could not be installed. Check the connection and try again."
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
  for _ in $(seq 1 60); do
    if curl --fail --silent --max-time 2 "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1 \
       || curl --fail --silent --max-time 2 -o /dev/null "http://127.0.0.1:${PORT}/"; then
      say "READY http://127.0.0.1:${PORT}/"
      return 0
    fi
    sleep 1
  done
  fail "code-server did not answer on ${PORT}. Its log is at ${LOG}."
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
