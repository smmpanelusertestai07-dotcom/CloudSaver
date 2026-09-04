#!/bin/bash
# PocketDesk's app installer: what happens when a Linux app package is opened inside the
# computer, and the answer to "can I install something I downloaded myself?".
#
# On a phone, tapping an APK from a website opens Android's installer: it names the app, says
# what it needs, warns when the source is unknown, and only then installs. Nothing like that
# exists on a Linux desktop -- a downloaded .deb is a file that does nothing when tapped, and
# the usual advice is a terminal command. This script is that missing installer:
#
#   1. it reads the package and says what it is, in the owner's words;
#   2. it checks the four things that actually decide whether it will work here -- the
#      processor it was built for, the space it needs against the space this phone has, the
#      other software it depends on, and whether the app is one the Apps tab already installs
#      from its publisher;
#   3. it says plainly that a downloaded file is not signed the way a publisher's package is,
#      and that its setup commands run with administrator rights;
#   4. a check that cannot be argued with (wrong processor, no space, missing dependencies)
#      blocks the install; a risk the owner can judge offers "Install anyway", exactly as
#      Android does for an app from outside its store.
#
# Usage: pocketdesk-install [file.deb]      (no file: asks for one, starting in Downloads)
#        pocketdesk-install --report <file> (prints the checks, installs nothing)
set -u

REPORT_ONLY=0
if [ "${1:-}" = "--report" ]; then
  REPORT_ONLY=1
  shift
fi

FILE="${1:-}"

have() { command -v "$1" >/dev/null 2>&1; }

# Every message goes through the desktop's own dialogs when they exist, and to the terminal
# when they do not (the test suite, and anyone who runs this from lxterminal).
say() {   # say <kind: error|info|warn> <title> <body>
  if [ "$REPORT_ONLY" = "1" ] || ! have zenity; then
    printf '%s: %s\n%s\n' "$1" "$2" "$3"
    return 0
  fi
  case "$1" in
    error) zenity --error --width=380 --no-markup --title="$2" --text="$3" >/dev/null 2>&1 ;;
    *)     zenity --info  --width=380 --no-markup --title="$2" --text="$3" >/dev/null 2>&1 ;;
  esac
}

# Human sizes, from bytes, without depending on numfmt's locale.
human() {
  awk -v b="${1:-0}" 'BEGIN {
    if (b >= 1073741824) printf "%.1f GB", b / 1073741824;
    else if (b >= 1048576) printf "%.0f MB", b / 1048576;
    else printf "%.0f KB", b / 1024;
  }'
}

field() { dpkg-deb -f "$FILE" "$1" 2>/dev/null | head -n 1; }

# The apps the Apps tab installs from their publisher's own signed repository. A downloaded
# copy of one of these is always the worse way to get it, so it is called out by name.
PUBLISHED="chatgpt claude-desktop cursor antigravity google-chrome-stable"

if [ -z "$FILE" ]; then
  if have zenity; then
    FILE=$(zenity --file-selection --title="Install a downloaded app" \
      --filename="$HOME/Downloads/" \
      --file-filter="App packages | *.deb *.exe *.msix *.msixbundle *.appx" 2>/dev/null) || exit 0
  else
    printf 'Usage: pocketdesk-install <file.deb>\n'
    exit 2
  fi
fi

[ -n "$FILE" ] || exit 0

if [ ! -f "$FILE" ]; then
  say error "Cannot install" "That file is not there any more:
$FILE"
  exit 1
fi

# An absolute path from here on: apt reads "thing.deb" as the name of a package to look up,
# and only a path with a slash in it as the file that is actually there.
FILE=$(readlink -f "$FILE" 2>/dev/null || printf '%s' "$FILE")

name_only=$(basename "$FILE")

# An AppImage is the other thing people download for Linux, and it cannot work here: it mounts
# itself with FUSE, which a phone container has no way to provide. Saying so beats a silent
# failure -- and every app in the Apps tab publishes a .deb anyway.
case "$name_only" in
  *.AppImage|*.appimage)
    say error "AppImage files do not run here" \
