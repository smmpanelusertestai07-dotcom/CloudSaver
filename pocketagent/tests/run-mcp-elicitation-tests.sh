#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JSON_JAR="${POCKETAGENT_JSON_JAR:-}"
if [[ ! -f "$JSON_JAR" ]]; then
  echo 'Set POCKETAGENT_JSON_JAR to org.json:json:20250517.' >&2
  exit 2
fi
if [[ "$(sha256sum "$JSON_JAR" | cut -d' ' -f1)" != '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796' ]]; then
  echo 'The fixture dependency did not match its pinned SHA-256.' >&2
  exit 2
fi
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-mcp-fixtures.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -cp "$JSON_JAR" -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentProtocol.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/CodexEffort.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/McpElicitation.java" \
  "$PROJECT_DIR/tests/agent-protocol/McpElicitationTest.java"
java -cp "$OUT:$JSON_JAR" com.pocketagent.mobile.McpElicitationTest
