#!/bin/bash
# System settings, inside the computer.
#
# The computer had no settings of its own. Everything it can change was real and reachable --
# the bar's edge at the bottom of a right-click menu, the icon theme behind a menu called
# "Appearance", the sound mixer two levels down in Tools -- but nothing on the desktop said
# "settings", so none of it was findable. This is that one place.
#
# It deliberately owns nothing. Every row runs a tool that already exists, and the two settings
# that belong to the phone rather than to the computer (where new files are saved, and the app
# lock) say so and point at PocketLinux's own Settings instead of pretending otherwise: the
# desktop is started fresh from those values each time, so a copy kept here would be overwritten
# the next morning and the owner would never know which one had won.
set -u
HOME_DIR=${HOME:-/home/coder}
CONFIG="$HOME_DIR/.config/pocketdesk"
WINDOWS=/usr/local/bin/pocketdesk-windows

have() { command -v "$1" >/dev/null 2>&1; }

tell() {   # tell <title> <text>
  if have zenity; then
    zenity --info --no-markup --width=560 --title="$1" --text="$2" 2>/dev/null || true
  else
    printf '%s\n\n%s\n' "$1" "$2"
  fi
}

start() {   # start <command...> -- detached, so Settings does not stay open behind it
  if have "$1"; then "$@" >/dev/null 2>&1 & else
    tell "Not installed" "$1 is not installed on this computer yet. Software can install it."
  fi
}

panel_edge() {
  edge=$(cat "$CONFIG/panel-edge" 2>/dev/null)
  case "$edge" in top) echo top ;; *) echo bottom ;; esac
}

theme_now() {
  grep -q '^gtk-application-prefer-dark-theme=0' "$HOME_DIR/.config/gtk-3.0/settings.ini" 2>/dev/null \
    && echo light || echo dark
}

# Light and dark, written the way the desktop's own start-up writes them, so one file is the
# truth and nothing here invents a second theme system. Windows already open keep the look they
# started with -- GTK reads this once per program -- and the note says so rather than leaving
# the owner to wonder why one window did not change.
set_theme() {   # set_theme dark|light
  want=$1
  case "$want" in light) prefer=0 ;; *) prefer=1 ;; esac
  for gtk_dir in "$HOME_DIR/.config/gtk-3.0" "$HOME_DIR/.config/gtk-4.0"; do
    ini="$gtk_dir/settings.ini"
    [ -f "$ini" ] || continue
    tmp="$ini.pocketdesk-new"
    awk -v prefer="$prefer" '
      /^gtk-application-prefer-dark-theme=/ { print "gtk-application-prefer-dark-theme=" prefer; next }
      { print }
    ' "$ini" > "$tmp" 2>/dev/null && mv -f "$tmp" "$ini"
  done
  printf '%s\n' "$want" > "$CONFIG/theme"
  tell "Theme: $want" "Windows opened from now on use the $want theme.
Anything already open keeps the look it started with -- every Linux program reads the theme once,
when it starts. Close and open it, or reopen the desktop, to see it change."
}

download_note() {
  where=$(cat "$CONFIG/download-dir" 2>/dev/null)
  [ -n "$where" ] || where="$HOME_DIR/Downloads"
  tell "Where new files are saved" "Right now: $where

This one belongs to the phone, not to the computer: PocketLinux writes it into the computer every
time the desktop starts, so a change made here would be replaced the next time you opened it.

Change it in PocketLinux -> Settings -> Where new files are saved. The three choices are the
computer's own Downloads, the phone's Download folder, or Ask each time."
}

about() {
  version=$(cat /var/lib/pocketdesk/basics-version 2>/dev/null || echo unknown)
  release=$(. /etc/os-release 2>/dev/null; printf '%s' "${PRETTY_NAME:-Ubuntu}")
  tell "About this computer" "$release on ARM64, running on your phone.

Desktop: Openbox and tint2, drawn by PocketLinux.
Screen: $(printf '%s' "${DISPLAY:-:1}") at $(xdpyinfo 2>/dev/null | awk '/dimensions:/ { print $2; exit }' || echo 'unknown')
Text size: $(awk -F: '/^Xft\.dpi:/ { gsub(/[^0-9]/, "", $2); print $2 " dpi"; exit }' "$HOME_DIR/.Xresources" 2>/dev/null || echo 'unknown')
Theme: $(theme_now)
PocketLinux basics: $version

Everything here is ordinary Ubuntu. Nothing is emulated and nothing is remote: the programs are
ARM64 Linux binaries running on this phone's own processor."
}

menu() {
  edge=$(panel_edge)
  case "$edge" in top) move_to="bottom" ;; *) move_to="top" ;; esac
  theme=$(theme_now)
  case "$theme" in light) other_theme="dark" ;; *) other_theme="light" ;; esac

  if ! have zenity; then
    printf 'usage: pocketdesk-settings [theme-dark|theme-light|panel|appearance|sound|storage|software|downloads|about]\n'
    return 0
  fi
  choice=$(zenity --list --radiolist --width=660 --height=470 \
    --title="System settings" \
    --text="The computer's own settings. Phone permissions and the app lock live in PocketLinux." \
    --column="" --column="id" --column="Setting" --column="Now" \
    --hide-column=2 --print-column=2 \
    TRUE  theme      "Theme -- switch to the $other_theme theme" "$theme" \
    FALSE panel      "Where the bar sits -- move it to the $move_to" "$edge" \
    FALSE size       "Text and icon size" "PocketLinux Settings" \
    FALSE appearance "Appearance -- icons, fonts, cursors" "lxappearance" \
    FALSE sound      "Sound -- output, input and levels" "pavucontrol" \
    FALSE storage    "Storage -- what is using the space" "" \
    FALSE software   "Software and updates" "" \
    FALSE downloads  "Where new files are saved" "$(cat "$CONFIG/download-dir" 2>/dev/null || echo "$HOME_DIR/Downloads")" \
    FALSE refresh    "Refresh the app list and the desktop" "" \
    FALSE about      "About this computer" "" \
    2>/dev/null) || return 0
  [ -n "$choice" ] || return 0
  run "$choice"
}

run() {
  case "$1" in
    theme)      [ "$(theme_now)" = dark ] && set_theme light || set_theme dark ;;
    theme-dark) set_theme dark ;;
    theme-light) set_theme light ;;
    panel)      "$WINDOWS" panel-edge "$( [ "$(panel_edge)" = top ] && echo bottom || echo top )" ;;
    size)       tell "Text and icon size" "The size of everything on this desktop is set in PocketLinux -> Settings -> Desktop text size.

It is one number -- the screen's dpi -- and every program on the computer reads it when it starts,
which is why it applies the next time the desktop is opened rather than straight away.

Inside the desktop screen you can also use Screen -> Bigger interface, which takes effect at once." ;;
    appearance) start lxappearance ;;
    sound)      start pavucontrol ;;
    storage)    start /usr/local/bin/pocketdesk-storage ;;
    software)   start /usr/local/bin/pocketdesk-software ;;
    downloads)  download_note ;;
    refresh)    /usr/local/bin/pocketdesk-menu >/dev/null 2>&1; "$WINDOWS" refresh >/dev/null 2>&1 || true ;;
    about)      about ;;
    *)          menu ;;
  esac
}

run "${1:-menu}"
