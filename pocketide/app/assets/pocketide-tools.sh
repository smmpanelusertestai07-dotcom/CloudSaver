#!/bin/bash
# What the agents can actually use: a real browser, screenshots, video, and a build toolchain.
#
# None of this is installed during set-up. Set-up is already a 410 MB download and twenty
# minutes; this is up to another 530 MB that most people will not need on the first day, so it
# is a switch in Settings that installs on demand and says what it will cost before it starts.
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
#   PHONE      And the phone can be driven, not only handed a file. Android 11 added Wireless
#              debugging -- adb over TCP with a pairing step -- and adbd listens on loopback
#              too, so the app's own adb can pair with and connect to the phone it is running
#              on. That adb ships inside the APK and runs in a root of its own, outside this
#              Linux, with the paired key and the server; what this Linux gets is the phone
#              command, a door with a short list on it (PhoneBroker.java). See "the phone
#              itself" below.
#
#   LISTS      Set-up and the nightly update delete /var/lib/apt/lists to save 60 MB, so every
#              apt-get install here refreshes the list first. Without that, a fresh workspace
#              answers "Unable to locate package" for a package that exists -- which is how the
#              JDK step failed on every phone until a review caught it.
#
# Usage:
#   pocketide-tools.sh browser      install the browser layer
#   pocketide-tools.sh playwright   install the automation layer on top of it
#   pocketide-tools.sh android      install the complete Android build toolchain
#   pocketide-tools.sh phone        the phone command, and the adb shim where Gradle looks
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

# Ubuntu's package list, before anything is installed from it. The lists are deleted after
# set-up and after every update (60 MB, stale within a day), so an install that does not
# refresh first finds no package at all. A refresh that fails is said, and the install is
# still tried: a package that is already present needs no list.
refresh_packages() {
  say "Refreshing Ubuntu's package list…"
  if ! apt-get update -qq; then
    say "The package list could not be refreshed. Trying with what is already here."
  fi
}

# --------------------------------------------------------------------------- the browser

install_browser() {
  if command -v chromium >/dev/null 2>&1; then
    say "The browser is already installed."
    return 0
  fi

  say "Adding the arm64 Chromium source…"
  refresh_packages
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
    refresh_packages
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
  refresh_packages
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
  say "Installing platform $PLATFORM, build-tools $BUILD_TOOLS and platform-tools from Google…"
  say "(about 130 MB)"
  # Its exit status is read directly, not through a pipe: under pipefail a grep that filters
  # every progress line away exits 1 and would have reported a finished install as a failure.
  local log="/tmp/sdkmanager.log"
  "$manager" --sdk_root="$SDK_DIR" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" \
    "platform-tools" >"$log" 2>&1
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
  # Google's platform-tools carry an x86-64 adb, for the same reason. The shim that names the
  # phone command takes its place, at the one path the Android Gradle Plugin looks for adb.
  link_adb
  fix_build_tools

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
  # from gradle.properties only. Appended, never written over anything, and only when absent.
  local properties="$HOME_DIR/.gradle/gradle.properties"
  local expected_line="android.aapt2FromMavenOverride=$bt/aapt2"
  mkdir -p "$(dirname "$properties")"
  if ! grep -qxF "$expected_line" "$properties" 2>/dev/null; then
    # The exact line, not any line: one this script wrote for an earlier build-tools version
    # names an aapt2 that may be gone, and is replaced. One the owner wrote pointing somewhere
    # else is theirs, left alone and said.
    if grep -q '^android.aapt2FromMavenOverride=.*pocketide-tools' "$properties" 2>/dev/null; then
      sed -i '/^android.aapt2FromMavenOverride=.*pocketide-tools/d' "$properties"
    fi
    if grep -q '^android.aapt2FromMavenOverride=' "$properties" 2>/dev/null; then
      say "~/.gradle/gradle.properties already points android.aapt2FromMavenOverride elsewhere;"
      say "it is left as you wrote it. Point it at $bt/aapt2 to use the aapt2 installed here."
    else
      # On a line of its own even when the owner's file does not end with one. Appended
      # straight onto a last line with no newline, the property would become the tail of that
      # line and Gradle would see neither.
      if [ -s "$properties" ] && [ "$(tail -c1 "$properties" | wc -l)" -eq 0 ]; then
        printf '\n' >> "$properties"
      fi
      printf '%s\n' "$expected_line" >> "$properties"
      say "Added android.aapt2FromMavenOverride to ~/.gradle/gradle.properties: the one line"
      say "Gradle needs to use this aapt2. Nothing else in that file was changed."
    fi
  fi

  say ""
  say "The Android toolchain is installed: JDK 21, platform $PLATFORM, build-tools $BUILD_TOOLS,"
  say "with aapt2, aidl, zipalign and split-select as aarch64 builds. ANDROID_HOME is set"
  say "for every new terminal, and Gradle is pointed at this aapt2."
  say ""
  say "A Java or Kotlin Android project builds here with its own ./gradlew. Two limits"
  say "are permanent: apps containing C or C++ (Google publishes no arm64 NDK), and the"
  say "emulator (no linux-aarch64 build, and no /dev/kvm on a phone). The phone is the"
  say "test device: Settings → The computer → Install an app built here, or Test on this"
  say "phone to pair it with itself so that adb, and an agent, can drive it."
  say ""
  say "One line worth adding to a project's build file: buildToolsVersion \"$BUILD_TOOLS\"."
  say "Without it the Android Gradle Plugin installs its own build-tools version, x86-64"
  say "and all; the four tools in any such directory are repaired here at every check,"
  say "but naming this one saves the download."
}

