#!/bin/bash
# Rebuilds everything that lists the installed apps: the Openbox right-click menu and the
# taskbar's launcher icons. Both are generated from what is actually installed, so a newly
# added app appears without the desktop needing to know about it in advance.
set -u
HOME_DIR=/home/coder
OPENBOX_DIR="$HOME_DIR/.config/openbox"
TINT2_DIR="$HOME_DIR/.config/tint2"
mkdir -p "$OPENBOX_DIR" "$TINT2_DIR"

launchers() {
  for launcher in /usr/local/bin/pocketdesk-*; do
    [ -x "$launcher" ] || continue
    case "$(basename "$launcher")" in
      pocketdesk-desktop|pocketdesk-menu) continue ;;
    esac
    echo "$launcher"
  done
}

label_of() {
  printf '%s' "$(basename "$1")" | sed 's/^pocketdesk-//' | sed 's/^./\U&/'
}

# ---- Openbox right-click menu -------------------------------------------------------
{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo '<openbox_menu xmlns="http://openbox.org/3.4/menu">'
  echo '<menu id="root-menu" label="PocketDesk">'
  echo '  <separator label="Apps"/>'
  found=0
  while read -r launcher; do
    [ -n "$launcher" ] || continue
    printf '  <item label="%s"><action name="Execute"><command>%s</command></action></item>\n' \
      "$(label_of "$launcher")" "$launcher"
    found=1
  done <<EOF
$(launchers)
EOF
  [ "$found" = 1 ] || echo '  <item label="No apps yet — add them in PocketDesk"><action name="Execute"><command>true</command></action></item>'
  echo '  <separator label="System"/>'
  echo '  <item label="Files"><action name="Execute"><command>pcmanfm /home/coder/Projects</command></action></item>'
  echo '  <item label="Terminal"><action name="Execute"><command>lxterminal</command></action></item>'
  echo '  <separator/>'
  echo '  <item label="Refresh desktop"><action name="Execute"><command>/usr/local/bin/pocketdesk-menu</command></action></item>'
  echo '</menu>'
  echo '</openbox_menu>'
} > "$OPENBOX_DIR/menu.xml"

# ---- Taskbar: launchers, window list, tray and an Indian-time clock -----------------
{
  echo 'panel_items = LTSC'
  echo 'panel_size = 100% 48'
  echo 'panel_padding = 6 3 8'
  echo 'panel_background_id = 1'
  echo 'background_color = #0f1327 100'
  echo 'border_color = #223056 100'
  echo 'border_width = 0'
  echo 'taskbar_name = 0'
  echo 'task_font = Sans 11'
  echo 'task_font_color = #e6ecf7 100'
  echo 'task_maximum_size = 260 44'
  echo 'task_padding = 8 3 6'
  # 12-hour clock, so 18:06 reads as 06:06 pm.
  echo 'time1_format = %I:%M %P'
  echo 'time2_format = %a %d %b'
  echo 'time1_font = Sans Bold 12'
  echo 'time2_font = Sans 9'
  echo 'clock_font_color = #e6ecf7 100'
  echo 'clock_padding = 10 2'
  echo 'time_tooltip_format = %A %d %B %Y, %I:%M %P'
  echo 'systray_padding = 6 2 6'
  echo 'systray_icon_size = 24'
  echo 'launcher_icon_size = 34'
  echo 'launcher_padding = 8 4 8'
  echo 'launcher_icon_theme = Adwaita'
  echo 'launcher_tooltip = 1'
  for desktop in /usr/share/applications/pocketdesk-*.desktop; do
    [ -f "$desktop" ] || continue
    echo "launcher_item_app = $desktop"
  done
} > "$TINT2_DIR/tint2rc"

printf 'XDG_DESKTOP_DIR="$HOME/Desktop"\nXDG_DOCUMENTS_DIR="$HOME/Projects"\nXDG_DOWNLOAD_DIR="$HOME/Downloads"\n' \
  > "$HOME_DIR/.config/user-dirs.dirs"
mkdir -p "$HOME_DIR/Downloads" "$HOME_DIR/Projects"

chown -R coder:coder "$OPENBOX_DIR" "$TINT2_DIR" "$HOME_DIR/.config/user-dirs.dirs" \
  "$HOME_DIR/Downloads" "$HOME_DIR/Projects" 2>/dev/null || true

# Pick the changes up live when a desktop is already running.
if [ -S /tmp/.X11-unix/X1 ]; then
  DISPLAY=:1 openbox --reconfigure 2>/dev/null || true
  if pgrep -x tint2 >/dev/null 2>&1; then
    pkill -x tint2 2>/dev/null || true
    sleep 1
    DISPLAY=:1 setsid tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
  fi
fi
