#!/bin/bash
# Rebuilds the Openbox right-click menu from whatever PocketDesk has installed.
# Every app the catalogue installs drops a /usr/local/bin/pocketdesk-<id> launcher, so a newly
# added app shows up here without the desktop needing to know about it in advance.
set -u
HOME_DIR=/home/coder
OUT_DIR="$HOME_DIR/.config/openbox"
mkdir -p "$OUT_DIR"

{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo '<openbox_menu xmlns="http://openbox.org/3.4/menu">'
  echo '<menu id="root-menu" label="PocketDesk">'
  echo '  <item label="Terminal"><action name="Execute"><command>lxterminal</command></action></item>'
  echo '  <item label="Files"><action name="Execute"><command>pcmanfm /home/coder/Shared</command></action></item>'
  echo '  <separator label="Apps"/>'

  found=0
  for launcher in /usr/local/bin/pocketdesk-*; do
    [ -x "$launcher" ] || continue
    base=$(basename "$launcher")
    case "$base" in
      pocketdesk-desktop|pocketdesk-menu) continue ;;
    esac
    label=$(printf '%s' "${base#pocketdesk-}" | sed 's/^./\U&/')
    printf '  <item label="%s"><action name="Execute"><command>%s</command></action></item>\n' \
      "$label" "$launcher"
    found=1
  done
  [ "$found" = 1 ] || echo '  <item label="No apps yet - add them in PocketDesk"><action name="Execute"><command>true</command></action></item>'

  echo '  <separator/>'
  echo '  <item label="Refresh menu"><action name="Execute"><command>/usr/local/bin/pocketdesk-menu</command></action></item>'
  echo '</menu>'
  echo '</openbox_menu>'
} > "$OUT_DIR/menu.xml"

chown coder:coder "$OUT_DIR/menu.xml" 2>/dev/null || true
DISPLAY=:1 openbox --reconfigure 2>/dev/null || true
