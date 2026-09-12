#!/bin/bash
# PocketLinux's lightweight software centre. It is intentionally an interface to Ubuntu's signed
# apt catalogue, not a second package system: one source of updates, ARM64 packages, and no daemon
# taking memory while an AI app is running.
set -u

have() { command -v "$1" >/dev/null 2>&1; }

tell() { # tell <title> <text>
  if have zenity; then
    zenity --info --no-markup --width=520 --title="$1" --text="$2" 2>/dev/null || true
  else
    printf '%s\n%s\n' "$1" "$2"
  fi
}

run_terminal() { # run_terminal <trusted command assembled below>
  command_text=$1
  if have lxterminal; then
    lxterminal -e bash -lc "$command_text; result=\$?; printf '\n'; [ \$result = 0 ] && echo 'Finished.' || echo 'That did not finish. Read the message above.'; read -r -p 'Press Enter to close '; exit \$result" &
  else
    bash -lc "$command_text"
  fi
}

# Ubuntu's own updates for everything already installed.
#
# This used to be a bare "sudo apt-get update && sudo apt-get -y upgrade" -- the one place in the
# app that reached apt on its own. Everything else goes through the helpers in LinuxApps.java,
# and two of the things they do matter here. They finish a half-applied install first, because
# dpkg refuses every later command until that is done. And they skip fetching the package lists
# when the lists this phone already has are only hours old: that fetch is about 40 MB of mobile
# data, and paying it on every tap also left no record that it had been paid, so the next install
# from the Apps tab paid it all over again.
#
# Those helpers are a Java string, which no shell script can source. What is shared instead is
# the one thing they keep on disk -- the stamp that says when this phone last fetched the lists
# -- and the same POCKETDESK_LIST_HOURS window, so neither side downloads again what the other
# has just fetched. The rest of what apt needs here (retries, timeouts, one request at a time,
# keeping the owner's own config files) is in /etc/apt/apt.conf.d/99pocketdesk, which those same
# helpers wrote during set-up and which every apt command inside this computer reads.
#
# POCKETDESK_TEST_ROOT is empty on the phone, so this is the real path; the test suite points it
# at a temporary folder, the same way it does for the helpers themselves.
APT_STATE="${POCKETDESK_TEST_ROOT:-}/var/lib/pocketdesk"
APT_STAMP="$APT_STATE/apt-updated-at"

list_is_fresh() {
  hours=${POCKETDESK_LIST_HOURS:-12}
  case "$hours" in ''|*[!0-9]*) hours=12 ;; esac
  # The age that matters is when THIS phone last fetched. The dates on apt's own index files are
  # the archive's publish dates, so they say nothing about it.
  at=$(cat "$APT_STAMP" 2>/dev/null || true)
  case "$at" in ''|*[!0-9]*) return 1 ;; esac
  age=$(( $(date +%s) - at ))
  [ "$age" -ge 0 ] && [ "$age" -lt $(( hours * 3600 )) ]
}

update_software() {
  if list_is_fresh; then
    refresh="echo 'The list of new versions was fetched recently, so nothing needs downloading.'"
  else
    refresh="sudo mkdir -p $APT_STATE && sudo apt-get update && date +%s | sudo tee $APT_STAMP >/dev/null || echo 'The list of new versions could not be downloaded. Carrying on with the list already on this phone.'"
  fi
  # apt-get clean at the end for the same reason every install ends with it: the packages it
  # downloaded are 300 MB of archives the computer will never read again.
  run_terminal "sudo dpkg --configure -a >/dev/null 2>&1; sudo apt-get -y -f install >/dev/null 2>&1; $refresh; sudo DEBIAN_FRONTEND=noninteractive apt-get -y upgrade; sudo apt-get clean; sudo /usr/local/bin/pocketdesk-menu"
}

