#!/bin/bash
# Builds the desktop's app list from the applications that are really installed.
#
# Reading each package's own .desktop file is what gives every app its real name, its real icon
# and the launch command its packager intended -- rather than a hand-made entry with a generic
# icon that has to be kept in step by hand.
set -u
HOME_DIR=/home/coder
OPENBOX_DIR="$HOME_DIR/.config/openbox"
TINT2_DIR="$HOME_DIR/.config/tint2"
DESKTOP_DIR="$HOME_DIR/Desktop"
mkdir -p "$OPENBOX_DIR" "$TINT2_DIR" "$DESKTOP_DIR" "$HOME_DIR/Projects" "$HOME_DIR/Downloads"

# Apps worth a desktop icon and a panel slot, most useful first. Everything installed still
# appears in the right-click menu.
FAVOURITES="chatgpt claude-desktop claude antigravity code firefox lxterminal pcmanfm"

field() {   # field <file> <key>
  sed -n "s/^$2=//p" "$1" | head -n 1
}

is_visible() {
  grep -qi '^NoDisplay=true' "$1" && return 1
  grep -qi '^Hidden=true' "$1" && return 1
  grep -qi '^Type=Application' "$1" || return 1
  return 0
}

# Exec lines carry placeholders like %U or %F that a menu must not pass through.
clean_exec() {
  printf '%s' "$1" | sed 's/ *%[UufFdDnNickvm]//g' | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g'
}

entries() {
  for desktop in /usr/share/applications/*.desktop; do
    [ -f "$desktop" ] || continue
    is_visible "$desktop" || continue
    exec_line=$(field "$desktop" Exec)
    [ -n "$exec_line" ] || continue
    binary=$(printf '%s' "$exec_line" | awk '{print $1}')
    case "$binary" in
      /*) [ -x "$binary" ] || continue ;;
      *) command -v "$binary" >/dev/null 2>&1 || continue ;;
    esac
    echo "$desktop"
  done
}

# ---- Openbox right-click menu: every installed app ----------------------------------
{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo '<openbox_menu xmlns="http://openbox.org/3.4/menu">'
  echo '<menu id="root-menu" label="PocketDesk">'
  found=0
  while read -r desktop; do
    [ -n "$desktop" ] || continue
    name=$(field "$desktop" Name)
    [ -n "$name" ] || continue
    printf '  <item label="%s"><action name="Execute"><command>%s</command></action></item>\n' \
      "$(printf '%s' "$name" | sed 's/&/\&amp;/g; s/</\&lt;/g')" \
      "$(clean_exec "$(field "$desktop" Exec)")"
    found=1
  done <<EOF
$(entries)
EOF
  [ "$found" = 1 ] || echo '  <item label="No apps yet"><action name="Execute"><command>true</command></action></item>'
  echo '  <separator/>'
  echo '  <item label="Files"><action name="Execute"><command>pcmanfm /home/coder/Projects</command></action></item>'
  echo '  <item label="Terminal"><action name="Execute"><command>lxterminal</command></action></item>'
  echo '  <item label="Refresh desktop"><action name="Execute"><command>/usr/local/bin/pocketdesk-menu</command></action></item>'
  echo '</menu>'
  echo '</openbox_menu>'
} > "$OPENBOX_DIR/menu.xml"

# ---- Desktop icons and panel launchers: the favourites, using their own entries -----
find "$DESKTOP_DIR" -maxdepth 1 -name '*.desktop' -delete 2>/dev/null || true
launcher_lines=""
for wanted in $FAVOURITES; do
  while read -r desktop; do
    [ -n "$desktop" ] || continue
    base=$(basename "$desktop" .desktop)
    binary=$(basename "$(printf '%s' "$(field "$desktop" Exec)" | awk '{print $1}')")
    if [ "$base" = "$wanted" ] || [ "$binary" = "$wanted" ]; then
      cp -f "$desktop" "$DESKTOP_DIR/" 2>/dev/null || true
      launcher_lines="$launcher_lines
launcher_item_app = $desktop"
      break
    fi
  done <<EOF
$(entries)
EOF
done
chmod 755 "$DESKTOP_DIR"/*.desktop 2>/dev/null || true

{
  echo 'panel_items = LTSC'
  echo 'panel_size = 100% 46'
  echo 'panel_padding = 6 3 8'
  echo 'background_color = #0f1327 100'
  echo 'border_width = 0'
  echo 'taskbar_name = 0'
  echo 'task_font = Sans 10'
  echo 'task_font_color = #e6ecf7 100'
  echo 'task_maximum_size = 240 40'
  echo 'task_padding = 8 3 6'
  # 12-hour clock, so 18:06 reads as 06:06 pm.
  echo 'time1_format = %I:%M %P'
  echo 'time2_format = %a %d %b'
  echo 'time1_font = Sans Bold 11'
  echo 'time2_font = Sans 8'
  echo 'clock_font_color = #e6ecf7 100'
  echo 'clock_padding = 10 2'
  echo 'time_tooltip_format = %A %d %B %Y, %I:%M %P'
  echo 'systray_padding = 6 2 6'
  echo 'systray_icon_size = 22'
  echo 'launcher_icon_size = 30'
  echo 'launcher_padding = 8 4 8'
  echo 'launcher_icon_theme = Adwaita'
  echo 'launcher_tooltip = 1'
  printf '%s\n' "$launcher_lines"
} > "$TINT2_DIR/tint2rc"

printf 'XDG_DESKTOP_DIR="$HOME/Desktop"\nXDG_DOCUMENTS_DIR="$HOME/Projects"\nXDG_DOWNLOAD_DIR="$HOME/Downloads"\n' \
  > "$HOME_DIR/.config/user-dirs.dirs"

chown -R coder:coder "$OPENBOX_DIR" "$TINT2_DIR" "$DESKTOP_DIR" \
  "$HOME_DIR/.config/user-dirs.dirs" "$HOME_DIR/Projects" "$HOME_DIR/Downloads" 2>/dev/null || true

if [ -S /tmp/.X11-unix/X1 ]; then
  DISPLAY=:1 openbox --reconfigure 2>/dev/null || true
  if pgrep -x tint2 >/dev/null 2>&1; then
    pkill -x tint2 2>/dev/null || true
    sleep 1
    DISPLAY=:1 setsid tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
  fi
fi
