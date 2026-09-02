#!/bin/bash
# The window controls a computer is expected to have, reachable from the desktop menu.
#
# On a phone every window opens maximised, so one covering another is the normal case rather
# than the exception -- which makes "show me what is open" and "close everything" ordinary
# things to want, not power-user extras.
#
# Usage: pocketdesk-windows list|minimise-all|close-all|kill-active|count
set -u
export DISPLAY=${DISPLAY:-:1}

# tint2's own launchers and the desktop itself are not windows the user opened.
skip='pocketdesk-panel|tint2|pcmanfm.*--desktop|Desktop'

open_windows() {
  command -v wmctrl >/dev/null 2>&1 || return 0
  wmctrl -l | grep -Ev "$skip" || true
}

case "${1:-list}" in
  count)
    open_windows | wc -l
    ;;
  minimise-all)
    # Openbox's own show-desktop toggle does this without touching each window.
    command -v xdotool >/dev/null 2>&1 && xdotool key --clearmodifiers super+d 2>/dev/null
    while read -r id _; do
      [ -n "$id" ] || continue
      xdotool windowminimize "$id" 2>/dev/null || true
    done <<EOF
$(open_windows)
EOF
    ;;
  close-all)
    count=$(open_windows | wc -l)
    if [ "$count" = 0 ]; then
      command -v notify-send >/dev/null 2>&1 &&
        notify-send -a PocketDesk "Nothing to close" "No windows are open." || true
      exit 0
    fi
    if command -v zenity >/dev/null 2>&1; then
      zenity --question --title="Close all windows" \
        --text="Close $count open window(s)? Anything unsaved will be lost." || exit 0
    fi
    while read -r id _; do
      [ -n "$id" ] || continue
      wmctrl -i -c "$id" 2>/dev/null || true
    done <<EOF
$(open_windows)
EOF
    # A hung app ignores the polite request. Whatever is still open after a moment is ended
    # outright, so "Close all" always ends with nothing open.
    sleep 3
    while read -r id _; do
      [ -n "$id" ] || continue
      command -v xdotool >/dev/null 2>&1 && xdotool windowkill "$id" 2>/dev/null || true
    done <<EOF
$(open_windows)
EOF
    ;;
  kill-active)
    # Force close: the window in front is ended without asking it, for an app that has stopped
    # answering. Bound to Super+F4 in Openbox and to Force close on the phone's toolbar.
    command -v xdotool >/dev/null 2>&1 || exit 0
    id=$(xdotool getactivewindow 2>/dev/null) || exit 0
    [ -n "$id" ] || exit 0
    # A tap on the wallpaper focuses the desktop itself; that, and the panel, are never
    # "the app in front", so they are refused exactly as the other commands skip them.
    hex=$(printf '0x%08x' "$id" 2>/dev/null) || exit 0
    wmctrl -l 2>/dev/null | grep -i "^$hex" | grep -Eqv "$skip" || exit 0
    xdotool windowkill "$id" 2>/dev/null || true
    ;;
  list|*)
    rows=""
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      id=$(printf '%s' "$line" | awk '{print $1}')
      title=$(printf '%s' "$line" | cut -d' ' -f5-)
      [ -n "$title" ] || title=$id
      rows="$rows$id
$title
"
    done <<EOF
$(open_windows)
EOF
    if [ -z "$rows" ]; then
      command -v notify-send >/dev/null 2>&1 &&
        notify-send -a PocketDesk "No open windows" "Open an app from the desktop or the menu." || true
      exit 0
    fi
    command -v zenity >/dev/null 2>&1 || exit 0
    chosen=$(printf '%s' "$rows" | zenity --list --title="Open windows" \
      --text="Pick one to bring to the front" --column="id" --column="Window" \
      --hide-column=1 --print-column=1 --width=460 --height=360) || exit 0
    [ -n "$chosen" ] && wmctrl -i -a "$chosen" 2>/dev/null || true
    ;;
esac