search_ubuntu() {
  if have zenity; then
    query=$(zenity --entry --title="Find Ubuntu software" \
      --text="Name or a short description (ARM64 packages only)" 2>/dev/null) || return 0
  else
    printf 'Search: ' >&2
    read -r query || return 0
  fi
  [ -n "$query" ] || return 0

  # Package names cannot contain tabs, so a two-column list stays unambiguous. apt-cache reads
  # the query as data because it is quoted; no part is ever evaluated as a shell command.
  results=$(apt-cache search --names-only "$query" 2>/dev/null \
    | awk -F' - ' 'NF >= 2 { name=$1; $1=""; sub(/^ - /, ""); print name "\t" $0 }' \
    | head -n 100)
  if [ -z "$results" ]; then
    tell "Nothing found" "Ubuntu's configured repositories have no package matching: $query\n\nTry a shorter word, or choose Update installed software (which also refreshes the package list) and search again."
    return 0
  fi

  if have zenity; then
    package=$(printf '%s\n' "$results" | awk -F'\t' '{print $1; print $2}' \
      | zenity --list --title="Ubuntu software" \
      --text="Choose a package to review" --width=780 --height=520 \
      --column="Package" --column="Description" --print-column=1 2>/dev/null) || return 0
  else
    printf '%s\n' "$results"
    return 0
  fi
  case "$package" in
    ''|*[!a-zA-Z0-9.+:-]*) tell "Cannot install" "That is not a valid Ubuntu package name."; return 1 ;;
  esac

  details=$(apt-cache show --no-all-versions "$package" 2>/dev/null \
    | awk -F': ' '/^(Package|Version|Architecture|Installed-Size|Homepage|Description): / {print $1 ": " $2}' \
    | head -n 12)
  zenity --question --no-markup --width=540 --title="Install $package?" \
    --text="$details\n\nSource: Ubuntu's configured, signed apt repositories.\nOnly the ARM64 build and its required packages will be installed." \
    --ok-label="Install" --cancel-label="Cancel" 2>/dev/null || return 0
  run_terminal "sudo apt-get install -y --no-install-recommends '$package' && sudo /usr/local/bin/pocketdesk-menu"
}

show_installed() {
  if have zenity; then
    dpkg-query -W -f='${binary:Package}\t${Version}\n' 2>/dev/null | sort \
      | zenity --text-info --title="Installed software" --width=760 --height=540 \
        --font="Monospace 10" 2>/dev/null || true
  else
    dpkg-query -W -f='${binary:Package}\t${Version}\n' 2>/dev/null | sort
  fi
}

case "${1:-menu}" in
  search) search_ubuntu ;;
  update) update_software ;;
  install-file) exec /usr/local/bin/pocketdesk-install ;;
  installed) show_installed ;;
  menu)
    if ! have apt-cache || ! have apt-get; then
      tell "Software is unavailable" "This Linux computer is missing Ubuntu's package tools. Update Computer basics from PocketLinux Settings."
      exit 1
    fi
    if ! have zenity; then
      printf 'usage: pocketdesk-software {search|update|install-file|installed}\n'
      exit 2
    fi
    action=$(zenity --list --radiolist --title="Software" --width=600 --height=390 \
      --text="Native ARM64 software from Ubuntu's signed repositories" \
      --column="" --column="Action" --column="What it does" \
      TRUE "Find Ubuntu software" "Search, review and install a package" \
      FALSE "Update installed software" "Security and software updates" \
      FALSE "Install a downloaded package" "Run PocketLinux's file safety checks" \
      FALSE "See installed software" "Names and versions" \
      --print-column=2 2>/dev/null) || exit 0
    case "$action" in
      "Find Ubuntu software") search_ubuntu ;;
      "Update installed software") update_software ;;
      "Install a downloaded package") exec /usr/local/bin/pocketdesk-install ;;
      "See installed software") show_installed ;;
    esac
    ;;
  *) printf 'usage: pocketdesk-software {search|update|install-file|installed}\n' >&2; exit 2 ;;
esac
