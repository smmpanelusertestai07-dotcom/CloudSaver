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
# The last word on the matter, for an app that reads no settings file. If one app ever looks
# wrong because of it, delete this line: settings.ini alone still gives a dark desktop.
export GTK_THEME=Adwaita:dark
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
mkdir -p "$HOME/Pictures" "$HOME/.config/gtk-3.0" "$HOME/.config/gtk-4.0" "$HOME/.config/lxterminal" "$HOME/.config/tint2" \
         "$HOME/.config/openbox" "$HOME/.config/pcmanfm/LXDE" "$HOME/.config/libfm" \
         "$HOME/.config/dunst" "$HOME/.icons/default" "$HOME/Desktop" "$HOME/Projects" \
         "$HOME/Downloads" "$HOME/Phone" "$HOME/.pocketdesk/logs"

# The left-hand list of every Open and Save dialog (ChatGPT's "attach", the browser's upload,
# the file manager): the phone's files and the computer's own, side by side.
printf 'file:///home/coder/Phone Phone\nfile:///home/coder/Phone/Download Phone Downloads\nfile:///home/coder/Phone/DCIM Phone Photos\nfile:///home/coder/Phone/Documents Phone Documents\nfile:///home/coder/Downloads Downloads\nfile:///home/coder/Pictures Pictures\nfile:///home/coder/Projects Projects\nfile:///home/coder/Shared Shared with phone\n' \
  > "$HOME/.config/gtk-3.0/bookmarks"

# Downloads stay inside the computer, where no other app on the phone can read them; the
# Shared folder is the deliberate way out to the phone's Files app. POCKETDESK_SHARE_DOWNLOADS
# is 0 from 10.0.30 onwards, and the second branch moves an older computer's shared Downloads
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
printf 'Xft.dpi: %s\nXft.antialias: true\nXft.hinting: true\nXft.hintstyle: hintslight\nXft.rgba: none\nXft.lcdfilter: none\nXcursor.theme: Adwaita\nXcursor.size: 32\n*background: #0b1320\n*foreground: #e6ecf7\n' \
  "$DPI" > "$HOME/.Xresources"

# Adwaita, not "Adwaita-dark": GTK 3 has no theme of that name unless gnome-themes-extra is
# installed, and asking for a theme GTK cannot find makes it drop the variant and fall back to
# the default -- light Adwaita. That one word is why every dialog, menu and file window has been
# white. The dark variant is compiled into libgtk-3-0 and is reached by asking for Adwaita and
# setting prefer-dark.
#
# Written only while the file is still PocketDesk's. lxappearance rewrites it without the marker,
# and from then on the owner's own choice is what starts. A file written by a PocketDesk before
# the marker existed is recognised by the wrong theme name it carries and is taken over once.
write_gtk_defaults() {
  for gtk_dir in "$HOME/.config/gtk-3.0" "$HOME/.config/gtk-4.0"; do
    printf '# pocketdesk-default\n[Settings]\ngtk-theme-name=Adwaita\ngtk-application-prefer-dark-theme=1\ngtk-icon-theme-name=Adwaita\ngtk-cursor-theme-name=Adwaita\ngtk-cursor-theme-size=32\ngtk-font-name=Sans 11\ngtk-xft-dpi=%s\ngtk-xft-antialias=1\ngtk-xft-hinting=1\ngtk-xft-hintstyle=hintslight\ngtk-xft-rgba=none\ngtk-enable-animations=0\n' \
      "$((DPI * 1024))" > "$gtk_dir/settings.ini"
  done
}
GTK_INI="$HOME/.config/gtk-3.0/settings.ini"
if [ ! -f "$GTK_INI" ] \
   || head -n 1 "$GTK_INI" 2>/dev/null | grep -qx '# pocketdesk-default' \
   || grep -q '^gtk-theme-name=Adwaita-dark$' "$GTK_INI" 2>/dev/null; then
  write_gtk_defaults
fi

# A normal arrow instead of the old X11 cross.
printf '[Icon Theme]\nName=Default\nComment=Default cursor\nInherits=Adwaita\n' \
  > "$HOME/.icons/default/index.theme"

