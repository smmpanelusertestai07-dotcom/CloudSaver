#!/bin/bash
# Electron can invoke xdg-open directly instead of looking up the desktop's MIME entry.
# Only web URLs need our Chromium launcher. File opens and app callbacks use xdg-utils,
# whose MIME entries point back to the app's wrapped, profile-preserving launcher.
if [ "$#" -eq 1 ]; then
  case "$1" in
    https://*|http://*) exec /usr/local/bin/pocketdesk-browser "$1" ;;
  esac
fi
exec /usr/bin/xdg-open "$@"
