#!/bin/bash
# PocketLinux's app installer: what happens when a Linux app package is opened inside the
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
#      Android does for an app from outside its store;
#   5. and it removes an app again, which is the other half of the Android moment: opening the
#      file of an app that is already there offers Remove beside Install.
#
# Usage: pocketlinux-install [file.deb]      (no file: asks for one, starting in Downloads)
#        pocketlinux-install --report <file> (prints the checks, installs nothing)
#        pocketlinux-install --remove <name> (removes an installed app by its package name)
set -u

REPORT_ONLY=0
REMOVE_MODE=0
REMOVE_ONLY=""
case "${1:-}" in
  --report) REPORT_ONLY=1; shift ;;
  --remove) REMOVE_MODE=1; REMOVE_ONLY="${2:-}"; shift; [ "$#" -gt 0 ] && shift ;;
esac

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

# The packages the computer itself is made of: the X server, the window manager, the panel, the
# file manager, the terminal, the message bus and the dialogs this script talks through. apt is
# asked to simulate every removal first, and a removal that would take any of these with it is
# refused outright. An owner who taps Remove on an app must never be left with a computer that
# has no window manager to show them the result.
CORE_PACKAGES="tigervnc-standalone-server tigervnc-common openbox tint2 pcmanfm lxterminal
dbus-x11 dbus-system-bus-common x11-xserver-utils x11-utils xdotool wmctrl zenity
desktop-file-utils librsvg2-common pulseaudio pulseaudio-utils xdg-utils
apt apt-utils dpkg sudo ca-certificates curl gnupg"

# A pulsing window while apt works, in one place because installing and removing both need it.
# apt with nothing on screen looks like an app that has hung, and the owner taps the file again.
progress_fifo=""
progress_start() {   # progress_start <what the window says>
  have zenity || return 0
  progress_fifo="/tmp/pocketlinux-install-$$.progress"
  rm -f "$progress_fifo"
  if mkfifo "$progress_fifo" 2>/dev/null; then
    zenity --progress --pulsate --auto-close --no-cancel --width=340 \
      --title="PocketLinux" --text="$1" < "$progress_fifo" >/dev/null 2>&1 &
    exec 9<>"$progress_fifo"
  else
    progress_fifo=""
  fi
}
progress_stop() {
  [ -n "$progress_fifo" ] || return 0
  printf '100\n' >&9 2>/dev/null || true
  exec 9>&- 2>/dev/null || true
  rm -f "$progress_fifo"
  progress_fifo=""
}
trap 'progress_stop' EXIT INT TERM

# Removing an app, which is the half that used to be a typed command in a terminal.
remove_package() {   # remove_package <package name>
  pkg="$1"
  case "$pkg" in
    ""|-*)
      say error "Cannot remove" "No app was named, so nothing was changed."
      return 1 ;;
  esac
  if ! have apt-get || ! have sudo || ! have dpkg-query; then
    say error "Cannot remove apps here" \
"This computer is missing the tools that add and remove software.

Update the computer's basics in PocketLinux (Settings -> Storage), then try again."
    return 1
  fi
  case "$(dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null || true)" in
    *"ok installed"*) ;;
    *)
      say info "$pkg is not on this computer" "There is nothing to remove."
      return 0 ;;
  esac

  # --auto-remove in the simulation and in the real run, the same flags in both, so the list
  # shown to the owner is the list that happens. Simulating without it and then removing with
  # it would take away packages nobody was shown.
  rm_log=$(mktemp 2>/dev/null || echo /tmp/pocketlinux-remove-sim.log)
  if ! sudo apt-get remove -s -y --auto-remove "$pkg" > "$rm_log" 2>&1; then
    rm -f "$rm_log" 2>/dev/null || true
    say error "$pkg cannot be removed" \
"The computer could not work out how to remove it without breaking something else. Nothing was changed."
    return 1
  fi
  going=$(awk '/^Remv /{print $2}' "$rm_log" | sort -u)
  rm -f "$rm_log" 2>/dev/null || true
  if [ -z "$going" ]; then
    # dpkg says it is installed and apt says removing it takes nothing away. The two disagree,
    # so this stops rather than running a removal nobody could be shown first.
    say error "$pkg cannot be removed" \
