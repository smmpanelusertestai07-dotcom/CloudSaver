#!/bin/bash
# Starts a desktop app the way a phone container has to start it, and says so when it fails.
#
# Chromium-based apps -- ChatGPT, Claude, Cursor, Antigravity -- ask the kernel for a sandbox
# built on user namespaces. A container running under PRoot cannot give them one, so they exit
# before drawing a window and the tap looks like it did nothing at all. Every launcher goes
# through this script so those flags are never forgotten, and so a failed start leaves a message
# on screen and a log to read instead of silence.
#
# Usage: pocketdesk-open [--label <text>] [--real-dir <folder>] [--log-name <name>]
#        [--probe-seconds <n>]
#        <command> [args...]
set -u

label=""
profile=""
real_dir_override=""
log_name=""
probe_seconds=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --label)
      [ "$#" -ge 2 ] || exit 2
      label=$2
      shift 2 ;;
    --real-dir)
      [ "$#" -ge 2 ] || exit 2
      real_dir_override=$2
      shift 2 ;;
    --log-name)
      [ "$#" -ge 2 ] || exit 2
      log_name=$2
      shift 2 ;;
    --probe-seconds)
      [ "$#" -ge 2 ] || exit 2
      probe_seconds=$2
      shift 2 ;;
    --) shift; break ;;
    *) break ;;
  esac
done
[ "$#" -ge 1 ] || exit 0

command_name=$1
shift
# Desktop entries commonly start with `env KEY=value /opt/App/app %U`. Resolve the
# application after those assignments, otherwise flags and singleton checks target
# /usr/bin/env and the real Electron is launched without its required sandbox flag.
if [ "${command_name##*/}" = env ]; then
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --) shift; break ;;
      [A-Za-z_]*=*)
        assignment_name=${1%%=*}
        case "$assignment_name" in *[!A-Za-z0-9_]*) exit 2 ;; esac
        export "$1"
        shift ;;
      -*) echo 'Unsupported desktop env option; no application was started.' >&2; exit 2 ;;
      *) break ;;
    esac
  done
  [ "$#" -gt 0 ] || exit 2
  command_name=$1
  shift
fi
name=${profile:-$(basename "$command_name")}
# Both names refer to the same Chrome profile and must share the startup lock.
[ "$name" != google-chrome-stable ] || name=google-chrome
[ -n "$label" ] || label=$name

case "$probe_seconds" in
  ''|*[!0-9]*) probe_seconds=0 ;;
esac
[ "$probe_seconds" -le 600 ] 2>/dev/null || probe_seconds=600
max_wait=300
[ "$probe_seconds" -le 0 ] || max_wait=$probe_seconds

log_dir="$HOME/.pocketdesk/logs"
mkdir -p "$log_dir" 2>/dev/null || true
log_name=${log_name:-$name}
log_name=$(printf '%s' "$log_name" | tr -cd '[:alnum:]._-')
[ -n "$log_name" ] || log_name=app
log="$log_dir/$log_name.log"

target=$(command -v "$command_name" 2>/dev/null || printf '%s' "$command_name")
real=$(readlink -f "$target" 2>/dev/null || printf '%s' "$target")
real_dir=$(dirname "$real")
if [ -n "$real_dir_override" ] && [ -d "$real_dir_override" ]; then
  real_dir=$(CDPATH= cd -- "$real_dir_override" 2>/dev/null && pwd)
fi

launch_command=("$target")
# The stable Chrome wrapper in /opt/google/chrome keeps two `cat` children alive
# for stdout/stderr. PocketLinux's supervisor already owns both streams. Execute
# the adjacent native payload directly only for this known publisher layout, and
# retain the wrapper identity Chrome uses for browser/protocol integration.
# See chromium/chrome/installer/linux/common/wrapper.
if [ "$name" = google-chrome ] \
    && [[ "$real" = */opt/google/chrome/google-chrome ]] && [ -x "$real_dir/chrome" ]; then
  chrome_magic=""
  IFS= read -r -N 4 chrome_magic < "$real_dir/chrome" || true
  if [ "$chrome_magic" = $'\x7fELF' ]; then
    export CHROME_WRAPPER="$real" CHROME_VERSION_EXTRA=stable
    export GNOME_DISABLE_CRASH_DIALOG=SET_BY_GOOGLE_CHROME
    launch_command=("$real_dir/chrome")
  fi
