#!/bin/bash
# Turns a bare Ubuntu base image into something the editor and a coding agent can work in.
#
# Runs inside PRoot, as root, with no display and no service manager. Everything here is chosen
# for that: no systemd, no daemons, nothing that expects a tty.
#
# Every guard below exists because of a specific failure on a real phone. They are not defensive
# programming in the abstract -- each one has a name and a screenshot behind it.

set -uo pipefail

export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

say() { printf '%s\n' "$*"; }

# --------------------------------------------------------------------------- man-db
#
# The single worst failure this project has had. Building the manual index under PRoot takes
# several minutes of apparent silence; the owner reasonably concluded the app had hung and
# closed it; PRoot's --kill-on-exit then killed dpkg in the middle of configuring a package,
# and every later attempt died with "dpkg was interrupted" before doing anything at all.
#
# Nothing in this workspace reads a man page from an index. Turning the trigger off removes
# minutes of silence and the entire class of failure with it.
turn_off_man_index() {
  mkdir -p /etc/dpkg/dpkg.cfg.d /var/lib/dpkg/info
  printf 'path-exclude=/usr/share/man/*\npath-exclude=/usr/share/doc/*\n' \
    > /etc/dpkg/dpkg.cfg.d/01-pocketide-no-docs
  if command -v debconf-set-selections >/dev/null 2>&1; then
    echo 'man-db man-db/auto-update boolean false' | debconf-set-selections || true
  fi
  # Some images ship the trigger before debconf exists; neutering the binary is the fallback.
  if [ -x /usr/bin/mandb ]; then
    dpkg-divert --local --rename --add /usr/bin/mandb >/dev/null 2>&1 || true
    ln -sf /bin/true /usr/bin/mandb 2>/dev/null || true
  fi
}

# --------------------------------------------------------------------------- dpkg repair
#
# Called before every apt run rather than only after a failure. If a previous session was killed
# part way through -- a flat battery, Android reclaiming the app, the owner closing it -- this is
# what clears it, and running it when there is nothing to repair costs a fraction of a second.
repair_packages() {
  dpkg --configure -a >/dev/null 2>&1 || true
  apt-get -y -f install >/dev/null 2>&1 || true
}

# --------------------------------------------------------------------------- apt with retries
#
# A phone changes network mid-download constantly: Wi-Fi to mobile data, a lift, a dead spot.
# One failed fetch is not a reason to fail a twenty-minute set-up, so each step gets three
# attempts with a growing pause.
apt_try() {
  local attempt
  for attempt in 1 2 3; do
    repair_packages
    if apt-get "$@"; then
      return 0
    fi
    if [ "$attempt" -lt 3 ]; then
      say "That did not go through. Trying again ($((attempt + 1)) of 3)…"
      sleep $((attempt * 4))
    fi
  done
  return 1
}

install_packages() {
  local label="$1"; shift
  say "Installing $label…"
  if ! apt_try install -y --no-install-recommends "$@"; then
    say "Could not install $label."
    return 1
  fi
}

# --------------------------------------------------------------------------- run

say "Preparing Ubuntu…"
turn_off_man_index
repair_packages

mkdir -p /root/projects /tmp /var/tmp
chmod 1777 /tmp /var/tmp

# A container has no hostname file and apt complains about it on every single invocation.
if [ ! -s /etc/hostname ]; then echo pocketide > /etc/hostname; fi
if ! grep -q pocketide /etc/hosts 2>/dev/null; then
  printf '127.0.0.1\tlocalhost pocketide\n::1\tlocalhost ip6-localhost\n' >> /etc/hosts
fi

say "Updating the package list…"
if ! apt_try update; then
  say "Could not reach Ubuntu's servers."
  exit 1
fi

# ca-certificates first and on its own: every later download is HTTPS, and without this they
# all fail with a certificate error that reads like a network fault.
install_packages "certificates" ca-certificates || exit 1
update-ca-certificates >/dev/null 2>&1 || true

install_packages "core tools" curl wget gnupg git openssh-client xz-utils unzip tar less \
  ripgrep jq procps || exit 1

install_packages "Python and build tools" python3 python3-pip python3-venv build-essential \
  pkg-config || exit 1

# The editor brings its own Node, so none is installed here. That is deliberate: code-server
# pins the Node it was built against, and an Ubuntu Node beside it is 60 MB that can only
# disagree with it.

say "Cleaning up…"
apt-get clean >/dev/null 2>&1 || true
rm -rf /var/lib/apt/lists/* /usr/share/man /usr/share/doc 2>/dev/null || true

# A first project, so the editor opens on something rather than an empty folder with no
# explanation of what the owner is looking at.
if [ ! -e /root/projects/README.md ]; then
  cat > /root/projects/README.md <<'WELCOME'
# Your projects

This folder is on your phone, inside PocketIDE's own storage. Nothing here is synced anywhere,
and uninstalling the app deletes all of it.

To start something new, open the terminal from the menu and make a folder:

    mkdir my-app && cd my-app && git init

Or ask the agent in the side panel to do it for you.
WELCOME
fi

say "Ubuntu is ready."
exit 0
