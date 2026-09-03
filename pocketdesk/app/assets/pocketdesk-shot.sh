#!/bin/bash
# A picture of the screen: saved in Pictures, copied into Shared where the phone's own Files app
# can open it, and put on the clipboard so it can be pasted straight into an AI chat.
set -u
export DISPLAY=${DISPLAY:-:1}
DIR="${HOME:-/home/coder}/Pictures"
mkdir -p "$DIR"
file="$DIR/Screenshot $(date '+%Y-%m-%d %H-%M-%S').png"
if [ "${1:-screen}" = window ]; then shot="scrot -u -d 1"; else shot="scrot -d 1"; fi
if $shot "$file" 2>/dev/null; then
  where="in Pictures"
  if [ -d "$HOME/Shared" ] && [ -w "$HOME/Shared" ] && cp -f "$file" "$HOME/Shared/" 2>/dev/null; then
    where="in Pictures, and in Shared for the phone"
  fi
  # xclip must stay alive to own the X selection; it lets go by itself when something else copies.
  command -v xclip >/dev/null 2>&1 &&
    xclip -selection clipboard -t image/png -i "$file" >/dev/null 2>&1 &
  notify-send -a PocketDesk -i "$file" 'Screenshot saved' "$(basename "$file") - $where" >/dev/null 2>&1 || true
else
  notify-send -a PocketDesk -u critical 'Screenshot failed' \
    'scrot could not read the screen. Tools are installed by Settings -> Update the computer basics.' >/dev/null 2>&1 || true
fi
