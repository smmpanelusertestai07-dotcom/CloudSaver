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
#   ANDROID    This layer installs a JDK and tunes Gradle. It does NOT install the Android
#              SDK, and it does not supply aarch64 rebuilds of Google's aapt2, aidl, zipalign
#              and split-select, which ship as x86-64 only and which a Gradle Android build
#              stops at. Those are steps the owner still has to take, and the screen says so
#              rather than implying the layer finishes the job -- an earlier version of this
#              header asserted that those four tools were already swapped for aarch64 builds,
#              while the function's own closing lines correctly said they were not. A claim
#              that contradicts itself inside one file is a claim nobody ever checked.
#
#              Two limits are permanent whatever is installed: apps containing C or C++ cannot
#              be built, because Google publishes no arm64 NDK; and the emulator cannot run,
#              because Google ships no linux-aarch64 build and even a self-built one needs
#              /dev/kvm, which SELinux denies to every app on an unrooted phone. The phone
#              itself is the test device instead.
#
# Usage:
#   pocketide-tools.sh browser      install the browser layer
#   pocketide-tools.sh playwright   install the automation layer on top of it
#   pocketide-tools.sh android      install the Java-only Android build toolchain
#   pocketide-tools.sh tune         write Gradle settings sized to this phone
#   pocketide-tools.sh check        report what is present, machine-readably
#   pocketide-tools.sh smoke        prove the browser really loads a page and screenshots it

set -uo pipefail
export DEBIAN_FRONTEND=noninteractive
export LC_ALL=C.UTF-8

HOME_DIR="/root"
TOOLS_DIR="$HOME_DIR/.pocketide-tools"
PROJECTS="$HOME_DIR/projects"

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

install_android() {
  say "Installing a JDK…"
  apt-get install -y -qq openjdk-21-jdk-headless unzip || {
    say "The JDK could not be installed."; return 1; }

  mkdir -p "$TOOLS_DIR/android"
  tune_gradle
  say ""
  say "A JDK is installed, and Gradle comes from each project's own gradlew wrapper,"
  say "which is how an Android project is meant to be built — nothing to install."
  say ""
  say "This is NOT a finished Android build setup, and the two missing pieces are"
  say "not small:"
  say "  • The Android SDK itself. Install Google's command-line tools and use"
  say "    sdkmanager for a platform and build-tools, then point ANDROID_HOME at it."
  say "  • aarch64 builds of aapt2, aidl, zipalign and split-select. Google ships"
  say "    those four as x86-64 only, so a Gradle build stops on the first one with"
  say "    an Exec format error until they are replaced."
  say ""
  say "What cannot be done on this phone, and cannot be fixed by installing anything:"
  say "  • Apps with C or C++ in them. Google publishes no arm64 NDK."
  say "  • The Android emulator. Google ships no linux-aarch64 build, and even a"
  say "    self-built one needs /dev/kvm, which Android denies to every app on an"
  say "    unrooted phone."
  say ""
  say "What works instead:"
  say "  • JVM and Robolectric unit tests run here natively."
  say "  • The phone itself is the test device: build the APK, then install it."
  say ""
  say "Google's own aapt2, aidl, zipalign and split-select are x86-64 only and need"
  say "aarch64 replacements before a Gradle build will finish."
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
  local browser=no playwright=no android=no
  command -v chromium >/dev/null 2>&1 && browser=yes
  [ -d "$TOOLS_DIR/node_modules/playwright" ] && playwright=yes
  command -v javac >/dev/null 2>&1 && android=yes
  echo "browser=$browser"
  echo "playwright=$playwright"
  echo "android=$android"
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
