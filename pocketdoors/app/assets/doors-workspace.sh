#!/bin/bash
# One editor, and all three agents inside it.
#
# Every earlier design had a different shape per maker: an extension for two of them and a
# command line for the third, plus a second place to see the same session for two of them and
# nothing for the other. That is three shapes wearing one name.
#
# There is one shape now, and it is decided by what the three actually publish for linux-arm64:
#
#   Google      Antigravity IDE, their own desktop application, from their own signed apt
#               repository. It is the editor. Nothing is typed at a prompt to reach it.
#   Anthropic   Anthropic.claude-code, their own extension, installed into that editor.
#   OpenAI      openai.chatgpt, their own extension, installed into that editor.
#
# That works because of one line in Google's own product.json, read out of the arm64 package
# they publish:
#
#     "extensionsGallery": { "serviceUrl": "https://open-vsx.org/vscode/gallery" }
#
# Antigravity's marketplace is Open VSX, which is exactly where Anthropic and OpenAI publish.
# So the other two makers' own interfaces install into Google's own interface, and every agent
# is reached the way its maker built it, in one window, with no command line anywhere.
#
#   doors-workspace.sh start <claude|codex|antigravity>
#   doors-workspace.sh stop
#   doors-workspace.sh status
set -eu

STATE=/var/lib/doors
WORK=/root/work
LOG="$STATE/workspace.log"

# ---------------------------------------------------------------------- Google's own repository
#
# An apt repository rather than a downloaded file, for one reason that matters more than any
# other on a phone: `apt upgrade` is the update path, it is Google's, and it keeps working
# without this app being rebuilt. Verified live against the repository's own index: suite
# antigravity-debian, component main, Architectures "all amd64 arm64", package "antigravity"
# published for arm64, with many versions in the pool.
AG_KEY_URL="https://us-central1-apt.pkg.dev/doc/repo-signing-key.gpg"
AG_REPO="https://us-central1-apt.pkg.dev/projects/antigravity-auto-updater-dev"
AG_SUITE="antigravity-debian"
AG_KEYRING=/etc/apt/keyrings/antigravity.gpg
AG_LIST=/etc/apt/sources.list.d/antigravity.list
AG_BIN=/usr/share/antigravity/bin/antigravity

# Their own identifiers on Open VSX, spelled the way each maker owns them. Google's agent is
# not here because it is not an extension: it is the editor.
EXT_claude="Anthropic.claude-code"
EXT_codex="openai.chatgpt"

# ---------------------------------------------------------------------- the screen
#
# A unix socket in this app's own private storage, not a port on loopback.
#
# Android does not keep loopback apart between applications: a display on 127.0.0.1 with no
# password is one any other app on this phone could open and watch. A socket under the app's
# private directory is one only this app can open. The port is kept as a fallback for a build
# of Xtigervnc without -rfbunixpath, and the app is told which of the two it got.
SOCK="$STATE/display.sock"
PORT=5901
GEOMETRY="${DOORS_GEOMETRY:-1280x720}"
DPI="${DOORS_DPI:-160}"

