#!/bin/bash
# The phone's own battery, temperature, memory and network, on the desktop's panel.
#
# tint2 runs this every few seconds (an "execp" item) and shows the one line it prints. The
# numbers come from the phone itself: Android's kernel publishes the battery under
# /sys/class/power_supply and memory under /proc/meminfo, both of which the container can read.
set -u
cap=""
status=""
temp=""
battery=""
for candidate in /sys/class/power_supply/*; do
  [ -r "$candidate/type" ] || continue
  if [ "$(cat "$candidate/type" 2>/dev/null)" = "Battery" ]; then battery=$candidate; break; fi
done
[ -z "$battery" ] && [ -d /sys/class/power_supply/battery ] && battery=/sys/class/power_supply/battery
if [ -n "$battery" ]; then
  cap=$(cat "$battery/capacity" 2>/dev/null || true)
  status=$(cat "$battery/status" 2>/dev/null || true)
  temp=$(cat "$battery/temp" 2>/dev/null || true)
  # power_supply reports tenths of a degree (195 = 19.5 C); a few kernels report whole degrees
  # and a few thousandths. Decide by magnitude in both directions, and print nothing at all
  # rather than a number that is obviously wrong -- 2500 C was reaching the panel.
  if [ -n "$temp" ] && [ "$temp" -eq "$temp" ] 2>/dev/null; then
    if [ "$temp" -gt 1000 ]; then temp=$((temp / 1000));
    elif [ "$temp" -gt 100 ]; then temp=$((temp / 10)); fi
    [ "$temp" -ge 0 ] && [ "$temp" -le 80 ] || temp=""
  fi
fi
# Decimal GB, exactly as DeviceProbe.formatBytes does it, so the computer and the app agree about
# the same phone. Free storage is one statfs on / : PRoot maps that to PocketDesk's own private
# folder, so the kernel answers for the phone's data partition -- the space this computer can
# still grow into. df would agree but reads /proc/self/mounts and stats every Android mount on
# the way, and under PRoot each of those is a traced syscall. This is one.
mem=$(awk '/MemAvailable/ { printf "%.1f", $2 * 1024 / 1000000000 }' /proc/meminfo 2>/dev/null || true)
free_gb=$(stat -f -c '%S %a' / 2>/dev/null \
  | awk 'NF == 2 && $1 > 0 && $2 > 0 { printf "%.1f", $1 * $2 / 1000000000 }')
net=""
for iface in /sys/class/net/wlan* /sys/class/net/wifi*; do
  [ -r "$iface/operstate" ] || continue
  [ "$(cat "$iface/operstate" 2>/dev/null)" = "up" ] && { net="Wi-Fi"; break; }
done
if [ -z "$net" ]; then
  for iface in /sys/class/net/rmnet* /sys/class/net/ccmni*; do
    [ -r "$iface/operstate" ] || continue
    [ "$(cat "$iface/operstate" 2>/dev/null)" = "up" ] && { net="Mobile data"; break; }
  done
fi
# Line one carries the two numbers asked for most, so it still says something useful even on a
# build of tint2 that draws only the first line of a multi-line item.
line1="${cap:+${cap}%}"
[ -n "$free_gb" ] && line1="${line1:+$line1 · }${free_gb}G"
line2="${temp:+${temp}°C}"
[ -n "$mem" ] && line2="${line2:+$line2 · }${mem}G"
printf '%s\n' "${line1:-PocketDesk}"
[ -n "$line2" ] && printf '%s\n' "$line2"
# tint2 shows a command's standard error as its tooltip when execp_tooltip is absent; the
# clear-screen sequence stops it growing run after run.
{
  printf '\033[2J'
  printf 'This phone, right now\n'
  if [ -n "$cap" ]; then
    case "$status" in
      Charging|Full) printf 'Battery: %s%%, charging\n' "$cap" ;;
      *) printf 'Battery: %s%%\n' "$cap" ;;
    esac
  fi
  [ -n "$temp" ] && printf 'Battery temperature: %s C\n' "$temp"
  [ -n "$mem" ] && printf 'Memory free: %s GB\n' "$mem"
  [ -n "$free_gb" ] && printf 'Storage free: %s GB - the computer may use all of it\n' "$free_gb"
  [ -n "$net" ] && printf 'Network: %s\n' "$net"
  printf 'Tap for storage.\n'
} >&2