# lxterminal applies a palette only when color_preset and all sixteen colours are present; one
# missing line and it silently loads its own preset instead. A blinking cursor is two full
# redraws a second for ever, and under PRoot every one is a traced round trip and a VNC frame.
printf '[general]\nfontname=Monospace 12\nscrollback=5000\nbgcolor=#0d1526\nfgcolor=#f1f5fb\ncolor_preset=PocketDesk\npalette_color_0=#0d1526\npalette_color_1=#ff6b6b\npalette_color_2=#4ade80\npalette_color_3=#fbbf24\npalette_color_4=#7a9bff\npalette_color_5=#c792ea\npalette_color_6=#56d4dd\npalette_color_7=#c2cae6\npalette_color_8=#55607d\npalette_color_9=#ff8a8a\npalette_color_10=#86efac\npalette_color_11=#fcd34d\npalette_color_12=#a5bcff\npalette_color_13=#ddb0ff\npalette_color_14=#8beaf2\npalette_color_15=#f1f5fb\ngeometry_columns=100\ngeometry_rows=30\nhidescrollbar=false\ndisallowbold=false\nboldbright=true\ncursorblinks=false\ncursorunderline=false\naudiblebell=false\nvisualbell=false\n' \
  > "$HOME/.config/lxterminal/lxterminal.conf"

# Without this, opening a desktop icon raises PCManFM's "this seems to be an executable
# script - what do you want to do with it?" prompt instead of just launching the app.
printf '[config]\nquick_exec=1\nsingle_click=1\nconfirm_del=1\nterminal=lxterminal\n\n[ui]\nbig_icon_size=72\nsmall_icon_size=24\nthumbnail_size=128\n' \
  > "$HOME/.config/libfm/libfm.conf"

# window manager's menu, which lists every installed app, Phone files, the terminal and the
# window commands, rather than the file manager's own short one.
# PocketDesk's own wallpaper (a dark-blue square with Tux and the app's name -- Canonical's
# artwork and the Ubuntu logo are not ours to ship, see OPEN_SOURCE_NOTICES.md); a right-click
# (a long press, in Finger mode) on it opens the apps menu.
printf '[*]\nwallpaper_mode=fit\nwallpaper=/usr/share/backgrounds/pocketdesk.jpg\nwallpaper_common=1\ndesktop_bg=#0b1320\ndesktop_fg=#e6ecf7\ndesktop_shadow=#04070f\nshow_documents=1\nshow_trash=0\nshow_mounts=0\nshow_wm_menu=1\ndesktop_font=Sans 11\n' \
  > "$HOME/.config/pcmanfm/LXDE/desktop-items-0.conf"
printf '[config]\nbm_open_method=0\n[volume]\nmount_on_startup=0\nmount_removable=0\n[ui]\nalways_show_tabs=1\nmax_tab_chars=32\n' \
  > "$HOME/.config/pcmanfm/LXDE/pcmanfm.conf"

# The window manager's settings are written by pocketdesk-menu, which runs below on every
# start: a container set up by an older version gets the current window rules too.

# Which app opens links, and which app a browser sign-in comes back to, is written by
# pocketdesk-menu below from the packages that are really installed.

# Toasts in the desktop's own colours, so "Opening ChatGPT" reads like part of the system.
# Toasts in the desktop's own colours. timeout belongs to the urgency sections -- dunst does not
# read it from [global] -- and the panel is at the bottom, so these sit at the top right.
printf '[global]\nfont = Sans 10\nwidth = 320\norigin = top-right\noffset = 12x12\ngap_size = 6\nnotification_limit = 3\nframe_width = 1\nframe_color = "#2b3563"\nseparator_color = frame\ncorner_radius = 12\npadding = 10\nhorizontal_padding = 12\nword_wrap = yes\nicon_theme = Adwaita\nmin_icon_size = 24\nmax_icon_size = 40\nmouse_left_click = close_current\nmouse_right_click = close_all\n\n[urgency_low]\nbackground = "#101a2e"\nforeground = "#9aa7bd"\nframe_color = "#23304a"\ntimeout = 5\n\n[urgency_normal]\nbackground = "#101a2e"\nforeground = "#f1f5fb"\nframe_color = "#2b3563"\ntimeout = 6\n\n[urgency_critical]\nbackground = "#3b1220"\nforeground = "#ffe4e6"\nframe_color = "#c7362b"\ntimeout = 0\n' \
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

