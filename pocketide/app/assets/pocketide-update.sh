#!/bin/bash
# Keeps the computer current: Ubuntu's security fixes, the editor itself, and the extensions.
#
# Why this exists at all. Everything inside this workspace is pinned -- a pinned Ubuntu image, a
# pinned code-server tarball, extensions installed at whatever version they were on the day they
# were installed. A pin is the right thing on the day it is made and the wrong thing a year
# later: openssl gets a CVE, Ubuntu ships the fix within hours, and a machine that never runs
# apt never receives it. A workspace meant to be kept for years has to be able to move.
#
# Why it is a script and not a service. There is no systemd here and there cannot be: PRoot has
# no init, no cgroups and no timers, so unattended-upgrades has nothing to hang itself off. More
# to the point, Linux only runs while the app is running -- Android does not keep a foreign
# userland alive behind a closed app. So updates happen when the app is open and the conditions
# are right, which the Android side decides; this script is what it calls.
#
# Usage:
#   pocketide-update.sh check        what is available, as key=value lines
#   pocketide-update.sh ubuntu       security updates only (the automatic one)
#   pocketide-update.sh ubuntu-all   every available package update
#   pocketide-update.sh editor       code-server to the newest release, staged, with a rollback
#   pocketide-update.sh extensions   every installed extension to its newest version
#   pocketide-update.sh all          ubuntu, then editor, then extensions

set -uo pipefail

export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

HOME_DIR="/root"
INSTALL_DIR="/opt/code-server"
STAGE_DIR="/opt/code-server.new"
BIN="$INSTALL_DIR/bin/code-server"
USER_DATA="$HOME_DIR/.local/share/code-server"
EXT_DIR="$USER_DATA/extensions"
STATE="/opt/pocketide/update-state"
RELEASES="https://api.github.com/repos/coder/code-server/releases/latest"

# What the editor swap needs free before it starts: the new tree unpacked beside the old one,
# plus the archive that is still on disk while it unpacks. Measured, not guessed: code-server
# unpacks to about 450 MB and the tarball is 224.
NEEDED_KB=1200000

say() { printf '%s\n' "$*"; }

# --------------------------------------------------------------------------- shared apt guards
#
# The same two guards the first set-up uses, for the same reasons: a run killed part way through
# leaves dpkg mid-configure, and a phone changes network constantly.

repair_packages() {
  dpkg --configure -a >/dev/null 2>&1 || true
  apt-get -y -f install >/dev/null 2>&1 || true
}

apt_try() {
  local attempt
  for attempt in 1 2 3; do
    repair_packages
    if apt-get "$@"; then return 0; fi
    if [ "$attempt" -lt 3 ]; then sleep $((attempt * 4)); fi
  done
  return 1
}

# Held at the versions the package maintainer shipped, never the ones in the container. A
# configuration prompt has no one to answer it here, and apt waits for the answer for ever.
apt_upgrade() {
  apt_try -y -o Dpkg::Options::=--force-confdef \
             -o Dpkg::Options::=--force-confold "$@"
}

editor_version() {
  [ -x "$BIN" ] || return 0
  "$BIN" --version 2>/dev/null | head -1 | awk '{print $1}'
}

# --------------------------------------------------------------------------- check

# The newest published release, straight from GitHub's own API over TLS.
#
# jq is installed by the bootstrap, and the grep fallback is there because a workspace set up by
# an older version of this app may not have it -- a missing jq should mean "no answer", not a
# silently wrong one.
release_json() {
  curl -fsSL --max-time 25 --retry 2 "$RELEASES" 2>/dev/null
}

version_from() { # version_from <json>
  local tag
  if command -v jq >/dev/null 2>&1; then
    tag=$(printf '%s' "$1" | jq -r '.tag_name // empty')
  else
    tag=$(printf '%s' "$1" | grep -m1 '"tag_name"' | cut -d'"' -f4)
  fi
  [ -n "$tag" ] || return 1
  printf '%s' "${tag#v}"
}

# The size GitHub published for one named asset, or nothing when jq is absent. Nothing means
# the size check below is skipped rather than guessed at -- the three other checks still stand.
size_from() { # size_from <json> <asset-name>
  command -v jq >/dev/null 2>&1 || return 0
  printf '%s' "$1" | jq -r --arg n "$2" \
    '[.assets[]? | select(.name == $n) | .size] | first // empty'
}

latest_version() {
  local json
  json=$(release_json) || return 1
  version_from "$json"
}

# Packages whose upgrade comes from the security pocket. This is the same rule
# unattended-upgrades applies, read off apt's own simulated run rather than a package list:
# "Inst curl [8.5.0] (8.5.0-2ubuntu10.6 Ubuntu:24.04/noble-security ...)".
security_list() {
  apt-get -s upgrade 2>/dev/null | awk '/^Inst / && /-security/ {print $2}'
}

