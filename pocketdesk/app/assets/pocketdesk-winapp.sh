#!/bin/bash
# Installs a Windows program into this computer, the way the .deb installer does for Linux ones.
#
# The rule that decides everything is the processor. This phone is ARM64, and so is the Wine
# here, so a Windows program built for ARM64 runs with nothing in between. One built only for
# Intel and AMD would have to be translated instruction by instruction, which on this phone is
# slower than it is useful -- so the owner is told that before anything is downloaded or
# installed, not after.
#
# Nothing here touches the Linux side of the computer. A Windows program lives in its own prefix
# and its own launcher; removing the Windows layer removes all of it and leaves everything else
# exactly as it was.
set -u
HOME_DIR=/home/coder
PREFIX="$HOME_DIR/.pocketdesk-wine"
APPS_DIR="$HOME_DIR/.local/share/applications"
DESKTOP_DIR="$HOME_DIR/Desktop"
WIN_DIR="$HOME_DIR/.pocketdesk/windows"
export WINEPREFIX="$PREFIX"
export WINEDLLOVERRIDES="mscoree=d;mshtml=d"
export WINEDEBUG=-all

WINE=$(command -v wine 2>/dev/null || command -v wine64 2>/dev/null || true)

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }

[ -n "$WINE" ] || die "The Windows layer is not installed. Add 'Windows apps support' from the Apps tab first."

# ---- what kind of file is this, and will it run here? ---------------------------------------
#
# A Windows program says which processor it was built for in its PE header: a two-byte machine
# code at the offset the DOS stub points to. 0xaa64 is ARM64, 0x8664 is x64, 0x014c is 32-bit
# Intel. Reading it is the difference between an honest answer in one second and a 500 MB
# download that ends in a shrug.
pe_machine() {   # pe_machine <file> -> arm64 | x64 | x86 | unknown
  python3 - "$1" <<'PYEOF' 2>/dev/null || echo unknown
import struct, sys
try:
    with open(sys.argv[1], 'rb') as handle:
        if handle.read(2) != b'MZ':
            print('unknown'); raise SystemExit
        handle.seek(0x3c)
        offset = struct.unpack('<I', handle.read(4))[0]
        handle.seek(offset)
        if handle.read(4) != b'PE\0\0':
            print('unknown'); raise SystemExit
        machine = struct.unpack('<H', handle.read(2))[0]
except Exception:
    print('unknown'); raise SystemExit
print({0xaa64: 'arm64', 0xa641: 'arm64', 0x8664: 'x64', 0x014c: 'x86'}.get(machine, 'unknown'))
PYEOF
}

verdict() {   # verdict <arch>
  case "$1" in
    arm64) say "SUPPORTED: built for ARM64 — this phone's own processor." ;;
    x64|x86) say "NOT SUPPORTED: built only for Intel and AMD. Running it here would mean "\
                 "translating every instruction, which this phone cannot do at a usable speed." ;;
    *) say "UNKNOWN: the processor this was built for could not be read." ;;
  esac
}

