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
  "$PROJECT_DIR/tests/android/net/LocalSocketAddress.java" \
  "$PROJECT_DIR/tests/android/net/LocalSocket.java" \
  "$PROJECT_DIR/app/src/com/pocketdesk/VncClient.java" \
  "$PROJECT_DIR/tests/VncClientProtocolTest.java"
java -cp "$OUT" com.pocketdesk.VncClientProtocolTest

"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
  "$PROJECT_DIR/tests/android/system/ErrnoException.java" \
  "$PROJECT_DIR/tests/android/system/OsConstants.java" \
  "$PROJECT_DIR/tests/android/system/StructStat.java" \
  "$PROJECT_DIR/tests/android/system/Os.java" \
  "$PROJECT_DIR/app/src/com/pocketdesk/TarGzExtractor.java" \
  "$PROJECT_DIR/app/src/com/pocketdesk/Trees.java" \
  "$PROJECT_DIR/tests/TarGzExtractorTest.java" \
  "$PROJECT_DIR/tests/TreesTest.java"
java -cp "$OUT" com.pocketdesk.TarGzExtractorTest
java -cp "$OUT" com.pocketdesk.TreesTest

# Crash's one judgement: Android's own teardown race, or a fault in this app? Getting it wrong
# hides a real bug for ever, or cries wolf every time Android closes a screen awkwardly.
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketdesk/FrameworkRace.java" \
  "$PROJECT_DIR/tests/CrashTest.java"
java -cp "$OUT" com.pocketdesk.CrashTest

"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
  "$PROJECT_DIR/tests/stub/com/pocketdesk/R.java" \
  "$PROJECT_DIR/app/src/com/pocketdesk/LinuxApps.java" \
  "$PROJECT_DIR/tests/LinuxAppsTest.java"
java -cp "$OUT" com.pocketdesk.LinuxAppsTest "$PROJECT_DIR"

bash "$PROJECT_DIR/tests/desktop-scripts-test.sh"

# The Android version the app says it supports must be the one the APK is built for.
build_min=$(grep -o -- '--min-sdk-version [0-9]*' "$PROJECT_DIR/build.sh" | head -n 1 | awk '{print $2}')
code_min=$(grep -o 'MIN_SDK = [0-9]*' "$PROJECT_DIR/app/src/com/pocketdesk/DeviceCheck.java" | awk '{print $3}')
[ -n "$build_min" ] && [ "$build_min" = "$code_min" ] \
  || { echo "FAIL MinSdkAgreement: build.sh says '$build_min', DeviceCheck says '$code_min'"; exit 1; }
echo "PASS MinSdkAgreement (Android API $build_min)"

# Three files carry the version. A forgotten bump ships an APK whose own screen contradicts
# Android's app info, and the owner cannot tell which build they are running.
name=$(grep -m1 '^VERSION_NAME=' "$PROJECT_DIR/build.sh" | cut -d'"' -f2)
code=$(grep -m1 '^VERSION_CODE=' "$PROJECT_DIR/build.sh" | cut -d'"' -f2)
java_name=$(grep -m1 'static final String VERSION = ' "$PROJECT_DIR/app/src/com/pocketdesk/MainActivity.java" | cut -d'"' -f2)
notes_name=$(grep -m1 '^# PocketDesk [0-9]' "$PROJECT_DIR/RELEASE-NOTES.md" | awk '{print $3}')
[ -n "$name" ] && [ -n "$code" ] && [ -n "$java_name" ] && [ -n "$notes_name" ] \
  || { echo "FAIL VersionAgreement: could not read one of the version values"; exit 1; }
[ "$name" = "$java_name" ] \
  || { echo "FAIL VersionAgreement: build.sh says '$name', MainActivity says '$java_name'"; exit 1; }
[ "$name" = "$notes_name" ] \
  || { echo "FAIL VersionAgreement: build.sh says '$name', RELEASE-NOTES says '$notes_name'"; exit 1; }
case "$name" in
  *0|*5) ;;
  *) echo "FAIL VersionAgreement: '$name' must end in 0 or 5"; exit 1 ;;
esac
echo "PASS VersionAgreement ($name, code $code)"

