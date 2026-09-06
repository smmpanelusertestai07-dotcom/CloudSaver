#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# The suite reads and writes UTF-8 on purpose -- one test feeds real Devanagari through a real
# process -- and the JVM picks its default charset from the locale. On a machine whose locale is
# plain ASCII (a container's usual default) that test failed for the environment rather than for
# the code. Pin it here so the result means the same thing everywhere.
export LC_ALL=${LC_ALL:-C.UTF-8}
export LANG=${LANG:-C.UTF-8}


OUT="$PROJECT_DIR/build/tests"
rm -rf "$OUT"
mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi



"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
  "$PROJECT_DIR/app/src/com/pocketlinux/TaskGeneration.java" \
  "$PROJECT_DIR/tests/TaskGenerationTest.java"
java -cp "$OUT" com.pocketlinux.TaskGenerationTest

# An Activity is not a usable Context until Android attaches it -- and a field initializer runs
# before that. One line that forgot it meant every "Open desktop" died in the constructor: a black
# screen, straight back to the home screen, and Android's own teardown race recorded as the cause.
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
  "$PROJECT_DIR/tests/ActivityStartupTest.java"
java -cp "$OUT" com.pocketlinux.ActivityStartupTest "$PROJECT_DIR"

bash "$PROJECT_DIR/tests/desktop-scripts-test.sh"
python3 "$PROJECT_DIR/tests/linux-startup-test.py"
python3 "$PROJECT_DIR/tests/app-log-test.py"
python3 "$PROJECT_DIR/tests/browser-handoff-test.py"
python3 "$PROJECT_DIR/tests/graphics-runtime-test.py"
python3 "$PROJECT_DIR/tests/desktop-runtime-test.py"
python3 "$PROJECT_DIR/tests/desktop-watch-test.py"
python3 "$PROJECT_DIR/tests/system-bus-test.py"
python3 "$PROJECT_DIR/tests/window-status-budget-test.py"
python3 "$PROJECT_DIR/tests/proot-process-test.py"
python3 "$PROJECT_DIR/tests/desktop-wake-test.py"
python3 "$PROJECT_DIR/tests/keyboard-input-test.py"
python3 "$PROJECT_DIR/tests/microphone-test.py"

# The Android version the app says it supports must be the one the APK is built for.
build_min=$(grep -o -- '--min-sdk-version [0-9]*' "$PROJECT_DIR/build.sh" | head -n 1 | awk '{print $2}')
code_min=$(grep -o 'MIN_SDK = [0-9]*' "$PROJECT_DIR/app/src/com/pocketlinux/DeviceCheck.java" | awk '{print $3}')
[ -n "$build_min" ] && [ "$build_min" = "$code_min" ] \
  || { echo "FAIL MinSdkAgreement: build.sh says '$build_min', DeviceCheck says '$code_min'"; exit 1; }
echo "PASS MinSdkAgreement (Android API $build_min)"

# The one reason this app ever targeted an old Android was the Windows layer: only targetSdk 28
# is assigned Android's untrusted_app_27 domain, the last one allowed to execute files written
# into app_data_file, and that is how a Windows program's downloaded code was mapped. With the
# Windows layer gone nothing needs it, and staying there would be a real cost: a low target opts
# the app out of a decade of Android's own hardening, and Android raises the floor it will
# install at with every release. The container itself does not need it -- PRoot and its loader
# are signed APK libraries, extracted by the package manager, which modern targets allow.
build_target=$(grep -o -- '--target-sdk-version [0-9]*' "$PROJECT_DIR/build.sh" | head -n 1 | awk '{print $2}')
[ "$build_target" = 35 ] \
  || { echo "FAIL AndroidTargetPolicy: target SDK must be 35, got '$build_target'"; exit 1; }
grep -q 'POCKETDESK_ANDROID_TARGET_SDK' "$PROJECT_DIR/app/src/com/pocketlinux/ContainerRuntime.java" \
  || { echo "FAIL AndroidTargetPolicy: Android target is missing from runtime diagnostics"; exit 1; }