# The terminal's prompt says where you are. Left alone it reads "coder@localhost", because PRoot
# keeps the phone's own hostname and /etc/hostname cannot change it without a namespace this
# container does not have. Appended once, behind a marker, so an owner's own .bashrc is never
# overwritten and never added to twice.
if ! grep -q 'PocketDesk prompt' "$HOME/.bashrc" 2>/dev/null; then
  cat >> "$HOME/.bashrc" <<'PROMPT'

# PocketDesk prompt: the computer's name, the folder, the git branch, and a red mark when the
# last command failed -- the one thing a phone screen cannot afford to make you scroll for.
pd_branch() {
  git rev-parse --abbrev-ref HEAD 2>/dev/null | sed 's/^/ /'
}
pd_mark() { [ $? -eq 0 ] && printf '\001\033[38;5;75m\002$' || printf '\001\033[38;5;203m\002$'; }
PS1='\[\e[38;5;75m\]PocketDesk\[\e[0m\]:\[\e[38;5;150m\]\w\[\e[38;5;180m\]$(pd_branch)\[\e[0m\]`pd_mark`\[\e[0m\] '

# A phone keyboard is slow, so history is long, shared between terminals and never truncated by
# whichever window closes last.
HISTSIZE=50000
HISTFILESIZE=200000
HISTCONTROL=ignoreboth:erasedups
shopt -s histappend checkwinsize cdspell autocd 2>/dev/null
PROMPT_COMMAND="history -a; history -n; ${PROMPT_COMMAND:-}"

# Colour and less typing: both matter more on a 6-inch screen than on a desk.
alias ls='ls --color=auto --group-directories-first'
alias ll='ls -alh --color=auto --group-directories-first'
alias la='ls -A --color=auto'
alias grep='grep --color=auto'
alias fgrep='fgrep --color=auto'
alias egrep='egrep --color=auto'
alias ..='cd ..'
alias ...='cd ../..'
alias df='df -h'
alias du='du -h'
alias free='free -h'
alias please='sudo'
alias ports='ss -tulpn 2>/dev/null || netstat -tulpn'
export EDITOR=nano
export VISUAL=nano
export PAGER=less
export LESS='-R -F -X -i -M'
export GREP_COLORS='mt=01;38;5;180'
# Ubuntu ships C.UTF-8 with no locale generation, so this works on a fresh container and keeps
# emoji, Devanagari and every other script rendering in the terminal.
export LANG=${LANG:-C.UTF-8}
export LC_ALL=${LC_ALL:-C.UTF-8}
# Node and npm put their caches inside the container rather than anywhere the phone syncs.
export npm_config_cache="$HOME/.cache/npm"
# Agents run here: give them a terminal that says it can do colour, and a wide-enough default.
export TERM=${TERM:-xterm-256color}
export COLORTERM=truecolor

# Bash completion, when the package is installed.
if ! shopt -oq posix; then
  [ -f /usr/share/bash-completion/bash_completion ] && . /usr/share/bash-completion/bash_completion
fi

# The one command worth knowing about on a phone: work that must survive the screen going off.
alias keep='tmux new -A -s pocketdesk'
PROMPT
fi

# The desktop's own eyes and hands, offered to whichever AI agent is installed.
#
# Codex's Appshots are macOS only and Claude Desktop's Computer Use is not in the Linux beta, so
# PocketDesk provides both itself over MCP: a picture of the window in front plus the words on
# it, and click, type, key and scroll. Registered here rather than at install time because the
# agents are installed after the computer is, and this runs at every start.
if [ -x /usr/local/bin/pocketdesk-mcp ]; then
  # Codex reads one TOML file. Appended once, behind its own marker.
  mkdir -p "$HOME/.codex"
  if ! grep -q 'mcp_servers.pocketdesk' "$HOME/.codex/config.toml" 2>/dev/null; then
    cat >> "$HOME/.codex/config.toml" <<'CODEXMCP'

