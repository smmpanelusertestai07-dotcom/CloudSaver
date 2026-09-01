#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_DIR/../.tooling/android-sdk}"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
BUILD_DIR="$PROJECT_DIR/build"
APP_BASENAME="PocketDesk"
VERSION_NAME="1.6.0"
VERSION_CODE="17"

if [[ ! -f "$ANDROID_JAR" || ! -x "$BUILD_TOOLS/aapt2" ]]; then
  echo "Android SDK platform 35 and build-tools 35.0.0 are required." >&2
  exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/gen"

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
  -A "$PROJECT_DIR/app/assets" \
  "$BUILD_DIR/compiled.zip"

# Android 10+ blocks exec from writable app-private storage for modern target SDKs.
# PRoot and its loader are therefore signed native APK libraries, extracted by PackageManager.
(cd "$PROJECT_DIR/app" && zip -q -0 "$BUILD_DIR/$APP_BASENAME-unsigned.apk" lib/arm64-v8a/*.so)

mapfile -t JAVA_SOURCES < <(find "$PROJECT_DIR/app/src" "$BUILD_DIR/gen" -name '*.java' -type f | sort)
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -Xlint:all \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  "${JAVA_SOURCES[@]}"

mapfile -t CLASS_FILES < <(find "$BUILD_DIR/classes" -name '*.class' -type f | sort)
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 29 --output "$BUILD_DIR/dex" "${CLASS_FILES[@]}"
zip -q -j "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/dex/classes.dex"
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/$APP_BASENAME-aligned.apk"

KEYSTORE="${POCKETDESK_KEYSTORE:-$PROJECT_DIR/.signing/pocketdesk-local.jks}"
STORE_PASS="${POCKETDESK_STORE_PASS:-pocketdesk-local}"
KEY_PASS="${POCKETDESK_KEY_PASS:-$STORE_PASS}"
if [[ ! -f "$KEYSTORE" ]]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -alias pocketdesk -keyalg RSA -keysize 3072 -validity 3650 \
    -dname "CN=PocketDesk Local Preview, O=PocketDesk, C=IN" >/dev/null 2>&1
fi

# v2 + v3 are both enabled so every sideload installer and Android 13 OEM build accepts the APK.
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias pocketdesk \
  --min-sdk-version 29 --max-sdk-version 35 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$BUILD_DIR/$APP_BASENAME-$VERSION_NAME-arm64.apk" \
  "$BUILD_DIR/$APP_BASENAME-aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$BUILD_DIR/$APP_BASENAME-$VERSION_NAME-arm64.apk"
"$BUILD_TOOLS/aapt2" dump badging "$BUILD_DIR/$APP_BASENAME-$VERSION_NAME-arm64.apk"

echo "$BUILD_DIR/$APP_BASENAME-$VERSION_NAME-arm64.apk"