fi
app_process_helper="$(dirname -- "$0")/pocketdesk-appprocess.py"
[ -f "$app_process_helper" ] || { echo "Linux app supervisor is missing." >&2; exit 17; }

# Software rendering, deliberately: probing an Android GPU through PRoot is slower than Mesa's
# software path and was the source of the black first frame on real devices.

free_mb() {
  # Tests set this; on the phone it is what the kernel says is still available.
  [ -n "${POCKETDESK_FREE_MB:-}" ] && { printf '%s' "$POCKETDESK_FREE_MB"; return 0; }
  awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null
}

# Browser state may include unfinished sign-in or unsaved work. Never close it automatically.
is_browser() {
  case "$name" in google-chrome*|chrome|brave-browser*|brave|chromium|chromium-browser|firefox|epiphany) return 0 ;; esac
  return 1
}

notify() {   # notify <urgency> <summary> <body>
  [ "$probe_seconds" -eq 0 ] || return 0
  command -v notify-send >/dev/null 2>&1 || return 0
  notify-send -a PocketLinux -u "$1" -i "$name" "$2" "$3" >/dev/null 2>&1 || true
}

# Do not claim measurements from the user's device. Show elapsed time while observing startup.
expected_wait() {
  case "$name" in
    chatgpt|claude-desktop|cursor|antigravity|code|codium)
      printf 'first startup may take several minutes' ;;
    *) printf 'waiting for its window' ;;
  esac
}

# The desktop's own "working on it": the round watch pointer, put back the moment the app draws
# a window or the launcher dies, whichever comes first.
#
# The pointer is the whole indicator, and that is a decision rather than an omission. A pulsing
# progress window would be a second GTK application started for every single launch -- more
# memory, and another Android child-process slot -- at the exact moment Chromium is creating its
# own workers, on a phone where both are already the scarce thing. The notification daemon
# already says what is opening and how long it usually takes.
spinner_open=0
spinner_start() {
  [ "$probe_seconds" -eq 0 ] || return 0
  command -v xsetroot >/dev/null 2>&1 && xsetroot -cursor_name watch >/dev/null 2>&1 || true
  spinner_open=1
}
spinner_stop() {
  [ "$spinner_open" = "1" ] || return 0
  spinner_open=0
  command -v xsetroot >/dev/null 2>&1 && xsetroot -cursor_name left_ptr >/dev/null 2>&1 || true
}
trap 'spinner_stop' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Chromium keeps these files beside its binary, and every Electron app inherits the layout.
# Finding one is what tells us the sandbox flags are needed -- and that they will be understood.
is_chromium() {
  # Publisher wrappers can live in /usr/bin while their payload lives elsewhere.
  # These are Chromium/Electron packages provided by PocketLinux's own catalogue.
  case "$name" in
    google-chrome*|chrome|chromium|chromium-browser|brave-browser*|brave|chatgpt|claude-desktop|cursor|antigravity|code|codium) return 0 ;;
  esac
  for dir in "$real_dir" "$real_dir/.." "/usr/lib/$name" "/usr/share/$name" "/opt/$name"; do
    for marker in chrome_100_percent.pak v8_context_snapshot.bin libvk_swiftshader.so resources/app.asar; do
      [ -e "$dir/$marker" ] && return 0
    done
  done
  return 1
}

base_flags=(--no-sandbox --disable-setuid-sandbox --disable-gpu-sandbox
            --disable-dev-shm-usage
            # No zygote: under PRoot the zygote's forked children reset their signal handlers,
            # which breaks the tracer and fills the log with "Error reading message from
            # browser: Function not implemented". Forking renderers straight from the browser
            # process avoids that whole path. Safe only with --no-sandbox, which is set above.
            --no-zygote
            # Keep optional GPU work in the browser process. Renderers still run as separate
            # processes; --renderer-process-limit is a hint, not a total child-process cap.
            --in-process-gpu
            # Chromium supports hosting networking on the browser's IO thread. One less
            # native utility child per Linux app leaves more room under Android's child
            # process budget; renderers stay separate.
            --enable-features=NetworkServiceInProcess2
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

pid=""
app_roots=()
case "$real_dir" in */bin|*/sbin|*/games) ;; *) app_roots+=("$real_dir") ;; esac
for app_root in "/usr/lib/$name" "/usr/share/$name" "/opt/$name"; do
  [ ! -d "$app_root" ] || app_roots+=("$app_root")
