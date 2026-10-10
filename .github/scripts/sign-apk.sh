#!/usr/bin/env bash
#
# Signs an aligned APK the one way every Ente Saver release is signed:
# APK Signature Scheme v2 + v3, with the one release key.
#
#   sign-apk.sh <in.apk> <out.apk>
#
# Reads from the environment (passwords by name, never on the command line):
#   SIGN_KS            keystore holding the signing key
#   SIGN_KS_ALIAS      the key's alias in it
#   KEYSTORE_PASSWORD  the keystore password
#   KEY_PASSWORD       the key password (a PKCS12 key's is the store's)

set -euo pipefail

in="$1"
out="$2"
: "${SIGN_KS:?}" "${SIGN_KS_ALIAS:?}" "${KEYSTORE_PASSWORD:?}" "${KEY_PASSWORD:?}"

BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"

"$BT/apksigner" sign \
  --ks "$SIGN_KS" \
  --ks-key-alias "$SIGN_KS_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD \
  --key-pass env:KEY_PASSWORD \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$out" \
  "$in"
