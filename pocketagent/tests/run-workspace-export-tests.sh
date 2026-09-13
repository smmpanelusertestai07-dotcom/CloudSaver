#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_DIR/../.tooling/android-sdk}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
if [[ ! -f "$ANDROID_JAR" || ! -d "$PROJECT_DIR/build/classes" ]]; then
  echo 'Build PocketAgent first and set ANDROID_SDK_ROOT to its Android 35 SDK.' >&2
  exit 2
fi
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-export-fixtures.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac)
else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 \
  -cp "$PROJECT_DIR/build/classes:$ANDROID_JAR" -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/WorkspaceTools.java" "$PROJECT_DIR/tests/workspace-export/WorkspaceExportTest.java"
java -cp "$OUT:$PROJECT_DIR/build/classes:$ANDROID_JAR" com.pocketagent.mobile.WorkspaceExportTest