done

# Read-only native executable ownership; a shell argument containing the app path is not proof.
app_pids() {
    python3 "$app_process_helper" list "$real" "${app_roots[@]}"
}

window_is_product() { # window_is_product <window id> <wmctrl remainder>
  # A window whose owning process is one of this app's is this app's window. The title and
  # dialog-type filtering that used to live here existed only to reject the Windows layer's own
  # error boxes, which had no owning process to check; it never ran for a Linux app.
  return 0
}

window_pid_is_app() { # window_pid_is_app <pid> <space-padded known pids>
  local candidate=$1 known=${2:-}
  case "$known" in *" $candidate "*) return 0 ;; esac
  return 1
}

# The id of a window owned by any process of this app, or nothing. Every window carries the
# pid of its owner (_NET_WM_PID), and wmctrl lists them all. The old check looked for a window
# with the app's class name -- and ChatGPT's window is not classed "chatgpt", so a running,
# visible ChatGPT counted as "no window": a second tap on its icon then ended it as a leftover,
# which is exactly what "ChatGPT closes by itself" looked like.
# Return the window in app_window_id instead of stdout. Command substitutions fork another
# shell under PRoot, including when the function only checks an empty desktop.
app_window_id=""
app_window() {   # app_window [strict]: strict = a window owned by one of the app's processes only
  local pids p id windows owners owner
  local -a owner_list=()
  local -A owner_seen=()
  app_window_id=""
  pids=" ${pid:-0} "
  if command -v wmctrl >/dev/null 2>&1; then
    windows=$(wmctrl -lp 2>/dev/null)
    if true; then
      # Looking for a window used to stat every Android /proc/<pid>/exe once a
      # second, even with no windows. Under PRoot this competes with the startup
      # being observed. Check only the few PIDs actually supplied by the window manager.
      [ -n "$windows" ] || return 1
      # Parse and deduplicate in this shell. The old awk | sort and Python | tr
      # pipelines added short-lived children on every startup poll while the
      # publisher app was already spawning its renderer/network children.
      while read -r id _ p rest; do
        case "$p" in ''|*[!0-9]*) continue ;; esac
        [ "$p" -gt 1 ] 2>/dev/null || continue
        if [ -z "${owner_seen[$p]+present}" ]; then
          owner_seen[$p]=1
          owner_list+=("$p")
        fi
      done <<< "$windows"
      [ "${#owner_list[@]}" -gt 0 ] || return 1
      owners=$(python3 "$app_process_helper" candidates "$real" "${app_roots[@]}" -- "${owner_list[@]}")
      pids=" ${owners//$'\n'/ } ${pid:-0} "
    else
      pids=" $(app_pids | tr '\n' ' ') ${pid:-0} "
    fi
    while read -r id _ p rest; do
      [ -n "$p" ] && [ "$p" != 0 ] || continue
      window_pid_is_app "$p" "$pids" && window_is_product "$id" "$rest" \
        && { app_window_id=$id; return 0; }
    done <<WINDOWS
$windows
WINDOWS
  fi
  [ "${1:-}" = strict ] && return 1
  command -v xdotool >/dev/null 2>&1 || return 1
  if [ -n "${pid:-}" ]; then
    id=$(xdotool search --onlyvisible --pid "$pid" 2>/dev/null | head -n 1)
    [ -n "$id" ] && window_is_product "$id" "" \
      && { app_window_id=$id; return 0; }
  fi
  # The class name is the last resort while waiting for a window to appear; it is never used
  # to decide that an app is already open, because the wallpaper process carries the file
  # manager's class and would have counted.
  id=$(xdotool search --onlyvisible --class "$name" 2>/dev/null | head -n 1)
  [ -n "$id" ] && window_is_product "$id" "" \
    && { app_window_id=$id; return 0; }
  return 1
}

has_window() { app_window; }

