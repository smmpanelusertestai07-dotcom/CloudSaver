#!/bin/bash
# Starts the Linux desktop that PocketLinux shows. Called as:
#   pocketdesk-desktop <width>x<height> <dpi>
set -u
# Called by Android before switching to coder. The stock system bus must create its socket
# and drop to messagebus as root; starting it after su silently failed on existing desktops.
if [ "${1:-}" = --prepare-system-bus ]; then
  exec python3 - <<'SYSTEMBUS'
import errno
import hashlib
import os
import pwd
import socket
import subprocess


def listener_state(path):
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
            connection.settimeout(2)
            connection.connect(path)
        return "connected"
    except OSError as error:
        if error.errno in (errno.ENOENT, errno.ECONNREFUSED):
            return "absent"
        # Permission, timeout or another ambiguous result must not replace a live bus.
        print("PD_SYSTEM_BUS: listener probe could not confirm absence: " + str(error), flush=True)
        return "unknown"


def restore_system_config(source="/usr/local/share/pocketdesk/dbus-system.conf",
                          destination="/usr/share/dbus-1/system.conf"):
    # Restore only the absent distro file, offline. Preserve existing configs, even
    # symlinks or empty files: a local policy is never replaced by a guessed one.
    if os.path.lexists(destination):
        return True
    try:
        with open(source, "rb") as config:
            data = config.read(16384)
        if hashlib.sha256(data).hexdigest() != "c0a02340950ce376ccee26d58df2c77466c534dcd368b3486b4b6a60d3741f6b":
            raise ValueError("bundled stock configuration checksum mismatch")
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        try:
            with open(destination, "xb") as config:
                os.chmod(destination, 0o644)
                config.write(data)
        except FileExistsError:
            return True
        print("PD_SYSTEM_BUS: restored missing Ubuntu system.conf (offline)", flush=True)
        return True
    except (OSError, ValueError) as error:
        print("PD_SYSTEM_BUS: configuration repair failed: " + str(error), flush=True)
        return False


def prepare_bus_identity():
    try:
        try:
            pwd.getpwnam("messagebus")
        except KeyError:
            result = subprocess.run(["useradd", "--system", "--user-group", "--no-create-home",
                                     "--home-dir", "/nonexistent", "--shell", "/usr/sbin/nologin",
                                     "messagebus"], stdin=subprocess.DEVNULL,
                                    stderr=subprocess.STDOUT, timeout=5)
            if result.returncode:
                return False
        result = subprocess.run(["dbus-uuidgen", "--ensure"], stdin=subprocess.DEVNULL,
                                stderr=subprocess.STDOUT, timeout=3)
        return result.returncode == 0
    except (OSError, subprocess.TimeoutExpired) as error:
        print("PD_SYSTEM_BUS: identity setup failed: " + str(error), flush=True)
        return False


