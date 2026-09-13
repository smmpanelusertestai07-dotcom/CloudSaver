#!/bin/bash
# Everything the doors need, and nothing a desktop would.
#
# PocketLinux's Ubuntu carries an X display, a window manager, a file manager and a browser.
# None of that is installed here. An agent reached through its publisher's own interface needs
# a shell, a network, certificates, git, and Node -- so that is the whole list.
#
# Runs again safely. Each step checks before it acts, and the whole script is re-run on every
# app update so a new version's requirements arrive without a fresh download.
set -eu

STATE=/var/lib/doors
mkdir -p "$STATE" /opt/doors /root/work

step() { printf '%s\n' "$1"; }
done_with() { [ -f "$STATE/$1" ]; }
mark() { : > "$STATE/$1"; }
die() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

# There is no terminal here to answer a package's questions, and one that stopped to ask would
# hang the set-up screen forever.
export DEBIAN_FRONTEND=noninteractive

arch=$(dpkg --print-architecture 2>/dev/null || echo unknown)
[ "$arch" = "arm64" ] || die "This workspace is $arch; the agents are built for arm64."

if ! done_with apt-ready; then
  step "Setting up package sources…"

  # Ubuntu 24.04's base image ships /etc/apt/sources.list.d/ubuntu.sources in the newer deb822
  # format, and it points at archive.ubuntu.com -- which carries amd64 and i386 only. On an
  # arm64 phone every index there is a miss, so apt ends up with no candidate for anything and
  # the first install dies with "has no installation candidate" after a screenful of
  # duplicate-source warnings. ports.ubuntu.com is the host that actually carries arm64, and
  # the shipped list has to go rather than sit alongside: two lists is what produced the
  # duplicates, and the wrong one is what produced the failure.
  rm -f /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true

  # The base image has no certificate store, so the very first fetch cannot be HTTPS. It goes
  # to Ubuntu's own host over plain HTTP, installs ca-certificates, and switches immediately --
  # which is exactly what every Ubuntu container image does on its first run.
  printf 'deb http://ports.ubuntu.com/ubuntu-ports noble main universe\ndeb http://ports.ubuntu.com/ubuntu-ports noble-updates main universe\ndeb http://ports.ubuntu.com/ubuntu-ports noble-security main universe\n' \
    > /etc/apt/sources.list
  printf 'APT::Install-Recommends "false";\nAPT::Install-Suggests "false";\nAcquire::Retries "3";\n' \
    > /etc/apt/apt.conf.d/99doors

  # Not -qq. A failed update here is the difference between a working workspace and an error
  # nobody can read, and hiding it is what made the first attempt confusing.
  apt-get update || die "Could not reach Ubuntu's package servers. Check the connection and try again."

  # Prove the index actually arrived before trusting it. "No installation candidate" is what an
  # empty index looks like from the other end, and saying so here is far clearer.
  apt-cache policy ca-certificates 2>/dev/null | grep -q 'Candidate: [0-9]' \
    || die "Ubuntu's package list came back empty for this architecture. Try again on a different connection."

  apt-get install -y ca-certificates \
    || die "Certificates could not be installed, so nothing after this could be fetched securely."

  sed -i 's|http://ports.ubuntu.com|https://ports.ubuntu.com|g' /etc/apt/sources.list
  apt-get update -qq || die "Package sources failed after switching to HTTPS."
  mark apt-ready
fi

if ! done_with basics; then
  step "Installing the basics…"
  # curl and git because every agent fetches and commits; python3 because two of the
  # publishers' installers are Python; tzdata so commit times are the owner's own time.
  apt-get install -y -qq curl git python3 python3-venv tzdata xz-utils procps \
    || die "The basic tools could not be installed."
  mark basics
fi

# The phone's time zone, read at every start so it follows the owner across one.
if [ -n "${DOORS_TZ:-}" ] && [ -f "/usr/share/zoneinfo/$DOORS_TZ" ]; then
  ln -sf "/usr/share/zoneinfo/$DOORS_TZ" /etc/localtime
  printf '%s\n' "$DOORS_TZ" > /etc/timezone
fi

if ! done_with node; then
  step "Installing Node…"
  # Node 22 is what both publishers' packages ask for. NodeSource publishes an arm64 build;
  # Ubuntu's own package is too old for the extensions.
  curl --fail --show-error --silent --location --proto '=https' --tlsv1.2 \
    https://deb.nodesource.com/setup_22.x -o /tmp/nodesource.sh \
    || die "Node's installer could not be downloaded."
  bash /tmp/nodesource.sh >/dev/null 2>&1 || die "Node's package source could not be added."
  rm -f /tmp/nodesource.sh
  apt-get install -y -qq nodejs || die "Node could not be installed."
  node --version
  mark node
fi

# Git needs an identity before it will make a commit, and an agent that cannot commit is
# half useless. These are placeholders the owner can change; they never leave the phone.
if [ ! -f /root/.gitconfig ]; then
  git config --global user.name "PocketAgent"
  git config --global user.email "agent@localhost"
  git config --global init.defaultBranch main
  git config --global --add safe.directory '*'
fi

step "Workspace ready."
