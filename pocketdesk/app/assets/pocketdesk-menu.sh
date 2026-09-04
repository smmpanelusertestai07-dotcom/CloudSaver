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
         "$HOME_DIR/Projects" "$HOME_DIR/Downloads" "$HOME_DIR/Phone" \
         "$HOME_DIR/.config/pocketdesk" "$HOME_DIR/.themes"

# Which edge the bar lives on. The owner's choice, kept outside tint2rc because this script
# rewrites that file on every start; anything but "top" means the bottom. Read here, at the top,
# because the root menu offers the opposite edge long before the bar itself is written.
PANEL_AT=bottom
[ "$(cat "$HOME_DIR/.config/pocketdesk/panel-edge" 2>/dev/null)" = "top" ] && PANEL_AT=top

field() {   # field <file> <key>  -- the key as the main [Desktop Entry] group sets it
  awk -F= -v key="$2" '
    /^\[/ { group++ }
    group > 1 { exit }
    $1 == key { sub(/^[^=]*=/, ""); print; exit }
  ' "$1"
}

# The first token of an Exec line. The desktop-entry spec allows a quoted path, so splitting
# on whitespace returned "\"/opt/My" for anything installed in a directory with a space in it,
# and the app then vanished from the menu, the desktop and the panel.
exec_binary() {   # exec_binary <exec line>
  case "$1" in
    \"*) printf '%s' "${1#\"}" | cut -d'"' -f1 ;;
    "'"*) printf '%s' "${1#\'}" | cut -d"'" -f1 ;;
    *) printf '%s' "$1" | awk '{print $1}' ;;
  esac
}

is_tool() {   # is_tool <base>: belongs in the Tools submenu, not at the root of the menu
  case "$1" in
    pavucontrol|lxtask|xarchiver|mousepad|org.xfce.mousepad|gpicview|galculator|lxappearance) return 0 ;;
  esac
  return 1
}

