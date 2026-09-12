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

# Uninstall, for the software the owner added themselves. Only packages apt records as
# manually installed are offered, and the desktop's own set is kept out of the list: removing
# pcmanfm or tint2 from here would take the desktop with it.
remove_installed() {
  have apt-mark || { tell "Not available" "This computer's package tools are incomplete."; return 0; }
  keep='^(pocketdesk|ubuntu-minimal|ubuntu-keyring|apt|dpkg|bash|coreutils|systemd|sudo|curl|gnupg|ca-certificates|adwaita-icon-theme|dmz-cursor-theme|tzdata|gnome-themes-extra-data|fonts-noto-color-emoji|fonts-noto-core|locales|bash-completion|lsb-release|xdg-utils|x11-xserver-utils|x11-utils|dbus-x11|dbus-system-bus-common|dunst|libnotify-bin|zenity|xdotool|wmctrl|desktop-file-utils|librsvg2-common|lxterminal|pcmanfm|libfm-modules|tint2|pulseaudio|pulseaudio-utils|less|file|unzip|zip|wget|apt-utils|python3|openssh-client|git|nano|vim)$'
  list=$(apt-mark showmanual 2>/dev/null | grep -Ev "$keep" | sort | head -n 200)
  if [ -z "$list" ]; then
    tell "Nothing to remove" "Everything installed here is part of the computer itself. Apps added from PocketLinux are removed on its Apps tab."
    return 0
  fi
  rows=""
  for package in $list; do
    version=$(dpkg-query -W -f='${Version}' "$package" 2>/dev/null || echo '')
    summary=$(apt-cache show "$package" 2>/dev/null | awk -F': ' '/^Description(-en)?: /{ print $2; exit }')
    rows="$rows
$package
${version:-unknown}
${summary:-Installed package}"
  done
  choice=$(printf '%s' "$rows" | sed '1d' | zenity --list --title="Remove installed software" \
    --width=640 --height=460 --text="Software you added to this computer" \
    --column="Package" --column="Version" --column="What it is" --print-column=1 2>/dev/null) || return 0
  [ -n "$choice" ] || return 0
  case "$choice" in *[!A-Za-z0-9.+-]*) tell "Not a package name" "Nothing was removed."; return 0 ;; esac
  zenity --question --title="Remove $choice" --width=440 \
    --text="Remove $choice and anything installed only for it?

Files you made with it are kept." >/dev/null 2>&1 || return 0
  run_terminal "sudo apt-get remove -y '$choice' && sudo apt-get -y autoremove && sudo /usr/local/bin/pocketdesk-menu"
}

case "${1:-menu}" in
  search) search_ubuntu ;;
  update) run_terminal "sudo apt-get update && sudo apt-get -y upgrade && sudo /usr/local/bin/pocketdesk-menu" ;;
  install-file) exec /usr/local/bin/pocketdesk-install ;;
  installed) show_installed ;;
  remove) remove_installed ;;
  --selftest) printf 'search\nupdate\ninstall-file\ninstalled\nremove\n'; exit 0 ;;
  menu)
    if ! have apt-cache || ! have apt-get; then
      tell "Software is unavailable" "This Linux computer is missing Ubuntu's package tools. Update Computer basics from PocketLinux Settings."
      exit 1
    fi
    if ! have zenity; then
      printf 'usage: pocketdesk-software {search|update|install-file|installed|remove}\n'
      exit 2
    fi
    action=$(zenity --list --radiolist --title="Software" --width=600 --height=390 \
      --text="Native ARM64 software from Ubuntu's signed repositories" \
      --column="" --column="Action" --column="What it does" \
      TRUE "Find Ubuntu software" "Search, review and install a package" \
      FALSE "Update installed software" "Security and software updates" \
      FALSE "Install a downloaded package" "Run PocketLinux's file safety checks" \
      FALSE "See installed software" "Names and versions" \
      FALSE "Remove installed software" "Uninstall something you added" \
      --print-column=2 2>/dev/null) || exit 0
    case "$action" in
      "Find Ubuntu software") search_ubuntu ;;
      "Update installed software") run_terminal "sudo apt-get update && sudo apt-get -y upgrade && sudo /usr/local/bin/pocketdesk-menu" ;;
      "Install a downloaded package") exec /usr/local/bin/pocketdesk-install ;;
      "See installed software") show_installed ;;
      "Remove installed software") remove_installed ;;
    esac
    ;;
  *) printf 'usage: pocketdesk-software {search|update|install-file|installed|remove}\n' >&2; exit 2 ;;
esac
