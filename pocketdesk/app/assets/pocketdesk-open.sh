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

free_mb() {
  # Tests set this; on the phone it is what the kernel says is still available.
  [ -n "${POCKETDESK_FREE_MB:-}" ] && { printf '%s' "$POCKETDESK_FREE_MB"; return 0; }
  awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null
}

# The browsers: they keep their extensions and background work, and they are what gets closed
# to make room for an AI app, never the other way round.
is_browser() {
  case "$name" in brave*|chromium*|google-chrome*|firefox*|epiphany*) return 0 ;; esac
  return 1
}

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
            # Every animated scroll frame is drawn on the CPU here; jump instead.
            --disable-smooth-scrolling
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

# Every process of this app. Matching the launcher's own path missed ChatGPT entirely:
# /usr/bin/chatgpt resolves to a two-line shell script that execs /usr/lib/chatgpt/ChatGPT, so
# the running process never carries the launcher's name. Everything the app runs lives in its
# own directory, so that is what to match.
# A shared directory (/usr/bin) names every program on the system, so there only this exact
# executable counts -- and the desktop's own "pcmanfm --desktop", started by name rather than
# by path, is not the file manager window the Files icon opens.
app_pids() {
  case "$real_dir" in
    */bin|*/sbin|*/games)
      pgrep -f "^$real( |$)" 2>/dev/null | grep -vx "$$" || true ;;
    *)
      pgrep -f "$real_dir/" 2>/dev/null | grep -vx "$$" || true ;;
  esac
}

# The id of a window owned by any process of this app, or nothing. Every window carries the
# pid of its owner (_NET_WM_PID), and wmctrl lists them all. The old check looked for a window
# with the app's class name -- and ChatGPT's window is not classed "chatgpt", so a running,
# visible ChatGPT counted as "no window": a second tap on its icon then ended it as a leftover,
# which is exactly what "ChatGPT closes by itself" looked like.
app_window() {   # app_window [strict]: strict = a window owned by one of the app's processes only
  local pids p id
  pids=" $(app_pids | tr '\n' ' ') ${pid:-0} "
  if command -v wmctrl >/dev/null 2>&1; then
    while read -r id _ p _; do
      [ -n "$p" ] && [ "$p" != 0 ] || continue
      case "$pids" in *" $p "*) printf '%s' "$id"; return 0 ;; esac
    done <<WINDOWS
$(wmctrl -lp 2>/dev/null)
WINDOWS
  fi
  [ "${1:-}" = strict ] && return 1
  command -v xdotool >/dev/null 2>&1 || return 1
  if [ -n "${pid:-}" ]; then
    id=$(xdotool search --onlyvisible --pid "$pid" 2>/dev/null | head -n 1)
    [ -n "$id" ] && { printf '%s' "$id"; return 0; }
  fi
  # The class name is the last resort while waiting for a window to appear; it is never used
  # to decide that an app is already open, because the wallpaper process carries the file
  # manager's class and would have counted.
  id=$(xdotool search --onlyvisible --class "$name" 2>/dev/null | head -n 1)
  [ -n "$id" ] && { printf '%s' "$id"; return 0; }
  return 1
}

# The browser that carried a sign-in has done its job once the link is back with the app;
# left open beside a 1.3 GB AI app it was the next thing the phone ran out of memory over.
close_browser_windows() {
  command -v wmctrl >/dev/null 2>&1 || return 0
  wmctrl -lx 2>/dev/null | awk 'tolower($3) ~ /epiphany|firefox|brave/ {print $1}' | while read -r id; do
    [ -n "$id" ] && wmctrl -ic "$id" 2>/dev/null || true
  done
}

browser_windows_open() {
  command -v wmctrl >/dev/null 2>&1 || return 1
  wmctrl -lx 2>/dev/null | awk 'tolower($3) ~ /epiphany|firefox|brave/' | grep -q .
}