mkdir -p "$STATE" "$WORK"
chmod 700 "$STATE" 2>/dev/null || true
say() { printf '%s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive

# The same repair the bootstrap does, for the same reason and with more at stake: the editor is
# a 700 MB package, so it spends the longest of anything here in the state where an interruption
# leaves dpkg half-applied and every later install refusing to start. Costs nothing when nothing
# is broken.
repair_packages() {
  dpkg --configure -a >/dev/null 2>&1 || true
  apt-get -y -f install >/dev/null 2>&1 || true
}

# Install, and if it fails, repair and try once more. A second failure is a real one.
apt_install() {
  what="$1"; shift
  repair_packages
  if apt-get install -y -qq "$@" >>"$LOG" 2>&1; then return 0; fi
  say "Putting the package system back in order and trying $what once more…"
  repair_packages
  apt-get install -y -qq "$@" >>"$LOG" 2>&1
}

# Four variables, each of which switches Anthropic's Remote Control off without saying so, and a
# fifth that gets it refused. Nothing here sets them, but a workspace is a real Ubuntu.
unset DISABLE_TELEMETRY DO_NOT_TRACK CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC DISABLE_GROWTHBOOK
unset ANTHROPIC_BASE_URL

# ---------------------------------------------------------------------- install the editor

install_ide() {
  [ -x "$AG_BIN" ] && return 0

  free_mb=$(df -Pm /usr 2>/dev/null | awk 'NR==2 {print $4}')
  if [ -n "${free_mb:-}" ] && [ "$free_mb" -lt 1500 ]; then
    fail "Antigravity needs about 1.5 GB free and the workspace has ${free_mb} MB. Free some space on the phone and try again."
  fi

  if [ ! -s "$AG_KEYRING" ]; then
    say "Adding Google's signing key…"
    mkdir -p /etc/apt/keyrings
    curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
      --retry 5 --retry-delay 2 --max-time 120 "$AG_KEY_URL" -o "$STATE/antigravity.key" \
      || fail "Google's signing key could not be downloaded."
    # Armoured or binary, whichever they serve: dearmor handles the first and passes the second.
    gpg --dearmor < "$STATE/antigravity.key" > "$AG_KEYRING" 2>/dev/null \
      || cp "$STATE/antigravity.key" "$AG_KEYRING"
    chmod 644 "$AG_KEYRING"
    rm -f "$STATE/antigravity.key"
  fi

  # signed-by, so this key may vouch for this repository and nothing else. Without it a key
  # added for one publisher can sign packages claiming to be from any other.
  printf 'deb [arch=arm64 signed-by=%s] %s %s main\n' "$AG_KEYRING" "$AG_REPO" "$AG_SUITE" \
    > "$AG_LIST"

  say "Reading Google's package list…"
  apt-get update -o Dir::Etc::sourcelist="$AG_LIST" -o Dir::Etc::sourceparts=- \
    -o APT::Get::List-Cleanup=0 >>"$LOG" 2>&1 \
    || { tail -n 12 "$LOG" 2>/dev/null || true; fail "Google's package list could not be read."; }

  # Caught before the download rather than after: an index that carries no arm64 build is the
  # failure that reads as "no installation candidate" twenty minutes into a download.
  apt-cache policy antigravity 2>/dev/null | grep -q 'Candidate: [0-9]' \
    || fail "Google's repository is reachable but offers no Antigravity build for this phone."

  say "Installing Antigravity… (about 700 MB, downloaded once)"
  say "There is no progress line for this; apt does not give one per file. It is not stuck."
  apt_install "Antigravity" antigravity \
    || { tail -n 20 "$LOG" 2>/dev/null || true; fail "Antigravity could not be installed."; }

  [ -x "$AG_BIN" ] || fail "Antigravity installed but its program is not where the package puts it."
  say "Antigravity installed."
}

# The display server and a window manager for it. Antigravity is a desktop application: without
# a window manager its windows have no frames, no focus and no way to be moved, and dialogs open
# behind the editor where nobody can answer them.
install_screen() {
  need=""
  command -v Xtigervnc >/dev/null 2>&1 || need="$need tigervnc-standalone-server"
  command -v openbox   >/dev/null 2>&1 || need="$need openbox"
  command -v xdpyinfo  >/dev/null 2>&1 || need="$need x11-utils"
  fc-list >/dev/null 2>&1              || need="$need fonts-dejavu-core"
  [ -z "$need" ] && return 0
  say "Installing the screen the editor draws on…"
  apt_install "the screen" $need \
    || { tail -n 12 "$LOG" 2>/dev/null || true; fail "The display server could not be installed."; }
}

# ---------------------------------------------------------------------- the other two makers

install_extension() {
  id="$1"
  if "$AG_BIN" --list-extensions 2>&1 | grep -qi "^${id}$"; then
    return 0
  fi
  size=$(extension_size "$id")
  if [ -n "$size" ]; then
    say "Installing ${id} — ${size} MB, downloaded once."
    say "There is no progress line for this; Open VSX does not give one. It is not stuck."
  else
    say "Installing ${id}… (a few hundred megabytes, downloaded once)"
  fi
  # --force, because without it a second run stops to ask about a version already present, and
  # nothing here can answer a question asked on a pipe.
  "$AG_BIN" --install-extension "$id" --force >>"$LOG" 2>&1 \
    || { tail -n 12 "$LOG" 2>/dev/null || true
         fail "${id} could not be installed from Open VSX. Check the connection and try again."; }
  "$AG_BIN" --list-extensions 2>&1 | grep -qi "^${id}$" \
    || fail "${id} reported success but is not in the editor's extension list."
}

# Open VSX publishes the size of every build. Asking costs one small request and turns a silent
# hour into a number someone on mobile data can plan around.
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

# ---------------------------------------------------------------------- settings

# Written once. Anything the owner changes afterwards is theirs and is never overwritten.
#
# Two of these were paid for the hard way on this exact phone, in the other app: Antigravity's
# terminal draws with WebGL, WebGL here is SwiftShader on the processor, and a fault in that
# path takes the whole editor down with SIGSEGV. There is no graphics chip for it to use, so
# nothing is lost by turning it off -- xterm.js falls back to its DOM renderer. update.mode is
# manual because the package updates through apt, and an editor that also updates itself ends
# up fighting its own package manager.
phone_defaults() {
  user_dir=/root/.config/Antigravity/User
  mkdir -p "$user_dir"
  [ -f "$user_dir/settings.json" ] && return 0
  cat > "$user_dir/settings.json" <<'JSON'
{
  "terminal.integrated.gpuAcceleration": "off",
  "window.titleBarStyle": "native",
  "window.zoomLevel": -1,
  "workbench.activityBar.location": "top",
  "workbench.startupEditor": "none",
  "workbench.tips.enabled": false,
  "workbench.enableExperiments": false,
  "editor.wordWrap": "on",
  "editor.minimap.enabled": false,
  "editor.fontSize": 12,
  "terminal.integrated.fontSize": 12,
  "explorer.compactFolders": false,
  "update.mode": "manual",
  "telemetry.telemetryLevel": "off"
}
JSON
  say "Editor sized for this screen. Change anything in its own Settings; it will be kept."
}

# ---------------------------------------------------------------------- the screen, running

start_display() {
  rm -f "$SOCK" /tmp/.X11-unix/X1 /tmp/.X1-lock 2>/dev/null || true
  ON_SOCKET=false
  # -rfbunixpath keeps the display off loopback entirely. Older builds of Xtigervnc do not have
  # it, and a desktop that will not start at all is worse than one on a port, so the port is the
  # fallback -- and the app is told which it got rather than left to guess.
  if Xtigervnc -help 2>&1 | grep -q 'rfbunixpath'; then
    Xtigervnc :1 -rfbunixpath "$SOCK" -SecurityTypes None -ac -AlwaysShared -SendPrimary=0 \
      -geometry "$GEOMETRY" -depth 24 -dpi "$DPI" -desktop 'PocketAgent' >>"$LOG" 2>&1 &
    DISPLAY_PID=$!
    ON_SOCKET=true
  else
    Xtigervnc :1 -rfbport "$PORT" -localhost -SecurityTypes None -ac -AlwaysShared -SendPrimary=0 \
      -geometry "$GEOMETRY" -depth 24 -dpi "$DPI" -desktop 'PocketAgent' >>"$LOG" 2>&1 &
    DISPLAY_PID=$!
  fi

  for _ in $(seq 1 40); do
    if [ -S /tmp/.X11-unix/X1 ] && DISPLAY=:1 timeout 5 xdpyinfo >/dev/null 2>&1; then
      return 0
    fi
    kill -0 "$DISPLAY_PID" 2>/dev/null || {
      tail -n 15 "$LOG" 2>/dev/null || true
      fail "The screen would not start."
    }
    sleep 1
  done
  fail "The screen did not come up in time."
}

# ---------------------------------------------------------------------- run

start_workspace() {
  agent="${1:-antigravity}"
  install_ide
  install_screen
  phone_defaults

  case "$agent" in
    claude)      install_extension "$EXT_claude" ;;
    codex)       install_extension "$EXT_codex" ;;
    antigravity) : ;;   # Google's agent is the editor; there is nothing to add.
    *) fail "Unknown agent: $agent" ;;
  esac

  start_display
  export DISPLAY=:1

  openbox >>"$LOG" 2>&1 &
  sleep 1

  say "Starting Antigravity…"
  # Every one of these was earned, not chosen. --no-sandbox because a container cannot create
  # the namespaces Chromium's sandbox wants; --no-zygote because the fork path it replaces is
  # the one that fails under proot; --in-process-gpu and --disable-gpu because there is no
  # graphics chip and the separate process only adds one more thing to crash; --disable-3d-apis
  # because WebGL here is software and a fault in it is a fault in the whole editor.
  "$AG_BIN" \
    --no-sandbox --disable-setuid-sandbox --disable-gpu-sandbox \
    --no-zygote --in-process-gpu --disable-dev-shm-usage \
    --disable-gpu --disable-gpu-compositing --disable-3d-apis \
    --user-data-dir=/root/.config/Antigravity \
    "$WORK" >>"$LOG" 2>&1 &
  EDITOR_PID=$!

  # Proof it is still there, not just that it was started. An Electron application that dies on
  # its first frame exits within a second or two, and announcing READY before that check is how
  # a crash becomes "connected to a screen with nothing on it".
  sleep 4
  kill -0 "$EDITOR_PID" 2>/dev/null || {
    say "Antigravity stopped as it started. Its own words:"
    tail -n 20 "$LOG" 2>/dev/null || true
    fail "The editor did not stay running."
  }

  if [ "$ON_SOCKET" = true ]; then
    say "READY unix:${SOCK}"
  else
    say "READY tcp:127.0.0.1:${PORT}"
  fi

  # And then stay, for as long as the editor runs. proot is started with --kill-on-exit, so a
  # script that returned here would kill the screen and the editor it had just started.
  wait "$EDITOR_PID" 2>/dev/null || true
  say "The editor has closed."
  kill "$DISPLAY_PID" 2>/dev/null || true
  rm -f "$SOCK" 2>/dev/null || true
}

stop_workspace() {
  pkill -f "$AG_BIN" 2>/dev/null || true
  pkill -x openbox 2>/dev/null || true
  pkill -f 'Xtigervnc :1' 2>/dev/null || true
  rm -f "$SOCK" 2>/dev/null || true
  say "Stopped."
}

case "${1:-}" in
  install) install_ide; install_screen; phone_defaults ;;
  start)   start_workspace "${2:-antigravity}" ;;
  stop)    stop_workspace ;;
  status)  pgrep -f "$AG_BIN" >/dev/null 2>&1 && say "running" || say "stopped" ;;
  *)       fail "Usage: doors-workspace.sh install|start|stop|status [agent]" ;;
esac
