#!/bin/bash
# Runs inside the engine test's container: sets the computer up with the app's own
# bootstrap.sh (mounted at /pocketide), then checks what the app relies on afterwards.
set -euo pipefail

readonly SCRIPTS=/pocketide

die() { printf '%s\n' "$*" >&2; exit 1; }

first=$(bash "$SCRIPTS/bootstrap.sh")
printf '%s\n' "$first"
grep -qx 'pocketide-progress 100' <<< "$first" || die "bootstrap.sh never reported pocketide-progress 100"

for tool in git python3 curl less nano unzip; do
  command -v "$tool" > /dev/null || die "$tool is missing after the set-up"
done
[ -s /etc/ssl/certs/ca-certificates.crt ] || die "no CA certificates after the set-up"
[ -f /opt/pocketide/bootstrap.stamp ] || die "no /opt/pocketide/bootstrap.stamp after the set-up"

second=$(bash "$SCRIPTS/bootstrap.sh")
grep -q 'The tools are already installed.' <<< "$second" || die "a second run of bootstrap.sh installed the tools again"

if [ -f "$SCRIPTS/update.sh" ]; then
  last=$(bash "$SCRIPTS/update.sh" | tail -n 1)
  printf 'update.sh: %s\n' "$last"
  grep -Eqx 'pocketide-fixed [0-9]+' <<< "$last" || die "update.sh did not end with 'pocketide-fixed <count>'"
fi
