#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$PROJECT_DIR/build/tests"
rm -rf "$OUT"
mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi

"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
  "$PROJECT_DIR/tests/android/net/LocalSocketAddress.java" \
  "$PROJECT_DIR/tests/android/net/LocalSocket.java" \
  "$PROJECT_DIR/app/src/com/pocketdesk/VncClient.java" \
  "$PROJECT_DIR/tests/VncClientProtocolTest.java"
java -cp "$OUT" com.pocketdesk.VncClientProtocolTest

"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
  "$PROJECT_DIR/tests/android/system/ErrnoException.java" \
  "$PROJECT_DIR/tests/android/system/OsConstants.java" \
  "$PROJECT_DIR/tests/android/system/StructStat.java" \
  "$PROJECT_DIR/tests/android/system/Os.java" \
  "$PROJECT_DIR/app/src/com/pocketdesk/TarGzExtractor.java" \
  "$PROJECT_DIR/app/src/com/pocketdesk/Trees.java" \
  "$PROJECT_DIR/tests/TarGzExtractorTest.java" \
  "$PROJECT_DIR/tests/TreesTest.java"
java -cp "$OUT" com.pocketdesk.TarGzExtractorTest
java -cp "$OUT" com.pocketdesk.TreesTest

"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
  "$PROJECT_DIR/tests/stub/com/pocketdesk/R.java" \
  "$PROJECT_DIR/app/src/com/pocketdesk/LinuxApps.java" \
  "$PROJECT_DIR/tests/LinuxAppsTest.java"
java -cp "$OUT" com.pocketdesk.LinuxAppsTest "$PROJECT_DIR"

bash "$PROJECT_DIR/tests/desktop-scripts-test.sh"

# The Android version the app says it supports must be the one the APK is built for.
build_min=$(grep -o -- '--min-sdk-version [0-9]*' "$PROJECT_DIR/build.sh" | head -n 1 | awk '{print $2}')
code_min=$(grep -o 'MIN_SDK = [0-9]*' "$PROJECT_DIR/app/src/com/pocketdesk/DeviceCheck.java" | awk '{print $3}')
[ -n "$build_min" ] && [ "$build_min" = "$code_min" ] \
  || { echo "FAIL MinSdkAgreement: build.sh says '$build_min', DeviceCheck says '$code_min'"; exit 1; }
echo "PASS MinSdkAgreement (Android API $build_min)"

# Three files carry the version. A forgotten bump ships an APK whose own screen contradicts
# Android's app info, and the owner cannot tell which build they are running.
name=$(grep -m1 '^VERSION_NAME=' "$PROJECT_DIR/build.sh" | cut -d'"' -f2)
code=$(grep -m1 '^VERSION_CODE=' "$PROJECT_DIR/build.sh" | cut -d'"' -f2)
java_name=$(grep -m1 'static final String VERSION = ' "$PROJECT_DIR/app/src/com/pocketdesk/MainActivity.java" | cut -d'"' -f2)
notes_name=$(grep -m1 '^# PocketDesk [0-9]' "$PROJECT_DIR/RELEASE-NOTES.md" | awk '{print $3}')
[ -n "$name" ] && [ -n "$code" ] && [ -n "$java_name" ] && [ -n "$notes_name" ] \
  || { echo "FAIL VersionAgreement: could not read one of the version values"; exit 1; }
[ "$name" = "$java_name" ] \
  || { echo "FAIL VersionAgreement: build.sh says '$name', MainActivity says '$java_name'"; exit 1; }
[ "$name" = "$notes_name" ] \
  || { echo "FAIL VersionAgreement: build.sh says '$name', RELEASE-NOTES says '$notes_name'"; exit 1; }
case "$name" in
  *0|*5) ;;
  *) echo "FAIL VersionAgreement: '$name' must end in 0 or 5"; exit 1 ;;
esac
echo "PASS VersionAgreement ($name, code $code)"

# The desktop scripts ship as assets and only ever run on the phone, so lint them here.
for script in "$PROJECT_DIR"/app/assets/*.sh; do
  bash -n "$script" || { echo "FAIL shell syntax: $script"; exit 1; }
done
echo "PASS AssetScriptSyntax ($(ls "$PROJECT_DIR"/app/assets/*.sh | wc -l) scripts)"
