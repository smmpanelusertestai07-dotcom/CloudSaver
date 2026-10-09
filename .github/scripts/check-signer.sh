#!/usr/bin/env bash
#
# Refuses an APK that is not signed by the Ente Saver release key alone.
#
#   check-signer.sh <apk> <release-cert-sha256>
#
# Android installs an update only when it is signed by the key the installed
# app was signed with. A release signed by any other key would be refused on
# every phone, leaving uninstalling - and losing what the app knew - as the
# only way on.

set -euo pipefail

apk="$1"
expected="$(echo "$2" | tr -d ':' | tr 'A-F' 'a-f')"
BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"

# Only the certificate digests are read, never the words around them:
# apksigner 36.0 prints "Signer #1 certificate SHA-256 digest: ...", the
# runners' later build-tools print "V3.0 Signer: certificate SHA-256
# digest: ...", and a check that matched the wording would find no signer at
# all on the next version.
digests() {
  sed -n -E 's/.*certificate SHA-256 digest: ([0-9a-fA-F]{64})[[:space:]]*$/\1/p' | tr 'A-F' 'a-f'
}

if ! verified="$("$BT/apksigner" verify --print-certs "$apk" 2>&1)"; then
  echo "$verified"
  echo "::error title=The APK's signature does not verify::apksigner rejected $apk."
  exit 1
fi
# One key signs under two schemes (v2 and v3) and may be listed once for each.
signers="$(printf '%s\n' "$verified" | digests | sort -u)"
count="$(printf '%s\n' "$signers" | grep -c . || true)"
if [ "$count" -ne 1 ]; then
  echo "$verified"
  echo "::error title=Unexpected signers::$apk has $count signing certificates; a release has exactly one."
  exit 1
fi

if [ "$signers" != "$expected" ]; then
  echo "::error title=Not signed by the release key::$apk is signed by $signers, not the Ente Saver release key ($expected). Every phone with Ente Saver would refuse it as an update."
  exit 1
fi
echo "Signed by the Ente Saver release key ($signers)."