echo "PASS AndroidTargetPolicy (target API 35, current hardening)"

# No trace of the removed Windows layer may return: not a helper, not a catalogue row, not a
# code path. It was removed because it cannot work on this hardware (see the release notes),
# and a half-restored version of it is worse than none.
# The words "Windows" and "Wine" may still be written -- the app explains at length why there is
# no Windows layer, and one line tidies up after an older version that had one. What may never
# come back is the layer itself, so this looks for the things only an implementation carries.
layer_pattern='WINEPREFIX|winapp|winelayer|wineserver|wineboot|winecfg|WindowsApps|xfreerdp|--windows-app'
if grep -rlnE "$layer_pattern" "$PROJECT_DIR/app/src" "$PROJECT_DIR/app/assets" >/dev/null 2>&1; then
  echo "FAIL LinuxOnly: a Windows-layer implementation reference is back"
  grep -rlnE "$layer_pattern" "$PROJECT_DIR/app/src" "$PROJECT_DIR/app/assets"
  exit 1
fi
for gone in pocketdesk-winapp.sh pocketdesk-winelayer.sh pocketdesk-wineprocess.py \
            pocketdesk-wineboot.py pocketdesk-winqueue.sh pocketdesk-rdp.sh; do
  [ ! -e "$PROJECT_DIR/app/assets/$gone" ] \
    || { echo "FAIL LinuxOnly: $gone is back"; exit 1; }
done
[ ! -e "$PROJECT_DIR/app/src/com/pocketlinux/WindowsApps.java" ] \
  || { echo "FAIL LinuxOnly: WindowsApps.java is back"; exit 1; }
echo "PASS LinuxOnly (no Windows layer in the shipped app)"

# Three files carry the version. A forgotten bump ships an APK whose own screen contradicts
# Android's app info, and the owner cannot tell which build they are running.
name=$(grep -m1 '^VERSION_NAME=' "$PROJECT_DIR/build.sh" | cut -d'"' -f2)
code=$(grep -m1 '^VERSION_CODE=' "$PROJECT_DIR/build.sh" | cut -d'"' -f2)
java_name=$(grep -m1 'static final String VERSION = ' "$PROJECT_DIR/app/src/com/pocketlinux/MainActivity.java" | cut -d'"' -f2)
notes_name=$(grep -m1 '^# PocketLinux [0-9]' "$PROJECT_DIR/RELEASE-NOTES.md" | awk '{print $3}')
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

grep -q 'PromptForDownloadLocation' "$PROJECT_DIR/app/src/com/pocketlinux/ContainerRuntime.java" \
  || { echo "FAIL Downloads: Chrome is not wired to the destination choice"; exit 1; }
grep -q 'LinuxApps.CHROME_POLICY' "$PROJECT_DIR/app/src/com/pocketlinux/ContainerRuntime.java" \
  || { echo "FAIL Downloads: an existing Chrome policy is not repaired on desktop start"; exit 1; }
grep -q 'POCKETDESK_DOWNLOAD_DIR' "$PROJECT_DIR/app/assets/pocketdesk-desktop.sh" \
  || { echo "FAIL Downloads: the desktop does not receive its selected destination"; exit 1; }
grep -q 'Windows programs cannot run here' "$PROJECT_DIR/app/assets/pocketdesk-install.sh" \
  || { echo "FAIL Installer: a downloaded Windows program must be refused with its reason"; exit 1; }
grep -q -- '--use-angle=swiftshader' "$PROJECT_DIR/app/assets/pocketdesk-open.sh" \
  || { echo "FAIL Installer: ChatGPT's software renderer profile is missing"; exit 1; }
grep -q '^installed$' < <(bash "$PROJECT_DIR/app/assets/pocketdesk-software.sh" --selftest) \
  || { echo "FAIL Software: the Ubuntu software centre did not self-test"; exit 1; }
