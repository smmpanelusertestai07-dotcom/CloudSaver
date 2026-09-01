#!/bin/bash
# Starts a desktop app the way a phone container has to start it, and says so when it fails.
#
# Chromium-based apps -- ChatGPT, Claude, Cursor, Antigravity -- ask the kernel for a sandbox
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

free_mb() { awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null; }

notify() {   # notify <urgency> <summary> <body>
  command -v notify-send >/dev/null 2>&1 || return 0
  notify-send -a PocketDesk -u "$1" -i "$name" "$2" "$3" >/dev/null 2>&1 || true
}

# Chromium keeps these files beside its binary, and every Electron app inherits the layout.
# Finding one is what tells us the sandbox flags are needed -- and that they will be understood.
is_chromium() {
  for dir in "$real_dir" "$real_dir/.." "/usr/lib/$name" "/usr/share/$name" "/opt/$name"; do
    for marker in chrome_100_percent.pak v8_context_snapshot.bin libvk_swiftshader.so resources/app.asar; do
      [ -e "$dir/$marker" ] && return 0
    done
  done
  return 1
}

base_flags=(--no-sandbox --disable-setuid-sandbox --disable-gpu-sandbox
            --disable-dev-shm-usage
            # Every extra process costs far more here than on a PC: PRoot traces each one, and
            # each re-executes a binary of 200-300 MB. The zygote stays -- it is forked, not
            # executed -- and everything optional around it goes.
            --in-process-gpu
            --renderer-process-limit=1
            --process-per-site
            # A spare renderer kept warm, and a process per origin, are both memory this phone
            # does not have. Site isolation is a real boundary, but --no-sandbox above is already
            # a larger one, and this is the difference between opening and not.
            --disable-features=SpareRendererForSitePerProcess,IsolateOrigins,site-per-process
            # Chromium's own switch for machines like this one: smaller caches, fewer threads.
            --enable-low-end-device-mode
            # --disable-gpu alone leaves software rasterisation available, which is what
            # Chromium needs to still report GPU access as possible. Adding
            # --disable-software-rasterizer on top denies it outright, and ChatGPT's main
            # process asks for it at startup: the rejected promise went unhandled and no
            # window was ever created. Claude never asks, which is why only one of them opened.
            --disable-gpu --disable-gpu-compositing
            --ozone-platform=x11
            # Without a keyring daemon the secret-service lookup blocks until it times out,
            # which reads as "the app never opened".
            --password-store=basic
            --js-flags=--max-old-space-size=384
            --disable-extensions
            --disable-background-networking
            --no-first-run
            --disable-crash-reporter)

# Everything in one process. Not how Electron expects to run, so it is the second try rather
# than the first -- but it is the only thing left that meaningfully cuts memory.
lean_flags=(--single-process --no-zygote --js-flags=--max-old-space-size=256)

pid=""
has_window() {
  command -v xdotool >/dev/null 2>&1 || return 1
  # _NET_WM_PID first, since that is exact; the class is the fallback for apps that reparent.
  [ -n "$(xdotool search --onlyvisible --pid "$pid" 2>/dev/null)" ] && return 0
  [ -n "$(xdotool search --onlyvisible --class "$name" 2>/dev/null)" ]
}

# Runs the app and waits for a window. Returns 0 once one appears or the app is still going,
# and the app's own exit code when it dies.
run_attempt() {
  "$target" "$@" >> "$log" 2>&1 &
  pid=$!
  for elapsed in $(seq 1 150); do
    kill -0 "$pid" 2>/dev/null || break
    if has_window; then
      echo "window appeared after ${elapsed}s" >> "$log"
      return 0
    fi
    case "$elapsed" in
      30|60|90|120)
        notify normal "$label is still starting" "$elapsed seconds so far · $(free_mb) MB free" ;;
    esac
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    if command -v xdotool >/dev/null 2>&1; then
      echo "still running after 150s with no window · $(free_mb) MB free" >> "$log"
    else
      echo "still running after 150s · window state unknown (xdotool not installed)" >> "$log"
    fi
    return 0
  fi
  wait "$pid" 2>/dev/null
  return $?
}

flags=()
is_chromium && flags=("${base_flags[@]}")

{
  echo "--- $(date '+%Y-%m-%d %I:%M:%S %p') ---"
  echo "free memory at launch: $(free_mb) MB"
  echo "launching: $target ${flags[*]:-} $*"
} > "$log" 2>/dev/null

notify normal "Opening $label" "The first start can take a minute or two."

run_attempt ${flags[@]+"${flags[@]}"} "$@"
status=$?
[ "$status" = 0 ] && exit 0

# 137 is SIGKILL: something outside the app stopped it, and on a phone that is nearly always
# memory pressure. One more try with everything in a single process is worth more than a
# message saying it did not work.
if [ "$status" = 137 ] && [ "${#flags[@]}" -gt 0 ]; then
  echo "killed (137) · retrying in a single process · $(free_mb) MB free" >> "$log"
  notify normal "$label was stopped, trying again" "Starting it in a smaller, single-process mode."
  run_attempt "${flags[@]}" "${lean_flags[@]}" "$@"
  status=$?
  [ "$status" = 0 ] && exit 0
fi

case "$status" in
  137) reason="Android stopped it, which on a phone nearly always means memory ran short" ;;
  139) reason="it crashed" ;;
  134) reason="it stopped itself with an error" ;;
  *)   reason="exit code $status" ;;
esac
message="$label stopped right after opening: $reason.

Free memory now: $(free_mb) MB

$(grep -v '^ *$' "$log" | tail -n 8 | cut -c1-150)

Full report: $log"

notify critical "$label could not open" "$reason. The report is in .pocketdesk/logs."
if command -v zenity >/dev/null 2>&1; then
  # zenity reads its text as Pango markup, so anything the app printed has to be escaped.
  markup=$(printf '%s' "$message" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g')
  zenity --error --width=620 --title="$label" --text="$markup" >/dev/null 2>&1 &
elif command -v xmessage >/dev/null 2>&1; then
  printf '%s\n' "$message" | xmessage -center -file - >/dev/null 2>&1 &
elif command -v lxterminal >/dev/null 2>&1; then
  lxterminal --title="$label could not open" \
    -e sh -c "cat '$log'; echo; echo 'Press Enter to close'; read reply" >/dev/null 2>&1 &
fi
exit "$status"
