#!/bin/bash
# Installs, configures and runs the editor, and installs extensions into it.
#
# The editor is code-server: Coder's packaging of Code-OSS, which is the MIT-licensed source of
# Visual Studio Code. It ships the Node it was built against, so nothing here installs Node
# separately. There is no X display and no VNC in this app -- the interface is HTML, drawn by
# the phone's own browser engine, which is the only version of this that ever felt like a phone.
#
# Usage:
#   pocketide-editor.sh install
#   pocketide-editor.sh start
#   pocketide-editor.sh install-extension <id> [vsix-file]
#   pocketide-editor.sh remove-extension <id>
#   pocketide-editor.sh list-extensions

set -uo pipefail

export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

VERSION="4.137.0"
TARBALL="code-server-${VERSION}-linux-arm64.tar.gz"
URL="https://github.com/coder/code-server/releases/download/v${VERSION}/${TARBALL}"
# Verified by downloading this exact file and hashing it, not copied from a listing. code-server
# publishes no checksum file of its own, so this pin is the only thing standing between a
# hijacked answer and 224 MB of executable code being unpacked and run.
SHA256="0fba760298fe06480d940e218f0873a645ba1e7e3ac4b527059af82d85a90462"
BYTES=223794781

HOME_DIR="/root"
INSTALL_DIR="/opt/code-server"
BIN="$INSTALL_DIR/bin/code-server"
USER_DATA="$HOME_DIR/.local/share/code-server"
EXT_DIR="$HOME_DIR/.local/share/code-server/extensions"
CONFIG="$HOME_DIR/.config/code-server/config.yaml"
PROJECTS="$HOME_DIR/projects"
PORT="${PIDE_PORT:-8391}"
PASSWORD="${PIDE_PASSWORD:-}"
# code-server never sees the plain password in its config. The app computes sha256 of it and
# passes the digest; if it ever arrives empty this derives the identical value, because both
# sides are plain SHA-256 of the same string. See configure() for why it is the digest and not
# the password that goes in the file.
HASHED_PASSWORD="${PIDE_HASHED_PASSWORD:-}"

say() { printf '%s\n' "$*"; }

# --------------------------------------------------------------------------- install

install_editor() {
  if [ -x "$BIN" ]; then
    say "The editor is already installed."
    return 0
  fi

  local archive="/tmp/$TARBALL"
  local have=0
  [ -f "$archive" ] && have=$(stat -c%s "$archive" 2>/dev/null || echo 0)

  if [ "$have" != "$BYTES" ]; then
    say "Downloading the editor… 224 MB"
    # --continue-at resumes a partial file, because a phone loses signal mid-download and
    # starting 224 MB again is how an owner's data allowance disappears.
    if ! curl -fL --retry 4 --retry-delay 3 --retry-connrefused \
         --continue-at - -o "$archive" "$URL"; then
      # A resume can fail against a server that will not honour the range; one clean retry.
      rm -f "$archive"
      if ! curl -fL --retry 4 --retry-delay 3 -o "$archive" "$URL"; then
        say "The editor could not be downloaded."
        return 1
      fi
    fi
  fi

  say "Checking the download…"
  local actual
  actual=$(sha256sum "$archive" 2>/dev/null | cut -d' ' -f1) || true
  if [ "$actual" != "$SHA256" ]; then
    # Refused and deleted, not refused and kept: leaving it would make the next attempt trust
    # a file this one already decided not to.
    rm -f "$archive"
    say "The editor download did not match its checksum and was discarded."
    return 1
  fi

  say "Unpacking the editor…"
  rm -rf "$INSTALL_DIR"
  mkdir -p "$INSTALL_DIR"
  # --strip-components=1 removes the single versioned top-level folder the archive carries.
  # Checked against the real archive rather than assumed: an earlier project stripped a level
  # that was not there and extracted nothing at all, silently.
  if ! tar -xzf "$archive" -C "$INSTALL_DIR" --strip-components=1; then
    say "The editor archive could not be unpacked."
    return 1
  fi
  rm -f "$archive"

  if [ ! -x "$BIN" ]; then
    say "The editor unpacked but its program is missing."
    return 1
  fi

  configure
  say "The editor is installed."
}

# --------------------------------------------------------------------------- configure

