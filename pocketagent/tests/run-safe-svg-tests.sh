#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-safe-svg.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -d "$OUT" "$PROJECT_DIR/app/src/com/pocketagent/mobile/SafeSvg.java" "$PROJECT_DIR/tests/SafeSvgTest.java"
java -cp "$OUT" com.pocketagent.mobile.SafeSvgTest