# Runs the app and waits for a window. Returns 0 once one appears or the app is still going,
# and the app's own exit code when it dies.
run_attempt() {
  if true; then
    # One supervisor both redacts stdout and records the direct child's exit. Once
    # a window appears the shell can finish instead of keeping a shell, a separate
    # Python redactor and repeated `sleep`/process scans alive for every open app.
    python3 "$app_process_helper" supervise "$log" "$label" -- "${launch_command[@]}" "$@" \
      8>&- </dev/null >/dev/null 2>&1 &
  else
    "${launch_command[@]}" "$@" >> "$log" 2>&1 &
  fi
  pid=$!
  spinner_start
  # Wall-clock throughout. Counting loop turns reported "3s" for a start that took forty,
  # because every xdotool call under PRoot costs seconds of its own.
  local t0 elapsed next_notice=30
  t0=$SECONDS
  while :; do
    elapsed=$((SECONDS - t0))
    if ! kill -0 "$pid" 2>/dev/null; then
      # An exec wrapper can exit after handing off to the primary Electron process.
      [ "$managed_app" = 1 ] && [ -n "$(app_pids)" ] || break
    fi
    if has_window; then
      echo "window appeared after ${elapsed}s" >> "$log"
      spinner_stop
      return 0
    fi
    [ "$elapsed" -ge "$max_wait" ] && break
    # No CPU-idle kill: waiting for OAuth or a network reply can legitimately use no CPU.
    if [ "$elapsed" -ge "$next_notice" ]; then
      notify normal "$label is opening" "${elapsed}s so far, $(expected_wait)."
      case "$next_notice" in
        30) next_notice=60 ;; 60) next_notice=120 ;; 120) next_notice=240 ;;
        240) next_notice=420 ;; 420) next_notice=600 ;; *) next_notice=100000 ;;
      esac
    fi
    sleep 1 || {
      echo 'PD_ERROR: Linux sleep failed; stopping after a possible lost PRoot tracer or blocked syscall.' >> "$log"
      return 159
    }
  done
  spinner_stop
  if kill -0 "$pid" 2>/dev/null || { [ -n "$(app_pids)" ]; }; then
    if [ "$probe_seconds" -gt 0 ]; then
      echo "launch proof timed out after ${elapsed}s without a product window" >> "$log"
      return 124
    fi
    echo "still running after ${elapsed}s without a detected window · process kept · $(free_mb) MB free" >> "$log"
    notify normal "$label is still starting" "Its process is still running. Settings → Linux app reports has the startup output."
    return 0
  fi
  wait "$pid" 2>/dev/null
  return $?
}

# Clear only this app's stale singleton files, after taking its startup lock and confirming
# that no native instance is alive. A browser's locks and another app's login are never touched.
clean_stale_locks() {
  # Everything here is local: this function once overwrote the script's own $target with a
  # lock's readlink result, and the launcher then tried to run "localhost-16621" as the app.
  local lock lock_target lock_owner
  local -a locks=()
  local data_dir
  local -a data_dirs=("${XDG_CONFIG_HOME:-$HOME/.config}/$name")
  case "$name" in
    chatgpt) data_dirs+=("${CODEX_ELECTRON_USER_DATA_PATH:-$HOME/.config/Codex}" "$HOME/.config/ChatGPT") ;;
    cursor) data_dirs+=("$HOME/.config/Cursor") ;;
    claude-desktop) data_dirs+=("$HOME/.config/Claude") ;;
    antigravity) data_dirs+=("$HOME/.config/Antigravity") ;;
    google-chrome*) data_dirs+=("$HOME/.config/google-chrome") ;;
    brave*) data_dirs+=("$HOME/.config/BraveSoftware/Brave-Browser") ;;
  esac
  for data_dir in "${data_dirs[@]}"; do
    lock="$data_dir/SingletonLock"
    { [ -e "$lock" ] || [ -L "$lock" ]; } && locks+=("$lock")
  done
  for lock in "${locks[@]}"; do
    { [ -e "$lock" ] || [ -L "$lock" ]; } || continue
    local lock_dir
    lock_dir=$(dirname "$lock")
    lock_target=$(readlink "$lock" 2>/dev/null || true)
    lock_owner=${lock_target##*-}
    if [ -n "$lock_owner" ] && [ "$lock_owner" -gt 0 ] 2>/dev/null && kill -0 "$lock_owner" 2>/dev/null; then
      continue
    fi
    echo "clearing stale lock in ${lock_dir##*/}" >> "$log"
    rm -f "$lock_dir/SingletonLock" "$lock_dir/SingletonSocket" \
          "$lock_dir/SingletonCookie" "$lock_dir"/.l2s.Singleton* 2>/dev/null || true
  done
}

flags=()
managed_app=0
if is_chromium; then
  managed_app=1
  flags=("${base_flags[@]}")
fi

