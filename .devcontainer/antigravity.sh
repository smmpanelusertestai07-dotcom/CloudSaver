#!/usr/bin/env bash
# Antigravity Remote Control in a codespace.
#
#   install  postCreateCommand: Google's own installer puts agy in ~/.local/bin.
#   start    postStartCommand: a codespace has no systemd, so the daemon does
#            not come back by itself after the codespace stops. This starts it
#            again, but only on a codespace where you started it yourself once
#            and never stopped it (remote-control stop unregisters it).
#
# Signing in is yours alone, in a terminal: agy, then agy remote-control start.
set -uo pipefail

AGY="$HOME/.local/bin/agy"

case "${1:-}" in
install)
  [ -x "$AGY" ] || curl -fsSL https://antigravity.google/cli/install.sh | bash
  ;;
start)
  [ -x "$AGY" ] || exit 0
  status=$(timeout 30 "$AGY" remote-control status 2>&1)
  case "$status" in
  *"Daemon status: active"*) exit 0 ;;
  *"not assigned yet"*)
    echo "Antigravity: run 'agy', sign in, then 'agy remote-control start'."
    exit 0
    ;;
  esac
  # Its own session, so the daemon outlives this lifecycle command.
  timeout 120 setsid "$AGY" remote-control start </dev/null || true
  ;;
*)
  echo "usage: $0 install|start" >&2
  exit 2
  ;;
esac
