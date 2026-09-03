#!/bin/bash
# The phone's own battery, temperature, memory and network, on the desktop's panel.
#
# tint2 runs this every few seconds (an "execp" item) and shows the one line it prints. The
# numbers come from the phone itself: Android's kernel publishes the battery under
# /sys/class/power_supply and memory under /proc/meminfo, both of which the container can read.
set -u
out=""
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
  if [ -n "$cap" ]; then
    out="Battery ${cap}%"
    case "$status" in Charging|Full) out="$out charging" ;; esac
  fi
  # power_supply reports tenths of a degree (195 = 19.5 C); a few kernels report whole degrees
  # and a few thousandths. Decide by magnitude in both directions, and print nothing at all
  # rather than a number that is obviously wrong -- 2500 C was reaching the panel.
  if [ -n "$temp" ] && [ "$temp" -eq "$temp" ] 2>/dev/null; then
    if [ "$temp" -gt 1000 ]; then temp=$((temp / 100));
    elif [ "$temp" -gt 100 ]; then temp=$((temp / 10)); fi
    if [ "$temp" -ge 0 ] && [ "$temp" -le 80 ]; then
      out="${out:+$out · }${temp}°C"
    fi
  fi
fi
mem=$(awk '/MemAvailable/ { printf "%.1f", $2 / 1048576 }' /proc/meminfo 2>/dev/null || true)
[ -n "$mem" ] && out="${out:+$out · }${mem} GB free"
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
[ -n "$net" ] && out="${out:+$out · }$net"
printf '%s\n' "${out:-PocketDesk}"
