#!/bin/bash
# Builds the desktop's app list from the applications that are really installed.
#
# Reading each package's own .desktop file is what gives every app its real name, its real icon
# and the launch command its packager intended -- rather than a hand-made entry with a generic
# icon that has to be kept in step by hand.
#
# Every launcher points at pocketdesk-open rather than the app directly, because a Chromium-based
# app started without --no-sandbox dies before it draws anything and the tap looks ignored.
set -u
HOME_DIR=/home/coder
OPENBOX_DIR="$HOME_DIR/.config/openbox"
TINT2_DIR="$HOME_DIR/.config/tint2"
DESKTOP_DIR="$HOME_DIR/Desktop"
LOCAL_APPS="$HOME_DIR/.local/share/applications"
APPLICATIONS_DIR=/usr/share/applications
OPEN=/usr/local/bin/pocketdesk-open
mkdir -p "$OPENBOX_DIR" "$TINT2_DIR" "$DESKTOP_DIR" "$LOCAL_APPS" \
         "$HOME_DIR/Projects" "$HOME_DIR/Downloads"

# What gets a desktop icon and a panel slot: the AI apps, the browser, the files. Everything
# else that is installed -- the terminal included -- stays one right-click away instead of
# taking up room on a phone-sized screen.
FAVOURITES="chatgpt claude-desktop cursor antigravity epiphany firefox pcmanfm"