def prepare_system_bus():
    path = "/run/dbus/system_bus_socket"
    try:
        os.makedirs("/run/dbus", exist_ok=True)
    except OSError as error:
        print("PD_SYSTEM_BUS: cannot prepare socket directory: " + str(error), flush=True)
        return 1
    state = listener_state(path)
    if state == "unknown":
        return 1
    if state == "absent":
        if not restore_system_config() or not prepare_bus_identity():
            return 1
        try:
            # The daemon forks. Its child must not retain a communicate() pipe and make the
            # root helper wait for EOF forever. Inherit Android's existing session output;
            # startup errors remain in that report, and the deadline waits only for our child.
            result = subprocess.run(["dbus-daemon", "--system", "--fork", "--nopidfile", "--nosyslog"],
                                    stdin=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=8)
            if result.returncode:
                print("PD_SYSTEM_BUS: daemon startup exit " + str(result.returncode), flush=True)
        except (OSError, subprocess.TimeoutExpired) as error:
            # A forked daemon might already be listening. Check it; never launch a second
            # daemon or unlink its socket simply because the startup command did not return.
            print("PD_SYSTEM_BUS: daemon startup: " + str(error), flush=True)
    try:
        result = subprocess.run(["dbus-send", "--system", "--print-reply", "--reply-timeout=2500",
                                 "--dest=org.freedesktop.DBus", "/", "org.freedesktop.DBus.ListNames"],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=4)
        if result.returncode == 0:
            print("PD_SYSTEM_BUS: ready", flush=True)
            return 0
        print("PD_SYSTEM_BUS: readiness check failed (existing listener kept)", flush=True)
        if result.stdout.strip():
            print(result.stdout[-4000:].strip(), flush=True)
    except (OSError, subprocess.TimeoutExpired) as error:
        print("PD_SYSTEM_BUS: readiness check: " + str(error), flush=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(prepare_system_bus())
SYSTEMBUS
fi
desktop_phase() { printf 'PD_DESKTOP_PHASE: %s\n' "$1"; }
desktop_phase "Preparing desktop settings"
GEOMETRY=${1:-1280x720}
DPI=${2:-160}
export HOME=/home/coder USER=coder LOGNAME=coder DISPLAY=:1 LANG=C.UTF-8
export XDG_CONFIG_HOME="$HOME/.config" XDG_DATA_HOME="$HOME/.local/share"
# Generic xdg-utils and applications that consult BROWSER must use the same safe launcher.
export BROWSER=/usr/local/bin/pocketdesk-browser
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
# A software-rendered browser and Electron must not each start a worker per phone CPU core.
export LP_NUM_THREADS=2 OMP_NUM_THREADS=2
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

# SendPrimary off: X11 treats any highlighted text as a selection, and the display server was
# forwarding every one of them to the phone as a copy -- so the phone showed "Copied" whenever
# a word was selected, and its clipboard was overwritten. Only a real copy (Ctrl+C, or the
# menu) reaches the phone now.
# The display server listens on a unix socket in this app's private storage, which no other
# app on the phone can open, instead of a TCP port on loopback, which any of them could:
# Android does not keep loopback apart between apps, and this session has no password on it.
# If this build of Xtigervnc has no -rfbunixpath, the old port is used instead so the desktop
# still comes up -- PocketLinux's viewer tries the socket first and the port second.
mkdir -p "$HOME/.pocketdesk"
chmod 700 "$HOME/.pocketdesk" 2>/dev/null || true
rm -f "$HOME/.pocketdesk/vnc.sock" /tmp/.X11-unix/X1 /tmp/.X1-lock 2>/dev/null || true

start_display() {   # start_display <extra args...>
  # Output stays on this script's stdout, which PocketLinux records for the session: a display
  # that refuses to start has to be able to say why where the phone can read it.
  /usr/bin/Xtigervnc :1 "$@" -SecurityTypes None -ac -AlwaysShared \
    -SendPrimary=0 -geometry "$GEOMETRY" -depth 24 -dpi "$DPI" -desktop 'PocketLinux' &
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

# Count actual elapsed seconds. An xdpyinfo probe can itself consume five seconds; counting
# probes as half-seconds turned the previous 40-second wait into several minutes.
wait_for_display() {   # wait_for_display <seconds>
  local deadline=$((SECONDS + $1))
  while [ "$SECONDS" -lt "$deadline" ]; do
    kill -0 "$VNC_PID" 2>/dev/null || return 1
    display_ready && return 0
    sleep 0.5
  done
  return 1
}

desktop_phase "Starting the private display"
start_display -rfbunixpath "$HOME/.pocketdesk/vnc.sock" -rfbunixmode 0600 -rfbport -1
if ! wait_for_display 40; then
  echo "display: the private socket did not come up; using the local port instead"
  kill "$VNC_PID" 2>/dev/null || true
  # A wedged display may ignore TERM; never wait forever before the fallback.
  for n in 1 2 3 4 5; do
    kill -0 "$VNC_PID" 2>/dev/null || break
    sleep 0.2
  done
  kill -KILL "$VNC_PID" 2>/dev/null || true
  wait "$VNC_PID" 2>/dev/null || true
  rm -f "$HOME/.pocketdesk/vnc.sock" /tmp/.X11-unix/X1 /tmp/.X11-unix/X1-lock /tmp/.X1-lock 2>/dev/null || true
  desktop_phase "Starting the fallback display"
  start_display -rfbport 5901 -localhost yes
  if ! wait_for_display 90; then
    echo "display: did not start"
    kill -KILL "$VNC_PID" 2>/dev/null || true
    wait "$VNC_PID" 2>/dev/null || true
    exit 1
  fi
fi


# Keep the computer's Downloads as a real private directory. Very old releases made it a link
# into Shared; unlinking that link leaves every old file safely in Shared/Downloads and avoids
# silently moving anything when the owner changes this setting.
COMPUTER_DOWNLOADS="$HOME/Downloads"
if [ -L "$COMPUTER_DOWNLOADS" ]; then
  unlink "$COMPUTER_DOWNLOADS" 2>/dev/null || true
fi
mkdir -p "$COMPUTER_DOWNLOADS"

# One explicit destination for every desktop component. "ask" makes browsers and Save dialogs
# ask for each file and uses the private folder only as the fallback. The phone destination is
# public Download/PocketLinux, so it is visible to Android's Files app and survives removing the
# Linux computer. If All files access was taken back, fall safely to ask/private.
DOWNLOAD_TARGET=${POCKETDESK_DOWNLOAD_TARGET:-ask}
DOWNLOAD_DIR="$COMPUTER_DOWNLOADS"
DOWNLOAD_PROMPT=${POCKETDESK_DOWNLOAD_PROMPT:-1}
PHONE_DOWNLOADS="$HOME/Phone/Download/PocketLinux"
case "$DOWNLOAD_TARGET" in
  computer) DOWNLOAD_PROMPT=0 ;;
  phone)
    if [ -d "$HOME/Phone/Download" ] && mkdir -p "$PHONE_DOWNLOADS" 2>/dev/null \
       && [ -w "$PHONE_DOWNLOADS" ]; then
      DOWNLOAD_DIR="$PHONE_DOWNLOADS"
      DOWNLOAD_PROMPT=0
    else
      DOWNLOAD_TARGET=ask
      DOWNLOAD_PROMPT=1
    fi
    ;;
  *) DOWNLOAD_TARGET=ask; DOWNLOAD_PROMPT=1 ;;
