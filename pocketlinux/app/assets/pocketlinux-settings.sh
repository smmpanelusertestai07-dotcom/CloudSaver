#!/bin/bash
# System settings, inside the computer.
#
# The computer had no settings of its own. Everything it can change was real and reachable --
# the bar's edge at the bottom of a right-click menu, the icon theme behind a menu called
# "Appearance", the sound mixer two levels down in Tools -- but nothing on the desktop said
# "settings", so none of it was findable. This is that one place.
#
# It deliberately owns nothing. Every row runs a tool that already exists, and the two settings
# that belong to the phone rather than to the computer (where new files are saved, and the app
# lock) say so and point at PocketLinux's own Settings instead of pretending otherwise: the
# desktop is started fresh from those values each time, so a copy kept here would be overwritten
# the next morning and the owner would never know which one had won.
set -u
HOME_DIR=${HOME:-/home/coder}
CONFIG="$HOME_DIR/.config/pocketlinux"
WINDOWS=/usr/local/bin/pocketlinux-windows

# The list stays open after a change (see menu), so a row that opens another program has to say
# so: having Settings pop straight back up on top of the sound mixer it had just started was
# worse than closing it.
HANDED_OVER=0

have() { command -v "$1" >/dev/null 2>&1; }

usage() {
  printf 'usage: pocketlinux-settings [theme|theme-dark|theme-light|theme-system|wallpaper|panel|size|appearance|sound|storage|software|downloads|refresh|about]\n'
}

tell() {   # tell <title> <text>
  if have zenity; then
    zenity --info --no-markup --width=560 --title="$1" --text="$2" 2>/dev/null || true
  else
    printf '%s\n\n%s\n' "$1" "$2"
  fi
}

start() {   # start <command...> -- detached, and Settings closes behind it
  if have "$1"; then
    "$@" >/dev/null 2>&1 &
    HANDED_OVER=1
  else
    tell "Not installed" "$1 is not installed on this computer yet. Software can install it."
  fi
}

panel_edge() {
  edge=$(cat "$CONFIG/panel-edge" 2>/dev/null)
  case "$edge" in top) echo top ;; *) echo bottom ;; esac
}

theme_now() {
  grep -q '^gtk-application-prefer-dark-theme=0' "$HOME_DIR/.config/gtk-3.0/settings.ini" 2>/dev/null \
    && echo light || echo dark
}

# What the owner chose, which is not the same question as what the windows look like right now:
# "system" means the phone decides, and the phone's answer is whatever it sent in at this
# desktop's start. A computer that has never been told reads as whatever it is wearing.
theme_choice() {
  case "$(cat "$CONFIG/theme" 2>/dev/null)" in
    light)  echo light ;;
    dark)   echo dark ;;
    system) echo system ;;
    *)      theme_now ;;
  esac
}

theme_label() {
  case "$(theme_choice)" in
    light)  echo "Light" ;;
    system) echo "Follow the phone" ;;
    *)      echo "Dark" ;;
  esac
}

theme_pick() {
  if ! have zenity; then
    usage
    return 0
  fi
  pick_light=FALSE
  pick_dark=FALSE
  pick_system=FALSE
  case "$(theme_choice)" in
    light)  pick_light=TRUE ;;
    system) pick_system=TRUE ;;
    *)      pick_dark=TRUE ;;
  esac
  want=$(zenity --list --radiolist --width=460 --height=300 \
    --title="Theme" \
    --text="How the computer's windows look." \
    --column="" --column="id" --column="Theme" \
    --hide-column=2 --print-column=2 \
    "$pick_light"  light  "Light" \
    "$pick_dark"   dark   "Dark" \
    "$pick_system" system "Follow the phone" \
    2>/dev/null) || return 0
  [ -n "$want" ] || return 0
  set_theme "$want"
}

# Light, dark, or whatever the phone is set to, written the way the desktop's own start-up writes
# them, so one file is the truth and nothing here invents a second theme system.
#
# The note used to say "Windows opened from now on use the $want theme", and that was not true.
# Only a program started through pocketlinux-open is handed the new theme; the bar, the window
# title bars, the terminal and the on-screen messages are drawn in the app's own dark colours
# whichever theme is chosen. So the note promises windows and says the bar stays as it is.
set_theme() {   # set_theme dark|light|system
  want=$1
  # "system" has no colour of its own. It uses the answer the phone sent in when this desktop
  # started, which is the same answer the desktop will ask the phone for again next time. Run
  # from a terminal with no desktop around it there is no answer, and then nothing is repainted
  # and only the choice is saved.
  case "$want" in
    system) applied=${POCKETLINUX_THEME:-} ;;
    *)      applied=$want ;;
  esac
  if [ -n "$applied" ]; then
    case "$applied" in light) prefer=0 ;; *) prefer=1 ;; esac
    for gtk_dir in "$HOME_DIR/.config/gtk-3.0" "$HOME_DIR/.config/gtk-4.0"; do
      ini="$gtk_dir/settings.ini"
      [ -f "$ini" ] || continue
      tmp="$ini.pocketlinux-new"
      awk -v prefer="$prefer" '
        /^gtk-application-prefer-dark-theme=/ { print "gtk-application-prefer-dark-theme=" prefer; next }
        { print }
      ' "$ini" > "$tmp" 2>/dev/null && mv -f "$tmp" "$ini"
    done
  fi
  printf '%s\n' "$want" > "$CONFIG/theme"
  if [ "$want" = system ]; then
    tell "Theme" "The computer now follows the phone.

