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
# IS the original key, or when its lineage starts at the original key and
# ends at its signer. Anything else - a new key added without the handover, a
# lineage from some other key - would make every phone refuse the update and
# leave uninstalling, and losing what the app knew, as the only way on.

set -euo pipefail

apk="$1"
original="$(echo "$2" | tr -d ':' | tr 'A-F' 'a-f')"
BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"

signers="$("$BT/apksigner" verify --print-certs "$apk" 2>/dev/null \
  | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')"
count="$(printf '%s\n' "$signers" | grep -c . || true)"
if [ "$count" -ne 1 ]; then
  echo "::error title=Unexpected signers::$apk has $count signers; a release has exactly one."
  exit 1
fi
signer="$signers"

if [ "$signer" = "$original" ]; then
  echo "Signed with the original key ($signer): installs over every earlier version."
  exit 0
fi

chain="$("$BT/apksigner" lineage --in "$apk" --print-certs 2>/dev/null \
  | sed -n 's/^Signer #[0-9]* in lineage certificate SHA-256 digest: //p' || true)"
first="$(printf '%s\n' "$chain" | head -1)"
last="$(printf '%s\n' "$chain" | tail -1)"
if [ -n "$chain" ] && [ "$first" = "$original" ] && [ "$last" = "$signer" ]; then
  echo "Signed with a rotated key ($signer) whose lineage starts at the original key ($original):"
  echo "Android installs it over every earlier version and keeps the app's data."
  exit 0
fi

echo "::error title=This APK could not update installed copies::It is signed by $signer, which is neither the original key ($original) nor a key the original handed over to through a key-rotation lineage. Every phone with Ente Saver would refuse it as an update."
exit 1
