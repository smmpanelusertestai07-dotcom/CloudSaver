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

# Canonical's mirrors over HTTPS, and no interactive prompts: there is no terminal here to
# answer them, and a package that stops to ask would hang the set-up screen forever.
export DEBIAN_FRONTEND=noninteractive

if ! done_with apt-ready; then
  step "Setting up package sources…"
  # The base image has no certificate store, so the first fetch has to be plain HTTP from
  # Ubuntu's own host; ca-certificates is the first thing installed, and everything after it
  # goes over HTTPS.
  printf 'deb http://ports.ubuntu.com/ubuntu-ports noble main universe\ndeb http://ports.ubuntu.com/ubuntu-ports noble-updates main universe\ndeb http://ports.ubuntu.com/ubuntu-ports noble-security main universe\n' \
    > /etc/apt/sources.list
  printf 'APT::Install-Recommends "false";\nAPT::Install-Suggests "false";\nAcquire::Retries "3";\n' \
    > /etc/apt/apt.conf.d/99doors
  apt-get update -qq
  apt-get install -y -qq ca-certificates
  sed -i 's|http://ports.ubuntu.com|https://ports.ubuntu.com|g' /etc/apt/sources.list
  apt-get update -qq
  mark apt-ready
fi

if ! done_with basics; then
  step "Installing the basics…"
  # curl and git because every agent fetches and commits; python3 because two of the
  # publishers' installers are Python; tzdata so commit times are the owner's own time.
  apt-get install -y -qq curl git python3 python3-venv tzdata xz-utils procps
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
    https://deb.nodesource.com/setup_22.x -o /tmp/nodesource.sh
  bash /tmp/nodesource.sh >/dev/null 2>&1
  rm -f /tmp/nodesource.sh
  apt-get install -y -qq nodejs
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