PocketLinux tells it which look to use every time the desktop opens, so Light or Dark on the
phone changes this too.

A window that is already open keeps the look it has, because every Linux program reads the theme
once, when it starts. Close it and open it again to see the change."
  else
    tell "Theme" "Saved: the $want theme.

A window that is already open keeps the look it has, because every Linux program reads the theme
once, when it starts. Close it and open it again to see the change.

The bar at the edge of the screen and the window title bars stay in PocketLinux's own dark
colours in both themes."
  fi
}

# The background picture. The file manager paints the desktop, and its own Desktop Preferences
# cannot be reached from here because a right-click on the desktop opens the apps menu instead,
# so there was no way at all to change the picture. This row is it.
#
# The picture is copied into the computer's own settings folder, and that copy is what is used.
# The one the owner picks is usually in Phone files or in Downloads: the first disappears when
# the All files permission is switched off, the second is a folder people empty, and either way
# the desktop would come back one morning with no background and nothing saying why.
wallpaper() {
  if ! have zenity; then
    printf 'Choose a background picture from the Settings window on the desktop.\n'
    return 0
  fi
  picked=$(zenity --file-selection --title="Choose a background picture" \
    --filename="$HOME_DIR/Pictures/" \
    --file-filter="Pictures | *.jpg *.jpeg *.JPG *.JPEG *.png *.PNG" \
    --file-filter="Every file | *" 2>/dev/null) || return 0
  [ -n "$picked" ] || return 0
  if [ ! -f "$picked" ]; then
    tell "Background" "That picture is not there any more."
    return 0
  fi
  case "$picked" in
    *.png|*.PNG) kept="$CONFIG/wallpaper.png" ;;
    *)           kept="$CONFIG/wallpaper.jpg" ;;
  esac
  mkdir -p "$CONFIG"
  # One kept picture at a time. Choosing a png after a jpg would otherwise leave the jpg in the
  # settings folder for ever, with nothing using it.
  rm -f "$CONFIG/wallpaper.jpg" "$CONFIG/wallpaper.png"
  if ! cp -f "$picked" "$kept" 2>/dev/null; then
    tell "Background" "That picture could not be copied. Check the computer has space left:
Settings, then What is using the space."
    return 0
  fi
  # --set-wallpaper does both halves: it changes the picture on the screen now, and the file
  # manager writes it into its own settings, so it is still there at the next start.
  if have pcmanfm; then
    pcmanfm --set-wallpaper="$kept" --wallpaper-mode=fit >/dev/null 2>&1 || true
  fi
  # And the path on its own, for a desktop starting with no file-manager settings yet.
  printf '%s\n' "$kept" > "$CONFIG/wallpaper"
  tell "Background" "Done. That picture is the desktop background now.

A copy is kept inside the computer, so the background stays even if you move or delete the
picture you chose."
}

wallpaper_label() {
  if [ -f "$CONFIG/wallpaper.jpg" ] || [ -f "$CONFIG/wallpaper.png" ]; then
    echo "Your own"
  else
    echo "PocketLinux"
  fi
}

# The whole folder path is far too wide for a phone screen, and the words the phone's own Settings
# offered are what the owner picked from in the first place.
downloads_label() {
  case "${POCKETLINUX_DOWNLOAD_TARGET:-}" in
    computer) echo "Computer" ;;
    phone)    echo "Phone" ;;
    ask)      echo "It asks" ;;
    *)
      where=$(cat "$CONFIG/download-dir" 2>/dev/null)
      [ -n "$where" ] || where="$HOME_DIR/Downloads"
      basename "$where"
      ;;
  esac
}

download_note() {
  where=$(cat "$CONFIG/download-dir" 2>/dev/null)
  [ -n "$where" ] || where="$HOME_DIR/Downloads"
  tell "Downloads go to" "Right now: $where (the folder an app that does not ask uses)

This one belongs to the phone, not to the computer: PocketLinux writes it into the computer every
time the desktop starts, so a change made here would be replaced the next time you opened it.

Change it in PocketLinux -> Settings -> Data and files -> Downloads go to: Ask every time,
Computer Downloads or Phone Downloads."
}

