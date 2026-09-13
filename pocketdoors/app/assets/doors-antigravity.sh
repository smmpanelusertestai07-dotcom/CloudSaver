#!/bin/bash
# Door A: Google's own Remote Control, with the daemon running here.
#
# Antigravity's documentation gives two hosts for a Remote Control session: the desktop
# application, or a headless daemon started by its CLI. The second one is what makes a phone
# enough on its own -- one process here, and Google's own dashboard as the screen.
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
set -eu

BASE=/opt/doors/antigravity
STATE=/var/lib/doors
PIDFILE="$STATE/antigravity.pid"
LOG="$STATE/antigravity.log"
SIGNED_IN="$STATE/antigravity.signed-in"

# Google's own dashboard. Note the domain: the documentation lives on antigravity.google, the
# dashboard is antigravity.google.com, and an earlier build of this app guessed a /remote path
# on the wrong one and handed the owner a 404. It is written down here, once, checked by a test,
# and never assembled from a guess again.
DASHBOARD="https://antigravity.google.com"

# The CLI updates itself, so what this needs is the current build, not a build from the day the
# app was written. Google's own installer reads a manifest that names the version, the download
# and its sha512; this reads the same manifest, which is what makes "auto update" true rather
# than a word in a README.
MANIFEST="https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"

# The last build this app verified by hand, kept as a floor. If the manifest cannot be reached
# the door still opens on a known-good copy instead of refusing to start.
FLOOR_URL="https://storage.googleapis.com/antigravity-public/antigravity-cli/1.2.2-6061403484848128/linux-arm/cli_linux_arm64.tar.gz"
FLOOR_SHA512="a1645a30f36b767c7534c2f6a53e99a9bfade993267efcca715f7a45d797d47d6561df787e9d4a51a3bdfc9be855d49d23fa3f4b91b2c661fd17314050836048"

mkdir -p "$STATE"
say() { printf '%s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

agy_path() {
  if [ -x "$BASE/agy" ]; then printf '%s\n' "$BASE/agy"; return 0; fi
  command -v agy 2>/dev/null && return 0
  return 1
}

# --------------------------------------------------------------------------- install

# Reads one string out of the manifest. python3 is installed by the bootstrap, so this is a
# real JSON parse rather than a regular expression that a reformatted file would defeat.
manifest_field() {
  printf '%s' "$1" | python3 -c \
    'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$2" 2>/dev/null || true
}

install_cli() {
  if agy_path >/dev/null; then
    say "Antigravity CLI is already here."
    return 0
  fi

  url=""
  sha512=""
  say "Checking Google's release manifest…"
  manifest=$(curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
    --retry 2 --max-time 60 "$MANIFEST" 2>/dev/null || true)
  if [ -n "$manifest" ]; then
    url=$(manifest_field "$manifest" url)
    sha512=$(manifest_field "$manifest" sha512)
    version=$(manifest_field "$manifest" version)
    [ -n "$version" ] && say "Latest Antigravity CLI: $version"
  fi

  # A manifest that names a download somewhere other than Google's own storage is not one to
  # follow, however well-formed it looks.
  case "$url" in
    https://storage.googleapis.com/antigravity-public/*) : ;;
    *) url=""; sha512="" ;;
  esac

  if [ -z "$url" ] || [ -z "$sha512" ]; then
    say "Using the last build this app verified."
    url="$FLOOR_URL"
    sha512="$FLOOR_SHA512"
  fi

  say "Downloading the Antigravity CLI…"
  tmp=$(mktemp -d)
  # Resumable, and given real time. This archive is around 50 MB and a phone on mobile data can
  # take a while; a download cut off by a short deadline is how the first attempt failed.
  if ! curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
      --retry 5 --retry-delay 2 --continue-at - --max-time 3600 \
      "$url" -o "$tmp/cli.tar.gz"; then
    rm -rf "$tmp"
    fail "The Antigravity CLI could not be downloaded. Check the connection and try again."
  fi

  printf '%s  %s\n' "$sha512" "$tmp/cli.tar.gz" | sha512sum --check --status \
    || { rm -rf "$tmp"; fail "The Antigravity CLI download did not match Google's published checksum."; }

  say "Unpacking…"
  mkdir -p "$BASE"
  # Never --strip-components here. This archive holds exactly one entry, a file called
  # "antigravity" at the root, so stripping one component strips its whole name: tar extracts
  # nothing at all and still exits zero, and the next line reports an archive that "did not
  # contain the binary" when it was never unpacked. That is a real failure this shipped once.
  tar -xzf "$tmp/cli.tar.gz" -C "$BASE" \
    || { rm -rf "$tmp"; fail "The Antigravity archive would not unpack."; }
  rm -rf "$tmp"

  # The layout has changed between builds and may change again, so the binary is found by name
  # rather than assumed to be at a path. Google's own installer takes the file called
  # "antigravity" out of this archive and installs it as "agy"; both names are accepted.
  if [ ! -f "$BASE/agy" ]; then
    found=$(find "$BASE" -maxdepth 4 -type f \( -name agy -o -name antigravity \) -print -quit 2>/dev/null || true)
    if [ -z "$found" ]; then
      say "What the archive did contain:"
      find "$BASE" -maxdepth 2 -mindepth 1 -printf '%P\n' 2>/dev/null | head -n 20 || true
      fail "The archive did not contain the Antigravity binary."
    fi
    [ "$found" = "$BASE/agy" ] || mv "$found" "$BASE/agy"
  fi
  chmod +x "$BASE/agy"
  [ -x "$BASE/agy" ] || fail "The Antigravity binary unpacked but cannot be run."
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

# The daemon's own answer, not this script's guess about it.
daemon_up() {
  "$1" remote-control status 2>&1 | grep -qiE 'running|active|connected|online'
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

  if daemon_up "$agy"; then
    say "The Antigravity daemon is already running."
    say "READY $DASHBOARD"
    return 0
  fi

  # The documented command, and the only one. `agy remote-control` takes start, status, stop
  # and --name; it has no flag for staying in the foreground. An earlier build of this app
  # invented one, which would have been rejected as an unknown flag.
  say "Starting Remote Control…"
  if "$agy" remote-control start >>"$LOG" 2>&1; then
    # Google register the daemon with the OS service manager, and there is none inside this
    # workspace. Exit zero is therefore not proof; its own status is.
    if daemon_up "$agy"; then
      say "SERVICE the daemon is registered and running"
      say "READY $DASHBOARD"
      return 0
    fi
    say "The CLI accepted the command but the daemon is not reporting itself as running."
  fi

  say "Remote Control could not start. Its own words:"
  "$agy" remote-control status 2>&1 | tail -n 10 || true
  tail -n 20 "$LOG" 2>/dev/null || true
  fail "The Antigravity daemon would not start inside this workspace."
}

stop_daemon() {
  agy=$(agy_path 2>/dev/null || true)
  [ -n "$agy" ] && "$agy" remote-control stop >/dev/null 2>&1 || true
  if [ -f "$PIDFILE" ]; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
    rm -f "$PIDFILE"
  fi
  say "Stopped."
}

case "${1:-}" in
  install) install_cli ;;
  login)   do_login ;;
  start)   start_daemon ;;
  stop)    stop_daemon ;;
  status)  agy=$(agy_path 2>/dev/null || true)
           if [ -n "$agy" ] && daemon_up "$agy"; then say "running"; else say "stopped"; fi ;;
  *)       fail "Usage: doors-antigravity.sh install|login|start|stop|status" ;;
esac
