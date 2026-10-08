#!/usr/bin/env bash
#
# Runs the instrumented suite on a booted emulator, collects the evidence
# (screenshots, logcat) whether it passes or fails, then installs the signed
# release APK and proves it launches without crashing.
#
# This lives in a file rather than in the workflow's `script:` block because
# android-emulator-runner executes that block one line at a time via `sh -c`:
# variables do not survive between lines and multi-line if/for blocks are a
# syntax error.

set -uo pipefail

# The app's permanent id (app/build.gradle.kts), not its name.
PKG=app.cloudsaver
SHOTS_ON_DEVICE=/sdcard/Pictures/CSTestShots
OUT=artifacts
mkdir -p "$OUT/screenshots"

adb wait-for-device
# Deterministic dates: EXIF carries no timezone, so MediaProvider resolves it
# in the device's zone. Pin it so capture times are reproducible.
adb shell "su root setprop persist.sys.timezone UTC" 2>/dev/null || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
# The emulator's own apps can hang on a loaded runner, and Android then puts
# an "isn't responding" dialog over everything. That dialog takes window
# focus, so taps and back presses stop reaching the app under test and a
# whole leg goes red with Ente Saver untouched (run 373, API 35: the Pixel
# Launcher's dialog sat over nine tests). Hide those dialogs. The suite's
# own assertions, the crash buffer collected below and the process checks
# after the release install still report every failure that is the app's.
adb shell settings put global hide_error_dialogs 1

echo "::group::Instrumented end-to-end tests"
tests_failed=0
./gradlew connectedDebugAndroidTest --no-daemon || tests_failed=1
echo "::endgroup::"

# Gradle prints only "There was N failure(s)" - the names live in the XML
# report, which used to mean downloading an artifact to learn what broke.
# Print them here so a red leg is diagnosable from the log alone.
if [ "$tests_failed" -ne 0 ]; then
  echo "::group::Which tests failed"
  python3 - <<'REPORT' || true
import glob, xml.etree.ElementTree as ET
for path in sorted(glob.glob(
        "app/build/outputs/androidTest-results/connected/**/*.xml",
        recursive=True)):
    for case in ET.parse(path).getroot().iter("testcase"):
        for bad in list(case.findall("failure")) + list(case.findall("error")):
            head = (bad.text or "").strip().splitlines()
            print("FAILED {}.{}".format(
                case.get("classname"), case.get("name")))
            for line in head[:6]:
                print("       " + line)
REPORT
  echo "::endgroup::"
fi

