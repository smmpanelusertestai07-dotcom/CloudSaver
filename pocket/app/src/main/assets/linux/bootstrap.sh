#!/bin/bash
# Turns Ubuntu's base image into the computer the agents work in: Ubuntu's own package
# sources, the few tools every project needs, and settings that suit proot on a phone.
#
# Runs as proot's faked root, with no init system, no services and no terminal. Safe to run
# again: every step checks before it acts, and the app runs it again when an app update
# changes it.
#
# Lines starting with "pocketide-progress" move the app's progress bar, apt's own status
# lines (APT::Status-Fd) say which package is being fetched or set up, and
# "pocketide-installed <count>" says how many missing tools were installed (for Repair).
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

readonly PACKAGES=(
  ca-certificates git curl python3 procps less nano unzip xz-utils openssh-client
  locales tzdata gnupg
)
readonly MIRROR="ports.ubuntu.com/ubuntu-ports"
readonly SOURCES=/etc/apt/sources.list.d/ubuntu.sources
readonly STAMP=/opt/pocketide/bootstrap.stamp
readonly VERSION=1

say() { printf '%s\n' "$*"; }

progress() { printf 'pocketide-progress %s\n' "$1"; }

make_folders() {
  mkdir -p /opt/pocketide/bin /run/pocketide /work /repos /tmp /var/tmp
  chmod 1777 /tmp /var/tmp
}

# Nothing supervises services here, so no package may try to start one.
block_services() {
  printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
  chmod 755 /usr/sbin/policy-rc.d
}

configure_apt() {
  cat > /etc/apt/apt.conf.d/90pocketide <<'EOF'
// PocketIDE: a phone changes networks often, one dpkg at a time waits for the other,
// and nothing here reads translated package descriptions.
Acquire::Retries "3";
Acquire::Languages "none";
DPkg::Lock::Timeout "120";
Dpkg::Use-Pty "false";
EOF
  # Rebuilding the manual index takes minutes of silence under proot, and a set-up closed in
  # that silence leaves dpkg half configured. Nothing here reads man pages.
  echo 'man-db man-db/auto-update boolean false' | debconf-set-selections
}

# Ubuntu's own archive, checked by apt against the signed index with ubuntu-keyring. Plain
# HTTP until ca-certificates is installed, HTTPS from then on.
write_sources() {
  cat > "$SOURCES.new" <<EOF
Types: deb
URIs: $1://$MIRROR/
Suites: noble noble-updates noble-security
Components: main restricted universe multiverse
Architectures: arm64
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
  mv "$SOURCES.new" "$SOURCES"
}

# A run killed part way (a flat battery, Android reclaiming the app) leaves dpkg mid-configure,
# and every later apt run refuses to start until that is finished.
repair_packages() {
  dpkg --configure -a || true
  apt-get -y -f install || true
}

# A phone drops its connection often enough that one failed fetch must not end a set-up.
apt_try() {
  local attempt
  for attempt in 1 2 3; do
    if apt-get -o APT::Status-Fd=1 "$@"; then
      return 0
    fi
    if [ "$attempt" -lt 3 ]; then
      say "That did not go through. Trying again ($((attempt + 1)) of 3)…"
      sleep $((attempt * 5))
      repair_packages
    fi
  done
  return 1
}

missing_packages() {
  local package
  for package in "${PACKAGES[@]}"; do
    if ! dpkg-query -W -f='${Status}\n' "$package" 2>/dev/null | grep -qx 'install ok installed'; then
      printf '%s\n' "$package"
    fi
  done
}

install_packages() {
  local missing
  mapfile -t missing < <(missing_packages)
  if [ "${#missing[@]}" -eq 0 ]; then
    say "The tools are already installed."
    printf 'pocketide-installed 0\n'
    return 0
  fi
  write_sources http
  say "Updating the package list…"
  if ! apt_try update; then
    say "Could not reach Ubuntu's servers."
    return 1
  fi
  progress 30
  say "Installing tools…"
  if ! apt_try install -y --no-install-recommends "${missing[@]}"; then
    say "Could not install the tools."
    return 1
  fi
  printf 'pocketide-installed %s\n' "${#missing[@]}"
}

configure_git() {
  # proot fakes a hard link with symbolic links to a hidden file, and git's usual object write
  # links a temporary file into place. Renaming writes a plain object file that the app's own
  # git, outside Linux, reads as it is.
  git config --system core.createObject rename
  # Each room sees only its own worktrees, so from here another room's worktree looks deleted.
  git config --system gc.worktreePruneExpire never
  # proot's faked root does not own the files the app created.
  git config --system --replace-all safe.directory '*'
  git config --system init.defaultBranch main
  git config --system advice.detachedHead false
}

make_locale() {
  if ! locale -a 2>/dev/null | grep -qix 'en_US.utf8'; then
    locale-gen en_US.UTF-8
  fi
}

# The lists are tens of megabytes and stale within a day; every apt user here updates first.
tidy() {
  apt-get clean
  rm -rf /var/lib/apt/lists/*
}

say "Preparing Ubuntu…"
progress 0
make_folders
block_services
configure_apt
repair_packages
progress 10
install_packages
progress 90
write_sources https
configure_git
make_locale
tidy
printf 'version=%s\n' "$VERSION" > "$STAMP"
progress 100
say "Ubuntu is ready."
