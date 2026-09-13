#!/usr/bin/env bash
# What has to stay true in this app, checked on every push.
#
# Most of this app is other people's software: Ubuntu, Node, code-server, and each publisher's
# own extension or daemon. There is very little of our own logic to unit-test. What there is to
# protect is the honesty of the thing -- that a door which has never been opened says so, that
# nothing claims a publisher supports something they do not, that the scripts are valid shell
# before they are shipped to a phone, and that no key or token rides along in the APK.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

SRC="app/src/com/pocketagent/doors"
ASSETS="app/assets"

fail() { echo "FAIL $1"; exit 1; }

# ---------------------------------------------------------------- shell, before it reaches a phone
for script in "$ASSETS"/*.sh; do
  bash -n "$script" || fail "AssetScriptSyntax: $script does not parse"
done
echo "PASS AssetScriptSyntax ($(ls "$ASSETS"/*.sh | wc -l | tr -d ' ') scripts)"

# ---------------------------------------------------------------- the catalog tells the truth
# Every agent's start line must name a script that exists, or be empty for a door that is not
# wired up. A start line pointing at a missing script would fail on the phone, not here.
while read -r start; do
  [ -z "$start" ] && continue
  name=${start%% *}
  [ -f "$ASSETS/$name" ] || fail "Doors: $name is named in the catalog but not shipped"
done < <(grep -oE '"doors-[a-z]+\.sh [a-z]+( [a-z]+)?"' "$SRC/Doors.java" | tr -d '"')
echo "PASS DoorScripts (every start line points at a shipped script)"

# No agent may be marked proven until somebody has actually run it on a phone. This is the
# gate that stops a confident claim sneaking into a build that has never been tested.
if grep -qE 'true\);\s*$' "$SRC/Doors.java" && grep -c 'proven' "$SRC/Doors.java" >/dev/null; then
  proven=$(grep -cE '^\s+(true|false)\),?$' "$SRC/Doors.java" || true)
fi
if grep -oE '\btrue\),$' "$SRC/Doors.java" | grep -q .; then
  fail "DoorHonesty: an agent is marked proven; nothing in this build has been proven on a phone"
fi
echo "PASS DoorHonesty (no agent claims to be proven)"

# Cursor has no headless mode and no published extension. If a future edit gives it a start
# line, that is a claim about Cursor that is not true, and it should fail here first.
if grep -A6 '"cursor", "Cursor"' "$SRC/Doors.java" | grep -q '"doors-'; then
  fail "DoorHonesty: Cursor has a start line, but Cursor publishes no headless mode"
fi
echo "PASS CursorClaim (no headless route is claimed for Cursor)"

# ---------------------------------------------------------------- integrity of what is downloaded
grep -q 'sha512sum --check' "$ASSETS/doors-antigravity.sh" \
  || fail "Integrity: the Antigravity download is not checksum-verified"
grep -q 'sha256sum' "$ASSETS/doors-codeserver.sh" \
  || fail "Integrity: the code-server download records no digest"
grep -q "IMAGE_SHA256" "$SRC/Ubuntu.java" \
  || fail "Integrity: the Ubuntu image is not checksum-verified"
# Every fetch that leaves the phone must be HTTPS; a plain http:// download of code would be a
# hole big enough to replace an agent with anything. Loopback is the one exception, because a
# certificate for 127.0.0.1 is a certificate nobody can verify, and that traffic never leaves.
if grep -REn "curl[^|]*[\"' ]http://" "$ASSETS" | grep -vE '127\.0\.0\.1|localhost' | grep -q .; then
  fail "Integrity: a download that leaves the phone uses plain HTTP"
fi
# Ubuntu's base image ships no certificate store, so the very first apt fetch cannot be HTTPS.
# What matters is the order, not the flags: the shipped source list is removed (it points at a
# host that serves no arm64, which is what made the first set-up fail), certificates are
# installed, and apt switches to HTTPS -- in that order. Pinning the exact apt flags here is
# what broke this gate once already, so the checks below look for the step, not its spelling.
line_of() { grep -nE "$1" "$ASSETS/doors-bootstrap.sh" 2>/dev/null | head -n1 | cut -d: -f1; }
drop=$(line_of 'rm -f /etc/apt/sources\.list\.d/')
plain=$(line_of 'deb http://ports\.ubuntu\.com')
certs=$(line_of 'apt-get install .*ca-certificates')
switch=$(line_of 'https://ports\.ubuntu\.com.*sources\.list|sed .*ports\.ubuntu\.com')
for step in drop plain certs switch; do
  eval "value=\$$step"
  [ -n "$value" ] || fail "Integrity: the bootstrap has no '$step' step"
done
[ "$drop" -lt "$plain" ] && [ "$plain" -lt "$certs" ] && [ "$certs" -lt "$switch" ] \
  || fail "Integrity: the bootstrap's apt steps are out of order (drop $drop, plain $plain, certs $certs, switch $switch)"
# The architecture is named before anything is fetched, and an empty index is caught before an
# install can fail with "no installation candidate" -- the error that stopped the first attempt.
grep -q 'dpkg --print-architecture' "$ASSETS/doors-bootstrap.sh" \
  || fail "Integrity: the bootstrap does not check the architecture before fetching"
grep -q 'apt-cache policy' "$ASSETS/doors-bootstrap.sh" \
  || fail "Integrity: nothing proves the package index arrived before the first install"
echo "PASS Integrity (checksums; HTTPS off the phone, and apt on HTTPS from its second fetch)"

# ---------------------------------------------------------------- the loopback stays loopback
grep -q 'bind-addr "127.0.0.1' "$ASSETS/doors-codeserver.sh" \
  || fail "Loopback: code-server is not bound to the loopback address"
if grep -q 'bind-addr "0.0.0.0' "$ASSETS/doors-codeserver.sh"; then
  fail "Loopback: code-server would listen on the network"
fi
grep -q '<domain includeSubdomains="false">127.0.0.1</domain>' app/res/xml/network_security_config.xml \
  || fail "Loopback: cleartext is not restricted to the loopback address"
grep -q 'cleartextTrafficPermitted="false"' app/res/xml/network_security_config.xml \
  || fail "Loopback: cleartext is not refused by default"
echo "PASS Loopback (the editor answers only this phone, and only there is cleartext allowed)"

# ---------------------------------------------------------------- sign-in happens, and first
# Google's own words: run agy, complete the sign-in flow, then exit, and only then start the
# daemon. The first cut of this app skipped that entirely and would have failed at step one.
grep -q 'doors-antigravity.sh login' "$SRC/Doors.java" \
  || fail "Login: Antigravity has no sign-in step, so its daemon would start with no credentials"
grep -q 'NEEDLOGIN' "$ASSETS/doors-antigravity.sh" \
  || fail "Login: the script does not tell the app when sign-in is missing"
grep -q 'NEEDLOGIN' "$SRC/DoorService.java" \
  || fail "Login: the app does not act on a missing sign-in"
grep -q 'ACTION_INPUT' "$SRC/DoorService.java" \
  || fail "Login: nothing can type the code back, so a sign-in could never finish"
echo "PASS Login (sign-in runs first, and the code can be typed back)"

# ---------------------------------------------------------------- sign-in leaves the app
grep -q 'accounts.google.com' "$SRC/DoorActivity.java" \
  || fail "SignIn: Google sign-in is not handed to the real browser"
grep -q 'FLAG_ACTIVITY_NEW_TASK' "$SRC/DoorActivity.java" \
  || fail "SignIn: the sign-in hand-off does not open a browser"
echo "PASS SignIn (passwords are typed in the browser, never in this app's window)"

# ---------------------------------------------------------------- one agent at a time
grep -q 'runningAgent' "$SRC/DoorService.java" \
  || fail "OneAtATime: nothing tracks which agent is running"
grep -q 'foregroundServiceType="specialUse"' app/AndroidManifest.xml \
  || fail "OneAtATime: the agent service is not a foreground service"
grep -q 'addAction' "$SRC/DoorService.java" \
  || fail "OneAtATime: the notification has no way to stop the agent"
echo "PASS OneAtATime (a visible, stoppable service, one agent at a time)"

# ---------------------------------------------------------------- versions agree
build_name=$(grep -oE 'VERSION_NAME="[0-9.]+"' build.sh | cut -d'"' -f2)
build_code=$(grep -oE 'VERSION_CODE="[0-9]+"' build.sh | cut -d'"' -f2)
grep -qi "version \*\*$build_name\*\*" RELEASE-NOTES.md \
  || fail "VersionAgreement: RELEASE-NOTES does not name version $build_name"
[ -n "$build_code" ] || fail "VersionAgreement: no version code"
echo "PASS VersionAgreement ($build_name, code $build_code)"

# ---------------------------------------------------------------- nothing secret ships
if find "$ASSETS" app/res -type f \( -name '*.pem' -o -name '*.key' -o -name '.env*' \
      -o -name 'id_rsa*' -o -name '*.jks' \) | grep -q .; then
  fail "NoSecrets: a key or credential file is inside the APK's assets"
fi
echo "PASS NoSecrets (no credential files in the packaged assets)"

# ---------------------------------------------------------------- the key stays in the repository
# The repository root ignores *.jks, and on this folder's first commit it quietly took the
# signing key with it. An APK signed by a key that is not in the repository cannot be replaced
# by the next build, which would cost whoever installed it their whole workspace.
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  git ls-files --error-unmatch .signing/pocketagent-local.jks >/dev/null 2>&1 \
    || fail "SigningKey: the signing key is not tracked; every build would install as a new app"
  echo "PASS SigningKey (the key that signs this app is in the repository)"
else
  echo "SKIP SigningKey (not a git checkout)"
fi

# ---------------------------------------------------------------- it compiles
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -f "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" ]; then
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" compile --dir app/res -o "$work/res.zip" >/dev/null
  "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" link -o "$work/app.apk" \
    -I "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
    --manifest app/AndroidManifest.xml --java "$work/gen" \
    --min-sdk-version 29 --target-sdk-version 35 --auto-add-overlay "$work/res.zip" >/dev/null
  mapfile -t SOURCES < <(find "$SRC" "$work/gen" -name '*.java' | sort)
  # core-lambda-stubs is what lets source 8 lambdas compile against android.jar; without it
  # every lambda in the app is an error, and the failure would look like a code problem.
  javac -encoding UTF-8 -source 8 -target 8 -nowarn \
    -bootclasspath "$ANDROID_SDK_ROOT/platforms/android-35/android.jar:$ANDROID_SDK_ROOT/build-tools/35.0.0/core-lambda-stubs.jar" \
    -d "$work/classes" "${SOURCES[@]}" \
    || fail "Compiles: the app does not compile"
  echo "PASS Compiles (${#SOURCES[@]} sources)"
else
  echo "SKIP Compiles (no Android SDK in ANDROID_SDK_ROOT)"
fi

echo
echo "All checks passed."