"The computer could not say what removing $pkg would take away, so nothing was changed."
    return 1
  fi

  protected=""
  for one in $going; do
    case "$one" in pocketlinux*) protected="$protected $one"; continue ;; esac
    for keep in $CORE_PACKAGES; do
      [ "$one" = "$keep" ] && protected="$protected $one"
    done
  done
  if [ -n "$protected" ]; then
    say error "Removing $pkg would break the computer" \
"Removing $pkg would also remove:$protected

Those are parts of the Linux computer itself, and without them the desktop would not start again. Nothing was removed."
    return 1
  fi

  freed_kb=0
  for one in $going; do
    size=$(dpkg-query -W -f='${Installed-Size}' "$one" 2>/dev/null || echo 0)
    case "$size" in *[!0-9]*|"") size=0 ;; esac
    freed_kb=$(( freed_kb + size ))
  done
  freed=$(human $(( freed_kb * 1024 )))
  going_line=$(printf '%s' "$going" | tr '\n' ' ')
  question="Removes: $going_line
Space freed: $freed

Remove $pkg from the Linux computer?"

  if have zenity; then
    zenity --question --width=430 --no-markup --title="Remove an app" --text="$question" \
      --ok-label="Remove" --cancel-label="Cancel" >/dev/null 2>&1 || return 0
  elif [ -t 0 ]; then
    printf '%s\n\nType yes to remove: ' "$question"
    read -r reply
    case "$reply" in y|Y|yes|YES|Yes) ;; *) printf 'Nothing was removed.\n'; return 0 ;; esac
  else
    say error "Cannot ask you first" \
"This computer is missing the desktop's dialogs, so there is no way to show you what is about to be removed - and nothing is removed without that."
    return 1
  fi

  log="$HOME/.pocketlinux/logs/install.log"
  mkdir -p "$(dirname "$log")" 2>/dev/null || true
  progress_start "Removing $pkg..."
  if sudo DEBIAN_FRONTEND=noninteractive apt-get remove -y --auto-remove "$pkg" >> "$log" 2>&1; then
    progress_stop
    sudo /usr/local/bin/pocketlinux-menu >/dev/null 2>&1 || true
    say info "$pkg is removed" \
"It is gone from the Apps menu, and $freed is free again."
    return 0
  fi
  progress_stop
  # The same repair the install path makes: a removal that stops half way leaves dpkg unable to
  # do anything else, and the owner meets that as a mystery a week later.
  sudo dpkg --configure -a >> "$log" 2>&1 || true
  sudo apt-get -y -f install >> "$log" 2>&1 || true
  say error "$pkg was not removed" \
"The computer could not finish removing it, and it was put back in working order.

The details are in the Apps menu -> App reports -> install.log."
  return 1
}

if [ "$REMOVE_MODE" = "1" ]; then
  remove_package "$REMOVE_ONLY"
  exit $?
fi

if [ -z "$FILE" ]; then
  if have zenity; then
    PICK_DIR=$(cat "$HOME/.config/pocketlinux/download-dir" 2>/dev/null || true)
    case "$PICK_DIR" in
      "$HOME/Downloads"|"$HOME/Phone/Download/PocketLinux") ;;
      *) PICK_DIR="$HOME/Downloads" ;;
    esac
    FILE=$(zenity --file-selection --title="Install a downloaded app" \
      --filename="$PICK_DIR/" \
      --file-filter="Linux app packages | *.deb" \
      2>/dev/null) || exit 0
  else
    printf 'Usage: pocketlinux-install <file.deb>\n       pocketlinux-install --remove <name>\n'
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
lower_name=$(printf '%s' "$name_only" | tr '[:upper:]' '[:lower:]')

# An AppImage is the other thing people download for Linux, and it cannot work here: it mounts
# itself with FUSE, which a phone container has no way to provide. Saying so beats a silent
# failure -- and every app in the Apps tab publishes a .deb anyway.
case "$lower_name" in
  *.appimage)
    say error "AppImage files do not run here" \
