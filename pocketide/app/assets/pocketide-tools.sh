#!/bin/bash
# What the agents can actually use: a real browser, screenshots, video, and a build toolchain.
#
# None of this is installed during set-up. Set-up is already a 410 MB download and twenty
# minutes; this is another 400 MB-odd that most people will not need on the first day, so it is
# a switch in Settings that installs on demand and says what it will cost before it starts.
#
# Everything here was checked against what arm64 Linux under PRoot can actually do, rather than
# assumed from what a laptop can:
#
#   BROWSER    Ubuntu 24.04 ships Chromium only as a snap, and snaps cannot run under PRoot at
#              all. The working source is the xtradeb PPA, which publishes a genuine aarch64
#              .deb built against Noble's t64 libraries. Debian's chromium .deb is NOT a
#              substitute -- its versioned dependencies on pre-t64 library names cannot be
#              satisfied on Noble, and forcing it breaks the system.
#
#   SANDBOX    Chromium must run --no-sandbox here, and that is not a shortcut. PRoot can
#              provide neither a SUID helper nor unprivileged user namespaces, so Chromium's own
#              sandbox has nothing to build on. What contains it instead is Android: the whole
#              rootfs is this app's private data, under this app's uid and SELinux domain. That
#              is a real boundary, just not Chromium's own -- so the app says "contained by
#              Android", never "safe".
#
#   VIDEO      No X server and none needed. Playwright records video headless through CDP
#              screencast with its own bundled arm64 ffmpeg. Xvfb would work under PRoot -- it
#              is pure userspace -- but it costs memory for nothing unless a real GUI app has to
#              be filmed.
#
#   ANDROID    A complete Android build toolchain, and the reason it can be complete now is
#              four files. Google's own SDK installs on arm64 without trouble -- sdkmanager,
#              d8, r8 and apksigner are Java -- but four of the build tools (aapt2, aidl,
#              zipalign, split-select) ship as x86-64 binaries only, and a Gradle build stops
#              on the first of them with an Exec format error. The Commit451
#              android-arm-build-tools project rebuilds exactly those four from AOSP for
#              aarch64 against glibc, under the MIT licence. This layer installs the JDK,
#              Google's command-line tools, platform 35 and build-tools 35.0.1 through
#              sdkmanager, then replaces the four binaries with those builds.
#
#              Every executable that arrives here is checked against a SHA-256 written into
#              this file, exactly as the editor's tarball is: Google's zip against the digest
#              Google publishes beside it, and the four rebuilt tools against digests taken by
#              downloading those exact files and hashing them. A hash that does not match is
#              deleted, not installed.
#
#              Then the one line that makes Gradle use them: the Android Gradle Plugin does
#              not take aapt2 from the SDK at all -- it downloads its own x86-64 copy from
#              Maven -- unless android.aapt2FromMavenOverride names a path. It is written into
#              ~/.gradle/gradle.properties, which is where AGP reads it from (local.properties
#              is ignored for this).
#
#              Two limits are permanent whatever is installed: apps containing C or C++ cannot
#              be built, because Google publishes no arm64 NDK; and the emulator cannot run,
#              because Google ships no linux-aarch64 build and even a self-built one needs
#              /dev/kvm, which SELinux denies to every app on an unrooted phone. The phone
#              itself is the test device instead -- and Settings has a row that hands an APK
#              built here to Android's own installer.
#
# Usage:
#   pocketide-tools.sh browser      install the browser layer
#   pocketide-tools.sh playwright   install the automation layer on top of it
#   pocketide-tools.sh android      install the complete Android build toolchain
#   pocketide-tools.sh tune         write Gradle settings sized to this phone
#   pocketide-tools.sh check        report what is present, machine-readably
#   pocketide-tools.sh smoke        prove the browser really loads a page and screenshots it

set -uo pipefail
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

HOME_DIR="/root"
TOOLS_DIR="$HOME_DIR/.pocketide-tools"
PROJECTS="$HOME_DIR/projects"