# The old path inherited Linux-only
# process switches (--no-zygote, --in-process-gpu, site-isolation changes and Ozone/X11), and the
# real RMX3197 report showed it dying before a window existed. Keep the official process model and
# vary only sandbox/GPU compatibility. Installation tries these profiles separately and remembers
# the first one that actually maps a stable publisher window.

# A browser is the one Chromium program here that must keep its extensions and its background
# work (extension and safe-browsing updates). For an AI app both are only memory.
if is_browser && [ "${#flags[@]}" -gt 0 ]; then
  kept=()
  for flag in "${flags[@]}"; do
    case "$flag" in --disable-extensions|--disable-background-networking) ;; *) kept+=("$flag") ;; esac
  done
  flags=("${kept[@]}")
fi


# Keep the same existing user-data directory for both startup and protocol callbacks.
if [ "$name" = "chatgpt" ]; then
  export CODEX_ELECTRON_USER_DATA_PATH="${CODEX_ELECTRON_USER_DATA_PATH:-$HOME/.config/Codex}"
  export BROWSER="${BROWSER:-/usr/local/bin/pocketdesk-browser}"
  export LP_NUM_THREADS=2 OMP_NUM_THREADS=2
fi

# The two VS Code forks. The report that named this was Antigravity dying with SIGSEGV about a
# minute after opening, every time, right after "GPU stall due to ReadPixels" and "GPU state
# invalid after WaitForGetOffsetInRange": its terminal draws with WebGL, WebGL here is
# SwiftShader on the processor, and with --in-process-gpu a fault in that path is a fault in the
# whole app. There is no graphics chip for WebGL to use on this phone, so nothing is lost by
# not offering it: xterm.js notices and falls back to its DOM renderer, which is what the
# editor's own settings do when gpuAcceleration is off -- written below as well, so the choice
# survives an update of the app that resets its flags.
if [ "$name" = cursor ] || [ "$name" = antigravity ]; then
  [ "${#flags[@]}" -eq 0 ] || flags+=(--disable-3d-apis)
  vscode_dir=""
  case "$name" in
    cursor)      vscode_dir="$HOME/.config/Cursor/User" ;;
    antigravity) vscode_dir="$HOME/.config/Antigravity/User" ;;
  esac
  if [ -n "$vscode_dir" ] && [ ! -f "$vscode_dir/settings.json" ]; then
    mkdir -p "$vscode_dir" 2>/dev/null && printf '%s\n' \
      '{' \
      '  "terminal.integrated.gpuAcceleration": "off",' \
      '  "window.titleBarStyle": "native",' \
      '  "editor.minimap.enabled": false,' \
      '  "workbench.enableExperiments": false,' \
      '  "update.mode": "manual",' \
      '  "telemetry.telemetryLevel": "off"' \
      '}' > "$vscode_dir/settings.json" 2>/dev/null || true
  fi
fi

# Linux ChatGPT needs its packaged SwiftShader path, which is independent of and
# deliberately never inherit the Linux process model.
if [ "$name" = "chatgpt" ] && [ "${#flags[@]}" -gt 0 ]; then
  kept=()
  for flag in "${flags[@]}"; do
    case "$flag" in
      --disable-gpu|--in-process-gpu|--js-flags=--max-old-space-size=384) ;;
      *) kept+=("$flag") ;;
    esac
  done
  # The old 384 MB limit applied to Electron's main process too. Loading the signed-in UI
  # could hit that artificial heap ceiling while Android still had memory available. Let
  # the bundled V8 select its heap size; the new-process guard below handles host pressure.
  # Keep SwiftShader in Chromium's separate GPU process. Putting it in Electron's main
  # process makes a native graphics fault end the entire app, bypassing GPU-child recovery.
  # The device's exit 139 is consistent with a native crash, not proof of the faulting module;
  # this restores graphics fault isolation without disabling WebGL or changing other apps.
  flags=("${kept[@]}" --use-gl=angle --use-angle=swiftshader --ignore-gpu-blocklist)
fi

