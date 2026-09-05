#!/bin/bash
# Mobile app development, from a phone, with no PC anywhere in it.
#
# This is the piece that turns "a Linux computer that happens to be on a phone" into a place you
# can actually ship a mobile app from. It knows three things:
#
#   1. what is installed here and what is connected, so you are never guessing;
#   2. how to start a project of each kind that really works on this hardware;
#   3. build, install and open, on a real phone -- the one this is running on, if you like.
#
# ANDROID is complete here: Java, Gradle, aapt2 and adb are Linux tools that build ARM64 Linux
# binaries fine, and Wireless debugging puts a real device on the other end of adb. Build here,
# install here, open here.
#
# iOS is honest here, which is not the same as complete, and the reason is Apple's and not this
# app's: compiling and signing an iOS app needs Xcode, and Xcode runs only on macOS. Nothing on
# any Android phone changes that. What DOES work, and works well:
#
#   * write the app here, in React Native through Expo, and run it on a real iPhone by scanning
#     a code with Expo Go -- the code is served from this computer, the app runs on the iPhone,
#     and no Mac is involved at any point;
#   * the same project runs on Android here, on this phone, at the same time;
#   * when it is time for a real iOS build (TestFlight, the App Store), Expo's own build service
#     compiles it on their Macs from this project. That needs an Apple Developer account, which
#     is Apple's charge, not ours.
#
# So: Android end to end on the phone; iOS written and tried on the phone, compiled elsewhere,
# because Apple allows nowhere else.
#
# What is NOT possible, said once so it is never a surprise: an Android or iOS EMULATOR. An
# emulator needs hardware virtualisation, and no app on an unrooted phone can have it. A real
# device is the answer here -- and it is the better test anyway.
set -u
HOME_DIR=/home/coder
PROJECTS="$HOME_DIR/Projects"
mkdir -p "$PROJECTS" 2>/dev/null || true

have() { command -v "$1" >/dev/null 2>&1; }
say() { printf '%s\n' "$*"; }
line() { printf '%-22s %s\n' "$1" "$2"; }

tell() {   # tell <title> <text>
  if have zenity; then
    zenity --info --no-markup --width=560 --title="$1" --text="$2" 2>/dev/null || true
  fi
  printf '%s\n\n%s\n' "$1" "$2"
}

version_of() {   # version_of <command> <args...>
  if have "$1"; then "$@" 2>&1 | head -n 1; else echo "not installed"; fi
}

status() {
  say "MOBILE APP DEVELOPMENT ON THIS COMPUTER"
  say ""
  say "Android toolchain"
  line "  Java" "$(version_of java -version)"
  line "  Gradle" "$(if have gradle; then gradle --version 2>/dev/null | grep -i '^Gradle' | head -n 1; else echo 'not installed'; fi)"
  line "  aapt2" "$(if have aapt2; then command -v aapt2; else echo 'not installed — Android builds need it'; fi)"
  line "  adb" "$(version_of adb version)"
  line "  scrcpy" "$(if have scrcpy; then echo installed; else echo 'not installed'; fi)"
  say ""
  say "Cross-platform (Android AND iPhone, from one project)"
  line "  Node" "$(version_of node --version)"
  line "  npm" "$(version_of npm --version)"
  line "  Expo project" "$(if [ -f package.json ] && grep -q '\"expo\"' package.json 2>/dev/null; then echo 'this folder is one'; else echo 'run: pocketdesk-mobile new'; fi)"
  say ""
  say "Devices"
  if have adb; then
    adb devices -l 2>&1 | sed 's/^/  /'
  else
    say "  adb is not installed."
  fi
  say ""
  say "This phone is 127.0.0.1. Pair it once with: pocketdesk-adb pair"
  say "An emulator cannot run here (it needs hardware virtualisation). A real phone is the device."
}

missing_android() {
  for pd_tool in java gradle adb; do
    have "$pd_tool" || { say "$pd_tool is not installed. Add 'Mobile app development' from PocketDesk's Apps tab."; return 0; }
  done
  return 1
}

