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
# What the editor costs, read from the repository's own index rather than guessed: 143 MB to
# fetch, 702 MB once unpacked. Saying the second number as if it were the first -- which this
# did -- tells somebody on mobile data to budget five times what they need.
AG_DOWNLOAD_MB=143
AG_INSTALLED_MB=702
AG_KEYRING=/etc/apt/keyrings/antigravity.gpg
AG_LIST=/etc/apt/sources.list.d/antigravity.list
# Two programs, and the difference is the whole of one bug.
#
# bin/antigravity is the command line. Its last line is
#   ELECTRON_RUN_AS_NODE=1 "$ELECTRON" "$CLI" "$@"
# -- it runs cli.js under Node, which spawns the editor detached and exits, which is exactly why
# `code .` gives you your prompt back. Launching through it and then watching that PID reported
# "the editor did not stay running" four seconds later, every time, while the editor was in fact
# starting perfectly as a different process.
#
# So: the command line for command-line work, and the editor itself for the editor.
AG_BIN=/usr/share/antigravity/bin/antigravity
AG_ELECTRON=/usr/share/antigravity/antigravity
# Where the editor keeps its settings and extensions. Not a preference: the launcher Google ship
# is Microsoft's, and it refuses to run at all as root unless --user-data-dir is on the command
# line. Everything in this workspace is root, so every single call needs it.
AG_DATA=/root/.config/Antigravity
# Named rather than left to the default, so the folder that fills up during an install is one
# this script can point a counter at.
AG_EXTENSIONS=/root/.antigravity/extensions

# Every call to the editor, with the two arguments its own launcher demands of a root user.
#
# From a real phone: "You are trying to start Antigravity as a super user which isn't
# recommended. If this was intended, please add the argument `--no-sandbox` and specify an
# alternate user data directory using the `--user-data-dir` argument." -- printed twice, then
# "openai.chatgpt could not be installed from Open VSX". The editor had installed perfectly; it
# was --install-extension and --list-extensions that were refused, because the flags were on the
# launch line and nowhere else. One wrapper now, so there is no second place to forget them.
editor() {
  "$AG_BIN" --no-sandbox --user-data-dir="$AG_DATA" --extensions-dir="$AG_EXTENSIONS" "$@"
}

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
# The phone's own screen, handed in by the app. The fallback is portrait on purpose: a landscape
# default on a portrait phone is what drew windows wider than the screen, with the editor's own
# dialogs half off the right-hand edge.
GEOMETRY="${DOORS_GEOMETRY:-720x1440}"
DPI="${DOORS_DPI:-160}"
# How much larger than its desktop default the editor draws, so a menu written for a mouse can
# be hit with a finger. Worked out from the screen's short side by the app.
SCALE="${DOORS_SCALE:-1.25}"

mkdir -p "$STATE" "$WORK"
chmod 700 "$STATE" 2>/dev/null || true
say() { printf '%s\n' "$1"; }
fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive

# The same repair the bootstrap does, for the same reason and with more at stake: the editor is
# a 702 MB package once unpacked, so it spends the longest of anything here in the state where
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

# A number, because neither apt nor Open VSX gives one.
#
# Neither prints anything per file, and a quarter of an hour of one unchanging line is what makes
# somebody close the app -- which is the thing that leaves dpkg half-applied. Both do write what
# they fetch somewhere on disk, though, so the size of that somewhere is real progress, and
# reading it costs one `du` every fifteen seconds.
#
# The minutes are said as well as the megabytes. On a connection that has stalled the bytes stop
# moving, and a line that still changes is the difference between "it is slow" and "it is dead" --
# which is the one question somebody watching a download actually has.
#
#   watch_size <expected MB, or 0 if unknown> <path>…
# One file, counted exactly.
#
# watch_size below reads a directory, which was right for apt -- it fills a cache nobody else
# touches. It was wrong for the extension: pointed at the extensions folder and /tmp, it read
# 865 MB where 231 was expected, because other things write there too. It then said "unpacking
# now" every fifteen seconds for the rest of the install. A file this script created is the
# only thing it can count without guessing.
#
#   watch_file <expected bytes> <path>
watch_file() {
  expected="$1"; path="$2"
  started=$(date +%s)
  last=-1
  while :; do
    sleep 15
    have=$(stat -c%s "$path" 2>/dev/null || echo 0)
    mb=$(( have / 1048576 ))
    want=$(( expected / 1048576 ))
    mins=$(( ($(date +%s) - started) / 60 ))
    if [ "$have" = "$last" ] && [ "$mins" -gt 0 ]; then
      say "Still ${mb} MB after ${mins} min. If this number has not moved for several minutes, the connection has stalled -- switching between mobile data and Wi-Fi restarts it."
    else
      say "Downloaded ${mb} MB of ${want} MB, ${mins} min in."
    fi
    last="$have"
  done
}

