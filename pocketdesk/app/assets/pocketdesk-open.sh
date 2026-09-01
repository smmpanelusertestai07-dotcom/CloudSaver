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

# The retry after a kill: a smaller JavaScript heap, nothing else. --single-process was tried
# here and must never return -- with no GPU process at all, Chromium reports GPU access as
# denied, and ChatGPT's error reporter turns that into the fatal unhandled rejection that the
# 2.2.0 fix had removed. A test fails if it comes back.
lean_flags=(--js-flags=--max-old-space-size=256)

pid=""
has_window() {
  command -v xdotool >/dev/null 2>&1 || return 1
  # _NET_WM_PID first, since that is exact; the class is the fallback for apps that reparent.
  [ -n "$(xdotool search --onlyvisible --pid "$pid" 2>/dev/null)" ] && return 0
  [ -n "$(xdotool search --onlyvisible --class "$name" 2>/dev/null)" ]
}

# Every process of this app. Matching the launcher's own path missed ChatGPT entirely:
# /usr/bin/chatgpt resolves to a two-line shell script that execs /usr/lib/chatgpt/ChatGPT, so
# the running process never carries the launcher's name. Everything the app runs lives in its
# own directory, so that is what to match.
app_pids() {
  pgrep -f "$real_dir/" 2>/dev/null | grep -vx "$$" || true
}

# CPU time used so far by every process of this app, in clock ticks. A start that is still
# consuming CPU is still working -- loading a large app on a slow core under PRoot takes
# minutes, and killing it at a fixed second count was cutting it off mid-way. A start whose
# CPU use has stopped with no window is the one that is actually stuck.
cpu_ticks() {
  local total=0 p
  for p in $(app_pids); do
    total=$((total + $(awk '{print $14 + $15}' "/proc/$p/stat" 2>/dev/null || echo 0)))
  done
  echo "$total"
}

# Runs the app and waits for a window. Returns 0 once one appears or the app is still going,
# and the app's own exit code when it dies.
run_attempt() {
  "$target" "$@" >> "$log" 2>&1 &
  pid=$!
  local last_ticks=0 idle_seconds=0 elapsed
  for elapsed in $(seq 1 900); do
    kill -0 "$pid" 2>/dev/null || break
    if has_window; then
      echo "window appeared after ${elapsed}s" >> "$log"
      return 0
    fi
    if [ $((elapsed % 15)) = 0 ] && command -v xdotool >/dev/null 2>&1; then
      local now_ticks
      now_ticks=$(cpu_ticks)
      if [ $((now_ticks - last_ticks)) -lt 30 ]; then
        idle_seconds=$((idle_seconds + 15))
      else
        idle_seconds=0
      fi
      last_ticks=$now_ticks
      # Ninety seconds with no window and no CPU work is a hang, not a slow start.
      if [ "$idle_seconds" -ge 90 ] && [ "$elapsed" -ge 120 ]; then
        echo "no window and no CPU activity for ${idle_seconds}s at ${elapsed}s · treating as stuck · $(free_mb) MB free" >> "$log"
        kill -9 "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null
        return 137
      fi
    fi
    case "$elapsed" in
      30|60|120|240|420|600)
        notify normal "$label is still loading" "This try: ${elapsed}s · still working · $(free_mb) MB free" ;;
    esac
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "still running after 900s · leaving it to finish · $(free_mb) MB free" >> "$log"
    return 0
  fi
  wait "$pid" 2>/dev/null
  return $?
}

# A Chromium app killed mid-run -- by Android, or by the watchdog above -- leaves its
# single-instance lock behind. On the next start Chromium finds a lock it cannot read, tries to
# replace it, fails, and aborts on purpose: "Failed to create a ProcessSingleton for your profile
# directory... Aborting now." That is the tap doing nothing all over again, so every stale lock
# is cleared first. A lock whose process is still alive is left alone.
clean_stale_locks() {
  # Everything here is local: this function once overwrote the script's own $target with a
  # lock's readlink result, and the launcher then tried to run "localhost-16621" as the app.
  local lock profile lock_target lock_owner
  for lock in "$HOME"/.config/*/SingletonLock; do
    { [ -e "$lock" ] || [ -L "$lock" ]; } || continue
    profile=$(dirname "$lock")
    lock_target=$(readlink "$lock" 2>/dev/null || true)
    lock_owner=${lock_target##*-}
    if [ -n "$lock_owner" ] && [ "$lock_owner" -gt 0 ] 2>/dev/null && kill -0 "$lock_owner" 2>/dev/null; then
      continue
    fi
    echo "clearing stale lock in ${profile##*/}" >> "$log"
    rm -f "$profile/SingletonLock" "$profile/SingletonSocket" "$profile/SingletonCookie" \
          "$profile"/.l2s.Singleton* 2>/dev/null || true
  done
}