has_window() { [ -n "$(app_window)" ]; }

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
  # Wall-clock throughout. Counting loop turns reported "3s" for a start that took forty,
  # because every xdotool call under PRoot costs seconds of its own.
  local t0 elapsed last_ticks=0 last_check=0 idle_since=-1 next_notice=30
  t0=$(date +%s)
  while :; do
    elapsed=$(( $(date +%s) - t0 ))
    kill -0 "$pid" 2>/dev/null || break
    if has_window; then
      echo "window appeared after ${elapsed}s" >> "$log"
      return 0
    fi
    [ "$elapsed" -ge 900 ] && break
    if [ $((elapsed - last_check)) -ge 15 ] && command -v xdotool >/dev/null 2>&1; then
      local now_ticks
      now_ticks=$(cpu_ticks)
      if [ $((now_ticks - last_ticks)) -lt 30 ]; then
        [ "$idle_since" -lt 0 ] && idle_since=$elapsed
      else
        idle_since=-1
      fi
      last_ticks=$now_ticks
      last_check=$elapsed
      # Ninety seconds with no window and no CPU work is a hang, not a slow start.
      if [ "$idle_since" -ge 0 ] && [ $((elapsed - idle_since)) -ge 90 ] && [ "$elapsed" -ge 120 ]; then
        echo "no window and no CPU activity since ${idle_since}s, now ${elapsed}s · treating as stuck · $(free_mb) MB free" >> "$log"
        kill -9 "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null
        return 137
      fi
    fi
    if [ "$elapsed" -ge "$next_notice" ]; then
      notify normal "$label is still loading" "This try: ${elapsed}s · still working · $(free_mb) MB free"
      case "$next_notice" in
        30) next_notice=60 ;; 60) next_notice=120 ;; 120) next_notice=240 ;;
        240) next_notice=420 ;; 420) next_notice=600 ;; *) next_notice=100000 ;;
      esac
    fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "still running after ${elapsed}s · leaving it to finish · $(free_mb) MB free" >> "$log"
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

# A browser is the one Chromium program here that must keep its extensions and its background
# work (extension and safe-browsing updates). For an AI app both are only memory.
if is_browser && [ "${#flags[@]}" -gt 0 ]; then
  kept=()
  for flag in "${flags[@]}"; do
    case "$flag" in --disable-extensions|--disable-background-networking) ;; *) kept+=("$flag") ;; esac
  done
  flags=("${kept[@]}")
fi


# ChatGPT honours this officially; setting it to its own default keeps the login while turning
# off the app's silent exit-if-second-instance path. The C++ lock below is handled separately.
[ "$name" = "chatgpt" ] && export CODEX_ELECTRON_USER_DATA_PATH="${CODEX_ELECTRON_USER_DATA_PATH:-$HOME/.config/Codex}"

# ChatGPT's main process asks Chromium for GPU information at startup and dies if the answer
# is "access denied". With this Chromium, --disable-gpu alone produces exactly that answer --
# the phone's log said so with no other GPU flag on the line. So for ChatGPT the GPU is not
# disabled but replaced: SwiftShader, the software GPU the package ships for this purpose,
# forced explicitly so nothing probes a real driver that is not there. GPU access stays
# "allowed", rendering happens on the CPU. Claude never asks, so its proven flags are untouched.
if [ "$name" = "chatgpt" ] && [ "${#flags[@]}" -gt 0 ]; then
  kept=()
  for flag in "${flags[@]}"; do
    [ "$flag" = "--disable-gpu" ] || kept+=("$flag")
  done
  flags=("${kept[@]}" --use-gl=angle --use-angle=swiftshader --ignore-gpu-blocklist)
fi

{
  echo "--- $(date '+%Y-%m-%d %I:%M:%S %p') ---"
  echo "free memory at launch: $(free_mb) MB"
  echo "launching: $target ${flags[*]:-} $*"
} > "$log" 2>/dev/null

