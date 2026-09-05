#!/bin/bash
# Where a file goes: the computer's own storage, or the phone's.
#
# Everything downloaded here -- a file an AI app writes for you, an .apk you just built, a
# document from the browser -- lands in the computer's Downloads folder first. That folder is
# inside PocketDesk's private storage: fast, needing no permission, and readable by nothing else
# on the phone. It is the right default, and for most files it is the end of the story.
#
# But a file is often wanted on the PHONE: to send it in a messaging app, to open it in the
# phone's own PDF reader, to install an APK you just built. The phone's Download folder is a
# different place on a different filesystem, and until now there was no way from in here to put
# a file there. This is that way.
#
# The choice is made once in PocketDesk -> Settings -> Where downloads go, and can be changed
# from the desktop's Tools menu. Three answers, and they mean exactly what they say:
#
#   computer   keep it here (the default). Private, and no permission needed.
#   phone      put a copy in the phone's own Download folder, where every app on the phone
#              can open it. Needs Phone files to be on, because that is Android's permission
#              for reaching the phone's storage at all.
#   ask        decide per file, in a dialog, as it arrives.
#
# Usage:  pocketdesk-save <file> [computer|phone|ask]   place one file
#         pocketdesk-save --where                        print the current setting
#         pocketdesk-save --set <choice>                 change the setting
#         pocketdesk-save --choose                       change the setting through a dialog
#         pocketdesk-save --phone-dir                    print the phone's folder, or why not
set -u
HOME_DIR=/home/coder
STATE="$HOME_DIR/.pocketdesk"
SETTING="$STATE/download-to"
DEFAULT=computer
mkdir -p "$STATE" 2>/dev/null || true

have() { command -v "$1" >/dev/null 2>&1; }
say() { printf '%s\n' "$*"; }

note() {   # note <title> <body>
  if have notify-send; then
    notify-send -a PocketDesk -i pocketdesk-linux "$1" "$2" 2>/dev/null || true
  fi
  printf '%s: %s\n' "$1" "$2"
}

current() {
  pd_value=$(cat "$SETTING" 2>/dev/null | tr -d '[:space:]')
  case "$pd_value" in
    computer|phone|ask) printf '%s' "$pd_value" ;;
    *) printf '%s' "$DEFAULT" ;;
  esac
}

remember() {   # remember <choice>
  case "${1:-}" in
    computer|phone|ask) printf '%s' "$1" > "$SETTING" && say "Downloads now go to: $1" ;;
    *) say "The choice must be computer, phone or ask."; exit 1 ;;
  esac
}

# The phone's own Download folder, or nothing at all when Android has not been asked yet.
#
# Phone files binds the phone's storage at ~/Phone. With the permission off, PocketDesk leaves a
# note there instead, and that note is how this tells the two apart -- more reliable than testing
# for writability, which a bind mount can pass while holding nothing.
phone_dir() {
  [ -d "$HOME_DIR/Phone" ] || return 1
  [ -f "$HOME_DIR/Phone/Phone files are off.txt" ] && return 1
  # A phone's storage always has one of these. An empty folder is the mount not being there.
  if [ -d "$HOME_DIR/Phone/Download" ] || [ -d "$HOME_DIR/Phone/DCIM" ] \
     || [ -d "$HOME_DIR/Phone/Documents" ]; then
    mkdir -p "$HOME_DIR/Phone/Download" 2>/dev/null || return 1
    printf '%s' "$HOME_DIR/Phone/Download"
    return 0
  fi
  return 1
}

phone_off_message() {
  printf '%s' "The phone's storage is not available to this computer yet.
Turn it on in PocketDesk -> Settings -> Permissions -> Phone files, and the phone's Download,
Photos and Documents folders appear here as the Phone folder.

Until then the file is kept in the computer's own Downloads."
}

# A name nothing else is using, so putting a file on the phone never quietly replaces one.
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

# Copy, never move: the computer keeps its own copy of everything it downloaded, so a file sent
# to the phone and then deleted there is not lost from here as well.
to_phone() {   # to_phone <file>
  pd_target=$(phone_dir) || { note "Kept in the computer" "$(phone_off_message)"; return 1; }
  pd_out=$(free_name "$pd_target" "$(basename "$1")")
  if cp -f "$1" "$pd_out" 2>/dev/null; then
    note "Saved to the phone" "$(basename "$pd_out") is in the phone's Download folder. Open the
phone's Files app to find it."
    say "$pd_out"
    return 0
  fi
  note "Could not save to the phone" "The phone would not accept the file. It is still here, in
the computer's Downloads."
  return 1
}

case "${1:-}" in
  --where)     current; echo ;;
  --set)       remember "${2:-}" ;;
  --phone-dir) phone_dir || { phone_off_message; exit 1; } ;;

  --choose)
    now=$(current)
    if have zenity; then
      picked=$(zenity --list --radiolist --width=520 --height=300 \
        --title="Where downloads go" \
        --text="A file downloaded in this computer, or written by an AI app, is saved:" \
        --column="" --column="Choice" --column="What happens" \
        $([ "$now" = computer ] && echo TRUE || echo FALSE) computer \
          "In this computer's Downloads. Private to PocketDesk. No permission needed." \
        $([ "$now" = phone ] && echo TRUE || echo FALSE) phone \
          "A copy also goes to the phone's Download folder, where every app can open it." \
        $([ "$now" = ask ] && echo TRUE || echo FALSE) ask \
          "Ask me each time a file arrives." 2>/dev/null) || exit 0
      [ -n "$picked" ] && remember "$picked"
    else
      say "Downloads currently go to: $now"
      say "Change it with: pocketdesk-save --set computer|phone|ask"
    fi
    ;;

  ""|-h|--help)
    say "usage: pocketdesk-save <file> [computer|phone|ask]"
    say "       pocketdesk-save --where | --set <choice> | --choose | --phone-dir"
    say "downloads currently go to: $(current)"
    ;;

  *)
    file=$1
    [ -f "$file" ] || { say "There is no file at $file"; exit 1; }
    choice=${2:-$(current)}

    if [ "$choice" = ask ]; then
      if have zenity; then
        # Named buttons rather than yes/no: "phone" and "computer" are the words the setting
        # uses, and a dialog that speaks differently from the setting is a dialog that confuses.
        zenity --question --width=460 --no-markup \
          --title="Where should this go?" \
          --text="$(basename "$file") has been downloaded.

It is in this computer's Downloads folder. Put a copy on the phone as well, where the phone's
own apps can open it?" \
          --ok-label="Copy to the phone" --cancel-label="Keep it here" 2>/dev/null
        [ $? -eq 0 ] && choice=phone || choice=computer
      else
        choice=computer
      fi
    fi

    case "$choice" in
      phone)
        to_phone "$file"
        ;;
      computer)
        note "Downloaded" "$(basename "$file") is in the computer's Downloads folder."
        say "$file"
        ;;
      *)
        say "The choice must be computer, phone or ask."
        exit 1 ;;
    esac
    ;;
esac
