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
WINDOWS=/usr/local/bin/pocketdesk-windows
mkdir -p "$OPENBOX_DIR" "$TINT2_DIR" "$DESKTOP_DIR" "$LOCAL_APPS" \
         "$HOME_DIR/Projects" "$HOME_DIR/Downloads" "$HOME_DIR/Phone"

field() {   # field <file> <key>  -- the key as the main [Desktop Entry] group sets it
  awk -F= -v key="$2" '
    /^\[/ { group++ }
    group > 1 { exit }
    $1 == key { sub(/^[^=]*=/, ""); print; exit }
  ' "$1"
}

runnable() {   # runnable <desktop file>: its Exec names a program that exists
  local exec_line binary
  exec_line=$(field "$1" Exec)
  [ -n "$exec_line" ] || return 1
  binary=$(printf '%s' "$exec_line" | awk '{print $1}')
  case "$binary" in
    /*) [ -x "$binary" ] ;;
    *) command -v "$binary" >/dev/null 2>&1 ;;
  esac
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
    runnable "$desktop" || continue
    echo "$desktop"
  done
}

# The browser. Google Chrome, which set-up installs; Brave or GNOME Web only on a computer
# built by an earlier version that still has one of them. One browser on the desktop and
# the panel, and the same one answers every link, so a sign-in never opens in a second one.
BROWSER_ENTRY=""
for candidate in google-chrome.desktop brave-browser.desktop org.gnome.Epiphany.desktop; do
  [ -f "$APPLICATIONS_DIR/$candidate" ] || continue
  runnable "$APPLICATIONS_DIR/$candidate" || continue
  BROWSER_ENTRY=$candidate
  break
done
BROWSER_BASE=${BROWSER_ENTRY%.desktop}

# What gets a desktop icon and a panel slot: the AI apps, the browser, the files, the terminal.
# Everything else that is installed stays one right-click (or one tap on Apps) away instead of
# taking up room on a phone-sized screen.
FAVOURITES="chatgpt claude-desktop cursor antigravity ${BROWSER_BASE:-org.gnome.Epiphany} pcmanfm lxterminal"

# A copy of the package's own entry with the launch command routed through pocketdesk-open.
# DBusActivatable would let a file manager start the app behind our back, and extra action groups
# would start it unwrapped, so both go. %U stays on the end: that is how a sign-in link handed
# to the app by the browser reaches it (the launcher passes it on to the running instance).
# "Web" says nothing and "File Manager PCManFM" does not fit under an icon on a phone.
short_name() {   # short_name <base> <name>
  case "$1" in
    org.gnome.Epiphany|epiphany) printf 'Web' ;;
    google-chrome) printf 'Chrome' ;;
    brave-browser) printf 'Brave' ;;
    firefox) printf 'Firefox' ;;
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

# ---- Openbox menu: every installed app, the folders, the window commands ---------------
# Opened by a right-click on the wallpaper (a long press in Finger mode), the Apps button on
# the panel, Super+A, or Window -> Apps menu on the phone.
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
  echo '  <separator label="Folders"/>'
  echo '  <item label="Phone files"><action name="Execute"><command>pcmanfm /home/coder/Phone</command></action></item>'
  echo '  <item label="Projects"><action name="Execute"><command>pcmanfm /home/coder/Projects</command></action></item>'
  echo '  <item label="Downloads"><action name="Execute"><command>pcmanfm /home/coder/Downloads</command></action></item>'
  echo '  <item label="App reports"><action name="Execute"><command>pcmanfm /home/coder/.pocketdesk/logs</command></action></item>'
  echo '  <separator label="Windows"/>'
  echo '  <item label="Open windows"><action name="Execute"><command>'"$WINDOWS"' list</command></action></item>'
  echo '  <item label="Minimise all"><action name="ToggleShowDesktop"/></item>'
  echo '  <item label="Close all"><action name="Execute"><command>'"$WINDOWS"' close-all</command></action></item>'
  echo '  <separator label="Desktop"/>'
  echo '  <item label="Terminal"><action name="Execute"><command>lxterminal</command></action></item>'
  echo '  <item label="Reload screen"><action name="Execute"><command>'"$WINDOWS"' refresh</command></action></item>'
  echo '  <item label="Refresh app list"><action name="Execute"><command>/usr/local/bin/pocketdesk-menu</command></action></item>'
  echo '</menu>'
  echo '</openbox_menu>'
} > "$OPENBOX_DIR/menu.xml"

# ---- Desktop icons and panel launchers: the favourites, using their own entries -----
find "$DESKTOP_DIR" -maxdepth 1 -name '*.desktop' -delete 2>/dev/null || true
find "$LOCAL_APPS" -maxdepth 1 -name 'pocketdesk-*.desktop' -delete 2>/dev/null || true

# The panel's first button: the apps menu, behind the Linux mascot. Hidden from the menu it
# opens, or it would list itself.
printf '[Desktop Entry]\nType=Application\nName=Apps\nComment=Every installed app\nExec=%s menu\nIcon=pocketdesk-linux\nTerminal=false\nNoDisplay=true\nX-PocketDesk=1\n' \
  "$WINDOWS" > "$LOCAL_APPS/pocketdesk-apps.desktop"
chmod 755 "$LOCAL_APPS/pocketdesk-apps.desktop"
launcher_lines="
launcher_item_app = $LOCAL_APPS/pocketdesk-apps.desktop"

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

# The phone's own files, as a folder on the desktop and a button on the panel. Empty but for a
# note until the owner turns Phone files on in PocketDesk's Settings; then Download, DCIM and
# Documents are in it. Super+P opens it too.
printf '[Desktop Entry]\nType=Application\nName=Phone files\nComment=Your phone\047s storage, inside the computer\nExec=pcmanfm /home/coder/Phone\nIcon=pocketdesk-phone\nTerminal=false\nX-PocketDesk=1\n' \
  > "$LOCAL_APPS/pocketdesk-phone.desktop"
chmod 755 "$LOCAL_APPS/pocketdesk-phone.desktop"
cp -f "$LOCAL_APPS/pocketdesk-phone.desktop" "$DESKTOP_DIR/pocketdesk-phone.desktop"
launcher_lines="$launcher_lines
launcher_item_app = $LOCAL_APPS/pocketdesk-phone.desktop"
chmod 755 "$DESKTOP_DIR"/*.desktop 2>/dev/null || true

{
  # L launchers, T open windows, S tray, E the phone's battery/temperature/memory, C clock.
  echo 'panel_items = LTSEC'
  echo 'panel_size = 100% 58'
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
  echo 'launcher_icon_size = 44'
  echo 'launcher_padding = 8 4 8'
  echo 'launcher_icon_theme = Adwaita'
  echo 'launcher_tooltip = 1'
  # The phone's own numbers, refreshed every 20 seconds (see pocketdesk-status).
  echo 'execp = new'
  echo 'execp_command = /usr/local/bin/pocketdesk-status'
  echo 'execp_interval = 20'
  echo 'execp_has_icon = 0'
  echo 'execp_continuous = 0'
  echo 'execp_markup = 0'
  echo 'execp_font = Sans 9'
  echo 'execp_font_color = #c2cae6 100'
  echo 'execp_padding = 10 0'
  echo 'execp_centered = 1'
  echo 'execp_tooltip = Battery, temperature, free memory and network of this phone'
  printf '%s\n' "$launcher_lines"
} > "$TINT2_DIR/tint2rc"

printf 'XDG_DESKTOP_DIR="$HOME/Desktop"\nXDG_DOCUMENTS_DIR="$HOME/Projects"\nXDG_DOWNLOAD_DIR="$HOME/Downloads"\n' \
  > "$HOME_DIR/.config/user-dirs.dirs"

# ---- Which app answers which kind of link -------------------------------------------
# http and https go to the browser -- through its wrapped entry, so Brave gets the sandbox
# flags a link needs just as a tap on its icon does. A sign-in that opens in the browser comes
# back to the app that asked for it through the app's own link scheme (chatgpt://, claude://,
# cursor://...), which each package declares in its .desktop file; those are copied here so
# the browser can find them. Without this table the login page said "done" and the app never
# heard.
browser_handler=$BROWSER_ENTRY
[ -n "$BROWSER_ENTRY" ] && [ -f "$LOCAL_APPS/pocketdesk-$BROWSER_ENTRY" ] && browser_handler="pocketdesk-$BROWSER_ENTRY"
{
  echo '[Default Applications]'
  if [ -n "$browser_handler" ]; then
    printf 'x-scheme-handler/http=%s\nx-scheme-handler/https=%s\ntext/html=%s\n' \
      "$browser_handler" "$browser_handler" "$browser_handler"
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
if command -v update-desktop-database >/dev/null 2>&1; then
  [ -w "$APPLICATIONS_DIR" ] && update-desktop-database "$APPLICATIONS_DIR" >/dev/null 2>&1
  update-desktop-database "$LOCAL_APPS" >/dev/null 2>&1 || true
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
#   - the keys the phone's toolbar and the panel send: Super+F4 force-closes the window in
#     front (for an app that stopped answering), Super+Tab lists the open windows, Super+P
#     opens Phone files, Super+A opens the apps menu, Super+R redraws the screen. Alt+F4 and
#     Alt+Tab are Openbox's own defaults and stay.
OPENBOX_DEFAULT=${POCKETDESK_OPENBOX_DEFAULT:-/etc/xdg/openbox/rc.xml}
if [ -f "$OPENBOX_DEFAULT" ]; then
  sed -e 's|<size>[0-9]*</size>|<size>11</size>|g' \
      -e 's|<titleLayout>[^<]*</titleLayout>|<titleLayout>CIMNL</titleLayout>|' \
      -e 's|<number>[0-9]*</number>|<number>1</number>|' \
      -e '/<keybind key="W-F[1-4]">/,/<\/keybind>/d' \
      -e 's|<applications>|<applications>\n    <application type="normal"><maximized>yes</maximized><decor>yes</decor></application>|' \
      -e 's|<keyboard>|<keyboard>\n    <keybind key="W-F4"><action name="Execute"><command>'"$WINDOWS"' kill-active</command></action></keybind>\n    <keybind key="W-Tab"><action name="Execute"><command>'"$WINDOWS"' list</command></action></keybind>\n    <keybind key="W-p"><action name="Execute"><command>pcmanfm /home/coder/Phone</command></action></keybind>\n    <keybind key="W-a"><action name="ShowMenu"><menu>root-menu</menu></action></keybind>\n    <keybind key="W-r"><action name="Execute"><command>'"$WINDOWS"' refresh</command></action></keybind>|' \
      "$OPENBOX_DEFAULT" > "$OPENBOX_DIR/rc.xml.new" \
    && mv -f "$OPENBOX_DIR/rc.xml.new" "$OPENBOX_DIR/rc.xml"
fi

chown -R coder:coder "$OPENBOX_DIR" "$TINT2_DIR" "$DESKTOP_DIR" "$LOCAL_APPS" \
  "$HOME_DIR/.config/user-dirs.dirs" "$HOME_DIR/.config/mimeapps.list" \
  "$HOME_DIR/Projects" "$HOME_DIR/Downloads" "$HOME_DIR/Phone" 2>/dev/null || true

# A desktop that is open right now gets the new list at once. This also runs as root from an
# install that happens while the desktop is open, so the panel is restarted as the desktop's
# own user: started as root it would read root's (empty) settings and come up blank.
if [ -S /tmp/.X11-unix/X1 ]; then
  DISPLAY=:1 openbox --reconfigure 2>/dev/null || true
  if pgrep -x tint2 >/dev/null 2>&1; then
    pkill -x tint2 2>/dev/null || true
    sleep 1
    if [ "$(id -u)" = 0 ] && command -v su >/dev/null 2>&1; then
      su coder -c 'DISPLAY=:1 setsid tint2 >/tmp/pocketdesk-tint2.log 2>&1 &' 2>/dev/null || true
    else
      DISPLAY=:1 setsid tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
    fi
  fi
fi