# The Android SDK, and the pins for every executable that goes into it.
SDK_DIR="$TOOLS_DIR/android/sdk"
BUILD_TOOLS="35.0.1"
PLATFORM="android-35"
# Google's command-line tools for Linux, as published on developer.android.com with this
# digest beside it. sdkmanager inside is Java and runs on arm64 as it is.
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"
CMDLINE_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
CMDLINE_BYTES=181833628
# The four build tools Google ships as x86-64 only, rebuilt for aarch64 from AOSP by the
# Commit451 android-arm-build-tools project (MIT). Digests taken by downloading these exact
# files and hashing them; they change only when BUILD_TOOLS does.
ARM_TOOLS_BASE="https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-${BUILD_TOOLS}"
ARM_SHA256_aapt2="c57d02d2986d7d68147d74525ad8a5fbb105f12723b33f1ce9eab12acd238cf0"
ARM_SHA256_aidl="9ae2dfac8ff49f34d493c2fd3d96f6153563f2ec0f07d18f6f661d0e8403d6a2"
ARM_SHA256_zipalign="d0688e26a0960b010c9702c4a4f6e53308d239a618b1dd0e5d0f4c5ad1c84fe6"
ARM_SHA256_split_select="172ecf6c4e93cc6f80a544f48b9a8fe770dd67f83b2f277504291cb4d1b82e3e"

say() { printf '%s\n' "$*"; }

# --------------------------------------------------------------------------- the browser

install_browser() {
  if command -v chromium >/dev/null 2>&1; then
    say "The browser is already installed."
    return 0
  fi

  say "Adding the arm64 Chromium source…"
  apt-get update -qq || true
  if ! apt-get install -y -qq --no-install-recommends \
       software-properties-common ca-certificates curl gnupg; then
    say "Could not install the tools needed to add a package source."
    return 1
  fi

  # xtradeb, not Canonical. Stated plainly because it is a third-party source and the owner is
  # entitled to know: Ubuntu's own Chromium is a snap, and a snap cannot run here.
  if ! add-apt-repository -y ppa:xtradeb/apps >/dev/null 2>&1; then
    say "The Chromium package source could not be added."
    return 1
  fi
  apt-get update -qq || true

  say "Installing Chromium… about 120 MB"
  # --no-install-recommends deliberately: it skips chromium-sandbox, which cannot work without
  # real root and only adds weight.
  if ! apt-get install -y -qq --no-install-recommends chromium; then
    say "Chromium could not be installed."
    return 1
  fi

  # The flags, once, where every launcher picks them up -- the app's own smoke test, an agent
  # calling chromium directly, Playwright, Puppeteer. /usr/bin/chromium sources this directory
  # and then execs with $CHROMIUM_FLAGS.
  mkdir -p /etc/chromium.d
  cat > /etc/chromium.d/00-pocketide <<'EOF'
# PRoot can give Chromium neither a SUID helper nor user namespaces, so its own sandbox has
# nothing to build on. Android's app sandbox is what contains this instead.
export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox --disable-dev-shm-usage --disable-gpu --no-zygote"
EOF

  mkdir -p "$TOOLS_DIR"
  say "The browser is installed. Try: pocketide-tools.sh smoke"
}

# The proof, not the promise. An install that reports success and then cannot load a page is
# worse than a failed install, because nobody finds out until an agent is half way through a task.
smoke_browser() {
  command -v chromium >/dev/null 2>&1 || { say "The browser is not installed."; return 1; }
  local shot="/tmp/pocketide-smoke.png"
  rm -f "$shot"
  say "Loading a page and taking a screenshot…"
  # Plain --headless. Never --headless=old, and --headless=new is redundant on current Chromium.
  chromium --headless --no-sandbox --disable-dev-shm-usage --hide-scrollbars \
      --window-size=1280,800 --screenshot="$shot" \
      "data:text/html,<h1>PocketIDE</h1>" >/dev/null 2>&1
  if [ -s "$shot" ]; then
    say "OK $(stat -c%s "$shot") bytes written"
    rm -f "$shot"
    return 0
  fi
  say "The browser did not produce a screenshot."
  return 1
}

# --------------------------------------------------------------------------- automation

