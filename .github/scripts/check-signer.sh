#!/usr/bin/env bash
#
# Refuses an APK that could not update the Ente Saver already on people's
# phones.
#
#   check-signer.sh <apk> <original-cert-sha256>
#
# Android installs an update only when it is signed by the installed app's
# key, or by a newer key the installed one handed over to (APK Signature
# Scheme v3 key rotation, the lineage). So a release passes when its signer
# IS the original key, or when its lineage holds the original key and its
# signer. Anything else - a new key added without the handover, a lineage
# from some other key - would make every phone refuse the update and leave
# uninstalling, and losing what the app knew, as the only way on.

set -euo pipefail

apk="$1"
original="$(echo "$2" | tr -d ':' | tr 'A-F' 'a-f')"
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
# One key may sign under several schemes (v2 and v3) and be listed once
# for each.
signers="$(printf '%s\n' "$verified" | digests | sort -u)"
count="$(printf '%s\n' "$signers" | grep -c . || true)"
if [ "$count" -ne 1 ]; then
  echo "$verified"
  echo "::error title=Unexpected signers::$apk has $count signing certificates; a release has exactly one."
  exit 1
fi
signer="$signers"

if [ "$signer" = "$original" ]; then
  echo "Signed with the original key ($signer): installs over every earlier version."
  exit 0
fi

chain="$("$BT/apksigner" lineage --in "$apk" --print-certs 2>/dev/null | digests || true)"
if printf '%s\n' "$chain" | grep -qx "$original" && printf '%s\n' "$chain" | grep -qx "$signer"; then
  echo "Signed with a rotated key ($signer) whose lineage holds the original key ($original):"
  echo "Android installs it over every earlier version and keeps the app's data."
  exit 0
fi

echo "::error title=This APK could not update installed copies::It is signed by $signer, which is neither the original key ($original) nor a key the original handed over to through a key-rotation lineage. Every phone with Ente Saver would refuse it as an update."
exit 1
