#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JSON_JAR="${POCKETAGENT_JSON_JAR:-}"
if [[ ! -f "$JSON_JAR" ]]; then echo 'Set POCKETAGENT_JSON_JAR to org.json:json:20250517.' >&2; exit 2; fi
if [[ "$(sha256sum "$JSON_JAR" | cut -d' ' -f1)" != '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796' ]]; then echo 'Unexpected JSON-Java dependency.' >&2; exit 2; fi
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-voice-fixtures.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -cp "$JSON_JAR" -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/AgentProtocol.java" "$PROJECT_DIR/app/src/com/pocketagent/mobile/CodexEffort.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/CodexVoice.java" "$PROJECT_DIR/app/src/com/pocketagent/mobile/VoiceTransport.java" \
  "$PROJECT_DIR/app/src/com/pocketagent/mobile/VoiceWebPage.java" "$PROJECT_DIR/tests/agent-protocol/CodexVoiceTest.java"
java -cp "$OUT:$JSON_JAR" com.pocketagent.mobile.CodexVoiceTest "$OUT"
if [[ -n "${POCKETAGENT_CODEX_SCHEMA_DIR:-}" ]]; then
  python - "$OUT/voice-start.json" "$POCKETAGENT_CODEX_SCHEMA_DIR/v2/ThreadRealtimeStartParams.json" <<'PY'
import sys,json
wire=json.load(open(sys.argv[1]));schema=json.load(open(sys.argv[2]))
try:
 import jsonschema
 jsonschema.validate(wire,schema)
 print('Voice start matches full pinned generated schema')
except ImportError:
 # The emitted request uses only required objects, strings and enums. Check
 # each emitted field against the independent generated schema without a dependency.
 def validate(value,node):
  if '$ref' in node:
   validate(value,schema['definitions'][node['$ref'].split('/')[-1]]);return
  for item in node.get('allOf',[]):validate(value,item)
  for combinator in ('anyOf','oneOf'):
   if combinator in node:
    matched=0
    for child in node[combinator]:
     try:validate(value,child);matched+=1
     except AssertionError:pass
    assert matched>=1 if combinator=='anyOf' else matched==1
  if 'enum' in node:assert value in node['enum']
  if 'type' in node:
   expected=node['type'];expected=[expected] if isinstance(expected,str) else expected
   actual='null' if value is None else 'object' if isinstance(value,dict) else 'string' if isinstance(value,str) else 'unsupported'
   assert actual in expected,(actual,expected)
  if isinstance(value,dict):
   assert set(node.get('required',[]))<=set(value)
   properties=node.get('properties')
   if properties is not None:
    assert set(value)<=set(properties)
    for name,item in value.items():validate(item,properties[name])
  if isinstance(value,str):assert len(value)>=node.get('minLength',0) and len(value)<=node.get('maxLength',len(value))
 validate(wire,schema)
 print('Voice emitted fields match pinned schema required/type/enum constraints')
PY
fi
node "$PROJECT_DIR/tests/voice-webview-test.js" "$OUT/voice.html"
