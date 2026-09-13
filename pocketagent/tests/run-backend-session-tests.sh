#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JSON_JAR="${POCKETAGENT_JSON_JAR:-}"
if [[ ! -f "$JSON_JAR" ]] || [[ "$(sha256sum "$JSON_JAR" | cut -d' ' -f1)" != '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796' ]]; then
  echo 'Set POCKETAGENT_JSON_JAR to the pinned JSON-Java 20250517 fixture dependency.' >&2; exit 2
fi
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-backend-session.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -cp "$JSON_JAR" -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentProtocol.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentCatalog.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/CodexEffort.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentReconnect.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentToolMedia.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/ClaudeBridge.java" \
  "$PROJECT_DIR/tests/backend-session/AgentReconnectTest.java" \
  "$PROJECT_DIR/tests/backend-session/AgentToolMediaTest.java" \
  "$PROJECT_DIR/tests/backend-session/ClaudeReconnectTest.java"
for FIXTURE in AgentReconnectTest AgentToolMediaTest ClaudeReconnectTest; do java -cp "$OUT:$JSON_JAR" "com.pocketagent.mobile.$FIXTURE"; done
