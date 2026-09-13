#!/usr/bin/env bash
# The gates. Every one of them exists because of a specific mistake that reached a phone --
# none are theoretical, and each names the failure it guards against.
#
# Run from anywhere: tests/run-tests.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
SRC="$APP/app/src/com/pocketide"
ASSETS="$APP/app/assets"
RES="$APP/app/res"

PASSED=0
FAILED=0
FAILURES=()

pass() { PASSED=$((PASSED + 1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() {
  FAILED=$((FAILED + 1))
  FAILURES+=("$1 — $2")
  printf '  \033[31m✗\033[0m %s\n      %s\n' "$1" "$2"
}
check() { # check <name> <condition-exit-code> <message-on-failure>
  if [ "$2" -eq 0 ]; then pass "$1"; else fail "$1" "$3"; fi
}

# Matching source code rather than the comments around it.
#
# This helper had a real bug worth keeping the fix for: it used to pipe sed into `grep -q`, and
# grep exits the moment it matches, which sends SIGPIPE to sed, which under `pipefail` failed
# the whole test. It passed four runs in five. The output is captured first now, so nothing can
# exit early.
in_code() { # in_code <pattern> <file...>
  local pattern="$1"; shift
  local code
  code=$(sed 's/[[:space:]]\/\/.*$//; s/^[[:space:]]*\*.*$//; s/^[[:space:]]*#[^!].*$//' "$@" 2>/dev/null) || return 1
  printf '%s\n' "$code" | grep -qE -- "$pattern"
}

echo
echo "PocketIDE — gates"
echo

# ---------------------------------------------------------------- build correctness

echo "Build"
python3 "$HERE/version_agreement.py" "$APP" && pass "VersionAgreement" \
  || fail "VersionAgreement" "build.sh and BuildFacts.java disagree about the version"

[ -x "$APP/build.sh" ]
check "BuildIsExecutable" $? "build.sh is not executable, which fails CI silently"

javac -version >/dev/null 2>&1
check "JavaAvailable" $? "no javac"

# ---------------------------------------------------------------- extensions and agents

echo
echo "Extensions"
python3 "$HERE/agent_facts.py" "$SRC" && pass "AgentFacts" \
  || fail "AgentFacts" "an agent's id, platform or engine does not match Open VSX"

in_code 'verified' "$SRC/Registry.java"
check "VerifiedNamespaces" $? \
  "Registry does not check the verified flag; every 2026 counterfeit came from an unverified namespace"

in_code 'ALLOW_UNVERIFIED' "$SRC/Registry.java"
check "UnverifiedIsOptIn" $? "unverified publishers are not gated behind a setting"

in_code 'publishedChecksum|sha256' "$SRC/AgentsPane.java"
check "PinnedChecksums" $? \
  "an extension is installed without comparing it against the registry's published checksum"

in_code 'Google\.google-antigravity' "$SRC/Agents.java"
check "GoogleIsPresent" $? \
  "Google's Antigravity extension is missing; an earlier search wrongly concluded it did not exist"

# ---------------------------------------------------------------- the editor script

echo
echo "Editor"
bash -n "$ASSETS/pocketide-editor.sh"
check "EditorScriptSyntax" $? "pocketide-editor.sh does not parse"

bash -n "$ASSETS/pocketide-bootstrap.sh"
check "BootstrapScriptSyntax" $? "pocketide-bootstrap.sh does not parse"

in_code 'SHA256="[0-9a-f]{64}"' "$ASSETS/pocketide-editor.sh"
check "EditorChecksumPinned" $? \
  "the editor archive is not pinned to a checksum; code-server publishes none of its own"

in_code 'bind-addr: 127\.0\.0\.1' "$ASSETS/pocketide-editor.sh"
check "Loopback" $? \
  "the editor binds somewhere other than loopback, so the phone's network could reach it"

in_code 'auth: password' "$ASSETS/pocketide-editor.sh"
check "EditorPassword" $? \
  "the editor has no password; Android does not keep loopback private between apps"

# The whole sign-in contract, read out of code-server's own source. This one caught a bug that
# had already been built into a signed APK: a ?password= query parameter code-server has never
# supported, which would have shown every owner a sign-in box for a password only the app knew.
python3 "$HERE/editor_signin.py" "$APP" && pass "EditorSignIn" \
  || fail "EditorSignIn" "the app cannot open the editor already signed in"

in_code 'strip-components=1' "$ASSETS/pocketide-editor.sh"
check "Unpacks" $? \
  "the archive's single versioned top-level folder is not stripped, so nothing lands in place"

# The marker must be printed only after the port answers. An earlier project printed it early
# and spent a release believing a working server had failed.
python3 "$HERE/marker_order.py" "$ASSETS/pocketide-editor.sh" && pass "MarkerOrder" \
  || fail "MarkerOrder" "PIDE-READY is printed before the port is known to answer"

in_code 'PIDE-READY' "$SRC/WorkspaceService.java"
check "MarkerIsRead" $? "the service does not wait for the editor's ready marker"

in_code 'man-db|mandb' "$ASSETS/pocketide-bootstrap.sh"
check "ManIndexOff" $? \
  "the manual index is left on; building it under PRoot is the silence that got dpkg killed"

in_code 'repair_packages' "$ASSETS/pocketide-bootstrap.sh"
check "DpkgRepairs" $? "there is no dpkg repair before apt, so one interrupted run poisons every later one"

# ---------------------------------------------------------------- the workspace

echo
echo "Workspace"
in_code 'kill-on-exit' "$SRC/Workspace.java"
check "KillOnExit" $? "PRoot is started without --kill-on-exit, so processes outlive the app"

in_code 'IMAGE_SHA256' "$SRC/Workspace.java"
check "UbuntuPinned" $? "the Ubuntu image is not checked against its published checksum"

# A point release has to move every place the version is written, or a screen says one version
# while the phone downloads another.
python3 "$HERE/ubuntu_pin.py" "$APP" && pass "UbuntuPinAgrees" \
  || fail "UbuntuPinAgrees" "the Ubuntu pin disagrees with itself somewhere"

in_code 'Dns\.refresh' "$SRC/Workspace.java"
check "Resolver" $? \
  "the resolver is not written before running, which is how the first real set-up died"

in_code 'precedence ::ffff:0:0/96' "$SRC/Workspace.java"
check "PreferIPv4" $? "IPv4 is not preferred; mobile networks answer AAAA and then refuse"

in_code 'SecureRandom' "$SRC/Workspace.java"
check "PasswordIsRandom" $? "the editor password is not generated randomly"

# ---------------------------------------------------------------- honesty

echo
echo "Honesty"
python3 "$HERE/no_secrets.py" "$APP" && pass "NoSecrets" \
  || fail "NoSecrets" "something that looks like a key or token is in the source or the assets"

python3 "$HERE/permissions.py" "$APP" && pass "PermissionsMatch" \
  || fail "PermissionsMatch" "the manifest and the Help screen disagree about what is requested"

python3 "$HERE/brand_tokens.py" "$APP" && pass "BrandTokens" \
  || fail "BrandTokens" "tokens.json, Brand.java and colors.xml disagree about a colour"

# A plan is the publisher's to state, not this app's to invent. Only Antigravity's free tier is
# claimed, because only Google publishes one.
python3 "$HERE/plans.py" "$SRC" && pass "PlansAreTheirs" \
  || fail "PlansAreTheirs" "a plan is described as free without the publisher saying so"

in_code 'not affiliated with' "$SRC/Texts.java"
check "NoAffiliationClaimed" $? "the terms do not disclaim affiliation with the publishers"

# ---------------------------------------------------------------- the interface

# Android 15 brought devices with 16 KB memory pages and Android 16 made them the default on
# new hardware. Two separate conditions have to hold for a bundled .so there, and the second --
# where GNU_RELRO ends -- is the one nothing else checks.
python3 "$HERE/native_alignment.py" "$APP" && pass "NativeAlignment" \
  || fail "NativeAlignment" "a bundled native library would fault on a 16 KB page device"

python3 "$HERE/native_alignment.py" "$APP" --self-test >/dev/null && pass "AlignmentGateWorks" \
  || fail "AlignmentGateWorks" "the alignment gate does not catch the layout it exists for"

echo
echo "Safety"
# The app lock and the phone's files are promises rather than features: "locked" and "off by
# default" are things an owner believes because the app says so, and a lock covering four
# screens out of five looks exactly like one covering five.
python3 "$HERE/safety.py" "$APP" && pass "LockAndFiles" \
  || fail "LockAndFiles" "the app lock or the phone-files switch does not hold what it claims"

echo
echo "Interface"
# The bar claims to follow a published specification, and the editor claims to size itself to
# the phone. The release before this one gave every phone the constant zoom 1.5.
python3 "$HERE/navigation.py" "$APP" && pass "NavigationAndFit" \
  || fail "NavigationAndFit" "the bottom bar or the editor's own sizing is off spec"
in_code 'TOUCH_TARGET_DP = 48' "$SRC/Ui.java"
check "TouchTargets" $? "the minimum touch target is not 48dp"

in_code 'workbench\.activityBar\.location' "$ASSETS/pocketide-editor.sh"
check "PhoneLayout" $? "the editor is not given a phone layout"

in_code 'window\.commandCenter' "$ASSETS/pocketide-editor.sh"
check "CommandCentre" $? \
  "the command centre is off, so 29 commands would need a keyboard shortcut a phone cannot press"

in_code 'commandPalette' "$SRC/WorkspaceActivity.java"
check "PaletteButton" $? "there is no one-tap way to reach the command palette"

python3 "$HERE/layout_sanity.py" "$SRC" && pass "FitsTheScreen" \
  || fail "FitsTheScreen" "a fixed width wider than a phone screen is set somewhere"

# ---------------------------------------------------------------- compile

echo
echo "Compile"
if [ -f "$APP/build/PocketIDE-v$(python3 -c "
import re,sys
print(re.search(r'VERSION_NAME=\"([^\"]+)\"', open('$APP/build.sh').read()).group(1))
")-release.apk" ]; then
  pass "Compiles"
else
  ( cd "$APP" && ./build.sh >/dev/null 2>&1 )
  check "Compiles" $? "the app does not build"
fi

echo
echo "──────────────────────────────────────────"
printf 'passed %d · failed %d\n' "$PASSED" "$FAILED"
if [ "$FAILED" -gt 0 ]; then
  echo
  for failure in "${FAILURES[@]}"; do echo "  ✗ $failure"; done
  exit 1
fi
echo "All gates green."
