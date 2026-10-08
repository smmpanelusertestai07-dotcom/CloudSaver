#!/usr/bin/env bash
#
# Signs an aligned APK the one way every Ente Saver release is signed, so the
# release build and the emulator's rotation check use exactly the same flags.
#
#   sign-apk.sh <in.apk> <out.apk>
#
# Reads from the environment (passwords by name, never on the command line):
#   SIGN_KS            keystore holding the signing key
#   SIGN_KS_ALIAS      the key's alias in it
#   KEYSTORE_PASSWORD  the keystore password
#   KEY_PASSWORD       the key password (a PKCS12 key's is the store's)
#   SIGN_LINEAGE       optional: a key-rotation lineage whose newest entry is
#                      SIGN_KS's certificate
#
# Without a lineage: APK Signature Scheme v2 + v3 with the one key, as every
# release before the key rotation.
#
# With a lineage: v3 only, carrying the lineage, for every Android this app
# installs on (minSdk 29; --rotation-min-sdk-version 28). Android then accepts
# the APK as an update to one signed with any older key in the lineage. v2 is
# off because apksigner can only make a v2 signature with the OLDEST key in
# the lineage - keeping it would keep the old key in use for good - and every
# supported Android verifies v3 before v2.

set -euo pipefail

in="$1"
out="$2"
: "${SIGN_KS:?}" "${SIGN_KS_ALIAS:?}" "${KEYSTORE_PASSWORD:?}" "${KEY_PASSWORD:?}"

BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"

if [ -n "${SIGN_LINEAGE:-}" ]; then
  "$BT/apksigner" sign \
    --ks "$SIGN_KS" \
    --ks-key-alias "$SIGN_KS_ALIAS" \
    --ks-pass env:KEYSTORE_PASSWORD \
    --key-pass env:KEY_PASSWORD \
    --lineage "$SIGN_LINEAGE" \
    --rotation-min-sdk-version 28 \
    --v1-signing-enabled false \
    --v2-signing-enabled false \
    --v3-signing-enabled true \
    --v4-signing-enabled false \
    --out "$out" \
    "$in"
else
  "$BT/apksigner" sign \
    --ks "$SIGN_KS" \
    --ks-key-alias "$SIGN_KS_ALIAS" \
    --ks-pass env:KEYSTORE_PASSWORD \
    --key-pass env:KEY_PASSWORD \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$out" \
    "$in"
fi
