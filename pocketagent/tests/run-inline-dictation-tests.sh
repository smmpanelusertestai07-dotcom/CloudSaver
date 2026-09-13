#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-inline-dictation-tests.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/VoiceTypingPolicy.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/DictationDraft.java" \
  "$PROJECT_DIR/tests/voice-typing/DictationDraftTest.java"
java -cp "$OUT" com.pocketagent.mobile.DictationDraftTest