# How big a Chromium app draws itself, chosen so that its window always fits the screen.
#
# Chromium takes its scale from Xft.dpi: 179 dpi is 1.86x, and every one of these apps has a
# minimum window width in CSS pixels that the window manager cannot shrink below -- 400 for the
# VS Code forks, more for a browser. 400 x 1.86 is 746 device pixels on a 720-pixel-wide
# screen, and that is the window whose close button was off the edge and whose right-hand
# side could not be reached whatever was zoomed. The scale is worked out here from the
# desktop's own short side instead: as large as the dpi asks for, but never so large that a
# 560-CSS-pixel window stops fitting. On the reference phone that is about 1.3x. Text inside
# the app is then made bigger with the app's own zoom (Ctrl and +), which every one of them
# remembers, and which does not move the window's minimum size.
chromium_scale() {
  # Worked out once by pocketdesk-desktop when the desktop starts, from the geometry and dpi
  # it was given -- an X round trip per app launch, with a five-second stall if the display is
  # wedged, is not a price worth paying for a number that does not change during a session.
  local saved
  saved=$(cat "$HOME/.config/pocketdesk/chromium-scale" 2>/dev/null)
  case "$saved" in [12].[0-9][0-9]) printf '%s' "$saved"; return 0 ;; esac
  # A computer whose desktop was started by an older version has no such file yet. The
  # arithmetic is the same as the desktop's: short side over 560, capped by the dpi.
  local geometry short dpi scale
  geometry=$(DISPLAY="${DISPLAY:-:1}" timeout 5 xdpyinfo 2>/dev/null | awk '/dimensions:/ { print $2; exit }')
  short=${geometry%%x*}
  case "$geometry" in
    *x*) [ "${geometry#*x}" -lt "$short" ] 2>/dev/null && short=${geometry#*x} ;;
    *) short=720 ;;
  esac
  case "$short" in ''|*[!0-9]*) short=720 ;; esac
  dpi=$(awk -F: '/^Xft\.dpi:/ { gsub(/[^0-9]/, "", $2); print $2; exit }' "$HOME/.Xresources" 2>/dev/null)
  case "$dpi" in ''|*[!0-9]*) dpi=120 ;; esac
  # Hundredths, because this shell has no decimals: 130 means 1.30.
  scale=$(( short * 100 / 560 ))
  [ "$scale" -gt $(( dpi * 100 / 96 )) ] && scale=$(( dpi * 100 / 96 ))
  [ "$scale" -lt 100 ] && scale=100
  [ "$scale" -gt 200 ] && scale=200
  printf '%d.%02d' $(( scale / 100 )) $(( scale % 100 ))
}
if [ "${#flags[@]}" -gt 0 ]; then
  flags+=("--force-device-scale-factor=$(chromium_scale)")
fi

# A per-app startup lock covers the gap before Electron creates its own singleton socket.
# A second icon tap never kills a slow startup. A protocol callback can still reach that startup.
startup_locked=0
unlock_startup() {
  [ "$startup_locked" = 1 ] || return 0
  flock -u 8
  exec 8>&-
  startup_locked=0
}
if [ "$managed_app" = 1 ]; then
  mkdir -p "$HOME/.pocketdesk/run"
  exec 8>"$HOME/.pocketdesk/run/open-$log_name.lock"
  if flock -n 8; then startup_locked=1; else
    if [ "$#" = 0 ]; then
      app_window strict || true
      open_id=$app_window_id
      [ -z "$open_id" ] || wmctrl -ia "$open_id" 2>/dev/null || true
      notify normal "$label is already starting" "The first launch is still working. No second copy was started."
      exit 0
    fi
    # A callback can arrive before the first child has exec'd. Wait briefly for ownership or
    # take over only if the original startup releases its lock. Never race a second primary.
    callback_deadline=$((SECONDS + 15))
    while [ -z "$(app_pids)" ]; do
      if flock -n 8; then startup_locked=1; break; fi
      if [ "$SECONDS" -ge "$callback_deadline" ]; then
        echo 'callback deferred: the primary startup is not ready; no process was stopped' >> "$log"
        notify normal "$label is still starting" "Wait for its window, then retry sign-in. The browser was kept."
        exit 75
      fi
      sleep 1 || exit 159
    done
  fi
fi

# Take one initial ownership snapshot. Reusing it for rotation and singleton dispatch avoids
# doing several full /proc walks before Chrome has even been started.
existing_pids=""
[ "$managed_app" != 1 ] || existing_pids=$(app_pids)

# Retain earlier failures and callbacks. Rotate only before a new cold launch, when no app or
# startup writer is active. Never truncate a file that a running app is still writing.
if [ "$startup_locked" = 1 ] && [ -z "$existing_pids" ] \
    && [ "$(stat -c %s "$log" 2>/dev/null || echo 0)" -gt 5242880 ]; then
  mv -f "$log" "$log.previous" 2>/dev/null || true