# Already open (a Chromium app, which only ever runs once): bring its window to the front and
# stop here. A second copy would only hand its request to the first and exit, so launching
# again never helps. Ordinary programs open as many windows as they are asked for.
open_id=""
[ "${#flags[@]}" -gt 0 ] && open_id=$(app_window strict)
if [ -n "$open_id" ]; then
  echo "already open (window $open_id) · bringing it to the front" >> "$log"
  command -v wmctrl >/dev/null 2>&1 && wmctrl -ia "$open_id" 2>/dev/null
  # A link to deliver (a sign-in coming back from the browser): a second copy started with the
  # same flags hands it to the running app through the single-instance socket and exits. The
  # browser's part is then over, so its windows are closed to give the app the memory.
  if [ "$#" -gt 0 ]; then
    echo "handing it: $*" >> "$log"
    "$target" ${flags[@]+"${flags[@]}"} "$@" >> "$log" 2>&1
    sleep 2
    close_browser_windows
    echo "sign-in handed back · browser closed" >> "$log"
  fi
  exit 0
fi

# A half-started instance that never drew a window still legitimately owns the single-instance
# socket. A fresh launch hands it the request, exits 0 -- "success" -- and nothing appears,
# which looks exactly like a dead tap and produces no error to read. If this app has processes
# but no window, those processes are the problem: end them, then start clean.
if [ "${#flags[@]}" -gt 0 ]; then
  leftovers=$(app_pids | tr '\n' ' ')
  if [ -n "${leftovers// /}" ]; then
    echo "ending windowless leftover instance(s): $leftovers" >> "$log"
    kill $leftovers 2>/dev/null || true
    sleep 2
    kill -9 $leftovers 2>/dev/null || true
  fi
fi

[ "${#flags[@]}" -gt 0 ] && clean_stale_locks

# Memory guard. A browser and a 1.3 GB AI app do not both fit in what a 4 GB phone has left,
# and when they were both open it was the AI app that Android took the memory back from --
# which read as "ChatGPT closed by itself". So an AI app started while memory is short first
# closes the browser's windows (the browser only; nothing unsaved lives in a browser tab that
# a sign-in or a search needs), and says so.
if [ "${#flags[@]}" -gt 0 ] && ! is_browser; then
  free_now=$(free_mb)
  if [ -n "$free_now" ] && [ "$free_now" -lt 900 ] 2>/dev/null && browser_windows_open; then
    echo "only ${free_now} MB free · closing the browser to make room for $label" >> "$log"
    notify normal "Closing the browser" "Making room for $label: only ${free_now} MB free."
    close_browser_windows
    sleep 2
  fi
fi

notify normal "Opening $label" "The first start can take a minute or two."

handed_off() {   # true when the last attempt exited 0 at once, or Chromium said it deferred
  [ "$1" = 0 ] || return 1
  [ "${#flags[@]}" -gt 0 ] || return 1
  grep -q 'Opening in existing browser session' "$log" 2>/dev/null && return 0
  [ "$2" -lt 8 ] || return 1
  ! has_window
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

# Stays quietly until the app ends, and writes down how it ended: "closed by itself" is only a
# mystery while nobody records the exit. 137 is a kill, on a phone nearly always the system
# taking memory back; anything else is the app's own doing.
record_end() {
  local started_at end ran
  started_at=$(date +%s)
  wait "$pid" 2>/dev/null
  end=$?
  # The launcher we started may have handed over to the real program; wait for that too.
  while [ -n "$(app_pids)" ]; do sleep 5; done
  ran=$(( $(date +%s) - started_at ))
  echo "$(date '+%I:%M:%S %p') $label ended after ${ran}s · exit $end · $(free_mb) MB free" >> "$log"
  if [ "$end" = 137 ] || [ "$end" = 9 ]; then
    notify critical "$label was closed by the phone" "Memory ran short. Keep one AI app open at a time and close the browser when you are done with it."
  fi
}

launch_guarded ${flags[@]+"${flags[@]}"} "$@"
status=$?
if [ "$status" = 0 ]; then
  [ "${#flags[@]}" -gt 0 ] && [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && record_end
  exit 0
fi

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
