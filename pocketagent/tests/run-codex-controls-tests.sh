#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JSON_JAR="${POCKETAGENT_JSON_JAR:-}"
if [[ ! -f "$JSON_JAR" ]] || [[ "$(sha256sum "$JSON_JAR" | cut -d' ' -f1)" != '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796' ]]; then
  echo 'Set POCKETAGENT_JSON_JAR to the pinned JSON-Java 20250517 dependency.' >&2
  exit 2
fi
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-controls-fixtures.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -cp "$JSON_JAR" -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentProtocol.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/CodexEffort.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentActivity.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/CodexControls.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/CodexLogout.java" \
  "$PROJECT_DIR/tests/codex-controls/CodexControlsTest.java" \
  "$PROJECT_DIR/tests/codex-controls/CodexLogoutTest.java"
java -cp "$OUT:$JSON_JAR" com.pocketagent.mobile.CodexControlsTest "$PROJECT_DIR" "$OUT/wire.json"
java -cp "$OUT:$JSON_JAR" com.pocketagent.mobile.CodexLogoutTest
python - "$PROJECT_DIR" "$OUT/wire.json" <<'PY'
import json,pathlib,sys
import jsonschema
root=pathlib.Path(sys.argv[1])/'tests/codex-controls/schemas/codex-0.154.0-experimental'
wire=json.load(open(sys.argv[2]))
for entry in wire:
    schema=json.load(open(root/entry['schema']))
    jsonschema.Draft7Validator(schema).validate(entry['params'])
print(f'PASS {len(wire)} actual wire payloads against pinned Codex 0.154 experimental JSON schemas')
PY