install_playwright() {
  command -v chromium >/dev/null 2>&1 || { say "Install the browser first."; return 1; }
  command -v npm >/dev/null 2>&1 || {
    say "Installing Node…"
    apt-get install -y -qq nodejs npm || { say "Node could not be installed."; return 1; }
  }

  mkdir -p "$TOOLS_DIR" && cd "$TOOLS_DIR" || return 1
  [ -f package.json ] || npm init -y >/dev/null 2>&1

  say "Installing Playwright… about 60 MB, then its browser"
  if ! npm i -D playwright >/dev/null 2>&1; then
    say "Playwright could not be installed."
    return 1
  fi
  npx playwright install-deps chromium >/dev/null 2>&1 || true
  # Playwright's own arm64 build, which is version-matched to its API. The apt one installed
  # above stays as the fallback: it is a different Chromium version, and Playwright warns that
  # driving a browser it did not ship is at your own risk.
  if ! npx playwright install chromium >/dev/null 2>&1; then
    say "Playwright installed, but its own browser did not download."
    say "Scripts can still use executablePath: '/usr/bin/chromium'."
    return 0
  fi
  say "Playwright is installed, with video recording."
}

# --------------------------------------------------------------------------- Android builds

# Downloads to a file and refuses anything whose digest is not the one written above.
# Refused means deleted: leaving the file would make the next attempt trust a download this
# one already decided not to.
fetch_pinned() {
  local url="$1" target="$2" expected="$3" label="$4"
  say "Downloading $label…"
  if ! curl -fL --retry 4 --retry-delay 3 --retry-connrefused --continue-at - \
       -o "$target" "$url"; then
    rm -f "$target"
    if ! curl -fL --retry 4 --retry-delay 3 -o "$target" "$url"; then
      say "$label could not be downloaded."
      return 1
    fi
  fi
  local actual
  actual=$(sha256sum "$target" 2>/dev/null | cut -d' ' -f1) || true
  if [ "$actual" != "$expected" ]; then
    rm -f "$target"
    say "$label did not match its checksum and was discarded."
    return 1
  fi
  return 0
}

