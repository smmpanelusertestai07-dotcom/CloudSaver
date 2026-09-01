#!/bin/bash
# Starts a desktop app the way a phone container has to start it, and says so when it fails.
#
# Chromium-based apps -- ChatGPT, Claude, VS Code, Antigravity -- ask the kernel for a sandbox
# built on user namespaces. A container running under PRoot cannot give them one, so they exit
# before drawing a window and the tap looks like it did nothing at all. Every launcher goes
# through this script so those flags are never forgotten, and so a failed start leaves a message
# on screen and a log to read instead of silence.
#
# Usage: pocketdesk-open [--label <text>] <command> [args...]
set -u

label=""
if [ "${1:-}" = "--label" ]; then
  label=${2:-}
  shift 2
fi
[ "$#" -ge 1 ] || exit 0

command_name=$1
shift
name=$(basename "$command_name")
[ -n "$label" ] || label=$name

log_dir="$HOME/.pocketdesk/logs"
mkdir -p "$log_dir" 2>/dev/null || true
log="$log_dir/$name.log"

target=$(command -v "$command_name" 2>/dev/null || printf '%s' "$command_name")
real=$(readlink -f "$target" 2>/dev/null || printf '%s' "$target")
real_dir=$(dirname "$real")

notify() {   # notify <urgency> <summary> <body>
  command -v notify-send >/dev/null 2>&1 || return 0
  notify-send -a PocketDesk -u "$1" -i "$name" "$2" "$3" >/dev/null 2>&1 || true
}

# Chromium keeps these files beside its binary, and every Electron app inherits the layout.
# Finding one is what tells us the sandbox flags are needed -- and that they will be understood.
chromium_flags() {
  for dir in "$real_dir" "$real_dir/.." "/usr/lib/$name" "/usr/share/$name" "/opt/$name"; do
    for marker in chrome_100_percent.pak v8_context_snapshot.bin libvk_swiftshader.so resources/app.asar; do
      [ -e "$dir/$marker" ] && return 0
    done
  done
  return 1
}

flags=()
if chromium_flags; then
  flags=(--no-sandbox --disable-setuid-sandbox --disable-gpu-sandbox
         --disable-dev-shm-usage --disable-gpu
         # Without a keyring daemon the secret-service lookup blocks until it times out, which
         # reads as "the app never opened".
         --password-store=basic)
fi

{
  echo "--- $(date '+%Y-%m-%d %I:%M:%S %p') ---"
  echo "launching: $target ${flags[*]:-} $*"
} > "$log" 2>/dev/null

notify normal "Opening $label" "The first start can take up to a minute."

"$target" ${flags[@]+"${flags[@]}"} "$@" >> "$log" 2>&1 &
pid=$!

# Long enough for a cold Electron start to put a window up. Still running by then means it worked.
for _ in $(seq 1 24); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.5
done
if kill -0 "$pid" 2>/dev/null; then
  exit 0
fi

wait "$pid" 2>/dev/null
status=$?
[ "$status" = 0 ] && exit 0

# A bare number tells nobody anything. These three are the ones that actually happen on a phone.
case "$status" in
  137) reason="the phone ran out of memory and Android stopped it" ;;
  139) reason="it crashed" ;;
  134) reason="it stopped itself with an error" ;;
  *)   reason="exit code $status" ;;
esac

detail=$(grep -v '^ *$' "$log" | tail -n 8 | cut -c1-150)
message="$label stopped right after opening: $reason.

$detail

Full report: $log"

notify critical "$label could not open" "$reason. The report is in .pocketdesk/logs."
if command -v zenity >/dev/null 2>&1; then
  # zenity reads its text as Pango markup, so anything the app printed has to be escaped.
  markup=$(printf '%s' "$message" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g')
  zenity --error --width=620 --title="$label" --text="$markup" >/dev/null 2>&1 &
elif command -v xmessage >/dev/null 2>&1; then
  printf '%s\n' "$message" | xmessage -center -file - >/dev/null 2>&1 &
elif command -v lxterminal >/dev/null 2>&1; then
  # Last resort, and the one a container from an older version still has: show the report in a
  # terminal window rather than leaving the tap looking ignored.
  lxterminal --title="$label could not open" \
    -e sh -c "cat '$log'; echo; echo 'Press Enter to close'; read reply" >/dev/null 2>&1 &
fi
exit "$status"
