#!/bin/bash
# Door A for Anthropic: Remote Control, with the session running here.
#
# Claude Code's Remote Control turns a running session into one that claude.ai/code and the
# Claude phone apps can reach. The session itself stays where it started -- and where it starts
# is this workspace, inside the phone. So the interface is Anthropic's own mobile app, built by
# them for a phone, and the work happens on the owner's own device.
#
#   doors-claude.sh install
#   doors-claude.sh login     <- must happen once, before start
#   doors-claude.sh start
#   doors-claude.sh stop
#
# Two things decide how this is written, and both are checked rather than assumed:
#
#   1. Remote Control needs a real terminal. It is confirmed working on a headless Linux host
#      wrapped in tmux or screen, and it refuses a bare pipe. `script` gives it a pty, which is
#      the same thing this app already does for Antigravity's sign-in.
#   2. proot runs with --kill-on-exit, so a session that detached would be killed on the way out.
#      This script stays for as long as the session runs.
set -eu

STATE=/var/lib/doors
LOG="$STATE/claude.log"
WORK=/root/work

# Anthropic's own surface. The session list lives here, and the Claude phone apps show the same
# sessions under their Code tab.
SURFACE="https://claude.ai/code"

# Remote Control needs 2.1.52 or later. The package is the publisher's own on npm, and it
# updates itself, so no version is pinned here -- a floor is checked instead.
PACKAGE="@anthropic-ai/claude-code"
MIN_VERSION="2.1.52"

mkdir -p "$STATE" "$WORK"
say() { printf '%s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

claude_path() {
  command -v claude 2>/dev/null && return 0
  [ -x /usr/local/bin/claude ] && { printf '%s\n' /usr/local/bin/claude; return 0; }
  return 1
}

# --------------------------------------------------------------------------- install

install_cli() {
  if claude_path >/dev/null; then
    say "Claude Code is already here."
    return 0
  fi
  command -v npm >/dev/null 2>&1 || fail "Node is missing from this workspace; set-up did not finish."
  say "Installing Claude Code from npm…"
  npm install -g "$PACKAGE" >>"$LOG" 2>&1 \
    || { tail -n 15 "$LOG" 2>/dev/null || true; fail "Claude Code could not be installed."; }
  claude_path >/dev/null || fail "Claude Code installed but the command is not on the path."
  say "Claude Code installed."
}

# A floor, not a pin. Remote Control did not exist before 2.1.52, and a session started on an
# older build would simply never appear in the app -- which looks like nothing happening at all.
check_version() {
  cli=$(claude_path) || return 0
  have=$("$cli" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -n1 || true)
  [ -n "$have" ] || return 0
  say "Claude Code $have"
  lowest=$(printf '%s\n%s\n' "$have" "$MIN_VERSION" | sort -V | head -n1)
  [ "$lowest" = "$MIN_VERSION" ] || fail "This build is $have; Remote Control needs $MIN_VERSION or later. Run set-up again to update it."
}

# --------------------------------------------------------------------------- sign in

# The publisher's own answer, parsed rather than guessed at. `auth status` prints JSON with a
# loggedIn field; this is the same check PocketAgent has used against this CLI for months.
signed_in() {
  cli=$(claude_path) || return 1
  "$cli" auth status 2>/dev/null \
    | python3 -c 'import json,sys
try:
    sys.exit(0 if json.load(sys.stdin).get("loggedIn") else 1)
except Exception:
    sys.exit(1)' 2>/dev/null
}

do_login() {
  install_cli
  cli=$(claude_path) || fail "Claude Code is not installed."

  if signed_in; then
    say "Already signed in."
    say "SIGNEDIN"
    return 0
  fi

  # On a pty the CLI prints a link and waits. The app opens that link in the phone's real
  # browser -- Anthropic's sign-in, like Google's, will not run inside an embedded view.
  say "ASK Sign in to Anthropic. A link will appear; open it, then follow what it asks."
  if command -v script >/dev/null 2>&1; then
    TERM=xterm script --quiet --return --command "$cli auth login" /dev/null 2>&1 || true
  else
    say "This workspace has no pty, so the sign-in screen may not draw. Its output follows."
    "$cli" auth login 2>&1 || true
  fi

  if signed_in; then
    say "Signed in."
    say "SIGNEDIN"
  else
    fail "Sign-in did not finish. Open Claude Code again and complete it."
  fi
}

# --------------------------------------------------------------------------- run

start_session() {
  install_cli
  check_version
  cli=$(claude_path) || fail "Claude Code is not installed."

  if ! signed_in; then
    say "NEEDLOGIN Sign in to your Claude account first."
    fail "Not signed in yet."
  fi

  # Remote Control is on Pro and Max. Saying so before the session starts beats a session that
  # runs locally and never appears in the app, which is what a plan without it looks like.
  say "Starting Claude Code with Remote Control…"
  say "READY $SURFACE"
  say "Open the Claude app on this phone, go to Code, and your session will be in the list."

  # --remote-control from the first line, on a pty, held in the foreground. The session URL the
  # CLI prints is picked up by the app and offered as a link, so the owner can jump straight in
  # rather than hunting for it in a list.
  cd -- "$WORK"
  if command -v script >/dev/null 2>&1; then
    TERM=xterm script --quiet --return --command "$cli --remote-control" /dev/null 2>&1 || true
  else
    fail "This workspace has no pty. Remote Control needs a real terminal and will not start without one."
  fi

  say "The Claude Code session has ended."
}

stop_session() {
  pkill -f 'claude --remote-control' 2>/dev/null || true
  say "Stopped."
}

case "${1:-}" in
  install) install_cli; check_version ;;
  login)   do_login ;;
  start)   start_session ;;
  stop)    stop_session ;;
  status)  pgrep -f 'claude --remote-control' >/dev/null 2>&1 && say "running" || say "stopped" ;;
  *)       fail "Usage: doors-claude.sh install|login|start|stop|status" ;;
esac