# --------------------------------------------------------------------------- the phone itself
#
# The test device is the phone this is running on. What this Linux gets for it is ONE command,
# phone: a client for the app's bridge (PhoneBroker.java), which installs an APK built under
# ~/projects, opens it, stops it, clears it, uninstalls it, runs its instrumented tests, reads
# its own log, and -- only while it is on the screen -- screenshots and drives it. Nothing else
# adb could do is reachable from here, by design.
#
# There is no adb in this Linux at all, and there must not be one that the app trusts: a
# program that runs from a rootfs an agent can write to is a program the agent can replace.
# The app carries its own adb (Ubuntu's arm64 build, inside the APK) and runs it in a root of
# its own, outside this Linux, with the paired key bound in there and nowhere else. 2.1.x and
# the 2.2.0 preview installed adb in here and pointed it at the app's socket; that socket is
# no longer reachable from here, so the package is harmless, and the profile line that named
# it is removed below.
#
# The Android Gradle Plugin looks for adb at exactly <sdk>/platform-tools/adb, and Google's
# platform-tools carry an x86-64 one that cannot run here. What sits at that path instead is a
# shim that says what to run: phone install does what installDebug would, phone instrument
# what connectedAndroidTest would. The same shim answers a bare "adb" in the terminal.

install_phone_command() {
  mkdir -p /usr/local/bin
  if [ -f /opt/pocketide/pocketide-phone.py ]; then
    install -m 0755 /opt/pocketide/pocketide-phone.py /usr/local/bin/phone
  fi
  # The shim, wherever adb would be looked for.
  cat > /usr/local/bin/adb <<'SHIM'
#!/bin/sh
echo "adb does not run inside this Linux: the phone's key never enters it." >&2
echo "Use the phone command instead: phone install app.apk, phone launch com.example.app," >&2
echo "phone log, phone screenshot, phone instrument. phone help lists everything." >&2
exit 1
SHIM
  chmod +x /usr/local/bin/adb
  # What 2.1.x wrote: a socket path this Linux can no longer reach. Removed, not left to
  # confuse the next shell.
  rm -f /etc/profile.d/pocketide-adb.sh
  if [ -f "$HOME_DIR/.bashrc" ] && grep -q 'pocketide-adb.sh' "$HOME_DIR/.bashrc" 2>/dev/null; then
    grep -v 'pocketide-adb.sh' "$HOME_DIR/.bashrc" > "$HOME_DIR/.bashrc.tmp" && \
      mv -f "$HOME_DIR/.bashrc.tmp" "$HOME_DIR/.bashrc"
  fi
}

# True when a binary is built for this processor: the ELF e_machine field, one byte at offset
# 18 (little-endian), is 0xB7 for AArch64 and 0x3E for x86-64. Read rather than run, because
# under PRoot a foreign binary does not fail in the kernel with bash's 126: PRoot's own loader
# takes the exec, resolves the x86-64 interpreter it names, and fails with whatever status
# that gives, which an exit-code test mistook for "it runs".
runs() {
  [ "$(od -An -tx1 -j18 -N1 "$1" 2>/dev/null | tr -d ' \n')" = "b7" ]
}