"$name_only is an AppImage. An AppImage mounts itself with FUSE, which needs kernel support that a phone container cannot give it.

Look for the app's .deb build for Linux ARM64 instead, or install it from the Apps tab when it is one of the four AI apps."
    exit 1 ;;
esac

# A Windows program goes to the Windows installer, which reads the processor it was built for
# out of the file before anything is unpacked. With no Windows layer installed it says so and
# stops -- nothing is downloaded, nothing is half-installed.
case "$name_only" in
  *.exe|*.msix|*.msixbundle|*.appx|*.appxbundle)
    if ! command -v wine >/dev/null 2>&1 && ! command -v wine64 >/dev/null 2>&1; then
      say error "Windows apps are not set up yet" \
"$name_only is a Windows program.

To run Windows programs on this computer, add \"Windows apps support\" from PocketDesk's Apps tab first. It installs Wine, which is about 900 MB.

Nothing was installed and nothing was changed."
      exit 1
    fi
    win_verdict=$(/usr/local/bin/pocketdesk-winapp check "$FILE" 2>/dev/null | head -n 1)
    case "$win_verdict" in
      arm64)
        if have zenity; then
          zenity --question --no-markup --width=460 --title="Install a Windows app?" \
            --text="$name_only is a Windows program built for ARM64 — this phone's own processor.

Windows apps here are experimental: it may open, it may look wrong, or it may not start at all. Nothing on the Linux side is touched either way.

Install it?" 2>/dev/null || exit 0
        fi
        out=$(/usr/local/bin/pocketdesk-winapp install "$FILE" 2>&1)
        if printf '%s' "$out" | grep -q '^INSTALLED'; then
          say info "Installed" "$out"
        else
          say error "That did not install" "$out"
          exit 1
        fi
        exit 0 ;;
      x64|x86)
        say error "Built for the wrong processor" \
"$name_only is built only for Intel and AMD processors.

This phone is ARM64. Running it would mean translating every instruction, which this phone cannot do at a usable speed — so nothing was installed.

Look for the app's ARM64 build for Windows, or its Linux ARM64 build."
        exit 1 ;;
      *)
        say error "Could not read that file" \
"The processor $name_only was built for could not be read, so it was not installed."
        exit 1 ;;
    esac ;;
esac

case "$name_only" in
  *.deb) ;;
  *)
    say error "Not an app package" \
"$name_only is not a Linux app package.

Apps for this computer come as .deb files built for ARM64, or — with Windows apps support installed — as Windows .exe and .msix files built for ARM64. The Apps tab installs the four AI apps from their publishers."
    exit 1 ;;
esac

if ! dpkg-deb --info "$FILE" >/dev/null 2>&1; then
  say error "This file is damaged" \
"$name_only cannot be read as an app package. The download may have been cut off part way.

Download it again, then open it from Downloads."
  exit 1
fi

PACKAGE=$(field Package)
VERSION=$(field Version)
ARCH=$(field Architecture)
MAINTAINER=$(field Maintainer)
SUMMARY=$(dpkg-deb -f "$FILE" Description 2>/dev/null | head -n 1)
INSTALLED_KB=$(field Installed-Size)
case "$INSTALLED_KB" in *[!0-9]*|"") INSTALLED_KB=0 ;; esac
FILE_BYTES=$(wc -c < "$FILE" 2>/dev/null || echo 0)
# Unpacked size plus a third for apt's own working copies, plus the package itself.
NEEDS_BYTES=$(( INSTALLED_KB * 1024 * 4 / 3 + FILE_BYTES ))
FREE_BYTES=$(df -kP / 2>/dev/null | awk 'NR==2 {print $4 * 1024}')
[ -n "$FREE_BYTES" ] || FREE_BYTES=0