esac
export POCKETDESK_DOWNLOAD_TARGET="$DOWNLOAD_TARGET"
export POCKETDESK_DOWNLOAD_DIR="$DOWNLOAD_DIR"
export POCKETDESK_DOWNLOAD_PROMPT="$DOWNLOAD_PROMPT"
mkdir -p "$HOME/.config/pocketdesk"
printf '%s\n' "$DOWNLOAD_DIR" > "$HOME/.config/pocketdesk/download-dir"

# The left-hand list of every Open and Save dialog (ChatGPT's "attach", the browser's upload,
# the file manager): the phone's files and the computer's own, side by side.
printf 'file://%s Download destination\nfile:///home/coder/Downloads Computer Downloads\nfile:///home/coder/Phone Phone files\nfile:///home/coder/Phone/Download Phone Downloads\nfile:///home/coder/Phone/DCIM Phone Photos\nfile:///home/coder/Phone/Documents Phone Documents\nfile:///home/coder/Pictures Pictures\nfile:///home/coder/Projects Projects\nfile:///home/coder/Shared App shared folder\n' \
  "$DOWNLOAD_DIR" > "$HOME/.config/gtk-3.0/bookmarks"

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
# Written only while the file is still PocketLinux's. lxappearance rewrites it without the marker,
# and from then on the owner's own choice is what starts. A file written by a PocketLinux before
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
printf '[general]\nfontname=Monospace 12\nscrollback=5000\nbgcolor=#0d1526\nfgcolor=#f1f5fb\ncolor_preset=PocketLinux\npalette_color_0=#0d1526\npalette_color_1=#ff6b6b\npalette_color_2=#4ade80\npalette_color_3=#fbbf24\npalette_color_4=#7a9bff\npalette_color_5=#c792ea\npalette_color_6=#56d4dd\npalette_color_7=#c2cae6\npalette_color_8=#55607d\npalette_color_9=#ff8a8a\npalette_color_10=#86efac\npalette_color_11=#fcd34d\npalette_color_12=#a5bcff\npalette_color_13=#ddb0ff\npalette_color_14=#8beaf2\npalette_color_15=#f1f5fb\ngeometry_columns=100\ngeometry_rows=30\nhidescrollbar=false\ndisallowbold=false\nboldbright=true\ncursorblinks=false\ncursorunderline=false\naudiblebell=false\nvisualbell=false\n' \
  > "$HOME/.config/lxterminal/lxterminal.conf"

# Without this, opening a desktop icon raises PCManFM's "this seems to be an executable
# script - what do you want to do with it?" prompt instead of just launching the app.
printf '[config]\nquick_exec=1\nsingle_click=1\nconfirm_del=1\nterminal=lxterminal\n\n[ui]\nbig_icon_size=72\nsmall_icon_size=24\nthumbnail_size=128\n' \
  > "$HOME/.config/libfm/libfm.conf"

# window manager's menu, which lists every installed app, Phone files, the terminal and the
# window commands, rather than the file manager's own short one.
# PocketLinux's own wallpaper (a dark-blue square with Tux and the app's name -- Canonical's
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
PREFS
  printf 'user_pref("browser.download.dir", "%s");\nuser_pref("browser.download.folderList", 2);\nuser_pref("browser.download.useDownloadDir", %s);\n' \
    "$DOWNLOAD_DIR" "$([ "$DOWNLOAD_PROMPT" = 1 ] && printf false || printf true)" \
    >> "$FIREFOX_PROFILE/user.js"
fi

