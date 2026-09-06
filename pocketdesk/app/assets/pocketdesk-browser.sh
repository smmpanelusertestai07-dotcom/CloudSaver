#!/bin/bash
# xdg-open's BROWSER fallback must not start an unwrapped Chromium, or wait for it to close.
# Keep the normal browser profile: OAuth cookies and callback registrations already live there.
set -u
[ "$#" -gt 0 ] || set -- about:blank
for uri in "$@"; do
  case "$uri" in https://*|http://*|about:blank) ;; *) exit 2 ;; esac
done
browser=""
for candidate in google-chrome google-chrome-stable chromium chromium-browser brave-browser brave-browser-stable epiphany firefox; do
  if command -v "$candidate" >/dev/null 2>&1; then browser=$candidate; break; fi
done
if [ -z "$browser" ]; then
  command -v notify-send >/dev/null 2>&1 && notify-send -u critical 'Browser is not installed' 'Install Chrome from PocketLinux Apps, then retry sign-in.'
  exit 127
fi
opener=$(command -v pocketdesk-open) || exit 127
log_dir="$HOME/.pocketdesk/logs"
mkdir -p "$log_dir"
# Routing metadata only; a copied login URL can contain private state/code values.
printf 'Browser request: %s, %s web URL(s); launch requested (not sign-in confirmation)\n' \
  "$browser" "$#" >> "$log_dir/browser-handoff.log"
# The desktop PRoot owns the new child. No new container, user-data directory, or browser is
# started on a second tap: pocketdesk-open hands URLs to the existing browser singleton.
nohup "$opener" --label 'Browser' "$browser" "$@" </dev/null >/dev/null 2>&1 &
child=$!
# Report immediate errors, but release Electron's openExternal call while the browser is open.
sleep 1
if kill -0 "$child" 2>/dev/null; then exit 0; fi
wait "$child"
status=$?
printf 'Browser launcher returned exit %s\n' "$status" >> "$log_dir/browser-handoff.log"
exit "$status"