watch_size() {
  expected="$1"; shift
  started=$(date +%s)
  last=-1
  while :; do
    sleep 15
    have=$(du -scm "$@" 2>/dev/null | tail -n1 | awk '{print $1}')
    [ -n "${have:-}" ] || continue
    mins=$(( ($(date +%s) - started) / 60 ))
    if [ "$have" = "$last" ] && [ "$mins" -gt 0 ]; then
      say "Still ${have} MB after ${mins} min. If this number has not moved for several minutes, the connection has stalled -- switching between mobile data and Wi-Fi restarts it."
    elif [ "$expected" -gt 0 ] && [ "$have" -ge "$expected" ]; then
      say "Downloaded ${have} MB. Unpacking now -- that part has no number and takes a few minutes."
    elif [ "$expected" -gt 0 ]; then
      say "Downloaded ${have} MB of about ${expected} MB, ${mins} min in."
    else
      say "Downloaded ${have} MB, ${mins} min in."
    fi
    last="$have"
  done
}

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

  say "Downloading Antigravity… about ${AG_DOWNLOAD_MB} MB to fetch, ${AG_INSTALLED_MB} MB once unpacked. Once only."
  # Counted in the background for as long as the install runs, and stopped either way after it.
  watch_size "$AG_DOWNLOAD_MB" /var/cache/apt/archives &
  watcher=$!
  apt_install "Antigravity" antigravity \
    || { kill "$watcher" 2>/dev/null || true
         tail -n 20 "$LOG" 2>/dev/null || true; fail "Antigravity could not be installed."; }
  kill "$watcher" 2>/dev/null || true

  [ -x "$AG_BIN" ] || fail "Antigravity installed but its command line is not where the package puts it."
  [ -x "$AG_ELECTRON" ] || fail "Antigravity installed but the editor itself is not where the package puts it."
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

# What Open VSX will hand over for one extension, on this architecture: its address and its
# size, from the registry's own API, in one request.
extension_release() {   # extension_release <publisher.name> -> "<url> <bytes>"
  publisher=${1%%.*}
  name=${1#*.}
  curl --fail --silent --location --proto '=https' --max-time 40 \
      "https://open-vsx.org/api/${publisher}/${name}/linux-arm64/latest" 2>/dev/null \
    | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin)["files"]["download"])
except Exception:
    pass' 2>/dev/null \
    | while read -r href; do
        [ -n "$href" ] || continue
        bytes=$(curl -sIL --max-time 40 "$href" 2>/dev/null \
                | awk 'tolower($1) == "content-length:" { print $2 }' | tr -d '\r' | tail -n 1)
        printf '%s %s\n' "$href" "${bytes:-0}"
      done
}

# What the phone has left to work with, the way the kernel reports it.
free_mb() {
  awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null
}

