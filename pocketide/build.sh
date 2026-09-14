#!/usr/bin/env bash
# Builds PocketIDE: aapt2, javac, d8, apksigner. No Gradle, no dependency resolution, no
# network. The whole build is four tools the Android SDK already ships, which is why it takes
# seconds and why it will still work in five years.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_DIR/../.tooling/android-sdk}"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
BUILD_DIR="$PROJECT_DIR/build"
APP_BASENAME="PocketIDE"

# Duplicated in app/src/com/pocketide/BuildFacts.java on purpose -- the manifest can only take
# them from here, and the screens can only take them from there. tests/version_agreement.py
# fails the build if the two ever disagree, because an app that reports one version to the
# package manager and shows another in Settings produces bug reports nobody can act on.
VERSION_NAME="1.6.0"
VERSION_CODE="160"

if [[ ! -f "$ANDROID_JAR" || ! -x "$BUILD_TOOLS/aapt2" ]]; then
  echo "Android SDK platform 35 and build-tools 35.0.0 are required." >&2
  exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/gen" "$BUILD_DIR/assets"

# The GPL-2.0 notice for the bundled PRoot has to reach whoever receives the APK, and the APK
# is the only thing they receive. Help -> "Read the notices" reads this copy.
cp "$PROJECT_DIR/OPEN_SOURCE_NOTICES.md" "$PROJECT_DIR/app/assets/open-source-notices.md"

# Package only source assets, through a staging copy, so a build never modifies the source tree.
cp -a "$PROJECT_DIR/app/assets/." "$BUILD_DIR/assets/"
find "$BUILD_DIR/assets" -type d -name '__pycache__' -prune -exec rm -rf -- {} + 2>/dev/null || true
find "$BUILD_DIR/assets" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete 2>/dev/null || true

echo "Compiling resources…"
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

# PRoot and its loader ship as native APK libraries, stored uncompressed so the package manager
# can extract them, which is what keeps the container bootstrap reliable on a modern target.
(cd "$PROJECT_DIR/app" && zip -q -0 "$BUILD_DIR/$APP_BASENAME-unsigned.apk" lib/arm64-v8a/*.so)

echo "Compiling Java…"
mapfile -t JAVA_SOURCES < <(find "$PROJECT_DIR/app/src" "$BUILD_DIR/gen" -name '*.java' -type f | sort)
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
# -Xlint:all with no -Werror: warnings are read, not ignored, but a deprecation in the platform
# SDK must not be able to stop a release.
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -Xlint:all -nowarn \
  -bootclasspath "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar" \
  -d "$BUILD_DIR/classes" \
  "${JAVA_SOURCES[@]}"

echo "Dexing…"
mapfile -t CLASS_FILES < <(find "$BUILD_DIR/classes" -name '*.class' -type f | sort)
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 29 --output "$BUILD_DIR/dex" "${CLASS_FILES[@]}"
zip -q -j "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/dex/classes.dex"
# -P 16, not -p. They are different flags and the difference is a crash on a modern phone:
# lowercase -p aligns uncompressed .so files to 4 KB, which is all Android needed until 15.
# Android 15 introduced devices with 16 KB memory pages, and on those the loader maps a
# library straight out of the APK -- so a library sitting at an offset that is not a multiple
# of 16384 cannot be mapped and the app dies at startup with no useful message.
#
# The four libraries here are already built with 16 KB ELF segment alignment (readelf shows
# LOAD align 0x4000 on each), and with -p 4 their offsets happened to land on 16 KB boundaries
# anyway. Happened to. Add a fifth library, or change the order of anything before them, and
# that luck ends. -P 16 makes it a property of the build instead.
"$BUILD_TOOLS/zipalign" -f -P 16 4 \
  "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/$APP_BASENAME-aligned.apk"

# Proved, not assumed: the same tool re-reads the file and is asked whether every uncompressed
# library actually sits on a 16 KB boundary. A build that cannot answer yes does not ship.
if ! "$BUILD_TOOLS/zipalign" -c -P 16 4 "$BUILD_DIR/$APP_BASENAME-aligned.apk" >/dev/null; then
  echo "Native libraries are not 16 KB aligned; this APK would not start on an Android 15" >&2
  echo "device with 16 KB pages. Refusing to sign it." >&2
  exit 1
fi

# ---------------------------------------------------------------------------- signing
#
# Android refuses an update signed with a different key than the one already installed, and the
# only way out of that is uninstalling -- which on this app means deleting the whole workspace.
# So the key has to be stable across releases.
#
# There is exactly one stable key, and it is the one supplied through the environment. No key is
# committed to this repository, and none should be: this repository is public, and a signing key
# anyone can read is a signing key anyone can use to build an "update" that installs straight
# over the owner's app.
#
# So the key has to be held as a GitHub secret. Create it once, keep it, and set the four
# secrets named below; every build after that -- in CI or on a laptop -- signs identically and
# every APK installs over the last one.
#
#   keytool -genkeypair -v -keystore pocketide.jks -storetype JKS \
#     -alias pocketide -keyalg RSA -keysize 4096 -validity 10950 \
#     -dname "CN=PocketIDE, OU=PocketIDE, O=PocketIDE, C=IN"
#   base64 -w0 pocketide.jks        # -> POCKETIDE_KEYSTORE_B64
#
# Without those secrets this script generates a throwaway key so a first build works at all.
# That APK installs fine on a phone with nothing installed, and will not install over an APK
# signed by any other throwaway. The warning below says so at build time rather than leaving it
# to be discovered as "App not installed" on a phone.
KEYSTORE="${POCKETIDE_KEYSTORE:-$PROJECT_DIR/.signing/pocketide.jks}"
STORE_PASS="${POCKETIDE_STORE_PASS:-pocketide-local}"
KEY_PASS="${POCKETIDE_KEY_PASS:-$STORE_PASS}"
KEY_ALIAS="${POCKETIDE_KEY_ALIAS:-pocketide}"

THROWAWAY_KEY=0
if [[ ! -f "$KEYSTORE" ]]; then
  THROWAWAY_KEY=1
  echo "No signing key was supplied; generating a throwaway one."
  echo "  This APK will NOT install over an APK signed by a different key."
  echo "  Set POCKETIDE_KEYSTORE_B64, POCKETIDE_STORE_PASS, POCKETIDE_KEY_PASS and"
  echo "  POCKETIDE_KEY_ALIAS as repository secrets to sign every build the same way."
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -storetype JKS \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10950 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=PocketIDE, OU=PocketIDE, O=PocketIDE, C=IN" >/dev/null 2>&1
fi

echo "Signing…"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME-release.apk" \
  "$BUILD_DIR/$APP_BASENAME-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose \
  "$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME-release.apk" | head -5

rm -f "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/$APP_BASENAME-aligned.apk"

APK="$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME-release.apk"
echo
echo "Built: $APK"
echo "Size:  $(du -h "$APK" | cut -f1)"

if [[ "$THROWAWAY_KEY" == "1" ]]; then
  echo
  echo "Signed with a throwaway key. Installs on a phone that has no PocketIDE yet;"
  echo "will not install over a PocketIDE signed by a different key. See the note above"
  echo "the signing block in this file for the four secrets that fix this for good."
fi