fi
{
  echo "--- $(date '+%Y-%m-%d %I:%M:%S %p') ---"
  echo "free memory at launch: $(free_mb) MB"
  printf 'launching:'; printf ' %q' "${launch_command[@]}" "${flags[@]}"; printf '\n'
} >> "$log" 2>/dev/null

open_id=""
if [ "$managed_app" = 1 ]; then
  app_window strict || true
  open_id=$app_window_id
fi
if [ -n "$open_id" ]; then
  echo "already open (window $open_id) · bringing it to the front" >> "$log"
  command -v wmctrl >/dev/null 2>&1 && wmctrl -ia "$open_id" 2>/dev/null || true
fi

# Electron on Linux delivers links through its second-instance socket. A hidden/minimized or
# still-starting main window must receive the link too; no cleanup or retry may kill it.
if [ "$managed_app" = 1 ] && { [ -n "$open_id$existing_pids" ] \
    || { [ "$startup_locked" = 0 ]; }; }; then
  if [ "$#" -gt 0 ]; then
    echo "handing arguments to the existing instance (URLs omitted)" >> "$log"
    if true; then
      timeout --signal=TERM --kill-after=3s 30s "${launch_command[@]}" "${flags[@]}" "$@" \
        8>&- > >(python3 "$app_process_helper" redact >> "$log") 2>&1
    else
      timeout --signal=TERM --kill-after=3s 30s "${launch_command[@]}" "${flags[@]}" "$@" >> "$log" 2>&1
    fi
    status=$?
    echo "argument handoff finished · exit $status (this does not confirm sign-in)" >> "$log"
    if [ "$status" != 0 ]; then
      notify critical "$label could not receive the link" "Handoff exit $status. Check the app report; the existing app and browser were kept."
    fi
    exit "$status"
  fi
  if true; then
    [ -n "$open_id" ] || notify normal "$label is already running" "Its window is not ready yet. See Settings → Linux app reports."
    echo 'existing process kept; no duplicate startup' >> "$log"
    exit 0
  fi
fi

[ "$managed_app" = 1 ] && clean_stale_locks

# Decline a new heavy launch before it competes for critically low available RAM. Existing
# app/callback handling is above this gate. Never close a browser or restart a killed app here.
if [ "$managed_app" = 1 ]; then
  minimum_mb=700
  # Reuse/callback delivery is above this check. Only a NEW browser is deferred when RAM is
  # critically low; launching one regardless could cost the entire PRoot desktop.
  is_browser && minimum_mb=450
  free_now=$(free_mb)
  if [ -n "$free_now" ] && [ "$free_now" -lt "$minimum_mb" ] 2>/dev/null; then
    echo "PD_ERROR: startup deferred: ${free_now} MB available; close other apps and retry" >> "$log"
    notify critical "Not enough free memory to start $label" "${free_now} MB available. Close other apps and retry. Your running apps were kept."
    exit 75
  fi
fi

notify normal "Opening $label" "The first start can take a minute or two."

launch_guarded() {
  # A second-instance exit 0 is a handoff, not permission
  # to kill the primary process. SIGKILL must not trigger an automatic memory-pressure loop.
  run_attempt "$@"
}

# Stays quietly until the app ends, and writes down how it ended: "closed by itself" is only a
# mystery while nobody records the exit. SIGKILL alone cannot identify who sent the signal.
record_end() {
  local started_at end ran
  started_at=$(date +%s)
  wait "$pid" 2>/dev/null
  end=$?
  # The launcher we started may have handed over to the real program; wait for that too.
  while [ -n "$(app_pids)" ]; do sleep 5 || break; done
  ran=$(( $(date +%s) - started_at ))
  echo "$(date '+%I:%M:%S %p') $label ended after ${ran}s · exit $end · $(free_mb) MB free" >> "$log"
  if [ "$end" = 137 ] || [ "$end" = 9 ]; then
    notify critical "$label was stopped" "Its process received SIGKILL. Memory pressure or another forced stop may be responsible. See the app report."
  elif [ "$end" != 0 ] && [ "$end" != 143 ]; then
    notify critical "$label stopped with an error" "Exit $end. Settings → Linux app reports has the last output."
  fi
}

