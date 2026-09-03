#!/bin/bash
# The window controls a computer is expected to have, reachable from the desktop menu.
#
# On a phone every window opens maximised, so one covering another is the normal case rather
# than the exception -- which makes "show me what is open" and "close everything" ordinary
# things to want, not power-user extras.
#
# Usage: pocketdesk-windows list|minimise-all|close-all|kill-active|fit|minimise|panel-edge|count|menu|refresh
set -u
export DISPLAY=${DISPLAY:-:1}

# The desktop's own root window and the panel are not windows the owner opened. They are told
# apart by what they ARE (their EWMH window type), not by what their title happens to contain:
# matching the word "Desktop" also skipped a real window whose title mentioned it, so Close all
# and Force close silently ignored, say, a Claude conversation called "Desktop setup".
is_own_furniture() {   # is_own_furniture <window id>
  if command -v xprop >/dev/null 2>&1; then
    xprop -id "$1" _NET_WM_WINDOW_TYPE 2>/dev/null \
      | grep -qE '_NET_WM_WINDOW_TYPE_(DESKTOP|DOCK)' && return 0
    return 1
  fi
  # No xprop (a container built before it was installed): fall back to the window class, which
  # names the program rather than whatever the window is called. Failing open here would let
  # Close all close the wallpaper and the panel.
  command -v wmctrl >/dev/null 2>&1 || return 1
  wmctrl -lx 2>/dev/null | grep -i "^$1" \
    | awk '{print $3}' | grep -qiE '^(tint2|pcmanfm)\.'
}

open_windows() {
  command -v wmctrl >/dev/null 2>&1 || return 0
  wmctrl -l 2>/dev/null | while read -r id rest; do
    [ -n "$id" ] || continue
    is_own_furniture "$id" && continue
    printf '%s %s\n' "$id" "$rest"
  done
}

case "${1:-list}" in
  count)
    open_windows | wc -l
    ;;
  menu)
    # The apps menu, from the panel's Apps button and the phone's Window menu. Openbox shows
    # its menu on Super+A (bound by pocketdesk-menu); a synthetic key press is the one way a
    # program can ask for it, so the menu opens where the pointer is.
    command -v xdotool >/dev/null 2>&1 && xdotool key --clearmodifiers super+a 2>/dev/null
    ;;
  refresh)
    # Redraws every window: the fix for a screen left with stale pieces after a heavy app.
    command -v xrefresh >/dev/null 2>&1 && xrefresh 2>/dev/null
    ;;
  minimise-all)
    # Openbox's own show-desktop toggle, and nothing else: the same command brings everything
    # back. Iconifying each window on top of it was the bug -- the second tap then un-hid a
    # desktop whose windows were all still minimised, and nothing came back.
    command -v xdotool >/dev/null 2>&1 && xdotool key --clearmodifiers super+d 2>/dev/null
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
    sleep 6
    while read -r id _; do
      [ -n "$id" ] || continue
      command -v xdotool >/dev/null 2>&1 && xdotool windowkill "$id" 2>/dev/null || true
    done <<EOF
$(open_windows)
EOF
    ;;
  fit)
    # A window dragged half off the screen, or un-maximised by a double tap, put back: top-left
    # corner, then maximised again. A root menu has no window to act on, so Openbox's own
    # Maximize cannot be reached from it; this works from the menu, the keys and the phone alike.
    command -v xdotool >/dev/null 2>&1 || exit 0
    id=$(xdotool getactivewindow 2>/dev/null) || exit 0
    [ -n "$id" ] || exit 0
    hex=$(printf '0x%08x' "$id" 2>/dev/null) || exit 0
    is_own_furniture "$hex" && exit 0
    wmctrl -i -r "$hex" -b remove,maximized_vert,maximized_horz 2>/dev/null || true
    xdotool windowmove "$id" 0 0 2>/dev/null || true
    wmctrl -i -r "$hex" -b add,maximized_vert,maximized_horz 2>/dev/null || true
    ;;
  minimise)
    command -v xdotool >/dev/null 2>&1 || exit 0
    id=$(xdotool getactivewindow 2>/dev/null) || exit 0
    [ -n "$id" ] || exit 0
    hex=$(printf '0x%08x' "$id" 2>/dev/null) || exit 0
    is_own_furniture "$hex" && exit 0
    xdotool windowminimize "$id" 2>/dev/null || true
    ;;
  panel-edge)
    # Which edge the bar lives on. pocketdesk-menu writes the new panel_position and tint2
    # rebuilds its panel from the file on SIGUSR1, so the bar moves without a restart.
    edge=${2:-bottom}
    case "$edge" in top|bottom) ;; *) edge=bottom ;; esac
    mkdir -p "$HOME/.config/pocketdesk"
    printf '%s\n' "$edge" > "$HOME/.config/pocketdesk/panel-edge"
    /usr/local/bin/pocketdesk-menu >/dev/null 2>&1 || true
    pkill -USR1 -x tint2 2>/dev/null || true
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
    # The desktop root window and the panel are never "the app in front": the same test the
    # listing uses, because $skip (a title match) is gone.
    is_own_furniture "$hex" && exit 0
    xdotool windowkill "$id" 2>/dev/null || true
    ;;
  list|*)
    rows=""
    active=$(command -v xdotool >/dev/null 2>&1 \
      && printf '0x%08x' "$(xdotool getactivewindow 2>/dev/null || echo 0)" 2>/dev/null || true)
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      id=$(printf '%s' "$line" | awk '{print $1}')
      # wmctrl prints "<id> <desktop> <host> <title>" padded with spaces, and cut counts each of
      # those spaces as another empty field -- which took the first word off a title whenever the
      # host name filled its column. Drop exactly three fields instead.
      title=$(printf '%s' "$line" | sed -E 's/^[[:space:]]*([^[:space:]]+[[:space:]]+){3}//')
      [ -n "$title" ] || title=$id
      [ -n "$active" ] && [ "$id" = "$active" ] && title="* $title"
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
      --text="* is the app you are looking at. Tap another to switch to it." \
      --column="id" --column="Window" \
      --hide-column=1 --print-column=1 --width=460 --height=360) || exit 0
    [ -n "$chosen" ] && wmctrl -i -a "$chosen" 2>/dev/null || true
    ;;
esac
