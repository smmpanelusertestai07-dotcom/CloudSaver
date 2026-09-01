#!/bin/bash
# Starts the Linux desktop that PocketDesk shows. Called as:
#   pocketdesk-desktop <width>x<height> <dpi>
set -u
GEOMETRY=${1:-1280x720}
DPI=${2:-160}
export HOME=/home/coder USER=coder LOGNAME=coder DISPLAY=:1 LANG=C.UTF-8
export XDG_CONFIG_HOME="$HOME/.config" XDG_DATA_HOME="$HOME/.local/share"
# PRoot cannot provide the process isolation these sandboxes need, so they fail closed and take
# the app with them -- Firefox's "Gah. Your tab just crashed", Electron refusing to start at all.
export MOZ_FAKE_NO_SANDBOX=1 MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_DISABLE_GMP_SANDBOX=1
export MOZ_DISABLE_RDD_SANDBOX=1 MOZ_DISABLE_SOCKET_PROCESS=1 MOZ_ENABLE_WAYLAND=0
export ELECTRON_DISABLE_SANDBOX=1 ELECTRON_DISABLE_SECURITY_WARNINGS=1
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
cd "$HOME"
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
mkdir -p "$HOME/.config/gtk-3.0" "$HOME/.config/lxterminal" "$HOME/.config/tint2" \
         "$HOME/.config/openbox" "$HOME/.config/pcmanfm/LXDE" "$HOME/.config/libfm" \
         "$HOME/.icons/default" "$HOME/Desktop" "$HOME/Projects"

# A real DPI is what makes text large without blurring it: the desktop renders at the phone's
# own pixel count and only the type and controls grow.
printf 'Xft.dpi: %s\nXft.antialias: true\nXft.hinting: true\nXft.hintstyle: hintslight\nXft.rgba: rgb\nXcursor.theme: Adwaita\nXcursor.size: 32\n' \
  "$DPI" > "$HOME/.Xresources"

printf '[Settings]\ngtk-font-name=Sans 11\ngtk-application-prefer-dark-theme=1\ngtk-xft-dpi=%s\ngtk-icon-theme-name=Adwaita\ngtk-theme-name=Adwaita-dark\ngtk-cursor-theme-name=Adwaita\ngtk-cursor-theme-size=32\n' \
  "$((DPI * 1024))" > "$HOME/.config/gtk-3.0/settings.ini"

# A normal arrow instead of the old X11 cross.
printf '[Icon Theme]\nName=Default\nComment=Default cursor\nInherits=Adwaita\n' \
  > "$HOME/.icons/default/index.theme"

printf '[general]\nfontname=Monospace 12\nscrollback=5000\nbgcolor=rgb(16,24,40)\nfgcolor=rgb(226,232,245)\ngeometry_columns=100\ngeometry_rows=30\nhidescrollbar=false\ndisallowbold=false\n' \
  > "$HOME/.config/lxterminal/lxterminal.conf"

# Without this, opening a desktop icon raises PCManFM's "this seems to be an executable
# script - what do you want to do with it?" prompt instead of just launching the app.
printf '[config]\nquick_exec=1\nsingle_click=1\nconfirm_del=1\nterminal=lxterminal\n' \
  > "$HOME/.config/libfm/libfm.conf"

printf '[*]\nwallpaper_mode=stretch\nwallpaper=/usr/share/backgrounds/pocketdesk.png\ndesktop_bg=#101828\ndesktop_fg=#e6ecf7\ndesktop_shadow=#000000\nshow_documents=1\nshow_trash=0\nshow_mounts=0\ndesktop_font=Sans 11\n' \
  > "$HOME/.config/pcmanfm/LXDE/desktop-items-0.conf"
printf '[config]\nbm_open_method=0\n[volume]\nmount_on_startup=0\nmount_removable=0\n[ui]\nalways_show_tabs=1\nmax_tab_chars=32\n' \
  > "$HOME/.config/pcmanfm/LXDE/pcmanfm.conf"

# Start from Openbox's own defaults, then enlarge the fonts and open every window maximised --
# on a phone-sized screen a floating half-size window is wasted space.
if [ ! -f "$HOME/.config/openbox/rc.xml" ] && [ -f /etc/xdg/openbox/rc.xml ]; then
  cp /etc/xdg/openbox/rc.xml "$HOME/.config/openbox/rc.xml"
  sed -i 's|<size>[0-9]*</size>|<size>11</size>|g' "$HOME/.config/openbox/rc.xml"
  sed -i 's|<applications>|<applications>\n    <application class="*"><maximized>yes</maximized></application>|' \
    "$HOME/.config/openbox/rc.xml"
fi

# Firefox: no sandbox, no separate content processes, software rendering.
FIREFOX_PROFILE=$(find "$HOME/.mozilla/firefox" -maxdepth 1 -name '*.default*' -type d 2>/dev/null | head -n 1)
if [ -z "${FIREFOX_PROFILE:-}" ] && command -v firefox >/dev/null 2>&1; then
  mkdir -p "$HOME/.mozilla/firefox/pocketdesk.default"
  printf '[Profile0]\nName=default\nIsRelative=1\nPath=pocketdesk.default\nDefault=1\n\n[General]\nStartWithLastProfile=1\nVersion=2\n' \
    > "$HOME/.mozilla/firefox/profiles.ini"
  FIREFOX_PROFILE="$HOME/.mozilla/firefox/pocketdesk.default"
fi
if [ -n "${FIREFOX_PROFILE:-}" ]; then
  cat > "$FIREFOX_PROFILE/user.js" <<'PREFS'
user_pref("security.sandbox.content.level", 0);
user_pref("browser.tabs.remote.autostart", false);
user_pref("fission.autostart", false);
user_pref("dom.ipc.processCount", 1);
user_pref("gfx.webrender.software", true);
user_pref("layers.acceleration.disabled", true);
user_pref("media.hardware-video-decoding.enabled", false);
user_pref("browser.shell.checkDefaultBrowser", false);
user_pref("datareporting.policy.dataSubmissionEnabled", false);
user_pref("toolkit.telemetry.reportingpolicy.firstRun", false);
user_pref("browser.aboutwelcome.enabled", false);
user_pref("browser.download.dir", "/home/coder/Downloads");
user_pref("browser.download.folderList", 2);
PREFS
fi

/usr/local/bin/pocketdesk-menu || true

/usr/bin/Xtigervnc :1 -rfbport 5901 -localhost yes -SecurityTypes None -ac -AlwaysShared \
  -geometry "$GEOMETRY" -depth 24 -dpi "$DPI" -desktop 'PocketDesk' &
VNC_PID=$!
for n in 1 2 3 4 5 6 7 8; do [ -S /tmp/.X11-unix/X1 ] && break; sleep 0.5; done

xrdb -merge "$HOME/.Xresources" >/dev/null 2>&1 || true
xsetroot -cursor_name left_ptr >/dev/null 2>&1 || true
eval "$(dbus-launch --sh-syntax)"
openbox-session >/tmp/pocketdesk-openbox.log 2>&1 &
tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
pcmanfm --desktop --profile LXDE >/tmp/pocketdesk-pcmanfm.log 2>&1 &
wait "$VNC_PID"