# The desktop scripts ship as assets and only ever run on the phone, so lint them here.
for script in "$PROJECT_DIR"/app/assets/*.sh; do
  bash -n "$script" || { echo "FAIL shell syntax: $script"; exit 1; }
done
echo "PASS AssetScriptSyntax ($(ls "$PROJECT_DIR"/app/assets/*.sh | wc -l) scripts)"

# Terminology, guarded. "Ubuntu Desktop" is Canonical's own GNOME product, which this is not,
# and this is not dual boot, a virtual machine or a second operating system. What is banned is
# the CLAIM, not the word: the app says "not dual boot" on purpose, so each file is folded to a
# single line (Java wraps a long sentence across many string literals) and the honest denials are
# removed before the scan. Cinnamon is deliberately NOT banned: the FAQ names it, along with
# GNOME, KDE and Xfce, precisely to say that this desktop is none of them. Ui.java is where the
# rule itself lives, and a rule has to name what it forbids, so it is the one file skipped.
banned='dual[ -]boot|Ubuntu Desktop|virtual machine on your phone|is a virtual machine|second operating system'
for file in "$PROJECT_DIR"/app/src/com/pocketdesk/*.java "$PROJECT_DIR"/app/assets/*.sh; do
  case "$file" in */Ui.java) continue ;; esac
  folded=$(tr '\n' ' ' < "$file" \
    | sed -E 's/" *\+ *"//g' \
    | sed -E 's/(not|never|nor|neither) +(a +|an +)?(second operating system|virtual machine|emulator|dual[ -]boot)//gI')
  if printf '%s' "$folded" | grep -qiE "$banned"; then
    echo "FAIL Terminology: $(basename "$file") claims a banned term (see the rule in Ui.java)"
    printf '%s' "$folded" | grep -oiE "$banned" | sort -u
    exit 1
  fi
done
# The Ubuntu Circle-of-Friends logo needs Canonical's written permission, so no Ubuntu-branded
# image may ship.
if ls "$PROJECT_DIR"/app/res/*/*ubuntu* >/dev/null 2>&1; then
  echo "FAIL Terminology: an Ubuntu-branded image must not ship; Canonical requires written permission"; exit 1
fi
grep -q 'trademark of Canonical' "$PROJECT_DIR/app/src/com/pocketdesk/MainActivity.java" \
  || { echo "FAIL Terminology: the Canonical trademark line is missing from the credits"; exit 1; }
tagline=$(grep -c 'A Linux computer that runs locally on your phone' "$PROJECT_DIR/app/src/com/pocketdesk/MainActivity.java")
[ "$tagline" -ge 2 ] \
  || { echo "FAIL Terminology: the one tagline must appear on the opening screen and the home header, found $tagline"; exit 1; }
echo "PASS Terminology"

# Every desktop helper has to be copied in TWO places: once by set-up, and once by the refresh
# that runs after each app install, or a computer built by an earlier version never gets it.
for helper in pocketdesk-storage.sh pocketdesk-shot.sh pocketdesk-mark.png pocketdesk-mcp.py pocketdesk-agent.sh pocketdesk-appshot.sh pocketdesk-winapp.sh pocketdesk-adb.sh; do
  n=$(grep -c "$helper" "$PROJECT_DIR/app/src/com/pocketdesk/ContainerRuntime.java" || true)
  [ "$n" = 2 ] || { echo "FAIL AssetCopySites: $helper must be installed by set-up AND refreshed on every app install (found $n)"; exit 1; }
done
echo "PASS AssetCopySites"

# The desktop's MCP server is what gives an agent eyes and hands, so it is checked the way an
# agent will meet it: a real initialize and tools/list over stdin, answered on stdout.
python3 -c "import ast,sys; ast.parse(open('$PROJECT_DIR/app/assets/pocketdesk-mcp.py').read())" \
  || { echo "FAIL McpServer: pocketdesk-mcp.py does not parse"; exit 1; }
mcp_out=$(printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | python3 "$PROJECT_DIR/app/assets/pocketdesk-mcp.py")
echo "$mcp_out" | grep -q '"protocolVersion"' \
  || { echo "FAIL McpServer: initialize did not answer with a protocol version"; exit 1; }
for pd_tool in appshot screenshot list_windows click type_text press_key scroll; do
  echo "$mcp_out" | grep -q "\"$pd_tool\"" \
    || { echo "FAIL McpServer: the $pd_tool tool is not offered"; exit 1; }
done
echo "$mcp_out" | grep -q '"id": 2' \
  || { echo "FAIL McpServer: tools/list did not answer the request it was asked"; exit 1; }
# An unknown method must be an error, not a crash that takes the agent's session with it.
echo '{"jsonrpc":"2.0","id":3,"method":"nonsense"}' \
  | python3 "$PROJECT_DIR/app/assets/pocketdesk-mcp.py" | grep -q '"error"' \
  || { echo "FAIL McpServer: an unknown method must answer with an error"; exit 1; }
echo "PASS McpServer ($(python3 "$PROJECT_DIR/app/assets/pocketdesk-mcp.py" --selftest | grep -o '"' | wc -l | awk '{print $1/2}') tools)"