configure() {
  # ~/phone is created whether or not the phone's storage is switched on, because PRoot binds
  # onto a path that already exists and the switch can be flipped between one start and the
  # next. An empty folder costs nothing; a bind with nowhere to land fails the whole start.
  mkdir -p "$(dirname "$CONFIG")" "$USER_DATA/User" "$EXT_DIR" "$PROJECTS" "$HOME_DIR/phone"

  if [ -z "$HASHED_PASSWORD" ] && [ -n "$PASSWORD" ]; then
    HASHED_PASSWORD=$(printf '%s' "$PASSWORD" | sha256sum | cut -d' ' -f1)
  fi
  if [ -z "$HASHED_PASSWORD" ]; then
    say "No password was supplied for the editor; refusing to start it unprotected."
    return 1
  fi

  # Loopback only, with a password. Android does not keep loopback private between apps, so
  # without the password any other app on this phone could open the editor and read every file
  # in the workspace. The password is generated on the phone and never leaves it.
  #
  # hashed-password, not password, and that choice is what lets the app open the editor without
  # ever showing a sign-in box. code-server takes hashed-password only from a config file
  # (out/node/cli.js refuses it on the command line), treats a digest with no "$argon" in it as
  # SHA256, and under that method its session cookie is the digest itself -- so the app can set
  # the cookie directly instead of reimplementing argon2. The typed password still signs in
  # normally, because the form check is sha256(typed) against this same line.
  cat > "$CONFIG" <<EOF
bind-addr: 127.0.0.1:${PORT}
auth: password
hashed-password: "${HASHED_PASSWORD}"
cert: false
disable-telemetry: true
disable-update-check: true
EOF
  chmod 600 "$CONFIG"

  write_settings
}

# The phone layout, written with Visual Studio Code's own documented settings. None of this is
# a patch or an injected stylesheet -- it is the same settings file a person would edit by hand.
#
# The numbers matter. A phone WebView gets roughly 400 density-independent pixels of width, and
# VS Code's desktop layout assumes three times that: the activity bar alone takes 48 and the
# sidebar 300. Hiding the chrome is not cosmetic, it is what makes the remaining space usable.
#
# The two update settings look contradictory and are not, so they are worth a line each.
#
#   extensions.autoCheckUpdates and extensions.autoUpdate are BOTH on. Extensions come from
#   Open VSX, and while the editor is open it keeps them current by itself -- an agent
#   extension a month out of date is an agent missing a month of its publisher's fixes.
#
#   update.mode is "none" because it governs updating the EDITOR, and code-server cannot
#   update itself: it is a tarball, not a package, and its own updater is compiled out. Left
#   on, it would show a notification offering an update that could never install. The app
#   updates the editor instead, from Settings, with the checks in pocketide-update.sh.
write_settings() {
  local layout zoom
  layout="${PIDE_LAYOUT:-phone}"
  zoom="${PIDE_ZOOM:-1.5}"

  if [ "$layout" = "desktop" ]; then
    cat > "$USER_DATA/User/settings.json" <<EOF
{
  "window.zoomLevel": ${zoom},
  "telemetry.telemetryLevel": "off",
  "update.mode": "none",
  "workbench.startupEditor": "none",
  "security.workspace.trust.enabled": false,
  "extensions.autoCheckUpdates": true,
  "extensions.autoUpdate": true
}
EOF
    return 0
  fi

  cat > "$USER_DATA/User/settings.json" <<EOF
{
  "window.commandCenter": true,
  "workbench.activityBar.location": "bottom",
  "workbench.statusBar.visible": false,
  "workbench.editor.showTabs": "none",
  "workbench.startupEditor": "none",
  "workbench.tips.enabled": false,
  "window.zoomLevel": ${zoom},
  "editor.minimap.enabled": false,
  "editor.fontSize": 13,
  "editor.lineNumbers": "on",
  "editor.wordWrap": "on",
  "editor.stickyScroll.enabled": false,
  "terminal.integrated.fontSize": 13,
  "explorer.compactFolders": false,
  "telemetry.telemetryLevel": "off",
  "update.mode": "none",
  "security.workspace.trust.enabled": false,
  "extensions.autoCheckUpdates": true,
  "extensions.autoUpdate": true
}
EOF
}

# --------------------------------------------------------------------------- extensions

