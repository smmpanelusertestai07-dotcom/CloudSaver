#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_DIR/../.tooling/android-sdk}"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
BUILD_DIR="$PROJECT_DIR/build"
APP_BASENAME="PocketAgent"
VERSION_NAME="16.0.0"
VERSION_CODE="600"

if [[ ! -f "$ANDROID_JAR" || ! -x "$BUILD_TOOLS/aapt2" ]]; then
  echo "Android SDK platform 35 and build-tools 35.0.0 are required." >&2
  exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/gen"

# The GPL-2.0 notice for the bundled PRoot has to reach whoever receives the APK, and the APK
# is the only thing they receive.
cp "$PROJECT_DIR/OPEN_SOURCE_NOTICES.md" "$PROJECT_DIR/app/assets/open-source-notices.md"

# Host-side Python tests may leave bytecode beside the helpers. Package only source assets,
# using a staging copy so a build never removes or changes files in the source tree.
mkdir -p "$BUILD_DIR/assets"
cp -a "$PROJECT_DIR/app/assets/." "$BUILD_DIR/assets/"
find "$BUILD_DIR/assets" -type d -name '__pycache__' -prune -exec rm -rf -- {} +
find "$BUILD_DIR/assets" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete

# API 35, like PocketAgent. PRoot and its loader are signed native libraries inside the APK,
# which the package manager extracts and every modern target permits -- so there is no reason
# to opt out of Android's own hardening.
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
# Keep uncompressed native libraries compatible with 16 KB Android page sizes.
"$BUILD_TOOLS/zipalign" -f -P 16 4 "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/$APP_BASENAME-aligned.apk"

KEYSTORE="${POCKETAGENT_KEYSTORE:-$PROJECT_DIR/.signing/pocketagent-local.jks}"
STORE_PASS="${POCKETAGENT_STORE_PASS:-pocketagent-local}"
KEY_PASS="${POCKETAGENT_KEY_PASS:-$STORE_PASS}"
KEY_ALIAS="${POCKETAGENT_KEY_ALIAS:-pocketagent}"
# The signing key lives in the repository on purpose.
#
# Android refuses an update signed with a different key, and the only way to take one is to
# uninstall -- which deletes the projects, the chats and every connected account. This app is
# delivered as an APK a person installs by hand, so a key that changes between builds would
# cost them everything on every update. The key is therefore committed and deliberately public:
# it guarantees that the next build installs over this one. It is not a secret and must not be
# treated as one -- anyone can sign an APK with it, so it proves nothing about origin. A real
# Play Store release needs its own private key in POCKETAGENT_KEYSTORE, kept out of the repo.
#
# This build shares PocketAgent's certificate deliberately, and still installs beside it,
# because the application ID is com.pocketagent.doors rather than com.pocketagent.mobile.
# Both can be on the phone at once, which is the point: the one that works today keeps working
# while this one is being proved.
SUFFIX=""
if [[ ! -f "$KEYSTORE" ]]; then
  if [[ -n "${POCKETAGENT_KEYSTORE:-}" ]]; then
    echo "Configured POCKETAGENT_KEYSTORE does not exist; refusing to generate a replacement." >&2
    exit 1
  fi
  mkdir -p "$(dirname "$KEYSTORE")"
  umask 077
  keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 3072 -validity 3650 \
    -dname "CN=PocketAgent Doors Preview, O=PocketAgent, C=IN" >/dev/null 2>&1
  # Persist the marker, so later builds with this same generated key remain labelled devkey.
  : > "$KEYSTORE.devkey"
fi
if [[ -f "$KEYSTORE.devkey" ]]; then
  SUFFIX="-devkey"
  echo "Development signing key: the APK is labelled -devkey and cannot replace a build signed with another key." >&2
fi

# Publish only after verification. A killed signer must not leave a partial file
# under the final release name. Bound the host JVM used for this small APK.
FINAL_APK="$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME$SUFFIX-release.apk"
SIGNED_APK="$BUILD_DIR/.doors-signing.apk"
# v2 + v3 are both enabled so every sideload installer and Android 13 OEM build accepts the APK.
JAVA_OPTS="${JAVA_OPTS:-} -Xmx256m" "$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --min-sdk-version 29 --max-sdk-version 35 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$SIGNED_APK" \
  "$BUILD_DIR/$APP_BASENAME-aligned.apk"
JAVA_OPTS="${JAVA_OPTS:-} -Xmx256m" "$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$SIGNED_APK"
"$BUILD_TOOLS/aapt2" dump badging "$SIGNED_APK"
mv -f "$SIGNED_APK" "$FINAL_APK"

echo "$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME$SUFFIX-release.apk"
