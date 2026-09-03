#!/bin/bash
# Storage, from inside the computer.
#
# One statfs is the whole measurement. PRoot maps / to PocketDesk's own private folder on the
# phone, so the kernel answers for the phone's data partition: the same partition Android's own
# Settings screen counts, and the same free figure the PocketDesk app shows. There is no
# PocketDesk quota to report, because Android gives an app none. What this computer is itself
# using is not shown here -- the only way to get it in the container is a du over gigabytes of
# Ubuntu, minutes of disk competing with the running apps. PocketDesk -> Settings -> Storage has
# that number, from Android, instantly.
set -u
export LC_ALL=C

gb() {   # gb <bytes>: decimal, exactly as Android's own storage screen counts
  awk -v b="$1" 'BEGIN { if (b >= 1000000000) printf "%.1f GB", b / 1000000000;
                         else printf "%.0f MB", b / 1000000 }'
}

set -- $(stat -f -c '%S %a %b' / 2>/dev/null \
  | awk 'NF == 3 && $1 > 0 { printf "%.0f %.0f", $1 * $2, $1 * $3 }')
free_bytes=${1:-}
total_bytes=${2:-}

if [ -z "$free_bytes" ]; then
  body="This phone did not report its free space just now.

Open PocketDesk on the phone: the home screen and Settings -> Storage always show it."
else
  advice="There is room here to install another AI app and keep working."
  if [ "$free_bytes" -lt 500000000 ]; then
    advice="Android is about to start clearing app caches by itself. Free some space now: remove an AI app you are not using from PocketDesk's Apps tab, or empty Downloads."
  elif [ "$free_bytes" -lt 2000000000 ]; then
    advice="Below about 2 GB free, watch it: an app install or a large download can run out part-way."
  fi
  body="Free on this phone: $(gb "$free_bytes")
Phone storage in total: $(gb "$total_bytes")

$advice

This computer has no size limit of its own. It grows into whatever the phone has free and gives
the space back when you remove an app; Android sets no quota for it, so the figure above is the
whole ceiling, shared with your photos and your Android apps. If it ever reaches zero, an install
or a download stops with 'no space left on device' and tells you -- what you have already saved
is not touched and the desktop keeps running.

Projects, Downloads and Pictures are inside the computer. Shared and Phone are on the phone
itself. All of them count against the same free space.

PocketDesk -> Settings -> Storage on the phone shows what this computer is using."
fi

if command -v zenity >/dev/null 2>&1; then
  zenity --info --width=430 --no-markup --title="Storage" --text="$body" >/dev/null 2>&1
elif command -v notify-send >/dev/null 2>&1; then
  notify-send -a PocketDesk -u normal "Storage" "$body" >/dev/null 2>&1
else
  printf '%s\n' "$body"
fi
