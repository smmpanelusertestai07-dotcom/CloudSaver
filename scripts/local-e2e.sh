#!/usr/bin/env bash
#
# The whole instrumented suite on a locally booted emulator, one command:
#
#   scripts/local-e2e.sh            # boot (or reuse) the AVD, run everything
#   scripts/local-e2e.sh --fresh    # wipe the AVD first: a first-boot run
#   scripts/local-e2e.sh --keep     # leave the emulator running afterwards
#
# Built for a machine with no KVM. A normal system image under pure software
# emulation starves system_server past its own watchdog - the package manager
# comes and goes and installs fail with "Can't find service". The ATD image
# (Automated Test Device) is Google's answer for exactly this: SystemUI cut to
# the bone, no setup wizard, no launcher extras, made to run instrumentation
# and nothing else. It boots and stays booted where the full image thrashes.
#
# The first boot is the slow one (software emulation pays for dexopt once).
# The emulator saves a quickboot snapshot on exit, so every later run skips
# the boot almost entirely.
#
# Evidence lands in artifacts/local/: every screenshot the suite takes, the
# failed-test screenshots, logcat, and the instrument transcript.

set -uo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-/opt/android-sdk}"
AVD=cloudsaver-atd30
IMG="system-images;android-30;aosp_atd;x86_64"
PKG=app.cloudsaver
SHOTS=/sdcard/Pictures/CSTestShots
OUT=artifacts/local
ADB="$SDK/platform-tools/adb"
FRESH=0; KEEP=0
for a in "$@"; do
  case "$a" in
    --fresh) FRESH=1 ;;
    --keep) KEEP=1 ;;
  esac
done

mkdir -p "$OUT/screenshots"

# ---- the device image and the AVD -----------------------------------------
if [ ! -d "$SDK/system-images/android-30/aosp_atd" ]; then
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "$IMG"
fi
if [ "$FRESH" = 1 ]; then
  "$SDK/cmdline-tools/latest/bin/avdmanager" delete avd -n "$AVD" 2>/dev/null || true
fi
if ! "$SDK/cmdline-tools/latest/bin/avdmanager" list avd -c 2>/dev/null | grep -qx "$AVD"; then
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMG" --force
fi

# ---- boot ------------------------------------------------------------------
if ! "$ADB" get-state >/dev/null 2>&1; then
  # Software emulation wants every core it can get; run nothing else heavy
  # while this is up. swiftshader renders on the CPU, which is all there is.
  "$SDK/emulator/emulator" "@$AVD" \
    -no-window -no-audio -no-boot-anim -no-accel \
    -gpu swiftshader_indirect -memory 4096 -cores 4 \
    >"$OUT/emulator.log" 2>&1 &
  echo "emulator starting (log: $OUT/emulator.log)"
fi

# Booted is not usable: under software emulation the system server can lag
# its own boot flag. Usable means the package manager answers, repeatedly.
echo "waiting for a stable boot..."
stable=0
for _ in $(seq 1 120); do
  if "$ADB" shell pm path android >/dev/null 2>&1; then
    stable=$((stable + 1))
  else
    stable=0
  fi
  [ "$stable" -ge 3 ] && break
  sleep 10
done
if [ "$stable" -lt 3 ]; then
  echo "the emulator never became stable; see $OUT/emulator.log" >&2
  exit 1
fi
echo "device is up"

# Deterministic dates and no animation time, exactly as CI sets it.
"$ADB" shell "su root setprop persist.sys.timezone UTC" 2>/dev/null || true
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0

# ---- build, install, run ---------------------------------------------------
./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:assembleDebugAndroidTest

# Streamed installs (adb install) hang against a loaded software-emulated
# guest; a push followed by pm install runs entirely inside the device and
# does not. Failure here is fatal - an uninstalled runner otherwise turns
# into a confusing INSTRUMENTATION_FAILED five lines later.
"$ADB" push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/app.apk
"$ADB" shell pm install -r -t /data/local/tmp/app.apk | grep -q Success
"$ADB" push app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /data/local/tmp/test.apk
"$ADB" shell pm install -r -t /data/local/tmp/test.apk | grep -q Success
"$ADB" shell pm list instrumentation | grep -q app.cloudsaver.test || {
  echo "the test runner is not installed" >&2; exit 1
}

echo "running the instrumented suite (this is the slow part)..."
"$ADB" shell am instrument -w \
  app.cloudsaver.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$OUT/instrument.txt"

# ---- evidence, pass or fail ------------------------------------------------
if "$ADB" pull "$SHOTS" "$OUT/" >/dev/null 2>&1; then
  mv "$OUT/CSTestShots"/* "$OUT/screenshots/" 2>/dev/null || true
  rmdir "$OUT/CSTestShots" 2>/dev/null || true
fi
"$ADB" logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
"$ADB" logcat -d -b crash > "$OUT/logcat-crash.txt" 2>/dev/null || true

[ "$KEEP" = 1 ] || "$ADB" emu kill >/dev/null 2>&1 || true

# The instrument stream ends with "OK (N tests)" only when everything passed.
if grep -qE "^OK \([0-9]+ tests?\)" "$OUT/instrument.txt"; then
  echo "ALL TESTS PASSED - evidence in $OUT/"
else
  echo "FAILURES - transcript in $OUT/instrument.txt, screenshots in $OUT/screenshots/" >&2
  grep -B1 -A6 "^Error in \|FAILURES!!!" "$OUT/instrument.txt" | head -40 || true
  exit 1
fi
