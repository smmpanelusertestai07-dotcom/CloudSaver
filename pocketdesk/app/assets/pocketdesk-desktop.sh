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
# WebKit (GNOME Web) builds its sandbox on bubblewrap, which needs the same namespaces.
export WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1
# No screen reader will ever run here, yet every GTK program and web page tried to reach the
# accessibility bus first and logged "Could not connect to accessibility bus" while it waited.
export NO_AT_BRIDGE=1 GTK_A11Y=none
export WEBKIT_DISABLE_COMPOSITING_MODE=1 WEBKIT_DISABLE_DMABUF_RENDERER=1
# One web process for every page: each new one is a 150 MB program started under PRoot, and
# starting it was most of the wait before a page appeared.
export WEBKIT_USE_SINGLE_WEB_PROCESS=1
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
# Electron and GTK both look for this. Unset, they fall back to slow paths and print warnings
# that end with an app sitting there having drawn nothing.
export XDG_RUNTIME_DIR=/tmp/runtime-coder
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
# Chromium wants shared memory to exist even when told not to rely on it.
mkdir -p /dev/shm 2>/dev/null && chmod 1777 /dev/shm 2>/dev/null || true
cd "$HOME"
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
mkdir -p "$HOME/.config/gtk-3.0" "$HOME/.config/lxterminal" "$HOME/.config/tint2" \
         "$HOME/.config/openbox" "$HOME/.config/pcmanfm/LXDE" "$HOME/.config/libfm" \
         "$HOME/.config/dunst" "$HOME/.icons/default" "$HOME/Desktop" "$HOME/Projects" \
         "$HOME/Downloads" "$HOME/Phone" "$HOME/.pocketdesk/logs"

# The left-hand list of every Open and Save dialog (ChatGPT's "attach", the browser's upload,
# the file manager): the phone's files and the computer's own, side by side.
printf 'file:///home/coder/Phone Phone\nfile:///home/coder/Phone/Download Phone Downloads\nfile:///home/coder/Phone/DCIM Phone Photos\nfile:///home/coder/Phone/Documents Phone Documents\nfile:///home/coder/Downloads Downloads\nfile:///home/coder/Projects Projects\nfile:///home/coder/Shared Shared with phone\n' \
  > "$HOME/.config/gtk-3.0/bookmarks"

