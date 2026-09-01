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
  "$PROJECT_DIR/tests/android/system/Os.java" \
  "$PROJECT_DIR/app/src/com/pocketdesk/TarGzExtractor.java" \
  "$PROJECT_DIR/tests/TarGzExtractorTest.java"
java -cp "$OUT" com.pocketdesk.TarGzExtractorTest
