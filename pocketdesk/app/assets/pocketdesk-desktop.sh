#!/bin/bash
# Starts the Linux desktop that PocketDesk shows. Called as:
#   pocketdesk-desktop <width>x<height> <dpi>
set -u
GEOMETRY=${1:-1280x720}
DPI=${2:-160}
export HOME=/home/coder USER=coder LOGNAME=coder DISPLAY=:1 LANG=C.UTF-8
cd "$HOME"
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
mkdir -p "$HOME/.config/gtk-3.0" "$HOME/.config/lxterminal" "$HOME/.config/tint2" \
         "$HOME/.config/openbox" "$HOME/.config/pcmanfm/LXDE" "$HOME/Desktop"

# A real DPI is what makes text large without blurring it: the desktop renders at the phone's
# own pixel count and only the type and controls grow.
printf 'Xft.dpi: %s\nXft.antialias: true\nXft.hinting: true\nXft.hintstyle: hintslight\nXft.rgba: rgb\n' \
  "$DPI" > "$HOME/.Xresources"

printf '[Settings]\ngtk-font-name=Sans 11\ngtk-application-prefer-dark-theme=1\ngtk-xft-dpi=%s\n' \
  "$((DPI * 1024))" > "$HOME/.config/gtk-3.0/settings.ini"

printf '[general]\nfontname=Monospace 12\nscrollback=4000\nbgcolor=rgb(16,24,40)\nfgcolor=rgb(226,232,245)\ngeometry_columns=100\ngeometry_rows=30\nhidescrollbar=false\ndisallowbold=false\n' \
  > "$HOME/.config/lxterminal/lxterminal.conf"

# A dark desktop with visible icon labels, instead of pure black.
printf '[*]\nwallpaper_mode=color\ndesktop_bg=#101828\ndesktop_fg=#e6ecf7\ndesktop_shadow=#000000\nshow_documents=1\nshow_trash=0\nshow_mounts=0\ndesktop_font=Sans 11\n' \
  > "$HOME/.config/pcmanfm/LXDE/desktop-items-0.conf"

# Start from Openbox's own defaults, then enlarge the fonts and open every window maximised --
# on a phone-sized screen a floating half-size window is wasted space.
if [ ! -f "$HOME/.config/openbox/rc.xml" ] && [ -f /etc/xdg/openbox/rc.xml ]; then
  cp /etc/xdg/openbox/rc.xml "$HOME/.config/openbox/rc.xml"
  sed -i 's|<size>[0-9]*</size>|<size>11</size>|g' "$HOME/.config/openbox/rc.xml"
  sed -i 's|<applications>|<applications>\n    <application class="*"><maximized>yes</maximized></application>|' \
    "$HOME/.config/openbox/rc.xml"
fi

/usr/local/bin/pocketdesk-menu || true

/usr/bin/Xtigervnc :1 -rfbport 5901 -localhost yes -SecurityTypes None -ac -AlwaysShared \
  -geometry "$GEOMETRY" -depth 24 -dpi "$DPI" -desktop 'PocketDesk' &
VNC_PID=$!
for n in 1 2 3 4 5 6 7 8; do [ -S /tmp/.X11-unix/X1 ] && break; sleep 0.5; done

xrdb -merge "$HOME/.Xresources" >/dev/null 2>&1 || true
eval "$(dbus-launch --sh-syntax)"
openbox-session >/tmp/pocketdesk-openbox.log 2>&1 &
tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
pcmanfm --desktop --profile LXDE >/tmp/pocketdesk-pcmanfm.log 2>&1 &
lxterminal >/tmp/pocketdesk-terminal.log 2>&1 &
wait "$VNC_PID"