# Downloads stay inside the computer, where no other app on the phone can read them; the
# Shared folder is the deliberate way out to the phone's Files app. POCKETDESK_SHARE_DOWNLOADS
# is 0 from 10.0.25 onwards, and the second branch moves an older computer's shared Downloads
# back inside. Nothing is ever deleted, only moved.
if [ "${POCKETDESK_SHARE_DOWNLOADS:-1}" = "1" ] && [ -d "$HOME/Shared" ] && [ -w "$HOME/Shared" ]; then
  mkdir -p "$HOME/Shared/Downloads"
  if [ -d "$HOME/Downloads" ] && [ ! -L "$HOME/Downloads" ]; then
    mv "$HOME/Downloads"/* "$HOME/Shared/Downloads"/ 2>/dev/null || true
    rmdir "$HOME/Downloads" 2>/dev/null && ln -s "$HOME/Shared/Downloads" "$HOME/Downloads"
  elif [ ! -e "$HOME/Downloads" ]; then
    ln -s "$HOME/Shared/Downloads" "$HOME/Downloads"
  fi
elif [ "${POCKETDESK_SHARE_DOWNLOADS:-1}" = "0" ] && [ -L "$HOME/Downloads" ]; then
  rm -f "$HOME/Downloads"
  mkdir -p "$HOME/Downloads"
  mv "$HOME/Shared/Downloads"/* "$HOME/Downloads"/ 2>/dev/null || true
fi

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
printf '[config]\nquick_exec=1\nsingle_click=1\nconfirm_del=1\nterminal=lxterminal\n\n[ui]\nbig_icon_size=72\nsmall_icon_size=24\nthumbnail_size=128\n' \
  > "$HOME/.config/libfm/libfm.conf"

# Ubuntu 24.04's own wallpaper; a right-click (a long press, in Finger mode) on it opens the
# window manager's menu, which lists every installed app, Phone files, the terminal and the
# window commands, rather than the file manager's own short one.
printf '[*]\nwallpaper_mode=crop\nwallpaper=/usr/share/backgrounds/pocketdesk.jpg\ndesktop_bg=#0b1220\ndesktop_fg=#e6ecf7\ndesktop_shadow=#000000\nshow_documents=1\nshow_trash=0\nshow_mounts=0\nshow_wm_menu=1\ndesktop_font=Sans 11\n' \
  > "$HOME/.config/pcmanfm/LXDE/desktop-items-0.conf"
printf '[config]\nbm_open_method=0\n[volume]\nmount_on_startup=0\nmount_removable=0\n[ui]\nalways_show_tabs=1\nmax_tab_chars=32\n' \
  > "$HOME/.config/pcmanfm/LXDE/pcmanfm.conf"

# The window manager's settings are written by pocketdesk-menu, which runs below on every
# start: a container set up by an older version gets the current window rules too.

# Which app opens links, and which app a browser sign-in comes back to, is written by
# pocketdesk-menu below from the packages that are really installed.

# Toasts in the desktop's own colours, so "Opening ChatGPT" reads like part of the system.
printf '[global]\nfont = Sans 10\nframe_width = 1\nframe_color = "#2b3563"\ncorner_radius = 10\noffset = 12x56\norigin = top-right\ntimeout = 6\nmax_icon_size = 40\n\n[urgency_low]\nbackground = "#0f1327"\nforeground = "#e6ecf7"\n\n[urgency_normal]\nbackground = "#0f1327"\nforeground = "#e6ecf7"\n\n[urgency_critical]\nbackground = "#3b1220"\nforeground = "#ffe4e6"\ntimeout = 0\n' \
  > "$HOME/.config/dunst/dunstrc"

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

# Sound. There is no sound card a container can reach, so PulseAudio plays into a virtual
# output called Phone, and everything written to it is streamed as plain PCM on a local port
# that PocketDesk's viewer reads and plays through the phone's own speaker. Every app that
# speaks PulseAudio (Electron apps, GNOME Web, Firefox, Brave) simply finds a working output.
if command -v pulseaudio >/dev/null 2>&1; then
  mkdir -p "$HOME/.config/pulse"
  printf 'exit-idle-time = -1\ndefault-sample-rate = 44100\nflat-volumes = no\nenable-shm = no\n' \
    > "$HOME/.config/pulse/daemon.conf"
  printf 'autospawn = no\nenable-shm = no\n' > "$HOME/.config/pulse/client.conf"
  pulseaudio --kill >/dev/null 2>&1 || true
  pulseaudio --daemonize=yes --exit-idle-time=-1 --disable-shm=yes >/tmp/pocketdesk-pulse.log 2>&1 || true
  for n in 1 2 3 4 5 6; do pactl info >/dev/null 2>&1 && break; sleep 0.5; done
  pactl load-module module-null-sink sink_name=phone sink_properties=device.description=Phone >/dev/null 2>&1 || true
  pactl set-default-sink phone >/dev/null 2>&1 || true
  pactl load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 \
    source=phone.monitor record=true playback=false listen=127.0.0.1 port=4712 >/dev/null 2>&1 || true
fi

# SendPrimary off: X11 treats any highlighted text as a selection, and the display server was
# forwarding every one of them to the phone as a copy -- so the phone showed "Copied" whenever
# a word was selected, and its clipboard was overwritten. Only a real copy (Ctrl+C, or the
# menu) reaches the phone now.
/usr/bin/Xtigervnc :1 -rfbport 5901 -localhost yes -SecurityTypes None -ac -AlwaysShared \
  -SendPrimary=0 -geometry "$GEOMETRY" -depth 24 -dpi "$DPI" -desktop 'PocketDesk' &
VNC_PID=$!
for n in 1 2 3 4 5 6 7 8; do [ -S /tmp/.X11-unix/X1 ] && break; sleep 0.5; done

xrdb -merge "$HOME/.Xresources" >/dev/null 2>&1 || true
xsetroot -cursor_name left_ptr >/dev/null 2>&1 || true
eval "$(dbus-launch --sh-syntax)"
# Electron apps ask the system bus about power, network and devices. There is no init here to
# start one, so every request failed and was retried: "Failed to connect to socket
# /run/dbus/system_bus_socket" appears dozens of times in their logs before they give up.
mkdir -p /run/dbus 2>/dev/null || true
pgrep -f 'dbus-daemon --system' >/dev/null 2>&1 || \
  dbus-daemon --system --fork >/dev/null 2>&1 || true
openbox-session >/tmp/pocketdesk-openbox.log 2>&1 &
tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
command -v dunst >/dev/null 2>&1 && dunst >/tmp/pocketdesk-dunst.log 2>&1 &
pcmanfm --desktop --profile LXDE >/tmp/pocketdesk-pcmanfm.log 2>&1 &
wait "$VNC_PID"
