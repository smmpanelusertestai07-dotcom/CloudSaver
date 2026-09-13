#!/bin/bash
# Door A: Google's own Remote Control, with the daemon running here.
#
# Antigravity's documentation gives two hosts for a Remote Control session: the desktop
# application, or a headless daemon started by its CLI. The second one is what makes a phone
# enough on its own -- one Go process here, and Google's own dashboard as the screen.
#
#   doors-antigravity.sh install
#   doors-antigravity.sh start
#   doors-antigravity.sh stop
#
# Two things about this door are not yet proven, and the script says which happened rather
# than pretending either way:
#
#   1. Google installs the daemon as a systemd service. There is no systemd inside PRoot, so
#      the fallback here runs it in the foreground and supervises it from the app. If the CLI
#      refuses to run without systemd, that is reported as exactly that.
#   2. The pinned download below is the last build this app could verify a digest for. If
#      Google has moved on, the CLI will say so on first run and update itself; the pin is a
#      floor, not a ceiling.
set -eu

BASE=/opt/doors/antigravity
STATE=/var/lib/doors
PIDFILE="$STATE/antigravity.pid"
LOG="$STATE/antigravity.log"

# Verified against Google's own public storage host. sha512, as they publish it.
URL="https://storage.googleapis.com/antigravity-public/antigravity-cli/1.2.2-6061403484848128/linux-arm/cli_linux_arm64.tar.gz"
SHA512="a1645a30f36b767c7534c2f6a53e99a9bfade993267efcca715f7a45d797d47d6561df787e9d4a51a3bdfc9be855d49d23fa3f4b91b2c661fd17314050836048"

mkdir -p "$STATE"
say() { printf '%s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

agy_path() {
  if [ -x "$BASE/agy" ]; then printf '%s\n' "$BASE/agy"; return 0; fi
  command -v agy 2>/dev/null && return 0
  return 1
}

install_cli() {
  if agy_path >/dev/null; then
    say "Antigravity CLI is already here."
    return 0
  fi
  say "Downloading the Antigravity CLI…"
  tmp=$(mktemp -d)
  curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
    --retry 3 --max-time 1200 "$URL" -o "$tmp/cli.tar.gz" \
    || { rm -rf "$tmp"; fail "The Antigravity CLI could not be downloaded."; }

  printf '%s  %s\n' "$SHA512" "$tmp/cli.tar.gz" | sha512sum --check --status \
    || { rm -rf "$tmp"; fail "The Antigravity CLI download did not match its published checksum."; }

  say "Unpacking…"
  mkdir -p "$BASE"
  tar -xzf "$tmp/cli.tar.gz" -C "$BASE" --strip-components=1 2>/dev/null \
    || tar -xzf "$tmp/cli.tar.gz" -C "$BASE"
  rm -rf "$tmp"
  # The archive's layout has changed between builds; find the binary rather than assume it.
  if [ ! -x "$BASE/agy" ]; then
    found=$(find "$BASE" -maxdepth 3 -type f -name agy -print -quit 2>/dev/null || true)
    [ -n "$found" ] || fail "The archive did not contain an 'agy' binary."
    mv "$found" "$BASE/agy"
  fi
  chmod +x "$BASE/agy"
  say "Antigravity CLI installed."
}

running() {
  [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

start_daemon() {
  install_cli
  agy=$(agy_path) || fail "The Antigravity CLI is not installed."

  if running; then
    say "The Antigravity daemon is already running."
    say "READY https://antigravity.google/remote"
    return 0
  fi

  # First, the documented way. Google installs it as an OS service; inside PRoot there is no
  # service manager, so this is expected to fail here -- it is tried anyway because if it ever
  # starts working, that is the better path and the app should use it.
  say "Starting Remote Control…"
  if "$agy" remote-control start >>"$LOG" 2>&1; then
    say "SERVICE the daemon started through the CLI's own service path"
    say "READY https://antigravity.google/remote"
    return 0
  fi

  say "No service manager here; running the daemon in the foreground instead."
  nohup "$agy" remote-control start --foreground >>"$LOG" 2>&1 &
  pid=$!
  printf '%s\n' "$pid" > "$PIDFILE"

  # Give it a moment, then say plainly whether it is alive. A daemon that exits immediately
  # is the likeliest outcome of the PRoot question, and guessing would waste the owner's time.
  sleep 4
  if kill -0 "$pid" 2>/dev/null; then
    say "FOREGROUND the daemon is running under the app's supervision"
    say "READY https://antigravity.google/remote"
    return 0
  fi

  rm -f "$PIDFILE"
  say "The daemon stopped straight away. Its own words:"
  tail -n 20 "$LOG" 2>/dev/null || true
  fail "The Antigravity daemon would not stay running inside this workspace."
}

stop_daemon() {
  agy=$(agy_path 2>/dev/null || true)
  [ -n "$agy" ] && "$agy" remote-control stop >/dev/null 2>&1 || true
  if running; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    sleep 1
    kill -9 "$(cat "$PIDFILE")" 2>/dev/null || true
  fi
  rm -f "$PIDFILE"
  say "Stopped."
}

case "${1:-}" in
  install) install_cli ;;
  start)   start_daemon ;;
  stop)    stop_daemon ;;
  status)  running && say "running" || say "stopped" ;;
  *)       fail "Usage: doors-antigravity.sh install|start|stop|status" ;;
esac