install_android() {
  say "Installing a JDK…"
  apt-get install -y -qq openjdk-21-jdk-headless unzip libstdc++6 zlib1g || {
    say "The JDK could not be installed."; return 1; }

  mkdir -p "$SDK_DIR"
  local manager="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"

  if [ ! -x "$manager" ]; then
    local archive="/tmp/commandlinetools-linux.zip"
    local have=0
    [ -f "$archive" ] && have=$(stat -c%s "$archive" 2>/dev/null || echo 0)
    if [ "$have" != "$CMDLINE_BYTES" ] || \
       [ "$(sha256sum "$archive" 2>/dev/null | cut -d' ' -f1)" != "$CMDLINE_SHA256" ]; then
      fetch_pinned "$CMDLINE_URL" "$archive" "$CMDLINE_SHA256" \
        "Google's Android command-line tools (182 MB)" || return 1
    fi
    say "Unpacking the command-line tools…"
    rm -rf "$SDK_DIR/cmdline-tools"
    mkdir -p "$SDK_DIR/cmdline-tools"
    if ! unzip -q -o "$archive" -d "$SDK_DIR/cmdline-tools.unpack"; then
      say "The command-line tools could not be unpacked."; return 1
    fi
    # The zip carries one folder called cmdline-tools; sdkmanager insists on finding itself
    # at <sdk>/cmdline-tools/latest, so that folder is what becomes "latest".
    mv "$SDK_DIR/cmdline-tools.unpack/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$SDK_DIR/cmdline-tools.unpack"
    rm -f "$archive"
  fi
  [ -x "$manager" ] || { say "sdkmanager is missing after unpacking."; return 1; }

  say "Accepting the SDK licences…"
  yes | "$manager" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
  say "Installing platform $PLATFORM and build-tools $BUILD_TOOLS from Google… (about 120 MB)"
  # Its exit status is read directly, not through a pipe: under pipefail a grep that filters
  # every progress line away exits 1 and would have reported a finished install as a failure.
  local log="/tmp/sdkmanager.log"
  "$manager" --sdk_root="$SDK_DIR" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" \
    >"$log" 2>&1
  local status=$?
  grep -v '^\[=' "$log" 2>/dev/null || true
  if [ "$status" -ne 0 ]; then
    say "sdkmanager did not finish. Nothing installed so far is lost; run this again."
    return 1
  fi
  local bt="$SDK_DIR/build-tools/$BUILD_TOOLS"
  [ -d "$bt" ] || { say "build-tools $BUILD_TOOLS did not install."; return 1; }

  say "Replacing the four x86-64 tools with aarch64 builds…"
  local tool hashvar expected
  for tool in aapt2 aidl zipalign split-select; do
    hashvar="ARM_SHA256_${tool//-/_}"
    expected="${!hashvar}"
    if [ -f "$bt/$tool" ] && \
       [ "$(sha256sum "$bt/$tool" 2>/dev/null | cut -d' ' -f1)" = "$expected" ]; then
      continue
    fi
    fetch_pinned "$ARM_TOOLS_BASE/$tool" "$bt/$tool.aarch64" "$expected" "$tool (aarch64)" \
      || return 1
    [ -f "$bt/$tool" ] && mv -f "$bt/$tool" "$bt/$tool.x86_64"
    chmod +x "$bt/$tool.aarch64"
    mv -f "$bt/$tool.aarch64" "$bt/$tool"
  done

  # Proved, not assumed: the one command that fails with Exec format error when the
  # replacement did not take.
  if ! "$bt/aapt2" version >/dev/null 2>&1; then
    say "aapt2 is installed but will not run on this phone. The build tools are not usable."
    return 1
  fi

  # Where the SDK is, for every shell the editor opens and every Gradle it runs.
  cat > /etc/profile.d/pocketide-android.sh <<EOF
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
export PATH="\$PATH:$SDK_DIR/cmdline-tools/latest/bin:$bt"
EOF
  grep -q 'pocketide-android.sh' "$HOME_DIR/.bashrc" 2>/dev/null || \
    printf '\n[ -f /etc/profile.d/pocketide-android.sh ] && . /etc/profile.d/pocketide-android.sh\n' \
      >> "$HOME_DIR/.bashrc"

  tune_gradle
  # The line Gradle needs whether or not tune_gradle wrote the file: AGP fetches its own
  # x86-64 aapt2 from Maven and ignores the SDK's unless told otherwise, and it reads this
  # from gradle.properties only.
  local properties="$HOME_DIR/.gradle/gradle.properties"
  mkdir -p "$(dirname "$properties")"
  if ! grep -q '^android.aapt2FromMavenOverride=' "$properties" 2>/dev/null; then
    printf 'android.aapt2FromMavenOverride=%s/aapt2\n' "$bt" >> "$properties"
  fi

  say ""
  say "The Android toolchain is installed: JDK 21, platform $PLATFORM, build-tools $BUILD_TOOLS,"
  say "with aapt2, aidl, zipalign and split-select as aarch64 builds. ANDROID_HOME is set"
  say "for every new terminal, and Gradle is pointed at this aapt2."
  say ""
  say "A Java or Kotlin Android project builds here with its own ./gradlew. Two limits"
  say "are permanent: apps containing C or C++ (Google publishes no arm64 NDK), and the"
  say "emulator (no linux-aarch64 build, and no /dev/kvm on a phone). The phone is the"
  say "test device: Settings → The computer → Install an app built here."
}