about() {
  version=$(cat /var/lib/pocketlinux/basics-version 2>/dev/null || echo unknown)
  release=$(. /etc/os-release 2>/dev/null; printf '%s' "${PRETTY_NAME:-Ubuntu}")
  tell "About this computer" "$release on ARM64, running on your phone.

Desktop: Openbox and tint2, drawn by PocketLinux.
Screen: $(printf '%s' "${DISPLAY:-:1}") at $(xdpyinfo 2>/dev/null | awk '/dimensions:/ { print $2; exit }' || echo 'unknown')
Text size: $(awk -F: '/^Xft\.dpi:/ { gsub(/[^0-9]/, "", $2); print $2 " dpi"; exit }' "$HOME_DIR/.Xresources" 2>/dev/null || echo 'unknown')
Theme: $(theme_label)
PocketLinux basics: $version

Everything here is ordinary Ubuntu. Nothing is emulated and nothing is remote: the programs are
ARM64 Linux binaries running on this phone's own processor."
}

# The rows carry a group of their own -- Look, Sound, Storage, Software, About -- because a flat
# list of eleven settings is a list you read from the top every time, and because the names had
# to get shorter to fit a phone screen: "Appearance -- icons, fonts, cursors" was cut off in the
# middle on a portrait screen, and the tool names that used to sit in the Now column
# ("lxappearance", "pavucontrol") meant nothing to the owner.
#
# The list stays open until the owner closes it. It used to show once, do the one thing and
# exit, so changing the theme and then moving the bar meant finding the Settings icon twice and
# waiting twice for a fresh dialog to start under PRoot.
menu() {
  if ! have zenity; then
    usage
    return 0
  fi
  while :; do
    HANDED_OVER=0
    edge=$(panel_edge)
    case "$edge" in top) move_to="bottom" ;; *) move_to="top" ;; esac
    choice=$(zenity --list --radiolist --width=720 --height=560 \
      --title="Settings" \
      --text="The computer's own settings. Phone permissions and the app lock live in PocketLinux." \
      --column="" --column="id" --column="Group" --column="Setting" --column="Now" \
      --hide-column=2 --print-column=2 \
      TRUE  theme      "Look"     "Theme"                        "$(theme_label)" \
      FALSE wallpaper  "Look"     "Background picture"           "$(wallpaper_label)" \
      FALSE size       "Look"     "Text and icon size"           "In PocketLinux" \
      FALSE panel      "Look"     "Move the bar to the $move_to" "$edge" \
      FALSE appearance "Look"     "Icons, fonts and pointer"     "" \
      FALSE sound      "Sound"    "Sound, input and levels"      "" \
      FALSE storage    "Storage"  "What is using the space"      "" \
      FALSE downloads  "Storage"  "Downloads go to"              "$(downloads_label)" \
      FALSE software   "Software" "Software and updates"         "" \
      FALSE refresh    "Software" "Refresh apps and desktop"     "" \
      FALSE about      "About"    "About this computer"          "" \
      2>/dev/null) || return 0
    [ -n "$choice" ] || return 0
    run "$choice"
    [ "$HANDED_OVER" = 1 ] && return 0
  done
}

run() {
  case "$1" in
    theme)      theme_pick ;;
    theme-dark) set_theme dark ;;
    theme-light) set_theme light ;;
    theme-system) set_theme system ;;
    wallpaper)  wallpaper ;;
    panel)      "$WINDOWS" panel-edge "$( [ "$(panel_edge)" = top ] && echo bottom || echo top )" ;;
    size)       tell "Text and icon size" "The size of everything on this desktop is set in PocketLinux -> Settings -> Desktop text size.

It is one number -- the screen's dpi -- and every program on the computer reads it when it starts,
which is why it applies the next time the desktop is opened rather than straight away.

Inside the desktop screen you can also use Screen -> Bigger interface, which takes effect at once." ;;
    appearance) start lxappearance ;;
    sound)      start pavucontrol ;;
    storage)    start /usr/local/bin/pocketlinux-storage ;;
    software)   start /usr/local/bin/pocketlinux-software ;;
    downloads)  download_note ;;
    refresh)    /usr/local/bin/pocketlinux-menu >/dev/null 2>&1; "$WINDOWS" refresh >/dev/null 2>&1 || true ;;
    about)      about ;;
    # The list is named here rather than left to the default, because menu calls run in a loop
    # now: a default that re-opened the list would be a loop inside a loop.
    menu)       menu ;;
    *)          usage >&2; return 2 ;;
  esac
}

run "${1:-menu}"
