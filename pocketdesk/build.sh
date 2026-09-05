#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_DIR/../.tooling/android-sdk}"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
BUILD_DIR="$PROJECT_DIR/build"
APP_BASENAME="PocketLinux"
VERSION_NAME="11.0.5"
VERSION_CODE="405"

if [[ ! -f "$ANDROID_JAR" || ! -x "$BUILD_TOOLS/aapt2" ]]; then
  echo "Android SDK platform 35 and build-tools 35.0.0 are required." >&2
  exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/gen"

# The GPL-2.0 notice for the bundled PRoot has to reach whoever receives the APK, and the APK
# is the only thing they receive. Settings -> "Open-source notices" reads this copy.
cp "$PROJECT_DIR/OPEN_SOURCE_NOTICES.md" "$PROJECT_DIR/app/assets/open-source-notices.md"

# Host-side Python tests may leave bytecode beside the helpers. Package only source assets,
# using a staging copy so a build never removes or changes files in the source tree.
mkdir -p "$BUILD_DIR/assets"
cp -a "$PROJECT_DIR/app/assets/." "$BUILD_DIR/assets/"
find "$BUILD_DIR/assets" -type d -name '__pycache__' -prune -exec rm -rf -- {} +
find "$BUILD_DIR/assets" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete

# Compiled and targeted at API 35, and a test locks that down. The app once targeted API 28 --
# the last compatibility domain Android lets execute files written into app_data_file -- purely
# so a Windows compatibility layer could map downloaded program code. That layer is gone, and
# with it the only reason to opt out of a decade of Android's own hardening. Linux programs need
# nothing of the kind: PRoot and its loader are signed native libraries inside the APK, which
# the package manager extracts and every modern target permits.
"$BUILD_TOOLS/aapt2" compile --dir "$PROJECT_DIR/app/res" -o "$BUILD_DIR/compiled.zip"
"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD_DIR/$APP_BASENAME-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/app/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 35 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  -A "$BUILD_DIR/assets" \
  "$BUILD_DIR/compiled.zip"

# PRoot and its loader ship as signed native APK libraries, extracted by PackageManager, which
# is what keeps the container bootstrap reliable on a modern target.
(cd "$PROJECT_DIR/app" && zip -q -0 "$BUILD_DIR/$APP_BASENAME-unsigned.apk" lib/arm64-v8a/*.so)

mapfile -t JAVA_SOURCES < <(find "$PROJECT_DIR/app/src" "$BUILD_DIR/gen" -name '*.java' -type f | sort)
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -Xlint:all \
  -bootclasspath "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar" \
  -d "$BUILD_DIR/classes" \
  "${JAVA_SOURCES[@]}"

mapfile -t CLASS_FILES < <(find "$BUILD_DIR/classes" -name '*.class' -type f | sort)
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 29 --output "$BUILD_DIR/dex" "${CLASS_FILES[@]}"
zip -q -j "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/dex/classes.dex"
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/$APP_BASENAME-aligned.apk"

KEYSTORE="${POCKETDESK_KEYSTORE:-$PROJECT_DIR/.signing/pocketdesk-local.jks}"
STORE_PASS="${POCKETDESK_STORE_PASS:-pocketdesk-local}"
KEY_PASS="${POCKETDESK_KEY_PASS:-$STORE_PASS}"
# Android refuses an update signed with a different key, and the only way to take it would be
# to uninstall -- which deletes the whole Ubuntu container, its apps and their sign-ins. So the
# key that signed every previous release lives in the repository, and a build that has to mint
# a new one says so loudly and names its APK differently, so it can never be handed over as the
# release by mistake.
SUFFIX=""
if [[ ! -f "$KEYSTORE" ]]; then
  SUFFIX="-devkey"
  echo "WARNING: $KEYSTORE is missing, so this build is signed with a throwaway key." >&2
  echo "         It CANNOT be installed over an existing PocketLinux; restore the keystore" >&2
  echo "         (pocketdesk/.signing/) or set POCKETDESK_KEYSTORE before a real release." >&2
fi
if [[ ! -f "$KEYSTORE" ]]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -alias pocketdesk -keyalg RSA -keysize 3072 -validity 3650 \
    -dname "CN=PocketLinux Local Preview, O=PocketLinux, C=IN" >/dev/null 2>&1
fi

# Publish only after verification. A killed signer must not leave a partial file
# under the final release name. Bound the host JVM used for this small APK.
FINAL_APK="$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME$SUFFIX-release.apk"
SIGNED_APK="$BUILD_DIR/.pocketdesk-signing.apk"
# v2 + v3 are both enabled so every sideload installer and Android 13 OEM build accepts the APK.
JAVA_OPTS="${JAVA_OPTS:-} -Xmx256m" "$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias pocketdesk \
  --min-sdk-version 29 --max-sdk-version 35 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$SIGNED_APK" \
  "$BUILD_DIR/$APP_BASENAME-aligned.apk"
JAVA_OPTS="${JAVA_OPTS:-} -Xmx256m" "$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$SIGNED_APK"
"$BUILD_TOOLS/aapt2" dump badging "$SIGNED_APK"
mv -f "$SIGNED_APK" "$FINAL_APK"

echo "$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME$SUFFIX-release.apk"