"$name_only is an AppImage. An AppImage mounts itself with FUSE, which needs kernel support that a phone container cannot give it.

Look for the app's .deb build for Linux ARM64 instead, or install it from the Apps tab when it is one of the four AI apps."
    exit 1 ;;
esac

# A Windows program is refused here, and told why once rather than after a long download.
#
# Windows programs cannot run on this computer, and this is not a setting that can be turned on.
# Android keeps hardware virtualisation away from installed apps, so a real Windows machine is
# not possible; the compatibility layer that once ran Windows programs on ARM64 dropped its
# Android support; and this container already traces every system call, which is the exact
# combination an instruction translator cannot work inside. All four AI desktop apps publish
# official Linux ARM64 builds, which is what the Apps tab installs.
case "$lower_name" in
  *.exe|*.msix|*.msixbundle|*.appx|*.appxbundle|*.msi)
    say error "Windows programs cannot run here" \
"$name_only is a Windows program, and this is a Linux computer.

Windows itself cannot run on a phone: Android does not give an installed app the hardware virtualisation a Windows machine needs. A compatibility layer is not a way round it either -- the project that ran Windows programs on ARM64 dropped Android support, and this container traces every system call, which is what an instruction translator cannot work inside.

Look for the Linux ARM64 build of what you wanted. All four AI desktop apps publish one, and the Apps tab installs them for you.

Nothing was installed and nothing was changed."
    exit 1 ;;
esac

case "$lower_name" in
  *.deb) ;;
  *)
    say error "Not an app package" \
"$name_only is not a Linux app package.

Apps for this computer come as Linux ARM64 .deb packages. The Apps tab installs the four AI desktop apps from their publishers' own repositories; anything else you download as a .deb opens here."
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

# What opening this file is actually going to do, which is what Android's own installer leads
# with: it says Update, not Install, when the app is already on the phone.
INSTALLED_VERSION=""
if have dpkg-query && [ -n "$PACKAGE" ]; then
  case "$(dpkg-query -W -f='${Status}' "$PACKAGE" 2>/dev/null || true)" in
    *"ok installed"*) INSTALLED_VERSION=$(dpkg-query -W -f='${Version}' "$PACKAGE" 2>/dev/null || true) ;;
  esac
fi
if [ -z "$INSTALLED_VERSION" ]; then
  ACTION="This app is not on the computer yet."
elif [ "$INSTALLED_VERSION" = "$VERSION" ]; then
  ACTION="Version $INSTALLED_VERSION is already installed. Installing it again puts the same version back."
elif have dpkg && dpkg --compare-versions "$VERSION" gt "$INSTALLED_VERSION" 2>/dev/null; then
  ACTION="Version $INSTALLED_VERSION is installed now. This updates it to $VERSION."
else
  ACTION="Version $INSTALLED_VERSION is installed now, which is newer than this one. Installing this puts the older version back."
fi

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
  warn "PocketLinux installs this app itself, from its publisher's own signed repository -- the Apps tab, or Settings for Google Chrome. That copy is verified and updates in place; this downloaded one is neither."
done

# 4. What apt would have to do. The simulation is the only honest way to know whether the
#    other software it needs can be found, and it changes nothing on the computer.
#
#    This used to be switchable off with an environment variable, which was there for the tests
#    and shipped in the product with them. A safety check with an off switch is not a safety
#    check: anything that set the variable turned off the only two things standing between a
#    tapped file and a computer that no longer starts. The tests drive the real path now.
sim_log=$(mktemp 2>/dev/null || echo /tmp/pocketlinux-install-sim.log)
if have apt-get && have sudo; then
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
else
  # Never silently. A screen headed "Safety check" that stands for a check nobody made is
  # worse than one that says the check could not be made.
  warn "The check for other software this app needs could not be run on this computer, so nothing here can say whether it has everything it needs."
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
  printf 'verdict=%s\npackage=%s\nversion=%s\narch=%s\nneeds_bytes=%s\nfree_bytes=%s\ninstalled=%s\n' \
    "$verdict" "$PACKAGE" "$VERSION" "$ARCH" "$NEEDS_BYTES" "$FREE_BYTES" "$INSTALLED_VERSION"
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

