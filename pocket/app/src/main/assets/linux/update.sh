#!/bin/bash
# Installs Ubuntu's security fixes. The app runs it once a day on Wi-Fi.
#
# Only packages whose new version comes from noble-security are installed, so a daily run
# stays small and changes nothing else on the computer. The last line the app reads is
# "pocketide-fixed <count>".
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

say() { printf '%s\n' "$*"; }

# A step the app shows as the set-up's current activity.
step() { printf 'pocketide-step %s\n' "$*"; }

# A run killed part way leaves dpkg mid-configure; finish that before anything else.
repair_packages() {
  dpkg --configure -a || true
  apt-get -y -f install || true
}

apt_try() {
  local attempt
  for attempt in 1 2 3; do
    if apt-get -o APT::Status-Fd=1 "$@"; then
      return 0
    fi
    if [ "$attempt" -lt 3 ]; then
      sleep $((attempt * 5))
      repair_packages
    fi
  done
  return 1
}

# Read from apt's own simulation, for example
#   Inst curl [8.5.0-2ubuntu10.5] (8.5.0-2ubuntu10.6 Ubuntu:24.04/noble-security [arm64])
# The pocket is matched inside the parentheses, which name the new version's archive, and
# dist-upgrade is simulated because a plain upgrade hides a fix that needs a new dependency.
security_fixes() {
  apt-get -s dist-upgrade | awk '/^Inst / {
    if (match($0, /\([^)]*\)/) && substr($0, RSTART, RLENGTH) ~ /-security/) print $2
  }'
}

step "Checking Ubuntu's security fixes…"
repair_packages
if ! apt_try update; then
  say "Could not reach Ubuntu's servers."
  exit 1
fi

mapfile -t fixes < <(security_fixes)
if [ "${#fixes[@]}" -eq 0 ]; then
  say "No security fixes are waiting."
else
  step "Installing ${#fixes[@]} security fixes…"
  # Installing by name marks a package as chosen by hand; put the automatic marks back so
  # apt can still remove what nothing needs any more.
  mapfile -t automatic < <(apt-mark showauto)
  if ! apt_try install -y --no-install-recommends \
      -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold "${fixes[@]}"; then
    say "Some security fixes could not be installed."
    exit 1
  fi
  if [ "${#automatic[@]}" -gt 0 ]; then
    apt-mark auto "${automatic[@]}" >/dev/null
  fi
fi

apt-get clean
rm -rf /var/lib/apt/lists/*
printf 'pocketide-fixed %s\n' "${#fixes[@]}"