runnable() {   # runnable <desktop file>: its Exec names a program that exists
  local exec_line binary
  exec_line=$(field "$1" Exec)
  [ -n "$exec_line" ] || return 1
  binary=$(exec_binary "$exec_line")
  case "$binary" in
    /*) [ -x "$binary" ] ;;
    *) command -v "$binary" >/dev/null 2>&1 ;;
  esac
}

is_visible() {
  # mousepad ships a second entry, "Text Editor Settings", with no NoDisplay line of its own.
  case "$(basename "$1")" in org.xfce.mousepad-settings.desktop) return 1 ;; esac
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
    org.xfce.mousepad|mousepad) printf 'Text Editor' ;;
    xarchiver) printf 'Archives' ;;
    gpicview) printf 'Pictures' ;;
    galculator) printf 'Calculator' ;;
    lxtask) printf 'Task manager' ;;
    lxappearance) printf 'Appearance' ;;
    pavucontrol) printf 'Volume and sound' ;;
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
  echo '<menu id="root-menu" label="PocketDesk - Ubuntu 24.04 LTS">'
  found=0
  tool_items=""
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
    line=$(printf '  <item label="%s"><action name="Execute"><command>%s</command></action></item>' \
      "$(xml_escape "$name")" "$(xml_escape "$command")")
    if is_tool "$(basename "$desktop" .desktop)"; then
      tool_items="$tool_items$line
"
    else
      printf '%s\n' "$line"
    fi
    found=1
  done <<EOF
$(entries)
EOF
  [ "$found" = 1 ] || echo '  <item label="No apps yet"><action name="Execute"><command>true</command></action></item>'
  # Everything that is a tool rather than an app goes one level down, so the four AI apps stay
  # at the top of a menu opened with a thumb.
  echo '  <menu id="tools-menu" label="Tools">'
  [ -n "$tool_items" ] && printf '%s' "$tool_items"
  echo '    <item label="Screenshot"><action name="Execute"><command>/usr/local/bin/pocketdesk-shot screen</command></action></item>'
  echo '    <item label="Screenshot (window in front)"><action name="Execute"><command>/usr/local/bin/pocketdesk-shot window</command></action></item>'
  echo '    <item label="Storage"><action name="Execute"><command>/usr/local/bin/pocketdesk-storage</command></action></item>'
  echo '    <item label="Appshot to the AI app (Super+Space)"><action name="Execute"><command>/usr/local/bin/pocketdesk-appshot</command></action></item>'
  echo '    <item label="AI computer use"><action name="Execute"><command>/usr/local/bin/pocketdesk-agent status</command></action></item>'
  echo '    <separator/>'
  echo '    <menu id="mobile-menu" label="Phone app testing">'
  echo '      <item label="How this works"><action name="Execute"><command>zenity --info --no-markup --width=500 --title="Phone app testing" --text="This computer can install and test an Android app on a real phone — including the one it is running on.\n\nAndroid 11 and later have Wireless debugging, and this computer shares the phone network, so 127.0.0.1 reaches this very phone. Build an APK here, install it here, and it opens on this screen.\n\nAnother phone on the same Wi-Fi works the same way, with its own address.\n\nAn Android EMULATOR cannot run here: it needs hardware virtualisation, which no app on an unrooted phone can have. A real phone is the test device."</command></action></item>'
  echo '      <item label="Pair a phone"><action name="Execute"><command>/usr/local/bin/pocketdesk-adb pair</command></action></item>'
  echo '      <item label="Connect"><action name="Execute"><command>/usr/local/bin/pocketdesk-adb connect</command></action></item>'
  echo '      <item label="Install an APK"><action name="Execute"><command>/usr/local/bin/pocketdesk-adb install</command></action></item>'
  echo '      <item label="Logs (logcat)"><action name="Execute"><command>/usr/local/bin/pocketdesk-adb logs</command></action></item>'
  echo '      <item label="Mirror the phone screen"><action name="Execute"><command>/usr/local/bin/pocketdesk-adb screen</command></action></item>'
  echo '      <item label="What is connected"><action name="Execute"><command>/usr/local/bin/pocketdesk-adb status</command></action></item>'
  echo '    </menu>'
  echo '    <menu id="windows-menu" label="Windows apps">'
  echo '      <item label="How this works"><action name="Execute"><command>zenity --info --no-markup --width=470 --title="Windows apps" --text="This computer can also run Windows programs that were built for ARM64 processors, through Wine.\n\nDownload one in the browser, then open it with Install a downloaded app. PocketDesk reads which processor it was built for FIRST: ARM64 installs, Intel-only is refused before anything is unpacked.\n\nWindows apps here are experimental. Nothing on the Linux side is affected either way."</command></action></item>'
  echo '      <item label="Get Cursor for Windows (ARM64)"><action name="Execute"><command>xdg-open https://cursor.com/download</command></action></item>'
  echo '      <item label="Get Antigravity for Windows (ARM64)"><action name="Execute"><command>xdg-open https://antigravity.google/download</command></action></item>'
  echo '      <item label="Get Claude for Windows (ARM64)"><action name="Execute"><command>xdg-open https://claude.com/download</command></action></item>'
  echo '      <item label="Get ChatGPT for Windows"><action name="Execute"><command>xdg-open https://chatgpt.com/download</command></action></item>'
  echo '      <item label="Install a downloaded app"><action name="Execute"><command>/usr/local/bin/pocketdesk-install</command></action></item>'
  echo '      <item label="Windows apps installed"><action name="Execute"><command>lxterminal -e bash -lc "/usr/local/bin/pocketdesk-winapp list; echo; /usr/local/bin/pocketdesk-winapp version; echo; read -p \"Press Enter to close \""</command></action></item>'
  echo '    </menu>'
  echo '  </menu>' 
  echo '  <separator label="Folders"/>'
  echo '  <item label="Phone files"><action name="Execute"><command>pcmanfm /home/coder/Phone</command></action></item>'
  echo '  <item label="Projects"><action name="Execute"><command>pcmanfm /home/coder/Projects</command></action></item>'
  echo '  <item label="Downloads"><action name="Execute"><command>pcmanfm /home/coder/Downloads</command></action></item>'
  echo '  <item label="App reports"><action name="Execute"><command>pcmanfm /home/coder/.pocketdesk/logs</command></action></item>'
  echo '  <separator label="Windows"/>'
  echo '  <item label="Open windows"><action name="Execute"><command>'"$WINDOWS"' list</command></action></item>'
  echo '  <item label="Fit window to the screen"><action name="Execute"><command>'"$WINDOWS"' fit</command></action></item>'
  echo '  <item label="Minimise this window"><action name="Execute"><command>'"$WINDOWS"' minimise</command></action></item>'
  echo '  <item label="Minimise all"><action name="ToggleShowDesktop"/></item>'
  echo '  <item label="Close all"><action name="Execute"><command>'"$WINDOWS"' close-all</command></action></item>'
  echo '  <separator label="Desktop"/>'
  echo '  <item label="Install a downloaded app"><action name="Execute"><command>/usr/local/bin/pocketdesk-install</command></action></item>'
  echo '  <item label="Terminal"><action name="Execute"><command>lxterminal</command></action></item>'
  echo '  <item label="Reload screen"><action name="Execute"><command>'"$WINDOWS"' refresh</command></action></item>'
  echo '  <item label="Refresh app list"><action name="Execute"><command>/usr/local/bin/pocketdesk-menu</command></action></item>'
  if [ "$PANEL_AT" = "top" ]; then
    echo '  <item label="Move the bar to the bottom"><action name="Execute"><command>'"$WINDOWS"' panel-edge bottom</command></action></item>'
  else
    echo '  <item label="Move the bar to the top"><action name="Execute"><command>'"$WINDOWS"' panel-edge top</command></action></item>'
  fi
  echo '</menu>'
  echo '</openbox_menu>'
} > "$OPENBOX_DIR/menu.xml"

# ---- Desktop icons and panel launchers: the favourites, using their own entries -----
# Only the entries this script wrote (they all carry X-PocketDesk=1). A blanket delete took
# away anything the owner had put on their own desktop, every time an app was installed.
for stale in "$DESKTOP_DIR"/*.desktop; do
  [ -f "$stale" ] || continue
  grep -q '^X-PocketDesk=1' "$stale" 2>/dev/null && rm -f "$stale"
done
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
        binary) candidate=$(basename "$(exec_binary "$(field "$desktop" Exec)")") ;;
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

# The installer that a downloaded app package opens into. Two jobs: it is the handler for
# .deb files (Chrome's Open, and a double tap in the file manager), and it is a launcher of its
# own so an app can be installed without finding the file first.
printf '[Desktop Entry]\nType=Application\nName=Install a downloaded app\nComment=Check and install a Linux app package (.deb) you downloaded\nExec=/usr/local/bin/pocketdesk-install %%f\nIcon=pocketdesk-linux\nTerminal=false\nX-PocketDesk=1\nMimeType=application/vnd.debian.binary-package;application/x-deb;application/x-debian-package;\n' \
  > "$LOCAL_APPS/pocketdesk-install.desktop"
chmod 755 "$LOCAL_APPS/pocketdesk-install.desktop"

# The phone's own files, as a folder on the desktop and a button on the panel. Empty but for a
# note until the owner turns Phone files on in PocketDesk's Settings; then Download, DCIM and
# Documents are in it. Super+P opens it too.
printf '[Desktop Entry]\nType=Application\nName=Phone files\nComment=Your phone\047s storage, inside the computer\nExec=pcmanfm /home/coder/Phone\nIcon=pocketdesk-phone\nTerminal=false\nX-PocketDesk=1\n' \
  > "$LOCAL_APPS/pocketdesk-phone.desktop"
chmod 755 "$LOCAL_APPS/pocketdesk-phone.desktop"
cp -f "$LOCAL_APPS/pocketdesk-phone.desktop" "$DESKTOP_DIR/pocketdesk-phone.desktop"
chmod 755 "$DESKTOP_DIR"/*.desktop 2>/dev/null || true

# tint2 draws its text at a fixed 96 dpi while windows, menus and titles draw at Xft.dpi, so the
# point sizes are converted here and the bar matches the rest of the desktop at any screen size
# the owner picks. A missing or odd .Xresources falls straight back to PocketDesk's own default.
DPI=$(awk -F: '/^Xft\.dpi:/ { gsub(/[^0-9]/, "", $2); print $2; exit }' "$HOME_DIR/.Xresources" 2>/dev/null)
case "${DPI:-}" in ''|*[!0-9]*) DPI=120 ;; esac
[ "$DPI" -lt 96 ] && DPI=96
[ "$DPI" -gt 240 ] && DPI=240
pt() { echo $(( $1 * DPI / 96 )); }    # a point size that looked right on a 96 dpi screen
px() { echo $(( $1 * DPI / 120 )); }   # a length that looked right at PocketDesk's default 120 dpi


# Which apps get a slot on the bar. The four AI apps are deliberately not here: a 720-pixel bar
# has room for either nine launchers or the buttons of the windows that are open, and once an app
# is open its own button is what you need. They keep their desktop icons, the Apps menu (the Tux
# button, Super+A, a long press on the wallpaper) and PocketDesk's own Apps tab.
panel_lines=""
for base in ${BROWSER_BASE:-} pcmanfm lxterminal; do
  [ -n "$base" ] || continue
  [ -f "$LOCAL_APPS/pocketdesk-$base.desktop" ] || continue
  panel_lines="$panel_lines
launcher_item_app = $LOCAL_APPS/pocketdesk-$base.desktop"
done

panel_lines="$panel_lines
launcher_item_app = $LOCAL_APPS/pocketdesk-phone.desktop"

MARK=/usr/share/pixmaps/pocketdesk-mark.png
[ -f "$MARK" ] || MARK=/usr/share/pixmaps/pocketdesk-linux.png

{
  # Background 0 first, and on purpose. tint2 seeds exactly one background before it reads this
  # file, and it is the one every element falls back to, so these two lines (which have no
  # "rounded" above them and therefore edit background 0 itself) keep it the panel's own colour:
  # anything whose id is wrong still looks right instead of going see-through.
  echo 'border_width = 0'
  echo 'background_color = #0f1327 100'
  # Every real background starts with "rounded" -- that is the line tint2 uses to begin a new
  # definition -- and all five come BEFORE the first *_background_id below, because tint2
  # resolves an id the moment it reads it and an id it has not met yet silently becomes 0.
  echo 'rounded = 0'          # 1 the bar itself
  echo 'border_width = 1'
  echo 'border_sides = T'
  echo 'background_color = #0f1327 100'
  echo 'border_color = #232a49 100'
  echo 'rounded = 8'          # 2 an app that is open, behind
  echo 'border_width = 1'
  echo 'background_color = #161d40 100'
  echo 'border_color = #2b3563 100'
  echo 'rounded = 8'          # 3 the app in front
  echo 'border_width = 1'
  echo 'background_color = #26326e 100'
  echo 'border_color = #5878d8 100'
  echo 'rounded = 8'          # 4 an app that is minimised
  echo 'border_width = 1'
  echo 'background_color = #10163a 100'
  echo 'border_color = #202a52 100'
  echo 'rounded = 10'         # 5 tooltips
  echo 'border_width = 1'
  echo 'background_color = #101a2e 100'
  echo 'border_color = #2b3563 100'
  # L launchers (Tux Apps in the corner, then browser, Files, Terminal, Phone files), T the open
  # windows, S tray, E the phone's own numbers, C clock, P the PocketDesk mark in the far corner.
  echo 'panel_items = LTSECP'
  echo "panel_position = $PANEL_AT center horizontal"
  echo 'panel_layer = top'
  echo 'strut_policy = follow_size'
  echo 'autohide = 0'
  echo 'wm_menu = 1'
  echo 'disable_transparency = 1'
  echo 'panel_background_id = 1'
  echo "panel_size = 100% $(px 58)"
  echo 'panel_margin = 0 0'
  echo "panel_padding = $(px 2) $(px 2) $(px 6)"
  echo 'panel_window_name = PocketDesk'
  echo 'font_shadow = 0'
  echo 'scale_relative_to_dpi = 0'
  echo 'scale_relative_to_screen_height = 0'
  echo 'mouse_effects = 0'
  echo 'urgent_nb_of_blink = 0'
  # The window list. Icon only: a name will not fit beside the launchers on a 720-pixel bar, and
  # tint2 shows a task's name only in a tooltip, which a finger cannot ask for. A tap raises the
  # app and never minimises it by accident; a long press (button 3 in Finger mode) minimises.
  echo 'taskbar_mode = single_desktop'
  echo 'taskbar_name = 0'
  echo 'taskbar_hide_if_empty = 0'
  echo "taskbar_padding = $(px 2) 0 $(px 4)"
  echo 'taskbar_background_id = 0'
  echo 'taskbar_active_background_id = 0'
  echo 'taskbar_sort_order = none'
  echo 'task_align = left'
  echo 'task_icon = 1'
  echo 'task_text = 0'
  echo 'task_centered = 1'
  echo 'task_tooltip = 0'
  echo 'task_thumbnail = 0'
  echo "task_maximum_size = $(px 46) $(px 46)"
  echo "task_padding = $(px 3) $(px 3) $(px 4)"
  echo 'task_background_id = 2'
  echo 'task_active_background_id = 3'
  echo 'task_iconified_background_id = 4'
  echo 'task_urgent_background_id = 3'
  echo 'mouse_left = toggle'
  echo 'mouse_middle = none'
  echo 'mouse_right = toggle_iconify'
  echo 'mouse_scroll_up = none'
  echo 'mouse_scroll_down = none'
  # 12-hour clock, so 18:06 reads as 06:06 pm. A tap opens the full list of open windows, which
  # is what makes the bar's own limit of three or four buttons acceptable.
  echo 'time1_format = %I:%M %P'
  echo 'time2_format = %a %d %b'
  echo "time1_font = Sans Bold $(pt 11)"
  echo "time2_font = Sans $(pt 8)"
  echo 'clock_font_color = #e6ecf7 100'
  echo "clock_padding = $(px 8) $(px 2)"
  echo 'clock_background_id = 0'
  echo 'clock_tooltip = %A %d %B %Y, %I:%M %P'
  echo 'clock_lclick_command = /usr/local/bin/pocketdesk-windows list'
  echo "systray_padding = $(px 6) $(px 2) $(px 6)"
  echo "systray_icon_size = $(px 24)"
  echo 'systray_background_id = 0'
  echo 'systray_sort = left2right'
  echo "launcher_icon_size = $(px 40)"
  echo "launcher_padding = $(px 6) $(px 2) $(px 8)"
  echo 'launcher_icon_theme = Adwaita'
  echo 'launcher_icon_theme_override = 1'
  echo 'launcher_tooltip = 1'
  echo 'launcher_background_id = 0'
  echo 'launcher_icon_background_id = 0'
  echo 'startup_notifications = 0'
  echo "tooltip_padding = $(px 10) $(px 6)"
  echo 'tooltip_show_timeout = 0.7'
  echo 'tooltip_hide_timeout = 0.2'
  echo 'tooltip_background_id = 5'
  echo "tooltip_font = Sans $(pt 10)"
  echo 'tooltip_font_color = #e6ecf7 100'
  # The phone's own numbers, two short lines, refreshed every 30 seconds. There is no
  # execp_tooltip line on purpose: without one tint2 shows the command's standard error as the
  # tooltip, and pocketdesk-status writes the full sentence there. A tap opens Storage.
  echo 'execp = new'
  echo 'execp_command = /usr/local/bin/pocketdesk-status'
  echo 'execp_interval = 30'
  echo 'execp_has_icon = 0'
  echo 'execp_continuous = 0'
  echo 'execp_markup = 0'
  echo "execp_font = Sans $(pt 9)"
  echo 'execp_font_color = #c2cae6 100'
  echo "execp_padding = $(px 6) 0 0"
  echo 'execp_centered = 1'
  echo 'execp_background_id = 0'
  echo 'execp_lclick_command = /usr/local/bin/pocketdesk-storage'
  # The far corner: PocketDesk's own mark, doing a real job -- show the desktop, tap again to
  # bring the windows back -- opposite Tux in the other corner. Exactly one "P" above, so
  # exactly one button block here.
  echo 'button = new'
  echo "button_icon = $MARK"
  echo 'button_tooltip = PocketDesk - show the desktop'
  echo "button_padding = $(px 8) 0 0"
  echo "button_max_icon_size = $(px 26)"
  echo 'button_background_id = 0'
  echo 'button_centered = 1'
  echo 'button_lclick_command = /usr/local/bin/pocketdesk-windows minimise-all'
  echo 'button_rclick_command = /usr/local/bin/pocketdesk-windows list'
  printf '%s\n' "$launcher_lines"
  printf '%s\n' "$panel_lines"
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
  # A downloaded app package opens PocketDesk's installer, the way tapping an APK opens
  # Android's. Without this line the file does nothing at all when it is tapped.
  printf 'application/vnd.debian.binary-package=pocketdesk-install.desktop\n'
  printf 'application/x-deb=pocketdesk-install.desktop\n'
  printf 'application/x-debian-package=pocketdesk-install.desktop\n'
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

# The window decorations, in the app's own colours. A themerc is text only -- Openbox draws its
# built-in button glyphs when a theme ships no .xbm -- and if this file is ever unreadable
# Openbox logs one line and falls back to Clearlooks, which is where we are today.
mkdir -p "$HOME_DIR/.themes/PocketDesk/openbox-3"
cat > "$HOME_DIR/.themes/PocketDesk/openbox-3/themerc" <<'THEMERC'
! PocketDesk -- the same palette as the phone app (see Ui.java).
border.width: 1
padding.width: 6
padding.height: 8
window.handle.width: 0
window.client.padding.width: 0
window.client.padding.height: 0
window.label.text.justify: left
menu.border.width: 1
menu.overlap.x: 0
menu.overlap.y: 0
menu.separator.width: 1
menu.separator.padding.width: 6
menu.separator.padding.height: 4
osd.border.width: 1

window.active.title.bg: flat solid
window.active.title.bg.color: #16213f
window.active.label.bg: parentrelative
window.active.label.text.color: #f1f5fb
window.active.title.separator.color: #1746c4
window.active.border.color: #1746c4
window.active.client.color: #16213f
window.active.handle.bg: flat solid
window.active.handle.bg.color: #16213f
window.active.grip.bg: flat solid
window.active.grip.bg.color: #16213f
window.active.button.unpressed.bg: parentrelative
window.active.button.unpressed.image.color: #c2cae6
window.active.button.hover.bg: flat solid
window.active.button.hover.bg.color: #1746c4
window.active.button.hover.image.color: #ffffff
window.active.button.pressed.bg: flat solid
window.active.button.pressed.bg.color: #7a9bff
window.active.button.pressed.image.color: #0b1320
window.active.button.disabled.bg: parentrelative
window.active.button.disabled.image.color: #55607d
window.active.button.close.unpressed.image.color: #ff9aa5
window.active.button.close.hover.bg: flat solid
window.active.button.close.hover.bg.color: #7a2436
window.active.button.close.hover.image.color: #ffffff

window.inactive.title.bg: flat solid
window.inactive.title.bg.color: #0d1526
window.inactive.label.bg: parentrelative
window.inactive.label.text.color: #9aa7bd
window.inactive.title.separator.color: #23304a
window.inactive.border.color: #23304a
window.inactive.client.color: #0d1526
window.inactive.handle.bg: flat solid
window.inactive.handle.bg.color: #0d1526
window.inactive.grip.bg: flat solid
window.inactive.grip.bg.color: #0d1526
window.inactive.button.unpressed.bg: parentrelative
window.inactive.button.unpressed.image.color: #6b7690
window.inactive.button.hover.bg: flat solid
window.inactive.button.hover.bg.color: #23304a
window.inactive.button.hover.image.color: #e6ecf7
window.inactive.button.pressed.bg: flat solid
window.inactive.button.pressed.bg.color: #7a9bff
window.inactive.button.pressed.image.color: #0b1320
window.inactive.button.disabled.bg: parentrelative
window.inactive.button.disabled.image.color: #3f4863

menu.border.color: #23304a
menu.title.bg: flat solid
menu.title.bg.color: #0f1327
menu.title.text.color: #7a9bff
menu.title.text.justify: left
menu.items.bg: flat solid
menu.items.bg.color: #101a2e
menu.items.text.color: #e6ecf7
menu.items.disabled.text.color: #6b7690
menu.items.active.bg: flat solid
menu.items.active.bg.color: #1746c4
menu.items.active.text.color: #ffffff
menu.separator.color: #23304a

osd.bg: flat solid
osd.bg.color: #101a2e
osd.border.color: #23304a
osd.label.bg: parentrelative
osd.label.text.color: #e6ecf7
osd.hilight.bg: flat solid
osd.hilight.bg.color: #1746c4
osd.unhilight.bg: flat solid
osd.unhilight.bg.color: #23304a
THEMERC

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
#   - the title bar carries the app's icon, close, minimise and the name (ICNL): a maximised
#     window starts at the left edge, so both buttons are always on screen even for an app whose
#     smallest width is wider than a portrait phone. There is no maximise button -- every window
#     opens maximised, so it could only make a floating window a phone cannot put back, and
#     Super+F does that job instead;
#   - every font is 14, not 11: Openbox sizes the title buttons from the window font alone, and
#     at 11 they were about two millimetres on this screen. The root menu gains the same way;
#   - the decorations use PocketDesk's own theme, written above; an unreadable themerc costs one
#     line in Openbox's log and falls back to the theme Ubuntu ships;
#   - the keys the phone's toolbar and the panel send: Super+F4 force-closes the window in
#     front (for an app that stopped answering), Super+Tab lists the open windows, Super+P
#     opens Phone files, Super+A opens the apps menu, Super+R redraws the screen, Super+M
#     minimises, Super+F fits the window to the screen, Super+S takes a screenshot. Alt+F4 and
#     Alt+Tab are Openbox's own defaults and stay.
OPENBOX_DEFAULT=${POCKETDESK_OPENBOX_DEFAULT:-/etc/xdg/openbox/rc.xml}
if [ -f "$OPENBOX_DEFAULT" ]; then
  sed -e 's|<size>[0-9]*</size>|<size>14</size>|g' \
      -e 's|<titleLayout>[^<]*</titleLayout>|<titleLayout>ICNL</titleLayout>|' \
      -e 's|<theme>|<theme>\n    <name>PocketDesk</name>|' \
      -e 's|<animateIconify>yes</animateIconify>|<animateIconify>no</animateIconify>|' \
      -e 's|<number>[0-9]*</number>|<number>1</number>|' \
      -e '/<keybind key="W-F[1-4]">/,/<\/keybind>/d' \
      -e 's|<applications>|<applications>\n    <application type="normal"><maximized>yes</maximized><decor>yes</decor></application>|' \
      -e 's|<keyboard>|<keyboard>\n    <keybind key="W-F4"><action name="Execute"><command>'"$WINDOWS"' kill-active</command></action></keybind>\n    <keybind key="W-Tab"><action name="Execute"><command>'"$WINDOWS"' list</command></action></keybind>\n    <keybind key="W-p"><action name="Execute"><command>pcmanfm /home/coder/Phone</command></action></keybind>\n    <keybind key="W-a"><action name="ShowMenu"><menu>root-menu</menu></action></keybind>\n    <keybind key="W-r"><action name="Execute"><command>'"$WINDOWS"' refresh</command></action></keybind>\n    <keybind key="W-m"><action name="Execute"><command>'"$WINDOWS"' minimise</command></action></keybind>\n    <keybind key="W-f"><action name="Execute"><command>'"$WINDOWS"' fit</command></action></keybind>\n    <keybind key="W-s"><action name="Execute"><command>/usr/local/bin/pocketdesk-shot screen</command></action></keybind>\n    <keybind key="W-space"><action name="Execute"><command>/usr/local/bin/pocketdesk-appshot</command></action></keybind>|' \
      "$OPENBOX_DEFAULT" > "$OPENBOX_DIR/rc.xml.new" \
    && mv -f "$OPENBOX_DIR/rc.xml.new" "$OPENBOX_DIR/rc.xml"
fi

chown -R coder:coder "$OPENBOX_DIR" "$TINT2_DIR" "$DESKTOP_DIR" "$LOCAL_APPS" \
  "$HOME_DIR/.config/pocketdesk" "$HOME_DIR/.themes" \
  "$HOME_DIR/.config/user-dirs.dirs" "$HOME_DIR/.config/mimeapps.list" \
  "$HOME_DIR/Projects" "$HOME_DIR/Downloads" "$HOME_DIR/Phone" 2>/dev/null || true

# A desktop that is open right now gets the new list at once. This also runs as root from an
# install that happens while the desktop is open, so the panel is restarted as the desktop's
# own user: started as root it would read root's (empty) settings and come up blank.
# The desktop's own X server must be answering -- a stale socket outlives an unclean stop, and
# starting anything against it hangs. xdpyinfo is in x11-utils, which set-up installs.
display_live() {
  [ -S /tmp/.X11-unix/X1 ] || return 1
  command -v xdpyinfo >/dev/null 2>&1 || return 0
  DISPLAY=:1 xdpyinfo >/dev/null 2>&1
}

if display_live; then
  DISPLAY=:1 openbox --reconfigure 2>/dev/null || true
  if pgrep -x tint2 >/dev/null 2>&1; then
    # tint2 re-reads its settings on SIGUSR1. It is never restarted from here: when this script
    # runs from an app install it is inside that install's own short-lived container, started
    # with --kill-on-exit, so a panel started here would be killed the moment the install
    # finished -- and the desktop would sit with no panel until it was closed and opened again.
    pkill -USR1 -x tint2 2>/dev/null || true
  elif [ "$(id -u)" != 0 ]; then
    # No panel at all (it crashed, or the phone killed it for memory) and we are the desktop's
    # own user, so a replacement started here belongs to the session and survives.
    DISPLAY=:1 setsid tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
  fi
fi