# --------------------------------------------------------------------------- build tuning
#
# Gradle out of the box is configured for a laptop with the machine to itself, and on a phone
# every one of those defaults is wrong in the same direction: it assumes memory it does not
# have and holds on to it after the build.
#
# What is set here, and why each one:
#
#   org.gradle.jvmargs          Sized from this phone's actual RAM, below. Gradle's own default
#                               is 512 MB, which is not enough to run R8 on a real app; the
#                               usual desktop advice of 4 GB is more than Android will let this
#                               process hold. Neither number is right here, so it is computed.
#
#   org.gradle.workers.max      One module compiled per worker, each wanting its own memory.
#                               Uncapped, Gradle uses every core, and eight parallel compiles
#                               is exactly the shape that trips Android's memory limiter.
#
#   org.gradle.daemon.idletimeout
#                               The daemon is kept, because throwing it away makes every build
#                               after the first pay the whole start-up again. What is changed
#                               is how long it sits idle holding that heap: three hours by
#                               default, ninety seconds here. An idle JVM holding 1.5 GB is
#                               the most likely reason a workspace is killed between builds.
#
#   org.gradle.vfs.watch=false  File-system watching needs inotify watches, and Android's
#                               per-uid limit is low enough that a large project exhausts it.
#                               When it does, Gradle does not fail -- it stalls.
#
#   kotlin.daemon.jvmargs       The Kotlin compiler runs in a SECOND JVM with its own heap, and
#                               its default is generous. Two unbounded JVMs is the other common
#                               way a build gets the app killed.
#
# It is written only when there is no file there already: a project owner who has tuned their
# own build has made a decision this script does not get to overrule.

tune_gradle() {
  local properties="$HOME_DIR/.gradle/gradle.properties"
  if [ -f "$properties" ]; then
    say "Gradle already has settings at ~/.gradle/gradle.properties; leaving them alone."
    return 0
  fi

  local total_kb heap workers kotlin_heap
  total_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
  if   [ "$total_kb" -ge 11500000 ]; then heap=3072; workers=4; kotlin_heap=1536
  elif [ "$total_kb" -ge 7500000  ]; then heap=2048; workers=3; kotlin_heap=1024
  elif [ "$total_kb" -ge 5500000  ]; then heap=1536; workers=2; kotlin_heap=768
  elif [ "$total_kb" -ge 3500000  ]; then heap=1024; workers=2; kotlin_heap=512
  else                                    heap=768;  workers=1; kotlin_heap=512
  fi

  mkdir -p "$(dirname "$properties")"
  cat > "$properties" <<EOF
# Written by PocketIDE, sized from this phone's own memory. Edit freely -- it is only
# written when the file does not already exist.
org.gradle.jvmargs=-Xmx${heap}m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.workers.max=${workers}
org.gradle.caching=true
org.gradle.daemon=true
org.gradle.daemon.idletimeout=90000
org.gradle.vfs.watch=false
kotlin.daemon.jvmargs=-Xmx${kotlin_heap}m
kotlin.incremental=true
EOF
  say "Gradle tuned for this phone: ${heap} MB build heap, ${workers} parallel worker(s)."
}

# --------------------------------------------------------------------------- what is present

check() {
  local browser=no playwright=no android=no android_sdk=no
  command -v chromium >/dev/null 2>&1 && browser=yes
  [ -d "$TOOLS_DIR/node_modules/playwright" ] && playwright=yes
  command -v javac >/dev/null 2>&1 && android=yes
  # Present AND runs: the x86-64 aapt2 Google ships is present and does not.
  [ -x "$SDK_DIR/build-tools/$BUILD_TOOLS/aapt2" ] && \
    "$SDK_DIR/build-tools/$BUILD_TOOLS/aapt2" version >/dev/null 2>&1 && android_sdk=yes
  echo "browser=$browser"
  echo "playwright=$playwright"
  echo "android=$android"
  echo "android_sdk=$android_sdk"
  if [ "$browser" = yes ]; then
    echo "chromium=$(chromium --version 2>/dev/null | head -1)"
  fi
  echo "cores=$(nproc 2>/dev/null || echo 0)"
  echo "mem_total_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
  echo "mem_available_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)"
  if [ -f "$HOME_DIR/.gradle/gradle.properties" ]; then
    echo "gradle_heap=$(grep -o 'Xmx[0-9]*m' "$HOME_DIR/.gradle/gradle.properties" \
      | head -1 | tr -d 'Xmx')"
  fi
}

case "${1:-check}" in
  browser)     install_browser ;;
  playwright)  install_playwright ;;
  android)     install_android ;;
  tune)        tune_gradle ;;
  smoke)       smoke_browser ;;
  check)       check ;;
  *)           say "Unknown command: ${1:-}"; exit 2 ;;
esac
