#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_DIR/../.tooling/android-sdk}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
JSON_JAR="${POCKETAGENT_JSON_JAR:-}"
if [[ ! -f "$ANDROID_JAR" || ! -f "$JSON_JAR" || ! -d "$PROJECT_DIR/build/classes" ]]; then
  echo 'Build PocketAgent and set ANDROID_SDK_ROOT plus POCKETAGENT_JSON_JAR before running skill fixtures.' >&2
  exit 2
fi
EXPECTED_JSON_SHA256='3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'
if [[ "$(sha256sum "$JSON_JAR" | cut -d' ' -f1)" != "$EXPECTED_JSON_SHA256" ]]; then
  echo 'The JSON-Java fixture dependency did not match its pinned SHA-256.' >&2
  exit 2
fi
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-skill-fixtures.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac)
else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 \
  -cp "$JSON_JAR:$PROJECT_DIR/build/classes:$ANDROID_JAR" -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/WorkspaceTools.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/CodexSkillFiles.java" \
  "$PROJECT_DIR/tests/CodexSkillFilesTest.java"
java -cp "$OUT:$JSON_JAR:$PROJECT_DIR/build/classes:$ANDROID_JAR" com.pocketagent.mobile.CodexSkillFilesTest