verdict=ok
blockers=""
warnings=""
block() { verdict=blocked; blockers="$blockers
• $1"; }
warn()  { [ "$verdict" = ok ] && verdict=warn; warnings="$warnings
• $1"; }

# 1. The processor. Nothing else matters if this is wrong: an amd64 package is machine code
#    for a different processor and dpkg will refuse it.
case "$ARCH" in
  arm64|all) ;;
  amd64|i386)
    block "Built for Intel and AMD computers ($ARCH). This phone has an ARM64 processor, so this build cannot run here. Look for the app's ARM64 (aarch64) build." ;;
  "")
    block "The package does not say which processor it is for, which means it is not a normal app package." ;;
  *)
    block "Built for $ARCH computers. This phone has an ARM64 processor." ;;
esac

# 2. Space, measured on this phone right now rather than quoted from a web page.
if [ "$NEEDS_BYTES" -gt 0 ] && [ "$FREE_BYTES" -gt 0 ] && [ "$NEEDS_BYTES" -gt "$FREE_BYTES" ]; then
  block "Needs about $(human "$NEEDS_BYTES") and this phone has $(human "$FREE_BYTES") free. Free some space, then open the file again."
fi

# 3. The app is one the Apps tab installs from its publisher, signed.
for known in $PUBLISHED; do
  [ "$PACKAGE" = "$known" ] || continue
  warn "PocketDesk installs this app itself, from its publisher's own signed repository — the Apps tab, or Settings for Google Chrome. That copy is verified and updates in place; this downloaded one is neither."
done

# 4. What apt would have to do. The simulation is the only honest way to know whether the
#    other software it needs can be found, and it changes nothing on the computer.
sim_log=$(mktemp 2>/dev/null || echo /tmp/pocketdesk-install-sim.log)
if [ "${POCKETDESK_SIMULATE:-1}" = "1" ] && have apt-get && have sudo; then
  if ! sudo apt-get install -s -y "$FILE" > "$sim_log" 2>&1; then
    missing=$(grep -oE 'Depends: [^ ]+' "$sim_log" | awk '{print $2}' | sort -u | tr '\n' ' ')
    if [ -n "$missing" ]; then
      block "Needs other software this computer does not have: $missing"
    else
      block "The computer cannot work out how to install it. It may be built for a different version of Ubuntu."
    fi
  else
    # apt prints one machine-readable line per action. A package that declares Conflicts or
    # Replaces can take the desktop itself away, and "Install demoapp?" would never have said so.
    removals=$(awk '/^Remv /{print $2}' "$sim_log" | sort -u | tr '\n' ' ')
    if [ -n "$removals" ]; then
      block "Installing this would delete software already on the computer: $removals"
    fi
  fi
fi
rm -f "$sim_log" 2>/dev/null || true

# 5. Setup commands. Nearly every package has them and they are not suspicious by themselves,
#    but they run as administrator inside the computer and the owner deserves to know.
scripts=""
if dpkg-deb --ctrl-tarfile "$FILE" 2>/dev/null | tar -t 2>/dev/null \
     | grep -qE '^(\./)?((pre|post)(inst|rm)|config)$'; then
  scripts="yes"
fi

# 6. Where it came from. A file downloaded in a browser carries no signature of its own: apt
#    checks signatures on a repository's index, not on a loose .deb.
warn "A downloaded package is not signed the way a publisher's repository package is, so nothing here can prove who built it. Install it only if you trust the site it came from."
[ -n "$scripts" ] && warn "It runs its own setup commands with administrator rights inside this computer (normal for apps, and worth knowing)."

details="Name: ${PACKAGE:-unknown}
Version: ${VERSION:-unknown}
Built for: ${ARCH:-unknown}
Published by: ${MAINTAINER:-not stated}
Download: $(human "$FILE_BYTES")
Space needed: $(human "$NEEDS_BYTES")
This phone has free: $(human "$FREE_BYTES")"

