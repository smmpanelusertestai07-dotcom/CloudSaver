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
    tell "Nothing found" "Ubuntu's configured repositories have no package matching: $query\n\nTry a shorter word, or choose Update package list and search again."
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
  update) run_terminal "sudo apt-get update && sudo apt-get -y upgrade && sudo /usr/local/bin/pocketdesk-menu" ;;
  install-file) exec /usr/local/bin/pocketdesk-install ;;
  installed) show_installed ;;
  --selftest) printf 'search\nupdate\ninstall-file\ninstalled\n'; exit 0 ;;
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
      "Update installed software") run_terminal "sudo apt-get update && sudo apt-get -y upgrade && sudo /usr/local/bin/pocketdesk-menu" ;;
      "Install a downloaded package") exec /usr/local/bin/pocketdesk-install ;;
      "See installed software") show_installed ;;
    esac
    ;;
  *) printf 'usage: pocketdesk-software {search|update|install-file|installed}\n' >&2; exit 2 ;;
esac
