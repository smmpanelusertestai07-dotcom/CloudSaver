#!/bin/bash
# Turns Ubuntu's base image into the computer the agents work in: Ubuntu's own package sources,
# the tools every project needs, and settings that suit PRoot on a phone.
#
# Runs as PRoot's faked root, with no init system, no services and no terminal. Safe to run
# again: every step checks before it acts, and the app runs it again when an app update
# changes it.
#
# Lines starting with "pocketide-progress" move the app's progress bar, "pocketide-step" lines
# name the step it shows, apt's own status lines (APT::Status-Fd) say which package is being
# fetched or set up, and "pocketide-installed <count>" says how many missing tools were
# installed (for Repair).
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

# What agents reach for first. Node.js comes from Ubuntu (security fixes through apt); npm from
# its own registry (install_npm), because Ubuntu's npm package pulls in over 300 others.
readonly PACKAGES=(
  ca-certificates git curl wget python3 python3-venv procps less nano unzip zip xz-utils
  openssh-client tzdata gnupg ripgrep jq file tree sqlite3 nodejs gh
)
readonly CODENAME=resolute
readonly SOURCES=/etc/apt/sources.list.d/ubuntu.sources
readonly STAMP=/opt/pocketide/bootstrap.stamp
readonly VERSION=1

say() { printf '%s\n' "$*"; }

# A step the app shows as the set-up's current activity.
step() { printf 'pocketide-step %s\n' "$*"; }

progress() { printf 'pocketide-progress %s\n' "$1"; }

make_folders() {
  mkdir -p /opt/pocketide/bin /root/projects /tmp /var/tmp
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
EOF
  # Rebuilding the manual index takes minutes of silence under PRoot, and a set-up closed in
  # that silence leaves dpkg half configured. Nothing here reads man pages.
  echo 'man-db man-db/auto-update boolean false' | debconf-set-selections
}

# Ubuntu's own archive, checked by apt against the signed index with ubuntu-keyring. Plain HTTP
# until ca-certificates is installed, HTTPS from then on.
write_sources() {
  cat > "$SOURCES.new" <<EOF
Types: deb
URIs: $1://archive.ubuntu.com/ubuntu/
Suites: $CODENAME $CODENAME-updates $CODENAME-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: $1://security.ubuntu.com/ubuntu/
Suites: $CODENAME-security
Components: main restricted universe multiverse
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
# apt reports its progress on the output the app reads (APT::Status-Fd). Ubuntu 26.04's apt then
# needs a terminal for dpkg (Dpkg::Use-Pty): without one, a package script's first line of
# output fails with an I/O error, and so does the package.
apt_try() {
  local attempt
  for attempt in 1 2 3; do
    if apt-get -o APT::Status-Fd=1 -o Dpkg::Use-Pty=true "$@"; then
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
  if dpkg-query -W -f='${Status}\n' ca-certificates 2>/dev/null | grep -qx 'install ok installed'; then
    write_sources https
  else
    write_sources http
  fi
  step "Updating the package list…"
  if ! apt_try update --error-on=any; then
    say "Could not reach Ubuntu's servers."
    return 1
  fi
  progress 30
  step "Installing tools…"
  if ! apt_try install -y --no-install-recommends "${missing[@]}"; then
    say "Could not install the tools."
    return 1
  fi
  printf 'pocketide-installed %s\n' "${#missing[@]}"
}

# npm from its own registry, in the release that Node.js itself ships with this Node version
# (nodejs.org's release index names it: the newest npm may not support Ubuntu's Node), checked
# against the SHA-512 the registry publishes before it is unpacked. Ubuntu's npm package would
# pull in over 300 others. Skipped when an npm is already there; an npm that fails to download
# never fails the set-up.
install_npm() {
  command -v npm >/dev/null 2>&1 && return 0
  local node wanted meta tarball integrity work
  step "Installing npm…"
  node=$(node --version 2>/dev/null) || { say "Node.js is missing, so npm was not installed."; return 0; }
  wanted=$(curl -fsSL --retry 3 https://nodejs.org/dist/index.json |
    jq -r --arg node "$node" 'map(select(.version == $node)) | first | .npm // empty') || wanted=""
  if ! [[ "$wanted" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    say "Could not tell which npm goes with Node.js $node, so npm was not installed."
    return 0
  fi
  meta=$(curl -fsSL --retry 3 "https://registry.npmjs.org/npm/$wanted") || { say "npm could not be downloaded; the rest works without it."; return 0; }
  tarball=$(printf '%s' "$meta" | jq -r '.dist.tarball // empty')
  integrity=$(printf '%s' "$meta" | jq -r '.dist.integrity // empty')
  [ "$tarball" = "https://registry.npmjs.org/npm/-/npm-$wanted.tgz" ] || { say "npm's download address was not the registry's."; return 0; }
  case "$integrity" in sha512-*) ;; *) say "npm's checksum was missing."; return 0 ;; esac
  work=$(mktemp -d)
  if curl -fsSL --retry 3 -o "$work/npm.tgz" "$tarball" &&
    [ "sha512-$(openssl dgst -sha512 -binary "$work/npm.tgz" | base64 -w0)" = "$integrity" ]; then
    rm -rf /usr/local/lib/node_modules/npm
    mkdir -p /usr/local/lib/node_modules/npm /usr/local/bin
    tar -xzf "$work/npm.tgz" -C /usr/local/lib/node_modules/npm --strip-components=1
    ln -sf ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm
    ln -sf ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
  else
    say "npm's download did not match its checksum, so it was not installed."
  fi
  rm -rf "$work"
}

configure_git() {
  # PRoot fakes a hard link with symbolic links to a hidden file, and git's usual object write
  # links a temporary file into place. Renaming writes a plain object file instead.
  git config --system core.createObject rename
  # PRoot's faked root does not own the files the app created.
  git config --system --replace-all safe.directory '*'
  git config --system init.defaultBranch main
  git config --system advice.detachedHead false
}

# The lists are tens of megabytes and stale within a day; every apt user here updates first.
tidy() {
  apt-get clean
  rm -rf /var/lib/apt/lists/*
}

step "Preparing Ubuntu…"
progress 0
make_folders
block_services
configure_apt
repair_packages
progress 10
install_packages
progress 85
write_sources https
install_npm
progress 90
configure_git
tidy
printf 'version=%s\n' "$VERSION" > "$STAMP"
progress 100
say "Ubuntu is ready."