# The terminal's prompt says where you are. Left alone it reads "coder@localhost", because PRoot
# keeps the phone's own hostname and /etc/hostname cannot change it without a namespace this
# container does not have. Appended once, behind a marker, so an owner's own .bashrc is never
# overwritten and never added to twice.
if ! grep -q 'PocketLinux prompt' "$HOME/.bashrc" 2>/dev/null; then
  cat >> "$HOME/.bashrc" <<'PROMPT'

# PocketLinux prompt: the computer's name, the folder, the git branch, and a red mark when the
# last command failed -- the one thing a phone screen cannot afford to make you scroll for.
pd_branch() {
  git rev-parse --abbrev-ref HEAD 2>/dev/null | sed 's/^/ /'
}
pd_mark() { [ $? -eq 0 ] && printf '\001\033[38;5;75m\002$' || printf '\001\033[38;5;203m\002$'; }
PS1='\[\e[38;5;75m\]PocketLinux\[\e[0m\]:\[\e[38;5;150m\]\w\[\e[38;5;180m\]$(pd_branch)\[\e[0m\]`pd_mark`\[\e[0m\] '

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
# PocketLinux provides both itself over MCP: a picture of the window in front plus the words on
# it, and click, type, key and scroll. Registered here rather than at install time because the
# agents are installed after the computer is, and this runs at every start.
if [ -x /usr/local/bin/pocketdesk-mcp ]; then
  # Codex reads one TOML file. Appended once, behind its own marker.
  mkdir -p "$HOME/.codex"
  if ! grep -q 'mcp_servers.pocketdesk' "$HOME/.codex/config.toml" 2>/dev/null; then
    cat >> "$HOME/.codex/config.toml" <<'CODEXMCP'

# PocketLinux's desktop tools: appshot, click, type_text, press_key, scroll.
[mcp_servers.pocketdesk]
command = "python3"
args = ["/usr/local/bin/pocketdesk-mcp"]
startup_timeout_sec = 30
CODEXMCP
  fi
  # Claude Code is registered through its own command, so the file format stays its business.
  if command -v claude >/dev/null 2>&1 \
     && ! grep -q '"pocketdesk"' "$HOME/.claude.json" 2>/dev/null; then
    timeout --foreground --kill-after=2s 15s claude mcp add --scope user pocketdesk -- python3 /usr/local/bin/pocketdesk-mcp \
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

# Sign-ins, kept the way a desktop keeps them.
#
# Electron's safeStorage encrypts an app's token -- but only when libsecret finds a keyring. With
# none, every Electron app on Linux quietly falls back to writing the token in plain text, which
# is how the four AI apps would have stored their sign-ins here. gnome-keyring gives them a real
# one.
#
# It is unlocked with a key made once on this phone and kept in the same private storage as the
# computer itself, because there is nobody to type a password to a keyring on a phone. That is
# not a second lock on top of Android's -- Android's app sandbox is the lock, and App lock in
# Settings is the one the owner sets. What it does buy is that a token is never sitting in a
# config file in plain text, which is what an AI app copying files around could otherwise pick up.
# Start one session bus BEFORE keyring/portal users. A second dbus-launch later stranded the
# secrets service on a different bus from ChatGPT and Chrome during sign-in.
desktop_phase "Starting the desktop session bus"
# dbus-launch keeps a separate babysitter process alive. PRoot already owns the lifetime of
# every desktop child; start the daemon directly to leave that process slot for the apps.
DBUS_SESSION_BUS_ADDRESS=$(timeout --foreground --kill-after=2s 12s \
  dbus-daemon --session --fork --print-address=1)
if [ -n "$DBUS_SESSION_BUS_ADDRESS" ]; then
  export DBUS_SESSION_BUS_ADDRESS
else
  echo "session bus: direct start failed; trying the compatibility launcher"
  eval "$(timeout --foreground --kill-after=2s 12s dbus-launch --sh-syntax)"