What this does: $ACTION

Safety check:$warnings

Install ${PACKAGE:-this app} on the Linux computer?"

# Nothing is ever installed without a yes. With the desktop's dialogs missing (a computer
# built before they were part of set-up), a terminal asks; with neither, it stops rather than
# installing something nobody agreed to.
# --no-markup, everywhere below: every package's Maintainer field looks like "Name <mail@host>",
# which Pango cannot parse, and GTK then shows a dialog with no body at all -- no name, no size,
# no warnings, just two buttons. It also stops a hostile package forging the text.
#
# A third button, Remove, is offered when the app is already on the computer, which is the same
# choice Android gives for an app it already has. Only when this build of zenity has it: an
# option it does not understand makes it print usage and exit, and that would read as a cancel
# and leave no way to install anything at all.
if have zenity; then
  if [ -n "$INSTALLED_VERSION" ] && zenity --help-all 2>/dev/null | grep -q -- '--extra-button'; then
    answer=$(zenity --question --width=430 --no-markup --title="Install an app" --text="$question" \
      --ok-label="Install anyway" --cancel-label="Cancel" --extra-button="Remove" 2>/dev/null)
    case "$?:$answer" in
      0:*) ;;
      *:Remove) remove_package "$PACKAGE"; exit $? ;;
      *) exit 0 ;;
    esac
  else
    zenity --question --width=430 --no-markup --title="Install an app" --text="$question" \
      --ok-label="Install anyway" --cancel-label="Cancel" >/dev/null 2>&1 || exit 0
  fi
elif [ -t 0 ]; then
  if [ -n "$INSTALLED_VERSION" ]; then
    printf '%s\n\nType yes to install, or remove to remove it: ' "$question"
  else
    printf '%s\n\nType yes to install: ' "$question"
  fi
  read -r reply
  case "$reply" in
    y|Y|yes|YES|Yes) ;;
    r|R|remove|REMOVE|Remove) remove_package "$PACKAGE"; exit $? ;;
    *) printf 'Nothing was installed.\n'; exit 0 ;;
  esac
else
  say error "Cannot ask you first" \
"This computer is missing the desktop's dialogs, so there is no way to show you what is about to be installed - and nothing is installed without that.

Update the computer's basics in PocketLinux (Settings -> Storage), then open the file again."
  exit 1
fi

log="$HOME/.pocketlinux/logs/install.log"
mkdir -p "$(dirname "$log")" 2>/dev/null || true

progress_start "Installing ${PACKAGE:-the app}..."

if sudo DEBIAN_FRONTEND=noninteractive apt-get install -y "$FILE" >> "$log" 2>&1; then
  progress_stop
  # The menu, the panel and the desktop icons are rebuilt so the new app is there at once.
  sudo /usr/local/bin/pocketlinux-menu >/dev/null 2>&1 || true
  say info "${PACKAGE:-The app} is installed" \
"It is in the Apps menu now -- the Linux button on the left of the panel.

To remove it later, open this file again and choose Remove. If you have deleted the file, the terminal can do it:  sudo apt-get remove ${PACKAGE:-the-app}"
else
  progress_stop
  # An install fails most often part way through unpacking, which leaves dpkg half-applied and
  # every later install refusing to run. Put that right here rather than leaving it for the
  # owner to meet as a mystery next week.
  sudo dpkg --configure -a >> "$log" 2>&1 || true
  sudo apt-get -y -f install >> "$log" 2>&1 || true
  say error "${PACKAGE:-The app} did not install" \
"The computer could not finish installing it. The usual reasons are a package built for a different version of Ubuntu, or software it needs that is not available here.

The computer was put back in working order, and anything half-installed was cleaned up. The details are in the Apps menu -> App reports -> install.log."
  exit 1
fi
