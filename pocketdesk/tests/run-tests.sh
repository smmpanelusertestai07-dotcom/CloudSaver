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
java -cp "$OUT" com.pocketdesk.LinuxAppsTest

# The desktop scripts ship as assets and only ever run on the phone, so lint them here.
for script in "$PROJECT_DIR"/app/assets/*.sh; do
  bash -n "$script" || { echo "FAIL shell syntax: $script"; exit 1; }
done
echo "PASS AssetScriptSyntax ($(ls "$PROJECT_DIR"/app/assets/*.sh | wc -l) scripts)"
