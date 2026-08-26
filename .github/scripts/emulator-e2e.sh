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

echo "::group::Instrumented end-to-end tests"
tests_failed=0
./gradlew connectedDebugAndroidTest --no-daemon || tests_failed=1
echo "::endgroup::"

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

echo "::group::Install the signed release APK and launch it"
adb uninstall "$PKG" 2>/dev/null || true
adb install -r CloudSaver-release.apk
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
# against the debug APK, so until now nothing ever opened a second screen
# with R8 applied - and a missing keep rule shows up as a crash on the
# screen that needs the stripped class, not at launch. Taps are placed by
# fraction of the screen, so they follow whatever size the AVD reports.
SIZE=$(adb shell wm size | tr -d '\r' | awk -F': *' '{print $2}' | tail -1)
W=${SIZE%x*}
H=${SIZE#*x}
if [ -n "$W" ] && [ -n "$H" ]; then
  TAB_Y=$(( H * 96 / 100 ))
  i=1
  for FRAC in 12 37 62 87; do
    adb shell input tap $(( W * FRAC / 100 )) "$TAB_Y" || true
    sleep 3
    adb exec-out screencap -p > "$OUT/screenshots/4${i}-release-tab-${FRAC}.png" || true
    if ! adb shell pidof "$PKG" > /dev/null 2>&1; then
      echo "::error::The released APK died while opening a tab (x=${FRAC}%)"
      adb logcat -d -b crash | tail -80
      exit 1
    fi
    i=$(( i + 1 ))
  done
fi

if adb logcat -d -b crash | grep -q "$PKG"; then
  echo "::error::Crash reported for $PKG"
  adb logcat -d -b crash | tail -120
  exit 1
fi
echo "::endgroup::"
echo "Emulator end-to-end run finished clean."
