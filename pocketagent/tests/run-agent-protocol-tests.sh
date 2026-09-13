#!/usr/bin/env bash
set -euo pipefail

# Separate from legacy host tests: these fixtures need a real org.json runtime,
# while Android's SDK android.jar contains non-executable method stubs.
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JSON_JAR="${POCKETAGENT_JSON_JAR:-}"
if [[ ! -f "$JSON_JAR" ]]; then
  echo 'Set POCKETAGENT_JSON_JAR to JSON-Java 20250517 (org.json:json) before running these fixtures.' >&2
  exit 2
fi
EXPECTED_JSON_SHA256='3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'
if [[ "$(sha256sum "$JSON_JAR" | cut -d' ' -f1)" != "$EXPECTED_JSON_SHA256" ]]; then
  echo 'The JSON-Java fixture dependency did not match the pinned SHA-256.' >&2
  exit 2
fi
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-agent-fixtures.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -cp "$JSON_JAR" -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentProtocol.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentCatalog.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/ChatHistory.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/CodexEffort.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentSnapshotDelivery.java" \
  "$PROJECT_DIR/tests/agent-protocol/AgentProtocolTest.java" \
  "$PROJECT_DIR/tests/agent-protocol/CodexRecoveryTest.java" \
  "$PROJECT_DIR/tests/agent-protocol/ChatHistoryTest.java" \
  "$PROJECT_DIR/tests/agent-protocol/CodexEffortTest.java" \
  "$PROJECT_DIR/tests/agent-protocol/AgentSnapshotDeliveryTest.java"
java -cp "$OUT:$JSON_JAR" com.pocketagent.mobile.AgentProtocolTest "$PROJECT_DIR"
java -cp "$OUT:$JSON_JAR" com.pocketagent.mobile.CodexRecoveryTest "$PROJECT_DIR"
java -cp "$OUT:$JSON_JAR" com.pocketagent.mobile.ChatHistoryTest
java -cp "$OUT:$JSON_JAR" com.pocketagent.mobile.CodexEffortTest "$PROJECT_DIR"
java -cp "$OUT:$JSON_JAR" com.pocketagent.mobile.AgentSnapshotDeliveryTest