fi
if command -v gnome-keyring-daemon >/dev/null 2>&1; then
  keyfile="$HOME/.pocketdesk/keyring-key"
  if [ ! -s "$keyfile" ]; then
    mkdir -p "$HOME/.pocketdesk"
    (head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n') > "$keyfile" 2>/dev/null || true
    chmod 600 "$keyfile" 2>/dev/null || true
  fi
  if [ -s "$keyfile" ] && ! pgrep -x gnome-keyring-d >/dev/null 2>&1; then
    eval "$(timeout --foreground --kill-after=2s 12s gnome-keyring-daemon --unlock --components=secrets < "$keyfile" 2>/dev/null)" || true
    export GNOME_KEYRING_CONTROL SSH_AUTH_SOCK
  fi
fi


desktop_phase "Preparing desktop icons and app links"
/usr/local/bin/pocketdesk-menu || true

# Sound. There is no sound card a container can reach, so PulseAudio plays into a virtual
# output called Phone, and everything written to it is streamed as plain PCM to PocketLinux's
# viewer, which plays it through the phone's own speaker. Every app that speaks PulseAudio
# (Electron apps, Chrome) simply finds a working output.
#
# The stream goes over a unix socket inside this app's private storage, not a TCP port:
# Android shares loopback between apps, so a port here could be read by any other app on the
# phone that holds the internet permission. The TCP module stays only as a fallback for a
# PulseAudio too old to have the unix one, so sound never simply stops working.
start_audio() {
  # Sound starts beside the visible desktop. An unavailable PulseAudio service must never
  # delay the display, and each client request has a deadline instead of hanging forever.
  audio_call() { timeout --foreground --kill-after=1s 5s "$@"; }
if command -v pulseaudio >/dev/null 2>&1; then
  mkdir -p "$HOME/.config/pulse"
  printf 'exit-idle-time = -1\ndefault-sample-rate = 44100\nflat-volumes = no\nenable-shm = no\n' \
    > "$HOME/.config/pulse/daemon.conf"
  printf 'autospawn = no\nenable-shm = no\n' > "$HOME/.config/pulse/client.conf"
  audio_call pulseaudio --kill >/dev/null 2>&1 || true
  audio_call pulseaudio --daemonize=yes --exit-idle-time=-1 --disable-shm=yes >/tmp/pocketdesk-pulse.log 2>&1 || true
  audio_call pactl info >/dev/null 2>&1 || return 0
  audio_call pactl load-module module-null-sink sink_name=phone sink_properties=device.description=Phone >/dev/null 2>&1 || true
  audio_call pactl set-default-sink phone >/dev/null 2>&1 || true
  # 100% and unmuted at every start. Android's media volume is the real control (see
  # AudioBridge): this sink's own level is a second gain stage on the very same sound, it is
  # applied to the monitor stream the phone reads, and PulseAudio's module-device-restore
  # remembers it by name -- so a level dropped once would follow the owner for ever, and a level
  # dropped far enough would fall into the viewer's own silence gate and stop sound altogether.
  # Per-app balance is still available: Tools -> Volume and sound opens pavucontrol.
  audio_call pactl set-sink-mute phone 0 >/dev/null 2>&1 || true
  audio_call pactl set-sink-volume phone 100% >/dev/null 2>&1 || true

  mkdir -p "$HOME/.pocketdesk"
  chmod 700 "$HOME/.pocketdesk" 2>/dev/null || true
  rm -f "$HOME/.pocketdesk/audio.sock"
  if audio_call pactl load-module module-simple-protocol-unix rate=44100 format=s16le channels=2 \
      source=phone.monitor record=true playback=false \
      socket="$HOME/.pocketdesk/audio.sock" >/dev/null 2>&1; then
    chmod 600 "$HOME/.pocketdesk/audio.sock" 2>/dev/null || true
    echo "sound: private socket" >> /tmp/pocketdesk-pulse.log
  else
    echo "sound: falling back to the local port" >> /tmp/pocketdesk-pulse.log
    audio_call pactl load-module module-simple-protocol-tcp rate=44100 format=s16le channels=2 \
      source=phone.monitor record=true playback=false listen=127.0.0.1 port=4712 >/dev/null 2>&1 || true
  fi

  # The phone's microphone, the other way round: a named pipe that PulseAudio reads as a real
  # recording device, so every program inside the computer -- a voice reply, a meeting page in
  # the browser, an AI app's dictation -- simply finds a microphone called "Phone microphone".
  #
  # A pipe rather than a socket because module-pipe-source is the one PulseAudio module that
  # turns bytes written by something outside PulseAudio into a source, and PocketLinux's Android
  # side can open a pipe in its own private storage like any other file. Nothing is recorded
  # until the owner turns the microphone on: with no writer the pipe simply reads as silence.
  rm -f "$HOME/.pocketdesk/mic.pipe"
  mkfifo -m 600 "$HOME/.pocketdesk/mic.pipe" 2>/dev/null || true
  if [ -p "$HOME/.pocketdesk/mic.pipe" ]; then
    # Keep the bounded client's failure output; a FIFO alone does not prove a source exists.
    if audio_call pactl load-module module-pipe-source source_name=phone_mic \
        source_properties=device.description='Phone microphone' \
        file="$HOME/.pocketdesk/mic.pipe" format=s16le rate=16000 channels=1 \
        >> /tmp/pocketdesk-pulse.log 2>&1; then
      if audio_call pactl set-default-source phone_mic >> /tmp/pocketdesk-pulse.log 2>&1; then
        echo "microphone: pipe source ready" >> /tmp/pocketdesk-pulse.log
      else
        echo "microphone: source loaded, but default selection failed (exit $?)" >> /tmp/pocketdesk-pulse.log
      fi
    else
      echo "microphone: pipe source setup failed (exit $?)" >> /tmp/pocketdesk-pulse.log
    fi
  fi
fi

}


desktop_phase "Drawing the desktop"
xrdb -merge "$HOME/.Xresources" >/dev/null 2>&1 || true
# The root window is grey until the file manager paints it, and grey again if the file manager is
# ever killed for memory. This is the same navy as the wallpaper's edge and the desktop's own
# background, so the seam is invisible either way.
xsetroot -solid '#0b1320' >/dev/null 2>&1 || true
xsetroot -cursor_name left_ptr >/dev/null 2>&1 || true
# The root preparation mode has already checked the system bus. Do not attempt another
# daemon here as coder: a second startup can unlink the listener used by running apps.
openbox-session >/tmp/pocketdesk-openbox.log 2>&1 &
DESKTOP_CHILDREN=("$!")
# One event-driven worker owns the panel's twelve-second startup check and download offers.
# The previous two shell watchers kept four helper processes alive during app startup (two
# remained indefinitely). Python reads inotify directly, so this work needs one process.
start_desktop_watch() {
  exec python3 - "$VNC_PID" "${DESKTOP_CHILDREN[@]}" <<'DESKTOPWATCH'
import ctypes
import errno
import fcntl
import importlib.util
import os
from pathlib import Path
import select
import signal
import struct
import subprocess
import sys
import threading
import time

EVENT = struct.Struct("iIII")
IN_CLOSE_WRITE = 0x00000008
IN_MOVED_TO = 0x00000080
IN_DELETE_SELF = 0x00000400
IN_MOVE_SELF = 0x00000800
IN_IGNORED = 0x00008000
IN_ISDIR = 0x40000000


def watch_directory(directory):
    # libc is already loaded by Python. No inotifywait, polling shell or daemon is needed.
    libc = ctypes.CDLL(None, use_errno=True)
    libc.inotify_init1.argtypes = [ctypes.c_int]
    libc.inotify_init1.restype = ctypes.c_int
    libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
    libc.inotify_add_watch.restype = ctypes.c_int
    descriptor = libc.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
    if descriptor < 0:
        raise OSError(ctypes.get_errno(), "inotify_init1")
    mask = IN_CLOSE_WRITE | IN_MOVED_TO | IN_DELETE_SELF | IN_MOVE_SELF
    if libc.inotify_add_watch(descriptor, os.fsencode(directory), mask) < 0:
        error = ctypes.get_errno()
        os.close(descriptor)
        raise OSError(error, "inotify_add_watch")
    return descriptor


def completed_downloads(data, directory):
    offset = 0
    while offset + EVENT.size <= len(data):
        _, mask, _, length = EVENT.unpack_from(data, offset)
        offset += EVENT.size
        name = os.fsdecode(data[offset:offset + length].split(b"\0", 1)[0])
        offset += length
        if mask & (IN_IGNORED | IN_DELETE_SELF | IN_MOVE_SELF):
            raise OSError(errno.ENOENT, "download directory is no longer watched")
        if not mask & (IN_CLOSE_WRITE | IN_MOVED_TO) or mask & IN_ISDIR:
            continue
        # Events name one completed file. Scanning the folder instead used to offer other
        # files while they were still being written.
        if not name or "/" in name:
            continue
        lowered = name.lower()
        # Every browser writes these while the transfer is still running.
        if lowered.endswith((".crdownload", ".part", ".tmp", ".download")) or name.startswith("."):
            continue
        yield directory / name


def notify(title, body, critical=False):
    command = ["notify-send", "-a", "PocketLinux", "-i", "pocketdesk-linux"]
    if critical:
        command += ["-u", "critical"]
    try:
        subprocess.run(command + [title, body], stdin=subprocess.DEVNULL,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        pass


def offer_download(path, children):
    """What happens the moment a download finishes.

    Two things, and neither used to happen at all. A Linux package is offered to PocketLinux's own
    installer, the way tapping an APK opens Android's. And every file, whatever it is, is placed
    where the owner said downloads should go: kept here in the computer, copied to the phone's own
    Download folder, or asked about. That setting is one file, read fresh for every download, so a
    change takes effect at once rather than at the next start.
    """
    if not path.is_file():
        return
    # Every browser writes these while a download is still running. Acting on one is acting on
    # half a file, and the marker below would then keep the finished file from being offered.
    if path.suffix.lower() in (".crdownload", ".part", ".tmp", ".download") or path.name.startswith("."):
        return
    marker = Path(str(path) + ".pocketdesk-seen")
    try:
        # Exactly one offer for a close+rename pair, or repeated writes to a finished file.
        with marker.open("x"):
            pass
    except FileExistsError:
        return
    except OSError:
        return

    if path.suffix.lower() == ".deb":
        notify("Linux app downloaded", path.name + " can be installed. Opening the installer.", True)
        try:
            # This is the existing confirmation UI, never a silent installation command.
            children.append(subprocess.Popen(["/usr/local/bin/pocketdesk-install", str(path)],
                                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                             stderr=subprocess.DEVNULL))
        except OSError:
            notify("Could not open the installer", "Open the downloaded file from Files to try again.")
        return

    try:
        children.append(subprocess.Popen(["/usr/local/bin/pocketdesk-save", str(path)],
                                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL))
    except OSError:
        # The file is downloaded and safe where it is; only the offer to move it was lost.
        pass


def start_panel(log):
    try:
        return subprocess.Popen(["tint2"], stdin=subprocess.DEVNULL, stdout=log, stderr=log)
    except OSError as error:
        print("panel: " + str(error), file=log, flush=True)
        return None


def load_childwatch():
    path = os.environ.get("POCKETDESK_CHILDWATCH_HELPER", "/usr/local/bin/pocketdesk-childwatch.py")
    spec = importlib.util.spec_from_file_location("childwatch", path)
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    return helper


# How close to Android's ceiling the computer may drift before something is closed on purpose.
# Six slots of headroom: an app being opened can add several processes between two checks, and
# being killed at 33 is indistinguishable, to the owner, from being killed at 40.
CROWDED_AT = 26
# Only act on a crowd that persists. A launch briefly spikes the count and then settles.
CROWDED_FOR_SECONDS = 6


def busiest_closable(proc=Path('/proc')):
    """The open program costing the most process slots that is safe to close and reopen.

    The browser first: it is the heaviest, and closing it loses a tab rather than a conversation.
    Never the desktop's own parts -- a computer with no panel is not a rescue.
    """
    groups = {}
    for entry in proc.iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            command = (entry / 'cmdline').read_bytes().split(b'\0', 1)[0]
        except OSError:
            continue
        name = os.fsdecode(command).rsplit('/', 1)[-1].lower()
        for known in ('chrome', 'chatgpt', 'claude', 'cursor', 'antigravity'):
            if known in name:
                groups.setdefault(known, []).append(int(entry.name))
    for candidate in ('chrome', 'antigravity', 'cursor', 'chatgpt', 'claude'):
        if len(groups.get(candidate, ())) >= 2:
            return candidate, groups[candidate]
    return None, []


def keep_under_ceiling(childwatch, crowded_since, now=None):
    """Close one program before Android kills the whole computer. Returns the new crowd start.

    Android gives an app 32 forked processes and takes the lot when that is passed. Under PRoot
    every Linux process is one of those, so the ceiling is the computer's. Reaching it is not a
    warning: it is the session ending mid-sentence. Ending one program instead is strictly better,
    and the owner is told which and why.
    """
    now = time.monotonic() if now is None else now
    if childwatch.process_count() < CROWDED_AT:
        return None
    if crowded_since is None:
        return now
    if now - crowded_since < CROWDED_FOR_SECONDS:
        return crowded_since
    name, pids = busiest_closable()
    if name is None:
        return now          # nothing safe to close; keep watching rather than kill a desktop part
    for pid in pids:
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError:
            pass
    notify("Closed " + name.title() + " to keep the computer running",
           "This phone lets an app run 32 programs at once, and the computer was at its limit. "
           "Closing one keeps everything else open; the whole computer would have stopped.", True)
    return None


def main(home=None, download=None, panel_delay=12, display_pid=None, inherited_pids=()):
    home = Path(os.environ["HOME"]) if home is None else Path(home)
    download = Path(os.environ["POCKETDESK_DOWNLOAD_DIR"]) if download is None else Path(download)
    state = home / ".pocketdesk"
    state.mkdir(parents=True, exist_ok=True)
    childwatch = load_childwatch()
    inherited = list(inherited_pids)
    # Orphans reparent here instead of nowhere, so they can be cleared rather than pile up.
    childwatch.become_subreaper()
    crowded_since = None
    with childwatch.ChildWakeup() as child_events, (state / "desktop-watch.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return 1 if display_pid is not None else 0
        with open("/tmp/pocketdesk-tint2.log", "a", buffering=1) as panel_log:
            panel = start_panel(panel_log)
            deadline = time.monotonic() + panel_delay
            children = [panel] if panel is not None else []
            pending = []
            offer_thread = None
            offer_children = []
            descriptor = None
            try:
                download.mkdir(parents=True, exist_ok=True)
                descriptor = watch_directory(download)
            except (OSError, AttributeError) as error:
                print("download offers: " + str(error), flush=True)
            try:
                while True:
                    # This worker replaces the desktop Bash waiter via exec. Xvnc
                    # remains our direct child, so preserve its real wait status.
                    # Reap only other known inherited children, never steal a
                    # Popen-owned panel/installer's status with waitpid(-1).
                    if display_pid is not None:
                        status = childwatch.inherited_child_status(display_pid)
                        if status is not None:
                            return status
                    inherited[:] = [pid for pid in inherited
                                    if childwatch.inherited_child_status(pid) is None]
                    # Clear finished processes nobody owns, every turn. This is the whole reason
                    # the session used to be killed: a zombie still holds an Android process slot,
                    # and a container has no init to wait for the ones an exiting app leaves.
                    owned = {child.pid for child in children}
                    owned.update(offer.pid for offer in offer_children)
                    owned.update(inherited)
                    if display_pid is not None:
                        owned.add(display_pid)
                    childwatch.reap_unowned(owned)
                    crowded_since = keep_under_ceiling(childwatch, crowded_since)
                    if offer_thread is not None and not offer_thread.is_alive():
                        children.extend(offer_children)
                        offer_children.clear()
                        offer_thread = None
                    # Reap only this worker's children; no pgrep/process-name match can mistake
                    # a different desktop's panel for the one we just started.
                    children[:] = [child for child in children if child.poll() is None]
                    now = time.monotonic()
                    if deadline is not None and now >= deadline:
                        if panel is None or panel.poll() is not None:
                            print("panel: tint2 did not stay up; using its own settings instead", file=panel_log)
                            config = home / ".config/tint2/tint2rc"
                            try:
                                config.replace(config.with_name("tint2rc.rejected"))
                            except OSError:
                                pass
                            panel = start_panel(panel_log)
                            if panel is not None:
                                children.append(panel)
                        deadline = None
                    if deadline is None and pending and offer_thread is None:
                        # Queue early downloads until the panel check is finished. A slow
                        # package inspection must not delay panel recovery or add installer
                        # helper processes to the desktop's first startup burst.
                        # Package inspection can take 20 seconds. A daemon thread
                        # keeps that optional prompt from delaying display death
                        # handling; it adds no native child just to wait.
                        offer_thread = threading.Thread(target=offer_download,
                                                        args=(pending.pop(0), offer_children), daemon=True)
                        offer_thread.start()
                        continue
                    if descriptor is None and deadline is None and not children and display_pid is None:
                        return 0
                    interval = max(0, deadline - now) if deadline is not None else None
                    # Only an in-progress optional offer needs a short thread
                    # completion check. Idle children wake us through SIGCHLD.
                    if offer_thread is not None:
                        interval = min(interval, 1) if interval is not None else 1
                    descriptors = [descriptor] if descriptor is not None else []
                    if child_events.reader is not None:
                        descriptors.append(child_events.reader)
                    ready, _, _ = select.select(descriptors, [], [], child_events.bounded_wait(interval))
                    if child_events.reader in ready:
                        child_events.drain()
                    if descriptor in ready:
                        try:
                            data = os.read(descriptor, 65536)
                            pending.extend(completed_downloads(data, download))
                        except BlockingIOError:
                            pass
                        except OSError as error:
                            print("download offers: " + str(error), flush=True)
                            os.close(descriptor)
                            descriptor = None
            finally:
                if descriptor is not None:
                    os.close(descriptor)


if __name__ == "__main__":
    raise SystemExit(main(display_pid=int(sys.argv[1]), inherited_pids=map(int, sys.argv[2:])))
DESKTOPWATCH
}
# File pickers, installer dialogs and other floating windows can keep their old landscape
# geometry after the phone turns to portrait. The guard listens for that screen/work-area
# change and keeps the complete decorated window above the panel. It is event-driven, so it
# consumes no repeating wmctrl scan while the desktop is sitting still.
/usr/local/bin/pocketdesk-window-guard watch >/tmp/pocketdesk-window-guard-start.log 2>&1 &
DESKTOP_CHILDREN+=("$!")
command -v dunst >/dev/null 2>&1 && dunst >/tmp/pocketdesk-dunst.log 2>&1 &
DESKTOP_CHILDREN+=("$!")
pcmanfm --desktop --profile LXDE >/tmp/pocketdesk-pcmanfm.log 2>&1 &
DESKTOP_CHILDREN+=("$!")
desktop_phase "Desktop services launched"
start_audio &
DESKTOP_CHILDREN+=("$!")
# The event worker also waits for Xvnc; replace this shell instead of keeping
# a separate Bash waiter alive for the entire desktop session.
start_desktop_watch
