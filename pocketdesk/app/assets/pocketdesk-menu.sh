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
  echo '  <item label="Terminal"><action name="Execute"><command>lxterminal</command></action></item>'
  echo '  <item label="Files"><action name="Execute"><command>pcmanfm /home/coder/Shared</command></action></item>'
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
  echo '  <separator/>'
  echo '  <item label="Refresh"><action name="Execute"><command>/usr/local/bin/pocketdesk-menu</command></action></item>'
  echo '</menu>'
  echo '</openbox_menu>'
} > "$OPENBOX_DIR/menu.xml"

# ---- Taskbar, with a launcher icon per installed app --------------------------------
{
  echo 'panel_items = LTSC'
  echo 'panel_size = 100% 46'
  echo 'panel_padding = 6 2 6'
  echo 'background_color = #0f1327 100'
  echo 'taskbar_name = 0'
  echo 'task_font = Sans 11'
  echo 'task_font_color = #e6ecf7 100'
  echo 'task_maximum_size = 240 42'
  echo 'clock_font_line1 = Sans 11'
  echo 'clock_font_color = #e6ecf7 100'
  echo 'time1_format = %H:%M'
  echo 'launcher_icon_size = 32'
  echo 'launcher_padding = 6 4 6'
  echo 'launcher_icon_theme = Adwaita'
  echo 'launcher_item_app = /usr/share/applications/pocketdesk-terminal.desktop'
  echo 'launcher_item_app = /usr/share/applications/pocketdesk-files.desktop'
  for desktop in /usr/share/applications/pocketdesk-*.desktop; do
    [ -f "$desktop" ] || continue
    case "$(basename "$desktop")" in
      pocketdesk-terminal.desktop|pocketdesk-files.desktop) continue ;;
    esac
    echo "launcher_item_app = $desktop"
  done
} > "$TINT2_DIR/tint2rc"

# The desktop folder must be where pcmanfm looks, or the icons never show.
printf 'XDG_DESKTOP_DIR="$HOME/Desktop"\nXDG_DOWNLOAD_DIR="$HOME/Shared"\n' \
  > "$HOME_DIR/.config/user-dirs.dirs"

chown -R coder:coder "$OPENBOX_DIR" "$TINT2_DIR" "$HOME_DIR/.config/user-dirs.dirs" 2>/dev/null || true

# Pick the changes up live when a desktop is already running.
if [ -S /tmp/.X11-unix/X1 ]; then
  DISPLAY=:1 openbox --reconfigure 2>/dev/null || true
  if pgrep -x tint2 >/dev/null 2>&1; then
    pkill -x tint2 2>/dev/null || true
    sleep 1
    DISPLAY=:1 setsid tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
  fi
fi