# ---- unpack -----------------------------------------------------------------------------------
#
# The installer stub of a Windows program is very often 32-bit Intel even when the program inside
# is ARM64, so running the installer would fail on a file that would itself have worked. Every
# format here is unpacked instead: an MSIX is a zip, and an .exe installer opens with 7z.
unpack() {   # unpack <file> <into>
  source_file=$1; into=$2
  mkdir -p "$into"
  case "$source_file" in
    *.msix|*.msixbundle|*.appx|*.appxbundle|*.zip)
      unzip -qo "$source_file" -d "$into" 2>/dev/null || die "This package could not be opened."
      # A bundle holds one package per processor: keep the ARM64 one and drop the rest.
      inner=$(find "$into" -maxdepth 1 -iname '*arm64*.msix' -o -maxdepth 1 -iname '*arm64*.appx' | head -n 1)
      if [ -n "$inner" ]; then
        say "This package holds one program per processor; taking the ARM64 one."
        unzip -qo "$inner" -d "$into/arm64" 2>/dev/null || true
        rm -f "$into"/*.msix "$into"/*.appx 2>/dev/null || true
      fi
      ;;
    *.exe)
      7z x -y -o"$into" "$source_file" >/dev/null 2>&1 \
        || die "This installer could not be opened. It may need to run on a real Windows PC."
      ;;
    *) die "PocketDesk can open .exe, .msix and .appx files." ;;
  esac
}

# The program itself, out of whatever the installer laid down: the biggest .exe that is not one
# of the little helpers every installer ships.
main_exe() {   # main_exe <folder>
  find "$1" -type f -iname '*.exe' -printf '%s\t%p\n' 2>/dev/null \
    | grep -viE '(uninst|unins[0-9]*|setup|installer|update|crashpad|squirrel|vcredist|dotnet)' \
    | sort -rn | head -n 1 | cut -f2
}

# ---- the commands ------------------------------------------------------------------------------
case "${1:-}" in
  check)
    [ -f "${2:-}" ] || die "usage: pocketdesk-winapp check <file>"
    arch=$(pe_machine "$2")
    if [ "$arch" = unknown ]; then
      # An MSIX is a zip, so its own header says nothing: look at the name, which carries the
      # processor by Microsoft's own convention.
      case "$2" in *[aA][rR][mM]64*) arch=arm64 ;; *[xX]64*|*[xX]86*) arch=x64 ;; esac
    fi
    say "$arch"
    verdict "$arch"
    ;;

  install)
    file=${2:-}
    [ -f "$file" ] || die "usage: pocketdesk-winapp install <file> [name]"
    name=${3:-$(basename "$file" | sed 's/\.[^.]*$//')}
    arch=$(pe_machine "$file")
    if [ "$arch" = unknown ]; then
      case "$file" in *[aA][rR][mM]64*) arch=arm64 ;; *[xX]64*|*[xX]86*) arch=x64 ;; esac
    fi
    case "$arch" in
      arm64) : ;;
      x64|x86) die "$name is built only for Intel and AMD processors. It cannot run on this phone. Nothing was installed." ;;
      *) say "Warning: the processor could not be read. Trying anyway." ;;
    esac

    target="$WIN_DIR/$(printf '%s' "$name" | tr -cd '[:alnum:]._-' | tr '[:upper:]' '[:lower:]')"
    rm -rf "$target"
    say "Unpacking $name…"
    unpack "$file" "$target"

    exe=$(main_exe "$target")
    [ -n "$exe" ] || { rm -rf "$target"; die "No program was found inside $name."; }
    say "Found $(basename "$exe")."

    slug=$(basename "$target")
    entry="$APPS_DIR/pocketdesk-win-$slug.desktop"
    mkdir -p "$APPS_DIR" "$DESKTOP_DIR"
    # --no-sandbox and --disable-gpu: an Electron program cannot start its sandbox under Wine,
    # and there is no graphics chip here for it to ask for either. Harmless for anything else.
    printf '[Desktop Entry]\nType=Application\nName=%s\nComment=A Windows program, through Wine\nExec=env WINEPREFIX=%s WINEDEBUG=-all %s "%s" --no-sandbox --disable-gpu\nIcon=pocketdesk-windows\nTerminal=false\nCategories=Utility;\nX-PocketDesk=1\n' \
      "$name" "$PREFIX" "$WINE" "$exe" > "$entry"
    chmod 755 "$entry"
    cp -f "$entry" "$DESKTOP_DIR/pocketdesk-win-$slug.desktop" 2>/dev/null || true
    chmod 755 "$DESKTOP_DIR/pocketdesk-win-$slug.desktop" 2>/dev/null || true
    update-desktop-database "$APPS_DIR" >/dev/null 2>&1 || true
    say "INSTALLED: $name is on the desktop. It is a Windows program, so it may look or behave differently."
    ;;

  list)
    ls -1 "$APPS_DIR"/pocketdesk-win-*.desktop 2>/dev/null \
      | while read -r one; do sed -n 's/^Name=//p' "$one"; done
    ;;

  remove)
    slug=${2:-}
    [ -n "$slug" ] || die "usage: pocketdesk-winapp remove <name>"
    slug=$(printf '%s' "$slug" | tr -cd '[:alnum:]._-' | tr '[:upper:]' '[:lower:]')
    rm -rf "$WIN_DIR/$slug" "$APPS_DIR/pocketdesk-win-$slug.desktop" \
           "$DESKTOP_DIR/pocketdesk-win-$slug.desktop"
    say "Removed."
    ;;

  version) $WINE --version 2>/dev/null || say "unknown" ;;

  *) die "usage: pocketdesk-winapp {check|install|list|remove|version} …" ;;
esac
