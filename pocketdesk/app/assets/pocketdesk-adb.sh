#!/bin/bash
# Puts a phone on the other end of adb, so an app built here can be installed and tried for real.
#
# Two phones can be reached, and the first one surprises people: THIS one. Android 11 and later
# have Wireless debugging, which listens on the phone's own network -- and the computer inside
# this app shares that network, so 127.0.0.1 reaches the phone it is running on. Build an APK
# here, install it here, and it opens on the same screen a moment later. No cable, no PC.
#
# The second is any other Android phone on the same Wi-Fi, which is the same steps with its
# address instead of localhost.
#
# What this cannot do, and says so rather than pretending: run an Android emulator. An emulator
# needs hardware virtualisation, which no app on an unrooted phone can have.
set -u
HOME_DIR=/home/coder
STATE="$HOME_DIR/.pocketdesk/adb"
mkdir -p "$STATE"

have() { command -v "$1" >/dev/null 2>&1; }
say() { printf '%s\n' "$*"; }

have adb || { say "adb is not installed. Add 'Mobile app development' from the Apps tab first."; exit 1; }

ask() {   # ask <title> <text>  -> prints what was typed
  if have zenity; then
    zenity --entry --title="$1" --text="$2" 2>/dev/null
  else
    printf '%s\n' "$2" >&2
    read -r reply && printf '%s' "$reply"
  fi
}

tell() {   # tell <title> <text>
  if have zenity; then
    zenity --info --no-markup --width=520 --title="$1" --text="$2" 2>/dev/null || true
  fi
  printf '%s\n%s\n' "$1" "$2"
}

case "${1:-help}" in

  # ---- pair with this phone, or another one, using Android's own pairing code ----------------
  pair)
    tell "Wireless debugging" \
"To let this computer install apps on a phone, that phone needs Wireless debugging on.

ON THIS PHONE:
1. Settings → About phone → tap Build number 7 times (this turns on Developer options)
2. Settings → Additional settings → Developer options → Wireless debugging → ON
3. Tap 'Pair device with pairing code'
4. Note the PORT and the 6-digit CODE it shows

Then come back here and enter them."

    host=$(ask "Pairing address" "Address to pair with.

For THIS phone leave it as 127.0.0.1.
For another phone on the same Wi-Fi, type the address it shows.") || exit 0
    [ -n "$host" ] || host=127.0.0.1
    port=$(ask "Pairing port" "The PORT under 'Pair device with pairing code' (it is not the same as the one on the main screen).") || exit 0
    [ -n "$port" ] || { say "No port given."; exit 1; }
    code=$(ask "Pairing code" "The 6-digit code on the phone.") || exit 0
    [ -n "$code" ] || { say "No code given."; exit 1; }

    say "Pairing with $host:$port…"
    if printf '%s\n' "$code" | adb pair "$host:$port" 2>&1 | tee "$STATE/pair.log" | grep -qi 'successfully'; then
      printf '%s' "$host" > "$STATE/host"
      tell "Paired" "This computer is now paired with $host.

Now use 'Connect' — the port for connecting is the one on the Wireless debugging MAIN screen, which is different from the pairing port."
    else
      tell "Pairing did not work" "$(cat "$STATE/pair.log" 2>/dev/null)

The pairing code and port change every time that screen is opened, so take them fresh and try again."
      exit 1
    fi
    ;;

  connect)
    host=$(cat "$STATE/host" 2>/dev/null || printf '127.0.0.1')
    host=$(ask "Connect" "Address of the phone.

This phone is 127.0.0.1.") || exit 0
    [ -n "$host" ] || host=127.0.0.1
    port=$(ask "Port" "The port shown on the Wireless debugging MAIN screen (under 'IP address & Port').") || exit 0
    [ -n "$port" ] || { say "No port given."; exit 1; }
    out=$(adb connect "$host:$port" 2>&1)
    if printf '%s' "$out" | grep -qiE 'connected to'; then
      printf '%s' "$host:$port" > "$STATE/device"
      tell "Connected" "$out

Now 'Install an APK' will put an app straight onto that phone, and 'Logs' will show what it prints."
    else
      tell "Could not connect" "$out

Wireless debugging turns itself off when the phone leaves Wi-Fi, and the port changes each time. Turn it on again and take the new port."
      exit 1
    fi
    ;;

  # ---- the point of all of it: build here, run there ------------------------------------------
  install)
    apk=${2:-}
    if [ -z "$apk" ] && have zenity; then
      apk=$(zenity --file-selection --title="Install an APK on the phone" \
        --filename="$HOME_DIR/Projects/" --file-filter="Android apps | *.apk" 2>/dev/null) || exit 0
    fi
    [ -f "$apk" ] || { say "usage: pocketdesk-adb install <file.apk>"; exit 1; }
    say "Installing $(basename "$apk")…"
    out=$(adb install -r "$apk" 2>&1)
    say "$out"
    printf '%s' "$out" | grep -qi 'Success' \
      && tell "Installed" "$(basename "$apk") is on the phone. Open it from the phone's own app list." \
      || { tell "Not installed" "$out"; exit 1; }
    ;;

  devices) adb devices -l ;;

  logs)
    if have lxterminal; then
      lxterminal -e bash -lc 'adb logcat -v brief; read -p "Press Enter to close "' &
    else
      adb logcat -v brief
    fi
    ;;

  screen)
    have scrcpy || { say "scrcpy is not installed."; exit 1; }
    # Small and slow on purpose: this phone is drawing the desktop, the viewer and now a mirror
    # of itself, all on a processor with no graphics chip to help.
    scrcpy --max-size 800 --max-fps 15 --no-audio 2>&1 | tail -n 5 &
    ;;

  disconnect) adb disconnect; rm -f "$STATE/device"; say "Disconnected." ;;

  status)
    tell "Phone testing" "Paired with: $(cat "$STATE/host" 2>/dev/null || echo 'nothing yet')
Connected to: $(cat "$STATE/device" 2>/dev/null || echo 'nothing')

$(adb devices -l 2>&1)

adb: $(adb version 2>&1 | head -n 1)
Java: $(java -version 2>&1 | head -n 1)
Gradle: $(gradle --version 2>/dev/null | grep -i '^Gradle' | head -n 1 || echo 'not installed')"
    ;;

  *)
    say "usage: pocketdesk-adb {pair|connect|install <apk>|devices|logs|screen|disconnect|status}"
    exit 1 ;;
esac