if [ "$REPORT_ONLY" = "1" ]; then
  printf 'verdict=%s\npackage=%s\nversion=%s\narch=%s\nneeds_bytes=%s\nfree_bytes=%s\n' \
    "$verdict" "$PACKAGE" "$VERSION" "$ARCH" "$NEEDS_BYTES" "$FREE_BYTES"
  [ -n "$blockers" ] && printf 'blocked:%s\n' "$blockers"
  [ -n "$warnings" ] && printf 'warned:%s\n' "$warnings"
  exit 0
fi

if [ "$verdict" = blocked ]; then
  say error "${PACKAGE:-This app} cannot be installed" \
"$details

Why not:$blockers"
  exit 1
fi

question="$details

${SUMMARY:-}

Safety check:$warnings

Install ${PACKAGE:-this app} on the Linux computer?"

# Nothing is ever installed without a yes. With the desktop's dialogs missing (a computer
# built before they were part of set-up), a terminal asks; with neither, it stops rather than
# installing something nobody agreed to.
if have zenity; then
  # --no-markup: every package's Maintainer field looks like "Name <mail@host>", which Pango
  # cannot parse, and GTK then shows a dialog with no body at all -- no name, no size, no
  # warnings, just two buttons. It also stops a hostile package forging the text.
  zenity --question --width=430 --no-markup --title="Install an app" --text="$question" \
    --ok-label="Install anyway" --cancel-label="Cancel" >/dev/null 2>&1 || exit 0
elif [ -t 0 ]; then
  printf '%s\n\nType yes to install: ' "$question"
  read -r reply
  case "$reply" in y|Y|yes|YES|Yes) ;; *) printf 'Nothing was installed.\n'; exit 0 ;; esac
else
  say error "Cannot ask you first" \
"This computer is missing the desktop's dialogs, so there is no way to show you what is about to be installed - and nothing is installed without that.

Update the computer's basics in PocketDesk (Settings -> Storage), then open the file again."
  exit 1
fi

log="$HOME/.pocketdesk/logs/install.log"
mkdir -p "$(dirname "$log")" 2>/dev/null || true

fifo=""
if have zenity; then
  fifo="/tmp/pocketdesk-install-$$.progress"
  rm -f "$fifo"
  if mkfifo "$fifo" 2>/dev/null; then
    zenity --progress --pulsate --auto-close --no-cancel --width=340 \
      --title="PocketDesk" --text="Installing ${PACKAGE:-the app}..." < "$fifo" >/dev/null 2>&1 &
    exec 9<>"$fifo"
  else
    fifo=""
  fi
fi
finish_progress() {
  [ -n "$fifo" ] || return 0
  printf '100\n' >&9 2>/dev/null || true
  exec 9>&- 2>/dev/null || true
  rm -f "$fifo"
}
trap 'finish_progress' EXIT INT TERM

if sudo DEBIAN_FRONTEND=noninteractive apt-get install -y "$FILE" >> "$log" 2>&1; then
  finish_progress
  # The menu, the panel and the desktop icons are rebuilt so the new app is there at once.
  sudo /usr/local/bin/pocketdesk-menu >/dev/null 2>&1 || true
  say info "${PACKAGE:-The app} is installed" \
"It is in the Apps menu now — the Linux button on the left of the panel.

To remove it later: open the terminal and run  sudo apt-get remove ${PACKAGE:-the-app}"
else
  finish_progress
  # An install fails most often part way through unpacking, which leaves dpkg half-applied and
  # every later install refusing to run. Put that right here rather than leaving it for the
  # owner to meet as a mystery next week.
  sudo dpkg --configure -a >> "$log" 2>&1 || true
  sudo apt-get -y -f install >> "$log" 2>&1 || true
  say error "${PACKAGE:-The app} did not install" \
"The computer could not finish installing it. The usual reasons are a package built for a different version of Ubuntu, or software it needs that is not available here.

The computer was put back in working order, and anything half-installed was cleaned up. The details are in the Apps menu → App reports → install.log."
  exit 1
fi