# PocketDesk's desktop tools: appshot, click, type_text, press_key, scroll.
[mcp_servers.pocketdesk]
command = "python3"
args = ["/usr/local/bin/pocketdesk-mcp"]
startup_timeout_sec = 30
CODEXMCP
  fi
  # Claude Code is registered through its own command, so the file format stays its business.
  if command -v claude >/dev/null 2>&1 \
     && ! grep -q '"pocketdesk"' "$HOME/.claude.json" 2>/dev/null; then
    claude mcp add --scope user pocketdesk -- python3 /usr/local/bin/pocketdesk-mcp \
      >/tmp/pocketdesk-mcp-register.log 2>&1 || true
  fi
  # Any project folder gets it too, for agents that read a project-scoped file.
  if [ ! -f "$HOME/Projects/.mcp.json" ]; then
    cat > "$HOME/Projects/.mcp.json" <<'PROJECTMCP'
{
  "mcpServers": {
    "pocketdesk": {
      "command": "python3",
      "args": ["/usr/local/bin/pocketdesk-mcp"]
    }
  }
}
PROJECTMCP
  fi
fi

/usr/local/bin/pocketdesk-menu || true

# Sound. There is no sound card a container can reach, so PulseAudio plays into a virtual
# output called Phone, and everything written to it is streamed as plain PCM to PocketDesk's
# viewer, which plays it through the phone's own speaker. Every app that speaks PulseAudio
# (Electron apps, Chrome) simply finds a working output.
#
# The stream goes over a unix socket inside this app's private storage, not a TCP port:
# Android shares loopback between apps, so a port here could be read by any other app on the
# phone that holds the internet permission. The TCP module stays only as a fallback for a
# PulseAudio too old to have the unix one, so sound never simply stops working.
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
  # 100% and unmuted at every start. Android's media volume is the real control (see
  # AudioBridge): this sink's own level is a second gain stage on the very same sound, it is
  # applied to the monitor stream the phone reads, and PulseAudio's module-device-restore
  # remembers it by name -- so a level dropped once would follow the owner for ever, and a level
  # dropped far enough would fall into the viewer's own silence gate and stop sound altogether.
  # Per-app balance is still available: Tools -> Volume and sound opens pavucontrol.
  pactl set-sink-mute phone 0 >/dev/null 2>&1 || true
  pactl set-sink-volume phone 100% >/dev/null 2>&1 || true

  mkdir -p "$HOME/.pocketdesk"
  chmod 700 "$HOME/.pocketdesk" 2>/dev/null || true
  rm -f "$HOME/.pocketdesk/audio.sock"
  if pactl load-module module-simple-protocol-unix rate=44100 format=s16le channels=2 \
      source=phone.monitor record=true playback=false \
      socket="$HOME/.pocketdesk/audio.sock" >/dev/null 2>&1; then
    chmod 600 "$HOME/.pocketdesk/audio.sock" 2>/dev/null || true
    echo "sound: private socket" >> /tmp/pocketdesk-pulse.log
  else
    echo "sound: falling back to the local port" >> /tmp/pocketdesk-pulse.log
    pactl load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 \
      source=phone.monitor record=true playback=false listen=127.0.0.1 port=4712 >/dev/null 2>&1 || true
  fi
fi

# SendPrimary off: X11 treats any highlighted text as a selection, and the display server was
# forwarding every one of them to the phone as a copy -- so the phone showed "Copied" whenever
# a word was selected, and its clipboard was overwritten. Only a real copy (Ctrl+C, or the
# menu) reaches the phone now.
# The display server listens on a unix socket in this app's private storage, which no other
# app on the phone can open, instead of a TCP port on loopback, which any of them could:
# Android does not keep loopback apart between apps, and this session has no password on it.
# If this build of Xtigervnc has no -rfbunixpath, the old port is used instead so the desktop
# still comes up -- PocketDesk's viewer tries the socket first and the port second.
mkdir -p "$HOME/.pocketdesk"
chmod 700 "$HOME/.pocketdesk" 2>/dev/null || true
rm -f "$HOME/.pocketdesk/vnc.sock" /tmp/.X11-unix/X1 /tmp/.X1-lock 2>/dev/null || true

