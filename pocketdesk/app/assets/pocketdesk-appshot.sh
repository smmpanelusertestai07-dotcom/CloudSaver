#!/bin/bash
# One key, and whatever is on screen goes to the AI app -- PocketLinux's answer to Cmd-Cmd.
#
# On a Mac, Codex's Appshots capture the window in front and hand it to the assistant without
# being asked. That feature is macOS only and will not come to a phone, so this is it, rebuilt
# from parts this desktop already has: scrot for the picture, tesseract for the words, xclip for
# the clipboard, xdotool and wmctrl for the windows.
#
# What happens on Super+Space:
#   1. the window in front is captured -- BEFORE anything is brought forward, or you would
#      photograph the assistant instead of the thing you wanted to ask about;
#   2. the picture goes on the clipboard as an image, and the words it contains beside it;
#   3. the AI app that is open is brought to the front and the picture is pasted into it.
# With no AI app open it stops after step 2 and says so, so the capture is never lost.
set -u
HOME_DIR=/home/coder
SHOTS="$HOME_DIR/Pictures/Appshots"
mkdir -p "$SHOTS"

note() {   # note <title> <body>
  if command -v notify-send >/dev/null 2>&1; then
    notify-send -a PocketLinux -i pocketdesk-linux "$1" "$2" 2>/dev/null || true
  fi
  printf '%s: %s\n' "$1" "$2"
}

for tool in scrot xdotool xclip; do
  command -v "$tool" >/dev/null 2>&1 || {
    note "Appshot needs $tool" "Settings, Update the computer's basics will install it."
    exit 1
  }
done

# The window in front, remembered before anything moves.
target=$(xdotool getactivewindow 2>/dev/null || true)
title=""
[ -n "$target" ] && title=$(xdotool getwindowname "$target" 2>/dev/null || true)

# An AI app's own window must never be the subject of its own appshot.
case "$title" in
  ChatGPT*|Claude*|Cursor*|Antigravity*) target=""; title="the desktop" ;;
esac

stamp=$(date +%Y%m%d-%H%M%S)
shot="$SHOTS/appshot-$stamp.png"
if [ -n "$target" ]; then
  scrot --overwrite -u "$shot" 2>/dev/null || scrot --overwrite "$shot" 2>/dev/null || true
else
  scrot --overwrite "$shot" 2>/dev/null || true
fi
[ -s "$shot" ] || { note "Appshot failed" "The picture could not be taken."; exit 1; }

# The picture on the clipboard, so it can be pasted anywhere that accepts an image.
xclip -selection clipboard -t image/png -i "$shot" 2>/dev/null &
sleep 0.4

# The words too, saved beside it: an assistant that cannot see an image can still read these,
# and the owner can open them from Pictures/Appshots.
words=""
if command -v tesseract >/dev/null 2>&1; then
  words=$(tesseract "$shot" stdout --psm 6 2>/dev/null | sed '/^[[:space:]]*$/d')
  [ -n "$words" ] && printf '%s\n' "$words" > "${shot%.png}.txt"
fi

# The AI app that is open, if there is one, and the paste into it.
pasted=""
if command -v wmctrl >/dev/null 2>&1; then
  for app in Claude ChatGPT Cursor Antigravity; do
    if wmctrl -l | grep -qi "[[:space:]]$app"; then
      wmctrl -a "$app" >/dev/null 2>&1 || continue
      sleep 0.6
      xdotool key --clearmodifiers ctrl+v >/dev/null 2>&1 || true
      pasted=$app
      break
    fi
  done
fi

count=$(printf '%s' "${words:-}" | wc -w | tr -d ' ')
if [ -n "$pasted" ]; then
  note "Appshot sent to $pasted" \
    "${title:-The window in front} · ${count:-0} words read · also saved in Pictures/Appshots"
else
  note "Appshot copied" \
    "Open an AI app and paste it. ${title:-The window in front} · ${count:-0} words read · saved in Pictures/Appshots"
fi