grep -q 'shell monkey' "$PROJECT_DIR/app/assets/pocketdesk-adb.sh" \
  || { echo "FAIL PhoneTesting: an installed APK must be launched for testing"; exit 1; }
echo "PASS CompletedComputerFeatures"

# Terminology, guarded. "Ubuntu Desktop" is Canonical's own GNOME product, which this is not,
# and this is not dual boot, a virtual machine or a second operating system. What is banned is
# the CLAIM, not the word: the app says "not dual boot" on purpose, so each file is folded to a
# single line (Java wraps a long sentence across many string literals) and the honest denials are
# removed before the scan. Cinnamon is deliberately NOT banned: the FAQ names it, along with
# GNOME, KDE and Xfce, precisely to say that this desktop is none of them. Ui.java is where the
# rule itself lives, and a rule has to name what it forbids, so it is the one file skipped.
banned='dual[ -]boot|Ubuntu Desktop|virtual machine on your phone|is a virtual machine|second operating system'
for file in "$PROJECT_DIR"/app/src/com/pocketlinux/*.java "$PROJECT_DIR"/app/assets/*.sh; do
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
grep -q 'trademark of Canonical' "$PROJECT_DIR/app/src/com/pocketlinux/MainActivity.java" \
  || { echo "FAIL Terminology: the Canonical trademark line is missing from the credits"; exit 1; }
tagline=$(grep -c 'A Linux computer that runs locally on your phone' "$PROJECT_DIR/app/src/com/pocketlinux/MainActivity.java")
[ "$tagline" -ge 2 ] \
  || { echo "FAIL Terminology: the one tagline must appear on the opening screen and the home header, found $tagline"; exit 1; }
echo "PASS Terminology"

# Every desktop helper has to be copied in TWO places: once by set-up, and once by the refresh
# that runs after each app install, or a computer built by an earlier version never gets it.
for helper in pocketdesk-storage.sh pocketdesk-software.sh pocketdesk-shot.sh pocketdesk-mark.png pocketdesk-mcp.py pocketdesk-agent.sh pocketdesk-appshot.sh pocketdesk-graphics.py pocketdesk-appprocess.py pocketdesk-childwatch.py pocketdesk-adb.sh pocketdesk-procinfo.py pocketdesk-save.sh pocketdesk-mobile.sh; do
  n=$(grep -c "$helper" "$PROJECT_DIR/app/src/com/pocketlinux/ContainerRuntime.java" || true)
  [ "$n" = 2 ] || { echo "FAIL AssetCopySites: $helper must be installed by set-up AND refreshed on every app install (found $n)"; exit 1; }
done
echo "PASS AssetCopySites"

# The crash this release exists for. Android 12+ SIGKILLs every forked process of an app once
# there are more than 32; under PRoot every Linux process is one, so the ceiling is the whole
# computer's. A real report showed 36 at peak with 1.2 GB free and lowMemory false -- memory was
# never the problem. Two things must therefore always be true, and neither may be a setting the
# owner has to find: finished processes nobody owns are cleared, and a persistent crowd closes one
# program rather than letting Android end the session.
watch="$PROJECT_DIR/app/assets/pocketdesk-desktop.sh"
helper="$PROJECT_DIR/app/assets/pocketdesk-childwatch.py"
grep -q 'become_subreaper' "$helper" \
  || { echo "FAIL ProcessCeiling: orphans cannot be reaped without becoming a subreaper"; exit 1; }
grep -q 'def reap_unowned' "$helper" \
  || { echo "FAIL ProcessCeiling: finished processes nobody owns are never cleared"; exit 1; }
grep -q 'WNOWAIT' "$helper" \
  || { echo "FAIL ProcessCeiling: reaping must look before it takes, or it steals an owner's status"; exit 1; }
grep -q 'childwatch.become_subreaper()' "$watch" \
  || { echo "FAIL ProcessCeiling: the session does not become a subreaper"; exit 1; }
grep -q 'childwatch.reap_unowned(owned)' "$watch" \
  || { echo "FAIL ProcessCeiling: the session never clears finished processes"; exit 1; }
grep -q 'keep_under_ceiling' "$watch" \
  || { echo "FAIL ProcessCeiling: nothing keeps the computer under Android's limit"; exit 1; }
ceiling=$(grep -o 'PROCESS_CEILING = [0-9]*' "$helper" | awk '{print $3}')
crowded=$(grep -o 'CROWDED_AT = [0-9]*' "$watch" | awk '{print $3}')
[ "$ceiling" = 32 ] \
  || { echo "FAIL ProcessCeiling: Android's limit is 32, the code says '$ceiling'"; exit 1; }
[ -n "$crowded" ] && [ "$crowded" -lt "$ceiling" ] && [ $((ceiling - crowded)) -ge 4 ] \
  || { echo "FAIL ProcessCeiling: acting at '$crowded' leaves no room before the ceiling"; exit 1; }
# The manual escape hatch is gone on purpose: it needed developer options, changed a setting for
# the whole phone, and did nothing for anyone who never found it.
for gone in AndroidProcessPolicy.java ProcessPolicyOutput.java; do
  [ ! -e "$PROJECT_DIR/app/src/com/pocketlinux/$gone" ] \
    || { echo "FAIL ProcessCeiling: $gone is back; the computer must manage itself"; exit 1; }
done
[ ! -e "$PROJECT_DIR/app/assets/pocketdesk-process-policy.py" ] \
  || { echo "FAIL ProcessCeiling: the phone-wide policy helper is back"; exit 1; }
grep -q 'Android process limit' "$PROJECT_DIR/app/src/com/pocketlinux/MainActivity.java" \
  && { echo "FAIL ProcessCeiling: the manual setting is back in Settings"; exit 1; }
echo "PASS ProcessCeiling (subreaper, unowned reaping, automatic ceiling guard, no manual setting)"


# The Android surface and foreground service are one feature. A catalogue row without one of

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
for pd_tool in appshot screenshot list_windows click type_text press_key scroll \
               phone_devices phone_install phone_launch phone_screenshot phone_ui \
               phone_tap phone_swipe phone_text phone_key phone_logcat phone_shell; do
  echo "$mcp_out" | grep -q "\"$pd_tool\"" \
    || { echo "FAIL McpServer: the $pd_tool tool is not offered"; exit 1; }
done
echo "$mcp_out" | grep -q '"id": 2' \
  || { echo "FAIL McpServer: tools/list did not answer the request it was asked"; exit 1; }
# An unknown method must be an error, not a crash that takes the agent's session with it.
echo '{"jsonrpc":"2.0","id":3,"method":"nonsense"}' \
  | python3 "$PROJECT_DIR/app/assets/pocketdesk-mcp.py" | grep -q '"error"' \
  || { echo "FAIL McpServer: an unknown method must answer with an error"; exit 1; }
# Every phone tool must refuse gracefully when nothing is connected, because that is the state
# an agent meets first. A tool that throws instead of saying "pair a phone" reads as a broken
# computer rather than an unpaired one.
no_device=$(printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"phone_tap","arguments":{"x":10,"y":10}}}' \
  | env PATH=/nonexistent "$(command -v python3)" "$PROJECT_DIR/app/assets/pocketdesk-mcp.py" 2>/dev/null || true)
echo "$no_device" | grep -qi 'adb is not installed\|No phone is connected' \
  || { echo "FAIL McpServer: a phone tool with no phone must say so, not fail"; exit 1; }
echo "PASS McpServer ($(python3 "$PROJECT_DIR/app/assets/pocketdesk-mcp.py" --selftest | grep -o '"' | wc -l | awk '{print $1/2}') tools)"