# An extension is installed from a file that has already been checked, never straight from a
# URL. The app downloads the .vsix, compares it against the checksum the registry publishes,
# and only then calls this -- so a registry that has been compromised between the listing and
# the download cannot put code into the workspace.
install_extension() {
  local id="$1"
  local vsix="${2:-}"
  if [ -z "$id" ]; then say "No extension was named."; return 1; fi
  if [ ! -x "$BIN" ]; then say "The editor is not installed yet."; return 1; fi

  if [ -n "$vsix" ] && [ -f "$vsix" ]; then
    say "Installing $id…"
    if ! "$BIN" --user-data-dir "$USER_DATA" --extensions-dir "$EXT_DIR" \
         --install-extension "$vsix" --force; then
      say "$id could not be installed."
      return 1
    fi
    rm -f "$vsix"
  else
    say "Installing $id from Open VSX…"
    if ! "$BIN" --user-data-dir "$USER_DATA" --extensions-dir "$EXT_DIR" \
         --install-extension "$id" --force; then
      say "$id could not be installed."
      return 1
    fi
  fi
  say "Installed $id."
}

remove_extension() {
  local id="$1"
  [ -x "$BIN" ] || { say "The editor is not installed yet."; return 1; }
  "$BIN" --user-data-dir "$USER_DATA" --extensions-dir "$EXT_DIR" \
    --uninstall-extension "$id" || true
  say "Removed $id."
}

list_extensions() {
  [ -x "$BIN" ] || return 0
  "$BIN" --user-data-dir "$USER_DATA" --extensions-dir "$EXT_DIR" --list-extensions 2>/dev/null || true
}

# --------------------------------------------------------------------------- run

start_editor() {
  # An update that was interrupted mid-swap leaves the working editor beside the hole it was
  # meant to fill, under .previous. Renaming it back costs nothing and is the difference
  # between opening the editor and being asked to download 224 MB again. See
  # pocketide-update.sh, which owns the swap and does the same on every entry point.
  if [ ! -x "$BIN" ] && [ -x "/opt/code-server.previous/bin/code-server" ]; then
    say "A previous update did not finish. Putting the last working editor back…"
    rm -rf "$INSTALL_DIR"
    mv "/opt/code-server.previous" "$INSTALL_DIR" || true
  fi
  if [ ! -x "$BIN" ]; then say "The editor is not installed."; return 1; fi
  configure

  # Node's default heap is sized for a server, not for a phone sharing four gigabytes with
  # Android. Left alone, the editor plus an extension host is what pushes the phone into
  # reclaiming the app mid-session.
  export NODE_OPTIONS="--max-old-space-size=512"

  say "Starting the editor on 127.0.0.1:${PORT}…"
  "$BIN" \
    --config "$CONFIG" \
    --user-data-dir "$USER_DATA" \
    --extensions-dir "$EXT_DIR" \
    --disable-telemetry \
    --disable-update-check \
    --disable-workspace-trust \
    "$PROJECTS" &
  local pid=$!

  # The app waits for this exact line before pointing the WebView at the editor. Printing it
  # before the port answers would show the owner a connection error instead of an editor, so
  # the port is polled first -- an earlier project in this repo printed the marker too early
  # and spent a release believing a working server had failed.
  local waited=0
  while [ "$waited" -lt 90 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      say "The editor stopped while starting."
      return 1
    fi
    if (exec 3<>/dev/tcp/127.0.0.1/"$PORT") 2>/dev/null; then
      exec 3>&- 2>/dev/null || true
      say "PIDE-READY http://127.0.0.1:${PORT}/"
      wait "$pid"
      return $?
    fi
    sleep 1
    waited=$((waited + 1))
    if [ $((waited % 10)) -eq 0 ]; then
      say "The editor is still starting… ${waited}s"
    fi
  done
  say "The editor did not answer within 90 seconds."
  kill "$pid" 2>/dev/null || true
  return 1
}

case "${1:-start}" in
  install)            install_editor ;;
  start)              start_editor ;;
  configure)          configure ;;
  install-extension)  install_extension "${2:-}" "${3:-}" ;;
  remove-extension)   remove_extension "${2:-}" ;;
  list-extensions)    list_extensions ;;
  *)                  say "Unknown command: ${1:-}"; exit 2 ;;
esac