start_display() {   # start_display <extra args...>
  # Output stays on this script's stdout, which PocketDesk records for the session: a display
  # that refuses to start has to be able to say why where the phone can read it.
  /usr/bin/Xtigervnc :1 "$@" -SecurityTypes None -ac -AlwaysShared \
    -SendPrimary=0 -geometry "$GEOMETRY" -depth 24 -dpi "$DPI" -desktop 'PocketDesk' &
  VNC_PID=$!
}

display_ready() {
  [ -S /tmp/.X11-unix/X1 ] || return 1
  command -v xdpyinfo >/dev/null 2>&1 || return 0
  # timeout, because a server wedged mid-start leaves xdpyinfo waiting for ever and the whole
  # start would hang here instead of falling back.
  if command -v timeout >/dev/null 2>&1; then
    DISPLAY=:1 timeout 5 xdpyinfo >/dev/null 2>&1
  else
    DISPLAY=:1 xdpyinfo >/dev/null 2>&1
  fi
}

# Far longer than the four seconds this used to allow -- on a cold, ptraced container the server
# can take a minute to answer, and starting the panel against a display that is not there was a
# desktop with no wallpaper, no panel and no icons. The two waits together (40 s for the private
# socket, then 90 s for the fallback) still finish inside the 150 s the phone waits before it
# gives up on the session.
wait_for_display() {   # wait_for_display <half-second attempts>
  n=0
  while [ "$n" -lt "$1" ]; do
    display_ready && return 0
    kill -0 "$VNC_PID" 2>/dev/null || return 1
    n=$((n + 1))
    sleep 0.5
  done
  return 1
}

start_display -rfbunixpath "$HOME/.pocketdesk/vnc.sock" -rfbunixmode 0600 -rfbport -1
if ! wait_for_display 80; then
  echo "display: the private socket did not come up; using the local port instead"
  kill "$VNC_PID" 2>/dev/null || true
  wait "$VNC_PID" 2>/dev/null || true
  rm -f "$HOME/.pocketdesk/vnc.sock" /tmp/.X11-unix/X1 /tmp/.X11-unix/X1-lock /tmp/.X1-lock 2>/dev/null || true
  start_display -rfbport 5901 -localhost yes
  wait_for_display 180 || echo "display: did not start"
fi

xrdb -merge "$HOME/.Xresources" >/dev/null 2>&1 || true
# The root window is grey until the file manager paints it, and grey again if the file manager is
# ever killed for memory. This is the same navy as the wallpaper's edge and the desktop's own
# background, so the seam is invisible either way.
xsetroot -solid '#0b1320' >/dev/null 2>&1 || true
xsetroot -cursor_name left_ptr >/dev/null 2>&1 || true
eval "$(dbus-launch --sh-syntax)"
# Electron apps ask the system bus about power, network and devices. There is no init here to
# start one, so every request failed and was retried: "Failed to connect to socket
# /run/dbus/system_bus_socket" appears dozens of times in their logs before they give up.
mkdir -p /run/dbus 2>/dev/null || true
pgrep -f 'dbus-daemon --system' >/dev/null 2>&1 || \
  dbus-daemon --system --fork >/dev/null 2>&1 || true
openbox-session >/tmp/pocketdesk-openbox.log 2>&1 &
# The panel must never be the reason there is no computer. If tint2 is not running twelve
# seconds after it was asked to start, the settings it was given are moved aside and it is
# started again on the ones it ships with -- a plain bar, but a bar. The rejected file is kept,
# never deleted, so it and the log together say what went wrong.
tint2 >/tmp/pocketdesk-tint2.log 2>&1 &
(
  sleep 12
  pgrep -x tint2 >/dev/null 2>&1 && exit 0
  echo 'panel: tint2 did not stay up; using its own settings instead' >> /tmp/pocketdesk-tint2.log
  mv -f "$HOME/.config/tint2/tint2rc" "$HOME/.config/tint2/tint2rc.rejected" 2>/dev/null || true
  tint2 >>/tmp/pocketdesk-tint2.log 2>&1 &
) &
command -v dunst >/dev/null 2>&1 && dunst >/tmp/pocketdesk-dunst.log 2>&1 &
pcmanfm --desktop --profile LXDE >/tmp/pocketdesk-pcmanfm.log 2>&1 &
wait "$VNC_PID"