install_extension() {
  id="$1"
  if editor --list-extensions 2>&1 | grep -qi "^${id}$"; then
    return 0
  fi

  # Fetched here rather than by the editor.
  #
  # Handing the editor an identifier makes it do the download itself, inside a Node ESM loader
  # worker, and on this phone that ended in "Aborted" after several hundred megabytes -- a
  # native abort, which on a 3.9 GB device running Electron is what running out of memory looks
  # like. Fetching it with curl instead takes that whole path out, and gives something the old
  # counter never had: an exact byte count, because it is one file this script created rather
  # than a `du` of a directory other things also write to.
  release=$(extension_release "$id")
  url=${release%% *}
  bytes=${release##* }
  [ -n "$url" ] && [ "${bytes:-0}" -gt 0 ] \
    || fail "Open VSX did not offer ${id} for this phone's architecture."
  total_mb=$(( bytes / 1048576 ))

  vsix="$STATE/${id}.vsix"
  say "Downloading ${id} — ${total_mb} MB, downloaded once."
  watch_file "$bytes" "$vsix" &
  fetcher=$!
  # Resumable, and with an hour to do it in: what arrived is kept, so a dropped connection costs
  # the time it was connected and nothing else.
  if ! curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
      --retry 5 --retry-delay 2 --continue-at - --max-time 3600 "$url" -o "$vsix"; then
    kill "$fetcher" 2>/dev/null || true
    fail "${id} could not be downloaded. What arrived is kept, so trying again resumes."
  fi
  kill "$fetcher" 2>/dev/null || true

  got=$(stat -c%s "$vsix" 2>/dev/null || echo 0)
  [ "$got" -eq "$bytes" ] \
    || fail "${id} arrived incomplete: ${got} bytes of ${bytes}. Trying again resumes."
  say "Downloaded ${total_mb} MB. Installing it into the editor now."

  # Unpacking a few hundred megabytes through Electron's Node is the memory peak of the whole
  # set-up, and it is where this aborted. Say what there is before spending it, and cap the heap
  # so Node gives up with a message instead of the process dying without one.
  have_mb=$(free_mb)
  if [ -n "${have_mb:-}" ] && [ "$have_mb" -lt 500 ]; then
    say "Only ${have_mb} MB of memory is free. Close other apps before this step if you can -- installing an extension is the heaviest moment of the whole set-up."
  fi

  if ! NODE_OPTIONS="--max-old-space-size=384" \
       editor --install-extension "$vsix" --force >>"$LOG" 2>&1; then
    say "The editor could not install it. Its own last words:"
    tail -n 12 "$LOG" 2>/dev/null || true
    if tail -n 40 "$LOG" 2>/dev/null | grep -qi 'aborted\|out of memory\|heap'; then
      say "That ending -- \"Aborted\" -- is what running out of memory looks like here. Close every other app and open this again; the download is kept, so only this step repeats."
    fi
    fail "${id} could not be installed."
  fi
  editor --list-extensions 2>&1 | grep -qi "^${id}$" \
    || fail "${id} reported success but is not in the editor's extension list."
  # Kept only until it is installed. A few hundred megabytes of archive is not worth the room
  # once the thing it contained is unpacked.
  rm -f "$vsix"
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
  user_dir="$AG_DATA/User"
  mkdir -p "$user_dir"
  [ -f "$user_dir/settings.json" ] && return 0
  cat > "$user_dir/settings.json" <<'JSON'
{
  "terminal.integrated.gpuAcceleration": "off",
  "window.titleBarStyle": "native",
  "window.zoomLevel": 0,
  "window.newWindowDimensions": "maximized",
  "window.restoreWindows": "none",
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

# Windows that fit the screen they are on.
#
# Openbox's defaults are a desktop's: a title bar, a border, and a window wherever the program
# asked to be put. On a phone that produced exactly what a real screenshot showed -- the editor
# wider than the display with its right-hand side off the edge, and its own crash dialog opened
# half outside the screen with its buttons unreachable.
#
# So two rules, and they are rules rather than a resize because a program can move itself back:
#   normal windows  no decoration, maximised -- the editor is the only thing here, so it gets
#                   all of it, and the title bar is a strip of nothing on a 720-pixel screen.
#   dialogs         centred, decorated, left at their own size -- a dialog asks a question, and
#                   a question whose buttons are off the screen cannot be answered.
window_rules() {
  rc=/root/.config/openbox/rc.xml
  mkdir -p "$(dirname "$rc")"
  [ -f "$rc" ] && return 0
  cat > "$rc" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc">
  <applications>
    <application class="*" type="normal">
      <decor>no</decor>
      <maximized>yes</maximized>
      <position force="yes"><x>0</x><y>0</y></position>
    </application>
    <application class="*" type="dialog">
      <decor>yes</decor>
      <position force="yes"><x>center</x><y>center</y></position>
    </application>
  </applications>
</openbox_config>
XML
  say "Windows set to fill this screen. Change them in the editor if you prefer."
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

  window_rules
  openbox >>"$LOG" 2>&1 &
  sleep 1

  say "Starting Antigravity…"
  # Every one of these was earned, not chosen. --no-sandbox because a container cannot create
  # the namespaces Chromium's sandbox wants; --no-zygote because the fork path it replaces is
  # the one that fails under proot; --in-process-gpu and --disable-gpu because there is no
  # graphics chip and the separate process only adds one more thing to crash; --disable-3d-apis
  # because WebGL here is software and a fault in it is a fault in the whole editor.
  # The editor itself, not the command line that launches it and leaves. This process is the
  # one the session is held open for.
  "$AG_ELECTRON" \
    --no-sandbox --user-data-dir="$AG_DATA" --extensions-dir="$AG_EXTENSIONS" \
    --disable-setuid-sandbox --disable-gpu-sandbox \
    --no-zygote --in-process-gpu --disable-dev-shm-usage \
    --disable-gpu --disable-gpu-compositing --disable-3d-apis \
    --force-device-scale-factor="$SCALE" \
    --js-flags=--max-old-space-size=384 \
    "$WORK" >>"$LOG" 2>&1 &
  EDITOR_PID=$!

  # Proof it is still there, not just that it was started, and long enough to mean it.
  #
  # Four seconds was never enough: an Electron editor on a phone under proot takes the better
  # part of a minute to draw its first frame, and the old check could only ever have passed by
  # luck. A minute of waiting is watched rather than slept through, so a start that is merely
  # slow says so and a start that has died says that instead.
  alive=false
  for waited in 5 10 15 20 30 40 50 60; do
    sleep 5
    if ! kill -0 "$EDITOR_PID" 2>/dev/null; then
      say "The editor stopped while starting. Its own last words:"
      tail -n 20 "$LOG" 2>/dev/null || true
      fail "The editor did not stay running."
    fi
    [ "$waited" -ge 20 ] && { alive=true; break; }
    say "The editor is starting… ${waited}s. The first start is the slow one."
  done
  [ "$alive" = true ] || fail "The editor did not finish starting."

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