new_project() {
  kind=${1:-}
  if [ -z "$kind" ] && have zenity; then
    kind=$(zenity --list --radiolist --width=620 --height=340 --title="A new mobile app" \
      --text="What kind of app? Both kinds are built and tried on this phone." \
      --column="" --column="Kind" --column="What it means" \
      TRUE expo "React Native through Expo — one project that runs on THIS phone and on a real iPhone (Expo Go). JavaScript." \
      FALSE android "A native Android app — Java or Kotlin, Gradle, installed straight onto this phone." \
      2>/dev/null) || exit 0
  fi
  [ -n "$kind" ] || kind=expo
  name=${2:-}
  if [ -z "$name" ] && have zenity; then
    name=$(zenity --entry --title="Name" --text="A name for the app (letters, digits and dashes):" 2>/dev/null) || exit 0
  fi
  [ -n "$name" ] || name=my-app
  name=$(printf '%s' "$name" | tr -cd '[:alnum:]-_' )
  [ -n "$name" ] || name=my-app

  case "$kind" in
    expo)
      have npx || { tell "Node is not installed" "Node and npm come with the computer's basics. Settings -> Update the computer's basics installs them."; exit 1; }
      tell "Making $name" "This downloads the Expo starter (about 250 MB of packages) into
Projects/$name. On mobile data that is a real download — Wi-Fi is kinder.

When it is done:
  cd ~/Projects/$name
  npx expo start

Then, to see it running:
  on an iPhone — open the Camera and scan the code Expo prints;
  on this phone — install Expo Go from the Play Store and scan the same code;
  in this computer — press w for the browser preview."
      cd "$PROJECTS" || exit 1
      npx --yes create-expo-app@latest "$name" --template blank
      say "Made $PROJECTS/$name"
      ;;
    android)
      missing_android && exit 1
      target="$PROJECTS/$name"
      [ -e "$target" ] && { say "$target already exists."; exit 1; }
      mkdir -p "$target"
      cd "$target" || exit 1
      # gradle init writes a working, current Android-less skeleton; the Android plugin is added
      # by the agent or the owner. Started this way rather than with a template written into
      # PocketDesk, which would be out of date the month after it shipped.
      gradle init --type basic --dsl kotlin --project-name "$name" --no-daemon >/dev/null 2>&1 \
        || say "gradle init did not finish; the folder is there and can be filled in by hand."
      say "Made $target"
      say "Open it in Cursor or ask an AI app here to turn it into an Android app."
      ;;
    *)
      say "usage: pocketdesk-mobile new [expo|android] [name]"
      exit 1 ;;
  esac
}

build_android() {
  missing_android && exit 1
  [ -f gradlew ] || [ -f build.gradle ] || [ -f build.gradle.kts ] || {
    say "This folder is not a Gradle project. cd into your app first."; exit 1; }
  say "Building… (Gradle runs without its daemon here, so the first build is the slow one)"
  if [ -x ./gradlew ]; then ./gradlew assembleDebug --no-daemon; else gradle assembleDebug --no-daemon; fi
}

newest_apk() {
  find . -type f -name '*.apk' -newermt '-1 day' 2>/dev/null | head -n 1 \
    || find . -type f -name '*.apk' 2>/dev/null | head -n 1
}

run_on_phone() {
  build_android || exit 1
  apk=$(newest_apk)
  [ -n "$apk" ] || { say "No .apk came out of the build."; exit 1; }
  say "Installing $(basename "$apk") on the phone…"
  /usr/local/bin/pocketdesk-adb install "$apk"
}

case "${1:-status}" in
  status)  status ;;
  new)     shift; new_project "$@" ;;
  build)   build_android ;;
  run)     run_on_phone ;;
  devices) /usr/local/bin/pocketdesk-adb devices ;;
  pair)    /usr/local/bin/pocketdesk-adb pair ;;

  ios)
    tell "Building for iPhone, from a phone" \
"An iOS app can be WRITTEN and RUN here. It cannot be COMPILED here, and that is Apple's rule:
signing an iOS app needs Xcode, and Xcode runs only on macOS. No Android phone changes that.

What works, today, with no Mac:

1. Write it here as a React Native app through Expo:
     pocketdesk-mobile new expo my-app
2. Run it on a REAL iPhone: 'npx expo start', then scan the code with the iPhone's Camera and
   open it in Expo Go. The code is served from this computer; the app runs on the iPhone.
   The same project runs on this Android phone at the same time.
3. When you need a real iOS build for TestFlight or the App Store, Expo's build service
   compiles it on their Macs from this same project ('eas build -p ios'). That needs an Apple
   Developer account — Apple's charge, not PocketDesk's.

Android needs none of that: it is built, installed and opened right here."
    ;;

  *)
    say "usage: pocketdesk-mobile {status|new [expo|android] [name]|build|run|devices|pair|ios}"
    exit 1 ;;
esac
