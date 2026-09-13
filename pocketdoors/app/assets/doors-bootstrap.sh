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

# man-db builds a search index in its post-install script. Under proot every syscall is traced,
# and that index takes minutes -- during which set-up shows one unchanging line and looks hung,
# which is exactly when somebody closes the app. proot runs with --kill-on-exit, so closing the
# app kills dpkg in the middle of configuring a package, and every install after that refuses to
# start. That is the whole chain behind "dpkg was interrupted". `man <page>` works without the
# index; only `apropos` and `man -k` need it, and `mandb` builds it whenever it is wanted.
echo 'man-db man-db/auto-update boolean false' | debconf-set-selections 2>/dev/null || true

# Put a half-applied dpkg right, before anything tries to install on top of it.
#
# A package interrupted between unpacking and configuring leaves dpkg in a state where it
# refuses every later install with "dpkg was interrupted, you must manually run
# 'dpkg --configure -a'" -- which is not something the owner of a phone can do, and which no
# amount of trying again fixes on its own. It runs on every start, costs nothing when nothing is
# broken, and is the difference between Try again working and Try again failing identically.
repair_packages() {
  dpkg --configure -a >/dev/null 2>&1 || true
  apt-get -y -f install >/dev/null 2>&1 || true
}

# Install, and if it fails, repair and try once more before giving up.
#
# The first failure is usually the half-applied state above rather than anything wrong with the
# package. Repairing and retrying turns a set-up that is permanently stuck into one that
# finishes, and a second failure is then a real one worth reporting.
install_packages() {
  what="$1"; shift
  repair_packages
  if apt-get install -y -qq "$@"; then return 0; fi
  step "Putting the package system back in order and trying $what once more…"
  repair_packages
  apt-get install -y -qq "$@"
}

repair_packages

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
  # A name that will not resolve is the one failure worth naming precisely: it means the
  # workspace has no DNS, not that the phone is offline, and the two need different answers.
  if ! apt-get update 2>&1 | tee /tmp/apt-update.log; then :; fi
  if grep -q 'Temporary failure resolving' /tmp/apt-update.log; then
    die "The workspace could not look up any address. Its resolver is empty -- close the app completely and open it again, and if that does not help, switch between mobile data and Wi-Fi once."
  fi

  # Prove the index actually arrived before trusting it. "No installation candidate" is what an
  # empty index looks like from the other end, and saying so here is far clearer.
  apt-cache policy ca-certificates 2>/dev/null | grep -q 'Candidate: [0-9]' \
    || die "Ubuntu's package list came back empty for this architecture. Try again on a different connection."

  install_packages "certificates" ca-certificates \
    || die "Certificates could not be installed, so nothing after this could be fetched securely."

  sed -i 's|http://ports.ubuntu.com|https://ports.ubuntu.com|g' /etc/apt/sources.list
  apt-get update -qq || die "Package sources failed after switching to HTTPS."
  mark apt-ready
fi

if ! done_with basics; then
  step "Installing the basics…"
  # curl and git because every agent fetches and commits; python3 because two of the
  # publishers' installers are Python; tzdata so commit times are the owner's own time.
  # gnupg because Google's repository is verified by a key that has to be dearmoured before
  # apt will trust it, and a key that cannot be dearmoured is a repository that cannot be
  # added. It is small and it is needed before anything else is fetched from a publisher.
  install_packages "the basics" curl git python3 python3-venv tzdata xz-utils procps gnupg \
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
  install_packages "Node" nodejs || die "Node could not be installed."
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