echo "::group::Collect screenshots and logs"
# The suite publishes PNGs through MediaStore because adb cannot read
# /sdcard/Android/data on Android 11+.
if adb pull "$SHOTS_ON_DEVICE" "$OUT/" ; then
  # adb creates artifacts/CSTestShots; flatten it into screenshots/.
  if [ -d "$OUT/CSTestShots" ]; then
    mv "$OUT/CSTestShots"/* "$OUT/screenshots/" 2>/dev/null || true
    rmdir "$OUT/CSTestShots" 2>/dev/null || true
  fi
fi
ls -la "$OUT/screenshots" || true
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
adb logcat -d -b crash > "$OUT/logcat-crash.txt" 2>/dev/null || true
echo "::endgroup::"

if [ "$tests_failed" -ne 0 ]; then
  echo "::error::Instrumented tests failed"
  tail -80 "$OUT/logcat-crash.txt" 2>/dev/null || true
  exit 1
fi

BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"

# Android must take an APK signed with a rotated key as an update to one
# signed with the key before it - the promise behind replacing the release
# key. Proven here on every Android this app supports, with two throwaway
# keys and the release's own signing recipe (sign-apk.sh), so it holds
# before the real keys are ever switched. The reverse must be refused: the
# old key keeps no right to replace the new one.
echo "::group::Key rotation: a rotated key's APK updates the old key's"
ROT=$(mktemp -d)
export ROT_OLD_PASS ROT_NEW_PASS
ROT_OLD_PASS=$(openssl rand -hex 16)
ROT_NEW_PASS=$(openssl rand -hex 16)
rot_step() { "$@" || { echo "::error::Rotation check could not prepare its APKs: $1 failed"; exit 1; }; }
rot_step keytool -genkeypair -keystore "$ROT/old.jks" -storetype JKS -alias old \
  -keyalg RSA -keysize 2048 -validity 3650 -storepass "$ROT_OLD_PASS" -keypass "$ROT_OLD_PASS" \
  -dname "CN=Rotation check old"
rot_step keytool -genkeypair -keystore "$ROT/new.p12" -storetype PKCS12 -alias new \
  -keyalg RSA -keysize 2048 -validity 3650 -storepass "$ROT_NEW_PASS" -keypass "$ROT_NEW_PASS" \
  -dname "CN=Rotation check new"
rot_step "$BT/apksigner" rotate --out "$ROT/lineage" \
  --old-signer --ks "$ROT/old.jks" --ks-key-alias old --ks-pass env:ROT_OLD_PASS --key-pass env:ROT_OLD_PASS \
  --new-signer --ks "$ROT/new.p12" --ks-key-alias new --ks-pass env:ROT_NEW_PASS
SIGN_KS="$ROT/old.jks" SIGN_KS_ALIAS=old KEYSTORE_PASSWORD="$ROT_OLD_PASS" KEY_PASSWORD="$ROT_OLD_PASS" SIGN_LINEAGE="" \
  rot_step bash .github/scripts/sign-apk.sh EnteSaver-release.apk "$ROT/old-key.apk"
SIGN_KS="$ROT/new.p12" SIGN_KS_ALIAS=new KEYSTORE_PASSWORD="$ROT_NEW_PASS" KEY_PASSWORD="$ROT_NEW_PASS" SIGN_LINEAGE="$ROT/lineage" \
  rot_step bash .github/scripts/sign-apk.sh EnteSaver-release.apk "$ROT/rotated.apk"
adb uninstall "$PKG" > /dev/null 2>&1 || true
adb install "$ROT/old-key.apk" > "$ROT/one.log" 2>&1
if ! grep -q "^Success" "$ROT/one.log"; then
  echo "::error::The old-key APK of the rotation check did not install"
  cat "$ROT/one.log"
  exit 1
fi
adb install -r "$ROT/rotated.apk" > "$ROT/two.log" 2>&1
if ! grep -q "^Success" "$ROT/two.log"; then
  echo "::error::Android refused an APK signed with a rotated key as an update to the old key's"
  cat "$ROT/two.log"
  exit 1
fi
adb install -r "$ROT/old-key.apk" > "$ROT/three.log" 2>&1
if grep -q "^Success" "$ROT/three.log"; then
  echo "::error::An APK signed with the old key replaced the rotated one; the old key must have no say once it has handed over"
  exit 1
fi
adb uninstall "$PKG" > /dev/null 2>&1 || true
rm -rf "$ROT"
echo "Rotation check passed: rotated key accepted as an update, old key refused afterwards."
echo "::endgroup::"

# The update a phone really gets: this build installed over the last
# published release, whatever key either was signed with. A release that
# cannot do this would leave uninstalling - and losing what the app knew -
# as the only way on, so it fails here rather than on someone's phone.
echo "::group::Update over the last published release"
if [ "${THROWAWAY_KEY:-false}" = "true" ]; then
  echo "::notice::This build was signed with a throwaway key; it is never published, so it is not checked against the last release."
else
  PREV_DIR=$(mktemp -d)
  if gh release download --repo "$GITHUB_REPOSITORY" --pattern 'EnteSaver-v*-release.apk' --dir "$PREV_DIR" > /dev/null 2>&1 \
     && PREV=$(ls "$PREV_DIR"/*.apk 2> /dev/null | head -1) && [ -n "$PREV" ]; then
    code_of() { "$BT/aapt2" dump badging "$1" 2> /dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1; }
    PREV_CODE=$(code_of "$PREV")
    NEW_CODE=$(code_of EnteSaver-release.apk)
    if [ -z "$PREV_CODE" ] || [ -z "$NEW_CODE" ] || [ "$PREV_CODE" -gt "$NEW_CODE" ]; then
      echo "::notice::The last release ($PREV_CODE) is newer than this build ($NEW_CODE); no update to check."
    else
      adb uninstall "$PKG" > /dev/null 2>&1 || true
      adb install -g "$PREV" > "$PREV_DIR/one.log" 2>&1
      if ! grep -q "^Success" "$PREV_DIR/one.log"; then
        echo "::error::The last published release did not install"
        cat "$PREV_DIR/one.log"
        exit 1
      fi
      # Opened once, so the update meets an app that has started.
      adb shell am start -n "$PKG/.MainActivity" > /dev/null 2>&1 || true
      sleep 8
      adb install -r -g EnteSaver-release.apk > "$PREV_DIR/two.log" 2>&1
      if ! grep -q "^Success" "$PREV_DIR/two.log"; then
        echo "::error::This build cannot be installed as an update over the last published release ($(basename "$PREV"))"
        cat "$PREV_DIR/two.log"
        exit 1
      fi
      echo "This build installs over $(basename "$PREV") as an update."
    fi
  else
    echo "::notice::No published release to update from."
  fi
  rm -rf "$PREV_DIR"
fi
echo "::endgroup::"

echo "::group::Install the signed release APK and launch it"
adb uninstall "$PKG" 2>/dev/null || true
# -g grants the runtime permissions up front, so setup's media step offers
# "Next" instead of a system prompt the walk below cannot answer.
adb install -r -g EnteSaver-release.apk
adb logcat -c || true
adb shell am start -n "$PKG/.MainActivity"
sleep 12

# A crash would leave no process behind.
if ! adb shell pidof "$PKG" > /dev/null 2>&1; then
  echo "::error::The released APK is not running after launch"
  adb logcat -d -b crash | tail -80
  exit 1
fi
adb exec-out screencap -p > "$OUT/screenshots/40-release-apk-launched.png"

# Walk the four tabs in the RELEASE build. The instrumented suite runs
# against the debug APK, so nothing else ever opens a second screen with R8
# applied - and a missing keep rule shows up as a crash on the screen that
# needs the stripped class, not at launch. A fresh install opens on setup,
# which has no tab bar: the walk finishes setup by each button's label first
# and fails unless every tab really opens. It used to tap by screen fraction,
# landed on setup every time, and passed without drawing a single tab.
if ! python3 .github/scripts/release-tab-walk.py "$PKG" "$OUT/screenshots"; then
  echo "::error::The release build's tab walk failed"
  exit 1
fi

# The release APK's promises that only the installed package can show.
# No internet permission, ever: the whole privacy claim rests on it.
if adb shell dumpsys package "$PKG" | grep -q "android.permission.INTERNET"; then
  echo "::error::The released APK holds the INTERNET permission"
  exit 1
fi
# The icon pack is found by launchers through their own actions; a pack
# no launcher can see is a feature that silently is not there.
if ! adb shell cmd package query-activities -a org.adw.launcher.THEMES | grep -q "IconPackActivity"; then
  echo "::error::The icon pack is not discoverable by launchers"
  exit 1
fi
# The Photos shortcut's landing screen must open without bringing the app down.
adb shell am start -n "$PKG/.OpenEnteActivity" || true
sleep 3

if adb logcat -d -b crash | grep -q "$PKG"; then
  echo "::error::Crash reported for $PKG"
  adb logcat -d -b crash | tail -120
  exit 1
fi
echo "::endgroup::"
echo "Emulator end-to-end run finished clean."