flags=()
is_chromium && flags=("${base_flags[@]}")


# ChatGPT honours this officially; setting it to its own default keeps the login while turning
# off the app's silent exit-if-second-instance path. The C++ lock below is handled separately.
[ "$name" = "chatgpt" ] && export CODEX_ELECTRON_USER_DATA_PATH="${CODEX_ELECTRON_USER_DATA_PATH:-$HOME/.config/Codex}"

{
  echo "--- $(date '+%Y-%m-%d %I:%M:%S %p') ---"
  echo "free memory at launch: $(free_mb) MB"
  echo "launching: $target ${flags[*]:-} $*"
} > "$log" 2>/dev/null

# A half-started instance that never drew a window still legitimately owns the single-instance
# socket. A fresh launch hands it the request, exits 0 -- "success" -- and nothing appears,
# which looks exactly like a dead tap and produces no error to read. If this app has processes
# but no window, those processes are the problem: end them, then start clean.
if [ "${#flags[@]}" -gt 0 ] && command -v xdotool >/dev/null 2>&1 \
   && [ -z "$(xdotool search --onlyvisible --class "$name" 2>/dev/null)" ]; then
  leftovers=$(app_pids | tr '\n' ' ')
  if [ -n "${leftovers// /}" ]; then
    echo "ending windowless leftover instance(s): $leftovers" >> "$log"
    kill $leftovers 2>/dev/null || true
    sleep 2
    kill -9 $leftovers 2>/dev/null || true
  fi
fi

[ "${#flags[@]}" -gt 0 ] && clean_stale_locks

notify normal "Opening $label" "The first start can take a minute or two."

handed_off() {   # true when the last attempt exited 0 at once, or Chromium said it deferred
  [ "$1" = 0 ] || return 1
  [ "${#flags[@]}" -gt 0 ] || return 1
  grep -q 'Opening in existing browser session' "$log" 2>/dev/null && return 0
  [ "$2" -lt 8 ] || return 1
  command -v xdotool >/dev/null 2>&1 || return 1
  [ -z "$(xdotool search --onlyvisible --class "$name" 2>/dev/null)" ]
}

launch_guarded() {   # launch_guarded <flags...> -- run_attempt, and once more if it was handed off
  local started status
  started=$(date +%s)
  run_attempt "$@"
  status=$?
  if handed_off "$status" $(( $(date +%s) - started )); then
    local stragglers
    stragglers=$(app_pids | tr '\n' ' ')
    echo "handed its request to another instance (${stragglers:-none found}) · ending it and starting again" >> "$log"
    [ -n "${stragglers// /}" ] && { kill $stragglers 2>/dev/null; sleep 2; kill -9 $stragglers 2>/dev/null; } || true
    clean_stale_locks
    run_attempt "$@"
    status=$?
  fi
  return "$status"
}

launch_guarded ${flags[@]+"${flags[@]}"} "$@"
status=$?
[ "$status" = 0 ] && exit 0

# 137 means the app was killed -- by Android for memory, or by the watch above for never
# drawing anything. Either way one more try with everything in a single process is worth more
# than a message saying it did not work.
if [ "$status" = 137 ] && [ "${#flags[@]}" -gt 0 ]; then
  echo "killed (137) · retrying with a smaller memory footprint · $(free_mb) MB free" >> "$log"
  notify normal "$label was stopped, trying again" "Starting it with less memory this time."
  launch_guarded "${flags[@]}" "${lean_flags[@]}" "$@"
  status=$?
  [ "$status" = 0 ] && exit 0
fi

append_own_log() {
  local own="$HOME/.local/state/codex/logs"
  [ "$name" = "chatgpt" ] && [ -d "$own" ] || return 0
  local newest
  newest=$(ls -t "$own" 2>/dev/null | head -n 1)
  [ -n "$newest" ] || return 0
  {
    echo "--- ChatGPT's own log: $newest ---"
    tail -n 25 "$own/$newest"
  } >> "$log" 2>/dev/null
}
append_own_log

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