# The same four tools in EVERY build-tools directory, not only the one this script installs.
# The Android Gradle Plugin insists on its own build-tools version when a project names none
# (35.0.0 for AGP 8.13, 36.0.0 for AGP 9) and installs it itself, x86-64 and all. aapt2 comes
# from the override either way, but aidl is run out of that directory and stops with Exec
# format error on the first .aidl file, with everything still reporting "installed". So every
# build-tools directory is walked and any of the four that will not run is replaced by a copy
# of the verified aarch64 build. Run at install and from check, which is how a directory AGP
# added during last night's build is repaired before the next one.
fix_build_tools() {
  local bt="$SDK_DIR/build-tools/$BUILD_TOOLS" dir tool
  [ -d "$SDK_DIR/build-tools" ] || return 0
  for dir in "$SDK_DIR"/build-tools/*/; do
    dir="${dir%/}"
    [ -d "$dir" ] || continue
    [ "$dir" = "$bt" ] && continue
    for tool in aapt2 aidl zipalign split-select; do
      [ -f "$dir/$tool" ] || continue
      runs "$dir/$tool" && continue
      [ -f "$bt/$tool" ] && runs "$bt/$tool" || continue
      [ -f "$dir/$tool.x86_64" ] || mv -f "$dir/$tool" "$dir/$tool.x86_64"
      cp -f "$bt/$tool" "$dir/$tool" && chmod +x "$dir/$tool"
    done
  done
}

# The shim at the one path the Android Gradle Plugin looks for adb. Google's x86-64 copy, if
# there is one, is set aside rather than deleted; a link 2.1.x made to Ubuntu's adb is
# replaced, because that adb has nothing to talk to any more.
link_adb() {
  local pt="$SDK_DIR/platform-tools"
  [ -d "$pt" ] || return 0
  [ -x /usr/local/bin/adb ] || install_phone_command
  if [ -L "$pt/adb" ] && [ "$(readlink "$pt/adb")" = "/usr/local/bin/adb" ]; then
    return 0
  fi
  if [ -e "$pt/adb" ] && ! [ -L "$pt/adb" ]; then
    mv -f "$pt/adb" "$pt/adb.x86_64"
  fi
  ln -sf /usr/local/bin/adb "$pt/adb"
}

install_phone() {
  install_phone_command
  link_adb
  say ""
  say "This Linux gets the phone command, a door to the phone with a short list on it:"
  say "  phone install app.apk · launch · stop · clear · uninstall · instrument · log"
  say "  phone screenshot / record / tap / text / key, only while that app is on the screen"
  say "phone help lists everything. No shell on the phone, no other app, no device details."
  say ""
  say "install and launch need no pairing: Android asks you to confirm each install."
  say "The rest needs the phone paired with itself over Wireless debugging (Android 11 and"
  say "newer), from Settings → The computer → Test on this phone in PocketIDE. The app's own"
  say "adb, the key and the adb server stay in the app's storage, in a root of their own,"
  say "never in here."
  say "Gradle's installDebug and connectedAndroidTest expect adb's network port, which the"
  say "app never opens; phone install and phone instrument do the same work."
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
# own build has made a decision this script does not get to overrule. The one exception is
# the aapt2 line install_android appends -- one line, added only when absent, over nothing.

tune_gradle() {
  local properties="$HOME_DIR/.gradle/gradle.properties"
  if [ -f "$properties" ]; then
    say "Gradle already has settings at ~/.gradle/gradle.properties; they are kept as they are."
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
  # Two repairs first, both idempotent and both silent: Gradle can re-install platform-tools
  # over the adb link or add a build-tools directory of its own overnight, and a check that
  # only reported it would leave the next build to find out.
  link_adb
  fix_build_tools
  # Present AND runs AND Gradle is told to use THIS one: the x86-64 aapt2 Google ships is
  # present and does not run, a runnable one Gradle is not pointed at is never used, and a
  # line naming some other aapt2 is not this one.
  [ -x "$SDK_DIR/build-tools/$BUILD_TOOLS/aapt2" ] && \
    "$SDK_DIR/build-tools/$BUILD_TOOLS/aapt2" version >/dev/null 2>&1 && \
    grep -qxF "android.aapt2FromMavenOverride=$SDK_DIR/build-tools/$BUILD_TOOLS/aapt2" \
      "$HOME_DIR/.gradle/gradle.properties" 2>/dev/null && android_sdk=yes
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
  phone)       install_phone ;;
  tune)        tune_gradle ;;
  smoke)       smoke_browser ;;
  check)       check ;;
  *)           say "Unknown command: ${1:-}"; exit 2 ;;
esac
