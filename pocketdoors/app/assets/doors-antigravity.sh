#!/bin/bash
# Door A: Google's own Remote Control, with the daemon running here.
#
# Antigravity's documentation gives two hosts for a Remote Control session: the desktop
# application, or a headless daemon started by its CLI. The second one is what makes a phone
# enough on its own -- one Go process here, and Google's own dashboard as the screen.
#
#   doors-antigravity.sh install
#   doors-antigravity.sh login     <- must happen once, before start
#   doors-antigravity.sh start
#   doors-antigravity.sh stop
#
# Sign-in comes first, and it is not optional. Google's own words: "run agy, complete the
# sign-in flow, then exit", and then "the credentials you used to sign into the CLI are used by
# the daemon for auth". A daemon started before that has nothing to authenticate with.
#
# On a machine with no desktop the CLI notices, prints an authorisation URL, and waits for the
# code that browser gives back. So `login` here runs the CLI with its input still connected and
# the app relays both halves: it opens the URL in the phone's real browser, and types the code
# back. That is also why sign-in never happens inside the app's own window -- Google refuse
# OAuth in an embedded view, and they are right to.
#
# Two things about this door are still unproven, and the script reports which happened rather
# than pretending either way:
#
#   1. Google install the daemon as a systemd service. There is no systemd inside PRoot, so the
#      fallback runs it in the foreground and the app supervises it.
#   2. The pinned download below is the last build this app could verify a digest for. If Google
#      have moved on, the CLI updates itself; the pin is a floor, not a ceiling.
set -eu

BASE=/opt/doors/antigravity
STATE=/var/lib/doors
PIDFILE="$STATE/antigravity.pid"
LOG="$STATE/antigravity.log"
SIGNED_IN="$STATE/antigravity.signed-in"

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

# --------------------------------------------------------------------------- sign in

signed_in() {
  [ -f "$SIGNED_IN" ] && return 0
  # A credential written by an earlier install counts too, so an update does not ask again.
  for path in /root/.antigravity /root/.config/antigravity /root/.config/Antigravity; do
    if [ -d "$path" ] && find "$path" -type f -name '*.json' -print -quit 2>/dev/null | grep -q .; then
      : > "$SIGNED_IN"
      return 0
    fi
  done
  return 1
}

do_login() {
  install_cli
  agy=$(agy_path) || fail "The Antigravity CLI is not installed."

  if signed_in; then
    say "Already signed in."
    say "SIGNEDIN"
    return 0
  fi

  # TERM=dumb keeps the CLI from redrawing a full-screen interface the app cannot show, and
  # nudges it toward the plain prompt-and-paste flow it uses over SSH.
  say "ASK Sign in to Google. A link will appear; open it, then paste the code back here."
  TERM=dumb "$agy" 2>&1 || true
  # The CLI exits once the owner leaves it. If a credential landed, remember that.
  if signed_in; then
    say "Signed in."
    say "SIGNEDIN"
  else
    fail "Sign-in did not finish. Open Antigravity again and complete it."
  fi
}

# --------------------------------------------------------------------------- run

running() {
  [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null
}

start_daemon() {
  install_cli
  agy=$(agy_path) || fail "The Antigravity CLI is not installed."

  if ! signed_in; then
    # Saying this plainly beats a daemon that dies with an authentication error the owner
    # has no way to read.
    say "NEEDLOGIN Sign in to Google first."
    fail "Not signed in yet."
  fi

  if running; then
    say "The Antigravity daemon is already running."
    say "READY https://antigravity.google/remote"
    return 0
  fi

  # First, the documented way. Google install it as an OS service; inside PRoot there is no
  # service manager, so this is expected to fail here -- it is tried anyway, because if it ever
  # starts working that is the better path and the app should use it.
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

  # Give it a moment, then say plainly whether it is alive. A daemon that exits immediately is
  # the likeliest outcome of the PRoot question, and guessing would waste the owner's time.
  sleep 5
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
  login)   do_login ;;
  start)   start_daemon ;;
  stop)    stop_daemon ;;
  status)  running && say "running" || say "stopped" ;;
  *)       fail "Usage: doors-antigravity.sh install|login|start|stop|status" ;;
esac