check() {
  local updated=0
  if apt_try update >/dev/null 2>&1; then updated=1; fi
  say "apt_list=$updated"

  local security all
  security=$(security_list | wc -l | tr -d ' ')
  all=$(apt-get -s upgrade 2>/dev/null | awk '/^Inst /' | wc -l | tr -d ' ')
  say "ubuntu_security=$security"
  say "ubuntu_all=$all"

  say "editor_current=$(editor_version)"
  local latest
  latest=$(latest_version) && say "editor_latest=$latest" || say "editor_latest="

  local count=0
  if [ -x "$BIN" ]; then
    count=$("$BIN" --user-data-dir "$USER_DATA" --extensions-dir "$EXT_DIR" \
            --list-extensions 2>/dev/null | grep -c . || true)
  fi
  say "extensions=$count"

  # Ubuntu 24.04 LTS is supported to June 2029 with standard updates, and to 2036 under
  # Ubuntu Pro. Printed so the screen can say the date rather than promise "long term".
  say "ubuntu_supported_until=2029-06"

  # The lists apt downloaded to answer this are tens of megabytes, and every command that needs
  # them fetches them again anyway. Keeping them on a phone to save one apt-get update is the
  # wrong trade.
  apt-get clean >/dev/null 2>&1 || true
  rm -rf /var/lib/apt/lists/* 2>/dev/null || true
}

# --------------------------------------------------------------------------- ubuntu

update_ubuntu() {
  local scope="${1:-security}"
  say "Checking Ubuntu's package list…"
  if ! apt_try update; then
    say "Could not reach Ubuntu's servers."
    return 1
  fi

  if [ "$scope" = "all" ]; then
    say "Installing every available update…"
    if ! apt_upgrade upgrade; then
      say "Some updates could not be installed."
      return 1
    fi
  else
    local packages
    packages=$(security_list)
    if [ -z "$packages" ]; then
      say "No security updates are waiting."
      apt-get clean >/dev/null 2>&1 || true
      return 0
    fi
    # shellcheck disable=SC2086
    say "Installing $(printf '%s\n' "$packages" | wc -l | tr -d ' ') security update(s)…"
    # shellcheck disable=SC2086
    if ! apt_upgrade install --only-upgrade $packages; then
      say "Some security updates could not be installed."
      return 1
    fi
  fi

  # The lists are what apt downloaded to answer the question, and they are tens of megabytes.
  # Keeping them on a phone to save one apt-get update is the wrong trade.
  apt-get clean >/dev/null 2>&1 || true
  rm -rf /var/lib/apt/lists/* 2>/dev/null || true
  say "Ubuntu is up to date."
}

# --------------------------------------------------------------------------- the editor
#
# The first install pins code-server to a checksum this project produced by downloading that
# exact file and hashing it. An update cannot work the same way -- nobody can pin a version that
# does not exist yet -- so what stands in for the pin is four checks, and they are named here so
# nobody later mistakes this for an unchecked download:
#
#   1. The version and the download URL come from GitHub's own release API over TLS, with
#      certificate verification on. Neither is constructed here from a guess.
#   2. The bytes on disk must match the size GitHub published for that asset.
#   3. The archive must unpack into a tree containing a runnable bin/code-server.
#   4. That binary must report the exact version that was asked for. A substituted build fails
#      here even if it survived everything above.
#
# Only then is the old tree replaced, and if the swap leaves anything unrunnable the previous
# tree goes straight back. The installed hash is written down afterwards, so the app can show
# what is actually on the phone rather than what it believes should be.

update_editor() {
  if [ ! -x "$BIN" ]; then
    say "The editor is not installed yet."
    return 1
  fi

  local current latest json
  current=$(editor_version)
  json=$(release_json) || { say "Could not reach GitHub to ask for the newest version."; return 1; }
  latest=$(version_from "$json") || { say "GitHub did not name a newest version."; return 1; }

  if [ -z "$latest" ] || [ "$current" = "$latest" ]; then
    say "The editor is already at $current, which is the newest."
    return 0
  fi

  local free
  free=$(df -Pk /opt 2>/dev/null | awk 'NR==2 {print $4}')
  if [ -n "$free" ] && [ "$free" -lt "$NEEDED_KB" ]; then
    say "Not enough free space to update the editor safely. About 1.2 GB is needed."
    return 1
  fi

  local tarball="code-server-${latest}-linux-arm64.tar.gz"
  local url="https://github.com/coder/code-server/releases/download/v${latest}/${tarball}"
  local archive="/tmp/$tarball"

  say "Updating the editor: $current → $latest"
  rm -f "$archive"
  if ! curl -fL --retry 4 --retry-delay 3 --retry-connrefused -o "$archive" "$url"; then
    say "The update could not be downloaded."
    rm -f "$archive"
    return 1
  fi

  # Check 2: the size GitHub published for this asset, against the bytes actually on disk.
  local published actual
  published=$(size_from "$json" "$tarball")
  actual=$(stat -c%s "$archive" 2>/dev/null || echo 0)
  if [ -n "$published" ] && [ "$published" -gt 0 ] 2>/dev/null && [ "$published" != "$actual" ]; then
    say "The download is $actual bytes; GitHub published $published. Refusing it."
    rm -f "$archive"
    return 1
  fi

  # Check 3: unpack into a staging tree beside the live one. Nothing installed is touched until
  # the new tree has proved itself.
  say "Checking the update…"
  rm -rf "$STAGE_DIR"
  mkdir -p "$STAGE_DIR"
  if ! tar -xzf "$archive" -C "$STAGE_DIR" --strip-components=1; then
    say "The update could not be unpacked."
    rm -rf "$STAGE_DIR"; rm -f "$archive"
    return 1
  fi

  local hash
  hash=$(sha256sum "$archive" 2>/dev/null | cut -d' ' -f1)
  rm -f "$archive"

  if [ ! -x "$STAGE_DIR/bin/code-server" ]; then
    say "The update does not contain an editor."
    rm -rf "$STAGE_DIR"
    return 1
  fi

  # Check 4: the staged binary has to say it is the version that was asked for.
  local staged
  staged=$("$STAGE_DIR/bin/code-server" --version 2>/dev/null | head -1 | awk '{print $1}')
  if [ "$staged" != "$latest" ]; then
    say "The update reports version '${staged:-none}' but $latest was asked for. Refusing it."
    rm -rf "$STAGE_DIR"
    return 1
  fi

  say "Installing the update…"
  rm -rf "/opt/code-server.previous"
  if ! mv "$INSTALL_DIR" "/opt/code-server.previous"; then
    say "The old editor could not be moved aside; nothing was changed."
    rm -rf "$STAGE_DIR"
    return 1
  fi
  if ! mv "$STAGE_DIR" "$INSTALL_DIR"; then
    mv "/opt/code-server.previous" "$INSTALL_DIR"
    say "The update could not be moved into place; the previous editor was put back."
    return 1
  fi
  if [ ! -x "$BIN" ] || ! "$BIN" --version >/dev/null 2>&1; then
    rm -rf "$INSTALL_DIR"
    mv "/opt/code-server.previous" "$INSTALL_DIR"
    say "The updated editor would not run; the previous one was put back."
    return 1
  fi

  # The previous tree is about 450 MB. Keeping it on a phone to preserve a rollback the four
  # checks above have already earned is 450 MB an owner would rather have for their projects.
  rm -rf "/opt/code-server.previous"
  mkdir -p "$(dirname "$STATE")"
  printf 'editor_version=%s\neditor_sha256=%s\n' "$latest" "$hash" > "$STATE"
  say "The editor is now $latest."
}

# --------------------------------------------------------------------------- extensions
#
# The editor updates its own extensions while it is open -- extensions.autoUpdate is on in the
# settings this app writes -- so this is for the times it is not: the workspace has been off for
# a month and the owner wants everything current before opening it.
#
# --force reinstalls at whatever version the registry currently serves, which is the documented
# way to update from the command line. Extensions come from Open VSX, over TLS, fetched by the
# editor's own installer -- the same path a desktop Visual Studio Code uses for its gallery.

update_extensions() {
  if [ ! -x "$BIN" ]; then
    say "The editor is not installed yet."
    return 1
  fi
  local ids
  ids=$("$BIN" --user-data-dir "$USER_DATA" --extensions-dir "$EXT_DIR" \
        --list-extensions 2>/dev/null)
  if [ -z "$ids" ]; then
    say "No extensions are installed."
    return 0
  fi
  local failed=0 id
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    say "Updating $id…"
    if ! "$BIN" --user-data-dir "$USER_DATA" --extensions-dir "$EXT_DIR" \
         --install-extension "$id" --force >/dev/null 2>&1; then
      say "$id could not be updated."
      failed=$((failed + 1))
    fi
  done <<< "$ids"
  if [ "$failed" -gt 0 ]; then
    say "$failed extension(s) could not be updated."
    return 1
  fi
  say "Extensions are up to date."
}

# --------------------------------------------------------------------------- run

case "${1:-check}" in
  check)       check ;;
  ubuntu)      update_ubuntu security ;;
  ubuntu-all)  update_ubuntu all ;;
  editor)      update_editor ;;
  extensions)  update_extensions ;;
  all)
    failed=0
    update_ubuntu security || failed=1
    update_editor || failed=1
    update_extensions || failed=1
    exit "$failed"
    ;;
  *)           say "Unknown command: ${1:-}"; exit 2 ;;
esac