field() {   # field <file> <key>  -- the key as the main [Desktop Entry] group sets it
  awk -F= -v key="$2" '
    /^\[/ { group++ }
    group > 1 { exit }
    $1 == key { sub(/^[^=]*=/, ""); print; exit }
  ' "$1"
}

is_visible() {
  grep -qi '^NoDisplay=true' "$1" && return 1
  grep -qi '^Hidden=true' "$1" && return 1
  grep -qi '^Type=Application' "$1" || return 1
  return 0
}

# Exec lines carry placeholders like %U or %F that a launcher must not pass through.
strip_codes() {
  printf '%s' "$1" | sed 's/ *%[UufFdDnNickvm]//g'
}

xml_escape() {
  printf '%s' "$1" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g'
}

entries() {
  for desktop in "$APPLICATIONS_DIR"/*.desktop; do
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

# A copy of the package's own entry with the launch command routed through pocketdesk-open.
# DBusActivatable would let a file manager start the app behind our back, and extra action groups
# would start it unwrapped, so both go. %U stays on the end: that is how a sign-in link handed
# to the app by the browser reaches it (the launcher passes it on to the running instance).
# "Web" says nothing and "File Manager PCManFM" does not fit under an icon on a phone.
short_name() {   # short_name <base> <name>
  case "$1" in
    org.gnome.Epiphany|epiphany) printf 'Browser' ;;
    pcmanfm) printf 'Files' ;;
    lxterminal) printf 'Terminal' ;;
    *) printf '%s' "$2" ;;
  esac
}

# Icon overrides where the theme's own mark reads badly on a phone.
icon_for() {   # icon_for <base> -> icon name or empty to keep the original
  case "$1" in
    pcmanfm) printf 'pocketdesk-files' ;;
    *) printf '' ;;
  esac
}

write_entry() {   # write_entry <source> <target> <label> <command>
  icon_override=$(icon_for "$(basename "$1" .desktop)")
  awk -v cmd="$4" -v label="$3" -v icon="$icon_override" '
    /^\[/ { group++ }
    group > 1 { next }
    /^Exec=/ { print "Exec=/usr/local/bin/pocketdesk-open --label \"" label "\" " cmd " %U"; next }
    /^Name=/ { print "Name=" label; next }
    /^Icon=/ && icon != "" { print "Icon=" icon; next }
    /^Name\[/ { next }
    /^(DBusActivatable|TryExec|Actions|X-PocketDesk)=/ { next }
    { print }
  ' "$1" > "$2"
  echo 'X-PocketDesk=1' >> "$2"
  chmod 755 "$2"
}

# ---- Openbox right-click menu: every installed app ----------------------------------
{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo '<openbox_menu xmlns="http://openbox.org/3.4/menu">'
  echo '<menu id="root-menu" label="PocketDesk">'
  found=0
  while read -r desktop; do
    [ -n "$desktop" ] || continue
    name=$(short_name "$(basename "$desktop" .desktop)" "$(field "$desktop" Name)")
    [ -n "$name" ] || continue
    command=$(strip_codes "$(field "$desktop" Exec)")
    if [ "$(field "$desktop" Terminal)" = "true" ]; then
      command="lxterminal -e $command"
    else
      command="$OPEN --label \"$(printf '%s' "$name" | tr -d '\"\\\\')\" $command"
    fi
    printf '  <item label="%s"><action name="Execute"><command>%s</command></action></item>\n' \
      "$(xml_escape "$name")" "$(xml_escape "$command")"
    found=1
  done <<EOF
$(entries)
EOF
  [ "$found" = 1 ] || echo '  <item label="No apps yet"><action name="Execute"><command>true</command></action></item>'
  echo '  <separator/>'
  echo '  <item label="Files"><action name="Execute"><command>pcmanfm /home/coder/Projects</command></action></item>'
  echo '  <item label="Downloads"><action name="Execute"><command>pcmanfm /home/coder/Downloads</command></action></item>'
  echo '  <item label="App reports"><action name="Execute"><command>pcmanfm /home/coder/.pocketdesk/logs</command></action></item>'
  echo '  <separator label="Windows"/>'
  echo '  <item label="Open windows"><action name="Execute"><command>/usr/local/bin/pocketdesk-windows list</command></action></item>'
  echo '  <item label="Minimise all"><action name="ToggleShowDesktop"/></item>'
  echo '  <item label="Close all"><action name="Execute"><command>/usr/local/bin/pocketdesk-windows close-all</command></action></item>'
  echo '  <item label="Terminal"><action name="Execute"><command>lxterminal</command></action></item>'
  echo '  <item label="Refresh desktop"><action name="Execute"><command>/usr/local/bin/pocketdesk-menu</command></action></item>'
  echo '</menu>'
  echo '</openbox_menu>'
} > "$OPENBOX_DIR/menu.xml"

# ---- Desktop icons and panel launchers: the favourites, using their own entries -----
find "$DESKTOP_DIR" -maxdepth 1 -name '*.desktop' -delete 2>/dev/null || true
find "$LOCAL_APPS" -maxdepth 1 -name 'pocketdesk-*.desktop' -delete 2>/dev/null || true
launcher_lines=""
add_favourite() {   # add_favourite <desktop file>
  base=$(basename "$1" .desktop)
  exec_line=$(field "$1" Exec)
  label=$(short_name "$base" "$(field "$1" Name)" | tr -d '"\\')
  wrapped="$LOCAL_APPS/pocketdesk-$base.desktop"
  write_entry "$1" "$wrapped" "$label" "$(strip_codes "$exec_line")"
  cp -f "$wrapped" "$DESKTOP_DIR/$base.desktop" 2>/dev/null || true
  launcher_lines="$launcher_lines
launcher_item_app = $wrapped"
  taken="$taken $base"
}

# Several entries can share one command -- the browser itself and the web-app launchers all run
# epiphany -- so a favourite claims the file named after it first, and only then one by command.
taken=""
for wanted in $FAVOURITES; do
  match=""
  for pass in base binary; do
    while read -r desktop; do
      [ -n "$desktop" ] || continue
      case " $taken " in *" $(basename "$desktop" .desktop) "*) continue ;; esac
      case "$pass" in
        base) candidate=$(basename "$desktop" .desktop) ;;
        binary) candidate=$(basename "$(printf '%s' "$(field "$desktop" Exec)" | awk '{print $1}')") ;;
      esac
      if [ "$candidate" = "$wanted" ]; then
        match=$desktop
        break
      fi
    done <<EOF
$(entries)
EOF
    [ -n "$match" ] && break
  done
  [ -n "$match" ] && add_favourite "$match"
done
chmod 755 "$DESKTOP_DIR"/*.desktop 2>/dev/null || true

{
  echo 'panel_items = LTSC'
  echo 'panel_size = 100% 52'
  echo 'panel_padding = 6 3 8'
  echo 'background_color = #0f1327 100'
  echo 'border_width = 0'
  echo 'taskbar_name = 0'
  echo 'task_font = Sans 10'
  echo 'task_font_color = #e6ecf7 100'
  echo 'task_maximum_size = 240 46'
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
  echo 'launcher_icon_size = 36'
  echo 'launcher_padding = 8 4 8'
  echo 'launcher_icon_theme = Adwaita'
  echo 'launcher_tooltip = 1'
  printf '%s\n' "$launcher_lines"
} > "$TINT2_DIR/tint2rc"

printf 'XDG_DESKTOP_DIR="$HOME/Desktop"\nXDG_DOCUMENTS_DIR="$HOME/Projects"\nXDG_DOWNLOAD_DIR="$HOME/Downloads"\n' \
  > "$HOME_DIR/.config/user-dirs.dirs"

# ---- Which app answers which kind of link -------------------------------------------
# http and https go to the browser. A sign-in that opens in the browser comes back to the app
# that asked for it through the app's own link scheme (chatgpt://, claude://, cursor://...),
# which each package declares in its .desktop file; those are copied here so the browser can
# find them. Without this table the login page said "done" and the app never heard.
BROWSER_ENTRY=""
for candidate in org.gnome.Epiphany.desktop firefox.desktop; do
  [ -f "$APPLICATIONS_DIR/$candidate" ] && { BROWSER_ENTRY=$candidate; break; }
done
{
  echo '[Default Applications]'
  if [ -n "$BROWSER_ENTRY" ]; then
    printf 'x-scheme-handler/http=%s\nx-scheme-handler/https=%s\ntext/html=%s\n' \
      "$BROWSER_ENTRY" "$BROWSER_ENTRY" "$BROWSER_ENTRY"
  fi
  for desktop in "$APPLICATIONS_DIR"/*.desktop; do
    [ -f "$desktop" ] || continue
    mime=$(field "$desktop" MimeType)
    [ -n "$mime" ] || continue
    handler=$(basename "$desktop")
    # The wrapped copy, when there is one, so the link arrives with the sandbox flags too.
    [ -f "$LOCAL_APPS/pocketdesk-$handler" ] && handler="pocketdesk-$handler"
    printf '%s' "$mime" | tr ';' '\n' | grep '^x-scheme-handler/' | while read -r scheme; do
      case "$scheme" in x-scheme-handler/http|x-scheme-handler/https) continue ;; esac
      printf '%s=%s\n' "$scheme" "$handler"
    done
  done
} > "$HOME_DIR/.config/mimeapps.list"
if command -v update-desktop-database >/dev/null 2>&1 && [ -w "$APPLICATIONS_DIR" ]; then
  update-desktop-database "$APPLICATIONS_DIR" >/dev/null 2>&1 || true
fi

# ---- Window manager: Openbox's defaults, adjusted for a phone-sized screen ------------
# Rewritten on every run, so a container built by an earlier version gets these rules too
# (it used to be written once and never touched again, which is why old installs kept
# floating half-size windows).
#   - every normal window opens maximised: a floating window is wasted space on a phone;
#   - a title bar on every window, Electron apps included, or there is no way to close them;
#   - the close, minimise and maximise buttons sit at the LEFT edge of the title bar. A
#     maximised window always starts at the left edge of the screen, so those buttons are
#     always on screen -- at the right edge they vanished whenever an app's smallest allowed
#     width was wider than a portrait phone screen;
#   - one desktop, not four: Openbox's defaults bind Super+F1..F4 to "go to desktop N", so a
#     window could vanish to a desktop the phone has no way to show. Those bindings go, and
#     the count becomes one;
#   - three keys the phone's toolbar sends: Super+F4 force-closes the window in front (for an
#     app that stopped answering), Super+Tab lists the open windows, Super+P opens the Phone
#     folder. Alt+F4 and Alt+Tab are Openbox's own defaults and stay.
OPENBOX_DEFAULT=${POCKETDESK_OPENBOX_DEFAULT:-/etc/xdg/openbox/rc.xml}
if [ -f "$OPENBOX_DEFAULT" ]; then
  sed -e 's|<size>[0-9]*</size>|<size>11</size>|g' \
      -e 's|<titleLayout>[^<]*</titleLayout>|<titleLayout>CIMNL</titleLayout>|' \
      -e 's|<number>[0-9]*</number>|<number>1</number>|' \
      -e '/<keybind key="W-F[1-4]">/,/<\/keybind>/d' \
      -e 's|<applications>|<applications>\n    <application type="normal"><maximized>yes</maximized><decor>yes</decor></application>|' \
      -e 's|<keyboard>|<keyboard>\n    <keybind key="W-F4"><action name="Execute"><command>/usr/local/bin/pocketdesk-windows kill-active</command></action></keybind>\n    <keybind key="W-Tab"><action name="Execute"><command>/usr/local/bin/pocketdesk-windows list</command></action></keybind>\n    <keybind key="W-p"><action name="Execute"><command>pcmanfm /home/coder/Phone</command></action></keybind>|' \
      "$OPENBOX_DEFAULT" > "$OPENBOX_DIR/rc.xml.new" \
    && mv -f "$OPENBOX_DIR/rc.xml.new" "$OPENBOX_DIR/rc.xml"
fi

# The phone's own files, as a folder on the desktop. Empty but for a note until the owner turns
# Phone files on in PocketDesk's Settings; then Download, DCIM and Documents are in it.
printf '[Desktop Entry]\nType=Application\nName=Phone\nComment=Your phone\047s files, inside the computer\nExec=pcmanfm /home/coder/Phone\nIcon=drive-removable-media\nTerminal=false\n' \
  > "$DESKTOP_DIR/pocketdesk-phone.desktop"
chmod +x "$DESKTOP_DIR/pocketdesk-phone.desktop" 2>/dev/null || true

chown -R coder:coder "$OPENBOX_DIR" "$TINT2_DIR" "$DESKTOP_DIR" "$LOCAL_APPS" \
  "$HOME_DIR/.config/user-dirs.dirs" "$HOME_DIR/.config/mimeapps.list" \
  "$HOME_DIR/Projects" "$HOME_DIR/Downloads" 2>/dev/null || true

if [ -S /tmp/.X11-unix/X1 ]; then
  DISPLAY=:1 openbox --reconfigure 2>/dev/null || true
  if pgrep -x tint2 >/dev/null 2>&1; then
    pkill -x tint2 2>/dev/null || true
    sleep 1
    DISPLAY=:1 setsid tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
  fi
fi