launch_guarded ${flags[@]+"${flags[@]}"} "$@"
status=$?
unlock_startup
if [ "$status" = 0 ]; then
  if [ "$probe_seconds" -gt 0 ]; then
    # An unpacked EXE is not an installed GUI app until it keeps a real window mapped. Hold it
    # briefly to reject a splash-only crash, then record the proof that Android and the menu use.
    stable_seconds=${POCKETDESK_PROBE_STABLE_SECONDS:-8}
    case "$stable_seconds" in ''|*[!0-9]*) stable_seconds=8 ;; esac
    [ "$stable_seconds" -ge 1 ] || stable_seconds=1
    [ "$stable_seconds" -le 30 ] || stable_seconds=30
    stable_until=$((SECONDS + stable_seconds))
    while [ "$SECONDS" -lt "$stable_until" ]; do
      if ! has_window; then
        echo "launch proof failed: the first window disappeared" >> "$log"
        status=70
        break
      fi
      sleep 1 || { echo 'PD_ERROR: Linux sleep failed during window verification.' >> "$log"; status=159; break; }
    done
    if [ "$status" = 0 ]; then
      proof_file=${POCKETDESK_PROOF_FILE:-}
      if [ -n "$proof_file" ]; then
        umask 077
        printf 'window-stable-%ss\n%s\n' "$stable_seconds" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$proof_file"
      fi
      echo "launch proof passed: a window stayed mapped for ${stable_seconds}s" >> "$log"
      probe_pids="$(app_pids | tr '\n' ' ') ${pid:-}"
      [ -z "${probe_pids// /}" ] || {
        kill $probe_pids 2>/dev/null || true
        sleep 2
        kill -9 $probe_pids 2>/dev/null || true
      }
      exit 0
    fi
    probe_pids="$(app_pids | tr '\n' ' ') ${pid:-}"
    [ -z "${probe_pids// /}" ] || {
      kill $probe_pids 2>/dev/null || true
      sleep 1
      kill -9 $probe_pids 2>/dev/null || true
    }
  fi
fi
if [ "$status" = 0 ]; then
  exit 0
fi

append_own_log() {
  [ "$name" = "chatgpt" ] || return 0
  local own newest
    own="$HOME/.local/state/codex/logs"
    [ -d "$own" ] || return 0
    newest=$(find "$own" -maxdepth 1 -type f -printf '%T@ %p\n' 2>/dev/null \
      | sort -nr | head -n 1 | cut -d' ' -f2-)
  [ -n "$newest" ] && [ -f "$newest" ] || return 0
  {
    echo "--- ChatGPT's own log: ${newest#"$own"/} ---"
    tail -n 60 "$newest"
  } | python3 "$app_process_helper" redact >> "$log" 2>/dev/null
}
append_own_log

# During setup Android displays the exact report; starting invisible zenity/notification processes
# wastes memory and can keep the private display/session alive after its task has finished.
if [ "$probe_seconds" -gt 0 ]; then
  exit "$status"
fi

case "$status" in
  137|9) reason="its process was killed (SIGKILL); memory pressure is one possible cause"
         advice="Close the browser and any other app, then open $label again." ;;
  159)   reason="Linux reported a blocked system call or lost runtime"
         advice="Open Settings → Linux app reports; stop this session before retrying." ;;
  124)   reason="it reached the launch time limit"
         advice="Open the app report for the last startup output." ;;
  139)   reason="it crashed while starting"
         advice="Open $label again. If it keeps happening, tap $label on the Apps tab to update it." ;;
  134)   reason="it stopped itself with an error"
         advice="Open $label again. If it keeps happening, tap $label on the Apps tab to update it." ;;
  *)     reason="it stopped with error $status"
         advice="Open $label again. If it keeps happening, tap $label on the Apps tab to update it." ;;
esac
message="$label could not open: $reason.

$advice"

notify critical "$label could not open" "$reason. $advice"
if command -v zenity >/dev/null 2>&1; then
  # zenity reads its text as Pango markup, so the app's name has to be escaped.
  markup=$(printf '%s' "$message" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g')
  zenity --error --width=420 --title="$label could not open" --text="$markup" >/dev/null 2>&1 &
elif command -v xmessage >/dev/null 2>&1; then
  printf '%s\n' "$message" | xmessage -center -file - >/dev/null 2>&1 &
fi
exit "$status"
