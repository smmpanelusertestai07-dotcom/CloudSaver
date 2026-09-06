#!/bin/bash
# Puts a file the owner did not download in the browser where they said downloads should go.
#
# Chrome already obeys the setting: PocketLinux writes it as a managed download policy at every
# start, so a browser download lands in the computer's own Downloads, or straight in the phone's
# Download folder, or asks -- whichever "Where new files are saved" says.
#
# This is the other half. An AI app writing a file for you, a build putting an .apk in the folder,
# a script saving a report: none of those go through Chrome, so none of them see that setting.
# The desktop's Downloads watcher hands each finished file to this, and it applies exactly the
# same three answers, from the same setting, so a file arrives in the same place whatever made it.
#
# It always copies, never moves. A file sent to the phone and then deleted there is still here.
#
# Usage: pocketdesk-save <file> [computer|phone|ask]
#        pocketdesk-save --where          print the setting in force
#        pocketdesk-save --phone-dir      print the phone folder, or say why there is none
set -u
HOME_DIR=${POCKETDESK_HOME_DIR:-${HOME:-/home/coder}}
# The same folder Chrome is pointed at, so both halves agree without a second setting.
PHONE_DIR=${POCKETDESK_PHONE_DOWNLOAD_DIR:-$HOME_DIR/Phone/Download/PocketLinux}

have() { command -v "$1" >/dev/null 2>&1; }
say() { printf '%s\n' "$*"; }

note() {   # note <title> <body>
  if have notify-send; then
    notify-send -a PocketLinux -i pocketdesk-linux "$1" "$2" 2>/dev/null || true
  fi
  printf '%s: %s\n' "$1" "$2"
}

current() {
  case "${POCKETDESK_DOWNLOAD_TARGET:-}" in
    computer|phone|ask) printf '%s' "$POCKETDESK_DOWNLOAD_TARGET" ;;
    *) printf 'computer' ;;
  esac
}

# The phone's own Download folder, or nothing when Android has not been asked for it yet.
#
# Phone files binds the phone's storage at ~/Phone. With the permission off PocketLinux leaves a
# note there instead, and that note is how the two are told apart -- more reliable than testing
# for writability, which a bind mount can pass while holding nothing.
phone_dir() {
  [ -d "$HOME_DIR/Phone" ] || return 1
  [ -f "$HOME_DIR/Phone/Phone files are off.txt" ] && return 1
  [ -d "$HOME_DIR/Phone/Download" ] || [ -d "$HOME_DIR/Phone/DCIM" ] \
    || [ -d "$HOME_DIR/Phone/Documents" ] || return 1
  mkdir -p "$PHONE_DIR" 2>/dev/null || return 1
  printf '%s' "$PHONE_DIR"
}

phone_off_message() {
  printf '%s' "The phone's own storage is not available to this computer yet.

Turn on Phone files in PocketLinux -> Settings -> Permissions, and the phone's Download, Photos
and Documents folders appear here as the Phone folder.

Until then the file is kept in the computer's Downloads, where nothing else on the phone can
read it."
}

# A name nothing else is using, so a copy never silently replaces a file already on the phone.
free_name() {   # free_name <folder> <name>
  pd_dir=$1; pd_base=$2
  [ -e "$pd_dir/$pd_base" ] || { printf '%s' "$pd_dir/$pd_base"; return 0; }
  pd_stem=${pd_base%.*}
  pd_ext=${pd_base##*.}
  [ "$pd_stem" = "$pd_base" ] && pd_ext=""
  pd_n=2
  while [ "$pd_n" -lt 500 ]; do
    if [ -n "$pd_ext" ]; then pd_try="$pd_dir/$pd_stem ($pd_n).$pd_ext"
    else pd_try="$pd_dir/$pd_stem ($pd_n)"; fi
    [ -e "$pd_try" ] || { printf '%s' "$pd_try"; return 0; }
    pd_n=$((pd_n + 1))
  done
  printf '%s' "$pd_dir/$pd_base"
}

to_phone() {   # to_phone <file>
  pd_target=$(phone_dir) || { note "Kept in the computer" "$(phone_off_message)"; return 1; }
  pd_out=$(free_name "$pd_target" "$(basename -- "$1")")
  if cp -f -- "$1" "$pd_out" 2>/dev/null; then
    note "Saved to the phone" "$(basename -- "$pd_out") is in the phone's Download folder. Open the phone's Files app to find it."
    say "$pd_out"
    return 0
  fi
  note "Could not save it to the phone" "The phone would not accept the file. It is still here, in the computer's Downloads."
  return 1
}

case "${1:-}" in
  --where)     current; echo ;;
  --phone-dir) phone_dir && echo || { phone_off_message; exit 1; } ;;
  ""|-h|--help)
    say "usage: pocketdesk-save <file> [computer|phone|ask]"
    say "new files currently go to: $(current)"
    ;;
  *)
    file=$1
    [ -f "$file" ] || { say "There is no file at $file"; exit 1; }
    choice=${2:-$(current)}

    # A file that is already on the phone -- because Chrome was pointed straight there -- must
    # not be copied to the phone a second time.
    case "$file" in "$HOME_DIR/Phone/"*) choice=computer ;; esac

    if [ "$choice" = ask ]; then
      if have zenity; then
        # The buttons use the same words as the setting: a dialog that speaks differently from
        # the setting it obeys is a dialog that confuses.
        if zenity --question --width=460 --no-markup \
             --title="Where should this go?" \
             --text="$(basename -- "$file") has been saved.

It is in this computer's Downloads folder, which nothing else on the phone can read. Put a copy
on the phone as well, where the phone's own apps can open it?" \
             --ok-label="Copy to the phone" --cancel-label="Keep it here" 2>/dev/null; then
          choice=phone
        else
          choice=computer
        fi
      else
        choice=computer
      fi
    fi

    case "$choice" in
      phone)    to_phone "$file" ;;
      computer) note "Saved" "$(basename -- "$file") is in the computer's Downloads folder."
                say "$file" ;;
      *)        say "The choice must be computer, phone or ask."; exit 1 ;;
    esac
    ;;
esac
