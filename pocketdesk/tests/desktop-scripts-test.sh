#!/usr/bin/env bash
# Behaviour tests for the two shell scripts that decide whether a Linux app opens at all.
#
# The bug these cover: a Chromium-based app started without --no-sandbox exits before it draws
# anything, so tapping ChatGPT looked like it did nothing.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL desktop-scripts: $1" >&2; exit 1; }

# ---- pocketdesk-open: sandbox flags only for the apps that need them -----------------
mkdir -p "$WORK/usr/lib/electronish" "$WORK/usr/bin" "$WORK/home"
: > "$WORK/usr/lib/electronish/chrome_100_percent.pak"
cat > "$WORK/usr/lib/electronish/electronish" <<'APP'
#!/bin/sh
echo "ARGS: $*"
exit 7
APP
chmod +x "$WORK/usr/lib/electronish/electronish"
ln -sf ../lib/electronish/electronish "$WORK/usr/bin/electronish"
cat > "$WORK/usr/bin/plainish" <<'APP'
#!/bin/sh
echo "ARGS: $*"
exit 0
APP
chmod +x "$WORK/usr/bin/plainish"

export HOME="$WORK/home"
set +e
PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" \
  --label "Electron App" electronish >/dev/null 2>&1
status=$?
set -e
[ "$status" = 7 ] || fail "the app's exit code must reach the caller, got $status"
log="$HOME/.pocketdesk/logs/electronish.log"
[ -f "$log" ] || fail "a failed start must leave a log at $log"
grep -q -- '--no-sandbox' "$log" || fail "a Chromium-based app must be started with --no-sandbox"
grep -q -- '--disable-dev-shm-usage' "$log" || fail "missing --disable-dev-shm-usage"
grep -q -- '--password-store=basic' "$log" || fail "missing --password-store=basic"
# ChatGPT's main process asks Chromium whether GPU access is possible and throws when the answer
# is no. --disable-gpu alone still leaves software rasterisation, so the answer stays yes; adding
# --disable-software-rasterizer denies it outright and no window is ever created.
grep -q -- '--disable-software-rasterizer' "$log" \
  && fail "--disable-software-rasterizer denies GPU access outright and stops ChatGPT opening"
grep -q -- '--disable-gpu ' "$log" || fail "missing --disable-gpu"
# In single-process mode there is no GPU process at all, so Chromium reports GPU access as
# denied whatever the flags say -- and ChatGPT's error reporter makes that fatal.
grep -q -- '--single-process' "$log" && fail "--single-process re-denies GPU access and must never be passed"
grep -q -- '--no-zygote' "$log" || fail "Chromium apps must start with --no-zygote (the zygote fails under PRoot)"

# A stale single-instance lock must be cleared -- and must never leak into the launcher's own
# variables. This exact setup once made it execute the lock's target, "localhost-16621", as the
# app: the launch line in the log carried the wrong command and the app exited 127.
mkdir -p "$HOME/.config/Codex" "$HOME/.config/Live"
touch "$HOME/.config/Codex/SingletonLock" "$HOME/.config/Codex/SingletonCookie"
ln -sf "localhost-$$" "$HOME/.config/Live/SingletonLock"
set +e
PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" electronish >/dev/null 2>&1
status=$?
set -e
[ "$status" = 7 ] || fail "with stale locks present the app's own exit code must still come back, got $status"
grep -q 'clearing stale lock in Codex' "$HOME/.pocketdesk/logs/electronish.log" \
  || fail "a dead app's singleton lock must be cleared before launching"
[ -e "$HOME/.config/Codex/SingletonLock" ] && fail "the stale lock should be gone"
[ -L "$HOME/.config/Live/SingletonLock" ] || fail "a live app's lock must not be touched"
grep -q 'launching: .*electronish' "$HOME/.pocketdesk/logs/electronish.log" \
  || fail "the launch line must name the app, not a lock target"

PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" plainish >/dev/null 2>&1
grep -q 'ARGS: *$' "$HOME/.pocketdesk/logs/plainish.log" \
  || fail "an app that is not Chromium-based must be started with no extra flags"

# Every ordinary program lives in the same bin directory. Another program from it having a
# window (or the wallpaper process carrying the file manager's class) must never make the
# launcher decide that the program being tapped is "already open".
cp "$(command -v sleep)" "$WORK/usr/bin/otherish"
"$WORK/usr/bin/otherish" 300 &
other=$!
cat > "$WORK/usr/bin/wmctrl" <<WM
#!/bin/sh
case "\$1" in -lp) printf '0x02000004  0 $other phone Other Program\\n' ;; esac
exit 0
WM
chmod +x "$WORK/usr/bin/wmctrl"
printf '#!/bin/sh\necho 0x02000009\n' > "$WORK/usr/bin/xdotool"; chmod +x "$WORK/usr/bin/xdotool"
PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" plainish >/dev/null 2>&1
grep -q 'already open' "$HOME/.pocketdesk/logs/plainish.log" \
  && fail "a plain program must open a new window even when another program from its directory has one"
grep -q 'ARGS: *$' "$HOME/.pocketdesk/logs/plainish.log" || fail "the plain program must actually run"
kill -9 "$other" 2>/dev/null || true
rm -f "$WORK/usr/bin/wmctrl" "$WORK/usr/bin/xdotool" "$WORK/usr/bin/otherish"

# A leftover instance with no window still owns the single-instance socket, so a fresh launch
# hands over its request and exits 0 at once -- "success" -- and nothing appears. The launcher
# must find that instance by the directory its binary lives in (ChatGPT's launcher path never
# appears in the running process) and end it before starting. xdotool and wmctrl are stubbed to
# report no window so the check runs here.
printf '#!/bin/sh\nexit 0\n' > "$WORK/usr/bin/xdotool"; chmod +x "$WORK/usr/bin/xdotool"
printf '#!/bin/sh\nexit 0\n' > "$WORK/usr/bin/wmctrl"; chmod +x "$WORK/usr/bin/wmctrl"
cp "$(command -v sleep)" "$WORK/usr/lib/electronish/ghostproc"
"$WORK/usr/lib/electronish/ghostproc" 300 &
ghost=$!
cat > "$WORK/usr/lib/electronish/electronish" <<'APP'
#!/bin/sh
exit 0
APP
set +e
PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" electronish >/dev/null 2>&1
set -e
grep -q "ending windowless leftover instance" "$HOME/.pocketdesk/logs/electronish.log" \
  || fail "a windowless leftover instance in the app's own directory must be ended before launching"
if kill -0 "$ghost" 2>/dev/null; then kill -9 "$ghost" 2>/dev/null; fail "the leftover instance must actually be gone"; fi

# The opposite case, which is the one that ended ChatGPT: the app IS running and HAS a window,
# but its window is not classed after the launcher's name. A second tap must recognise the
# window by the owning pid (wmctrl -lp lists it) and bring it to the front -- never end it.
"$WORK/usr/lib/electronish/ghostproc" 300 &
ghost=$!
cat > "$WORK/usr/bin/wmctrl" <<WM
#!/bin/sh
case "\$1" in
  -lp) printf '0x02000003  0 $ghost phone Something Unrelated\\n' ;;
  -ia) echo "raised \$2" >> "$HOME/.pocketdesk/raised" ;;
esac
exit 0
WM
chmod +x "$WORK/usr/bin/wmctrl"
set +e
PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" electronish >/dev/null 2>&1
status=$?
set -e
[ "$status" = 0 ] || fail "a tap on an app that is already open must simply succeed, got $status"
grep -q "already open" "$HOME/.pocketdesk/logs/electronish.log" \
  || fail "an app with a window owned by one of its processes must be recognised as open"
grep -q "raised 0x02000003" "$HOME/.pocketdesk/raised" || fail "the open window must be brought to the front"
kill -0 "$ghost" 2>/dev/null || fail "an app that has a window must never be ended by a second tap"
kill -9 "$ghost" 2>/dev/null || true
rm -f "$WORK/usr/bin/xdotool" "$WORK/usr/bin/wmctrl" "$HOME/.pocketdesk/raised"

# ChatGPT specifically: its main process asks for GPU info and dies on "access denied", and on
# this Chromium --disable-gpu alone yields that answer. It must get SwiftShader instead.
mkdir -p "$WORK/usr/lib/chatgpt"
: > "$WORK/usr/lib/chatgpt/chrome_100_percent.pak"
printf '#!/bin/sh\necho "ARGS: $*"\nexit 0\n' > "$WORK/usr/lib/chatgpt/chatgpt"
chmod +x "$WORK/usr/lib/chatgpt/chatgpt"
ln -sf ../lib/chatgpt/chatgpt "$WORK/usr/bin/chatgpt"
PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" chatgpt >/dev/null 2>&1 || true
gpt_log="$HOME/.pocketdesk/logs/chatgpt.log"
grep -q -- '--use-angle=swiftshader' "$gpt_log" || fail "ChatGPT must be given SwiftShader so GPU access stays allowed"
grep -q -- ' --disable-gpu ' "$gpt_log" && fail "--disable-gpu denies GPU access outright for ChatGPT and must not be passed to it"
grep -q -- '--no-sandbox' "$gpt_log" || fail "ChatGPT still needs the sandbox flags"

# ---- pocketdesk-desktop: what the display server is started with -----------------------
# Every highlighted word used to be pushed to the phone's clipboard (the phone said "Copied"
# on every selection). Only a real copy may reach the phone.
grep -q -- '-SendPrimary=0' "$PROJECT_DIR/app/assets/pocketdesk-desktop.sh" \
  || fail "Xtigervnc must be started with -SendPrimary=0"
grep -q 'openbox/rc.xml' "$PROJECT_DIR/app/assets/pocketdesk-desktop.sh" \
  && fail "the window manager settings belong to pocketdesk-menu, which runs on every start"

# ---- pocketdesk-menu: launchers route through pocketdesk-open ------------------------
APPS="$WORK/apps"
mkdir -p "$APPS" "$WORK/coder" "$WORK/fakebin"
cat > "$APPS/chatgpt.desktop" <<'ENTRY'
[Desktop Entry]
Name=ChatGPT
Exec=chatgpt %U
Icon=chatgpt
Type=Application
ENTRY
cat > "$APPS/com.anthropic.Claude.desktop" <<'ENTRY'
[Desktop Entry]
Name=Claude
Exec=claude-desktop %U
Icon=claude-desktop
Type=Application
Actions=NewChat;

[Desktop Action NewChat]
Name=New Chat
Exec=claude-desktop "claude://new"
ENTRY
cat > "$APPS/nodisplay.desktop" <<'ENTRY'
[Desktop Entry]
Name=Should Not Appear
Exec=chatgpt
Type=Application
NoDisplay=true
ENTRY
for binary in chatgpt claude-desktop; do
  printf '#!/bin/sh\ntrue\n' > "$WORK/fakebin/$binary"
  chmod +x "$WORK/fakebin/$binary"
done

sed -e "s#^HOME_DIR=/home/coder#HOME_DIR=$WORK/coder#" \
    -e "s#/usr/share/applications#$APPS#g" \
    -e "s#chown -R coder:coder#true #" \
    "$PROJECT_DIR/app/assets/pocketdesk-menu.sh" > "$WORK/menu.sh"
PATH="$WORK/fakebin:$PATH" bash "$WORK/menu.sh"

entry="$WORK/coder/Desktop/chatgpt.desktop"
[ -f "$entry" ] || fail "ChatGPT should get a desktop icon"
grep -q '^Exec=/usr/local/bin/pocketdesk-open --label "ChatGPT" chatgpt %U$' "$entry" \
  || fail "the desktop icon must launch through pocketdesk-open and still accept a link (%U)"
grep -q '^Icon=chatgpt$' "$entry" || fail "the package's own icon name must be kept"

claude="$WORK/coder/Desktop/com.anthropic.Claude.desktop"
[ -f "$claude" ] || fail "Claude should get a desktop icon, looked for $claude"
grep -q 'Desktop Action' "$claude" && fail "extra action groups would start the app unwrapped"
grep -q '^Actions=' "$claude" && fail "Actions= must be dropped along with its groups"
grep -q '^Exec=/usr/local/bin/pocketdesk-open --label "Claude" claude-desktop %U$' "$claude" \
  || fail "Claude's launcher must go through pocketdesk-open too"

menu="$WORK/coder/.config/openbox/menu.xml"
grep -q 'pocketdesk-open --label "ChatGPT" chatgpt' "$menu" || fail "the menu must use the wrapper"
grep -q '%U' "$menu" && fail "field codes must not survive into a menu command, which no launcher expands"
grep -q 'Should Not Appear' "$menu" && fail "NoDisplay entries must stay out of the menu"

grep -q 'launcher_item_app = .*pocketdesk-chatgpt.desktop' "$WORK/coder/.config/tint2/tint2rc" \
  || fail "the panel launcher must point at the wrapped entry"

# ---- pocketdesk-menu: the window manager rules, rewritten on every run ------------------
cat > "$WORK/rc-default.xml" <<'RC'
<openbox_config>
  <font place="ActiveWindow"><name>sans</name><size>8</size></font>
  <theme><titleLayout>NLIMC</titleLayout></theme>
  <desktops><number>4</number></desktops>
  <keyboard>
    <keybind key="W-F1">
      <action name="GoToDesktop"><to>1</to></action>
    </keybind>
    <keybind key="W-F4">
      <action name="GoToDesktop"><to>4</to></action>
    </keybind>
    <keybind key="A-F4"><action name="Close"/></keybind>
  </keyboard>
  <applications>
  </applications>
</openbox_config>
RC
cat > "$APPS/chatgpt.desktop" <<'ENTRY'
[Desktop Entry]
Name=ChatGPT
Exec=chatgpt %U
Icon=chatgpt
Type=Application
MimeType=x-scheme-handler/chatgpt;x-scheme-handler/codex;
ENTRY
cat > "$APPS/org.gnome.Epiphany.desktop" <<'ENTRY'
[Desktop Entry]
Name=Web
Exec=epiphany %U
Icon=org.gnome.Epiphany
Type=Application
MimeType=text/html;x-scheme-handler/http;x-scheme-handler/https;
ENTRY
printf '#!/bin/sh\ntrue\n' > "$WORK/fakebin/epiphany"; chmod +x "$WORK/fakebin/epiphany"
POCKETDESK_OPENBOX_DEFAULT="$WORK/rc-default.xml" PATH="$WORK/fakebin:$PATH" bash "$WORK/menu.sh"
rc="$WORK/coder/.config/openbox/rc.xml"
[ -f "$rc" ] || fail "pocketdesk-menu must write the Openbox settings"
grep -q '<titleLayout>CIMNL</titleLayout>' "$rc" \
  || fail "the close button must sit at the left edge of the title bar, where a maximised window always starts"
grep -q '<application type="normal"><maximized>yes</maximized><decor>yes</decor></application>' "$rc" \
  || fail "every normal window must open maximised with a title bar"
grep -q '<size>11</size>' "$rc" || fail "title font must be enlarged for a phone"
grep -q 'key="W-F4".*pocketdesk-windows kill-active' "$rc" || fail "Super+F4 must force-close the window in front"
grep -q 'key="A-F4"' "$rc" || fail "Openbox's own bindings must be kept"
# Openbox binds Super+F1..F4 to "go to desktop N" by default: Force close on Super+F4 would also
# have switched every window out of sight. Those bindings go, and there is one desktop.
grep -q 'GoToDesktop' "$rc" && fail "the default go-to-desktop bindings must be removed"
grep -q '<number>1</number>' "$rc" || fail "a phone has one desktop"
grep -q 'key="W-p".*pcmanfm /home/coder/Phone' "$rc" || fail "Super+P must open the Phone folder"
grep -q 'key="W-a".*ShowMenu.*root-menu' "$rc" || fail "Super+A must open the apps menu (the panel's Apps button sends it)"
grep -q 'key="W-r".*pocketdesk-windows refresh' "$rc" || fail "Super+R must redraw the screen"
phone="$WORK/coder/Desktop/pocketdesk-phone.desktop"
[ -f "$phone" ] || fail "the desktop must carry a Phone files icon"
grep -q '^Exec=pcmanfm /home/coder/Phone$' "$phone" || fail "the Phone files icon must open /home/coder/Phone"
grep -q '^Name=Phone files$' "$phone" || fail "the folder is called Phone files, not Phone"
grep -q '^Icon=pocketdesk-phone$' "$phone" || fail "the Phone files icon must be the phone-with-a-folder mark"
tint="$WORK/coder/.config/tint2/tint2rc"
grep -q 'launcher_item_app = .*pocketdesk-phone.desktop' "$tint" || fail "Phone files must be on the panel too"
grep -q 'launcher_item_app = .*pocketdesk-apps.desktop' "$tint" || fail "the panel must carry the Apps button"
grep -q '^execp_command = /usr/local/bin/pocketdesk-status$' "$tint" || fail "the panel must show the phone's battery, temperature and memory"
grep -q '^panel_items = LTSEC$' "$tint" || fail "the panel items must include the executor (E)"
[ "$(grep 'launcher_item_app' "$tint" | head -n 1 | grep -c pocketdesk-apps.desktop)" = 1 ] \
  || fail "the Apps button must be the first thing on the panel"
grep -q '^Icon=pocketdesk-linux$' "$WORK/coder/.local/share/applications/pocketdesk-apps.desktop" \
  || fail "the Apps button wears Tux"
grep -q '^NoDisplay=true$' "$WORK/coder/.local/share/applications/pocketdesk-apps.desktop" \
  || fail "the Apps button must not list itself in the menu it opens"
grep -q 'label="Phone files"' "$menu" || fail "the menu must offer Phone files"
grep -q 'label="Terminal"' "$menu" || fail "the menu must offer the terminal"
grep -q 'label="Reload screen"' "$menu" || fail "the menu must offer a screen redraw"

# The browser opens links; a sign-in that opened in the browser comes back to the app through
# the scheme its package declares.
mime="$WORK/coder/.config/mimeapps.list"
[ -f "$mime" ] || fail "pocketdesk-menu must write mimeapps.list"
grep -q '^x-scheme-handler/https=pocketdesk-org.gnome.Epiphany.desktop$' "$mime" \
  || fail "https must open in the browser through its wrapped entry (the sandbox flags ride on it)"
grep -q '^x-scheme-handler/chatgpt=pocketdesk-chatgpt.desktop$' "$mime" \
  || fail "chatgpt:// links must come back to ChatGPT through the wrapped entry"
grep -q '^x-scheme-handler/codex=pocketdesk-chatgpt.desktop$' "$mime" || fail "every scheme an app declares must be routed"
grep -q '^MimeType=x-scheme-handler/chatgpt' "$WORK/coder/.local/share/applications/pocketdesk-chatgpt.desktop" \
  || fail "the wrapped entry must keep the schemes the package declares"

# A link handed to an app that is already open must reach it, not be dropped.
"$WORK/usr/lib/electronish/ghostproc" 300 &
ghost=$!
cat > "$WORK/usr/bin/wmctrl" <<WM
#!/bin/sh
case "\$1" in -lp) printf '0x02000004  0 $ghost phone Open Already\\n' ;; esac
exit 0
WM
chmod +x "$WORK/usr/bin/wmctrl"
printf '#!/bin/sh\necho "ARGS: $*"\nexit 0\n' > "$WORK/usr/lib/electronish/electronish"
set +e
PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" electronish 'electronish://callback?code=1' >/dev/null 2>&1
set -e
grep -q 'ARGS: .*--no-sandbox.*electronish://callback?code=1' "$HOME/.pocketdesk/logs/electronish.log" \
  || fail "a link for an open app must be handed to it with the sandbox flags"
kill -9 "$ghost" 2>/dev/null || true
rm -f "$WORK/usr/bin/wmctrl"
grep -c '^x-scheme-handler/http=' "$mime" | grep -qx 1 || fail "http must be routed to the browser exactly once"

# ---- The browser choice: Brave, when installed, becomes the one browser everywhere -------
cat > "$APPS/brave-browser.desktop" <<'ENTRY'
[Desktop Entry]
Name=Brave Web Browser
Exec=/usr/bin/brave-browser-stable %U
Icon=brave-browser
Type=Application
MimeType=text/html;x-scheme-handler/http;x-scheme-handler/https;
ENTRY
cat > "$APPS/lxterminal.desktop" <<'ENTRY'
[Desktop Entry]
Name=LXTerminal
Exec=lxterminal
Icon=lxterminal
Type=Application
ENTRY
printf '#!/bin/sh\ntrue\n' > "$WORK/fakebin/lxterminal"; chmod +x "$WORK/fakebin/lxterminal"
sed -e "s#^HOME_DIR=/home/coder#HOME_DIR=$WORK/coder#" \
    -e "s#/usr/share/applications#$APPS#g" \
    -e "s#/usr/bin/brave-browser-stable#$WORK/fakebin/brave-browser#g" \
    -e "s#chown -R coder:coder#true #" \
    "$PROJECT_DIR/app/assets/pocketdesk-menu.sh" > "$WORK/menu.sh"
sed -i "s#Exec=/usr/bin/brave-browser-stable#Exec=$WORK/fakebin/brave-browser#" "$APPS/brave-browser.desktop"
printf '#!/bin/sh\ntrue\n' > "$WORK/fakebin/brave-browser"; chmod +x "$WORK/fakebin/brave-browser"
POCKETDESK_OPENBOX_DEFAULT="$WORK/rc-default.xml" PATH="$WORK/fakebin:$PATH" bash "$WORK/menu.sh"
grep -q '^x-scheme-handler/https=pocketdesk-brave-browser.desktop$' "$mime" \
  || fail "with Brave installed, links must open in Brave"
[ -f "$WORK/coder/Desktop/brave-browser.desktop" ] || fail "Brave must get the browser's desktop icon"
grep -q '^Name=Brave$' "$WORK/coder/Desktop/brave-browser.desktop" || fail "the icon is labelled Brave"
[ -f "$WORK/coder/Desktop/org.gnome.Epiphany.desktop" ] && fail "one browser on the desktop, not two"
[ -f "$WORK/coder/Desktop/lxterminal.desktop" ] || fail "the terminal must have a desktop icon"
grep -q '^Name=Terminal$' "$WORK/coder/Desktop/lxterminal.desktop" || fail "the terminal icon is labelled Terminal"
grep -q 'launcher_item_app = .*pocketdesk-lxterminal.desktop' "$tint" || fail "the terminal must be on the panel"

# Google Chrome, when present, is the browser over anything else, and its Exec goes through the launcher.
cat > "$APPS/google-chrome.desktop" <<ENTRY
[Desktop Entry]
Name=Google Chrome
Exec=$WORK/fakebin/google-chrome-stable %U
Icon=google-chrome
Type=Application
MimeType=text/html;x-scheme-handler/http;x-scheme-handler/https;
ENTRY
printf '#!/bin/sh\ntrue\n' > "$WORK/fakebin/google-chrome-stable"; chmod +x "$WORK/fakebin/google-chrome-stable"
POCKETDESK_OPENBOX_DEFAULT="$WORK/rc-default.xml" PATH="$WORK/fakebin:$PATH" bash "$WORK/menu.sh"
grep -q '^x-scheme-handler/https=pocketdesk-google-chrome.desktop$' "$mime" || fail "with Chrome installed, links must open in Chrome"
[ -f "$WORK/coder/Desktop/google-chrome.desktop" ] || fail "Chrome must get the browser's desktop icon"
grep -q '^Name=Chrome$' "$WORK/coder/Desktop/google-chrome.desktop" || fail "the icon is labelled Chrome"
[ -f "$WORK/coder/Desktop/brave-browser.desktop" ] && fail "one browser on the desktop: Chrome, not Brave too"

# ---- pocketdesk-open: the memory guard, and a browser keeps its extensions ---------------
cat > "$WORK/usr/bin/wmctrl" <<WM
#!/bin/sh
case "\$1" in
  -lx) printf '0x02000007  0 epiphany.Epiphany phone Web\\n' ;;
  -ic) echo "closed \$2" >> "$HOME/.pocketdesk/closed" ;;
esac
exit 0
WM
chmod +x "$WORK/usr/bin/wmctrl"
printf '#!/bin/sh\nexit 0\n' > "$WORK/usr/bin/xdotool"; chmod +x "$WORK/usr/bin/xdotool"
printf '#!/bin/sh\necho "ARGS: $*"\nexit 0\n' > "$WORK/usr/lib/electronish/electronish"
rm -f "$HOME/.pocketdesk/closed"
POCKETDESK_FREE_MB=500 PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" electronish >/dev/null 2>&1 || true
grep -q 'closing the browser to make room' "$HOME/.pocketdesk/logs/electronish.log" \
  || fail "an AI app started with 500 MB free must close the browser first"
grep -q 'closed 0x02000007' "$HOME/.pocketdesk/closed" || fail "the browser window must actually be closed"
rm -f "$HOME/.pocketdesk/closed"
POCKETDESK_FREE_MB=2000 PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" electronish >/dev/null 2>&1 || true
grep -q 'closing the browser' "$HOME/.pocketdesk/logs/electronish.log" \
  && fail "with 2 GB free the browser must be left alone"
[ -e "$HOME/.pocketdesk/closed" ] && fail "no window may be closed when memory is plentiful"
mkdir -p "$WORK/usr/lib/brave-browser"
: > "$WORK/usr/lib/brave-browser/chrome_100_percent.pak"
printf '#!/bin/sh\necho "ARGS: $*"\nexit 0\n' > "$WORK/usr/lib/brave-browser/brave-browser"
chmod +x "$WORK/usr/lib/brave-browser/brave-browser"
ln -sf ../lib/brave-browser/brave-browser "$WORK/usr/bin/brave-browser"
POCKETDESK_FREE_MB=500 PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" brave-browser >/dev/null 2>&1 || true
brave_log="$HOME/.pocketdesk/logs/brave-browser.log"
grep -q -- '--no-sandbox' "$brave_log" || fail "Brave is Chromium and needs the sandbox flags under PRoot"
grep -q -- '--disable-extensions' "$brave_log" && fail "a browser must keep its extensions"
grep -q -- '--disable-background-networking' "$brave_log" && fail "a browser must keep its background updates"
grep -q 'closing the browser' "$brave_log" && fail "the browser is never closed to make room for itself"
mkdir -p "$WORK/opt/google/chrome"
: > "$WORK/opt/google/chrome/chrome_100_percent.pak"
printf '#!/bin/sh\necho "ARGS: $*"\nexit 0\n' > "$WORK/opt/google/chrome/google-chrome"
chmod +x "$WORK/opt/google/chrome/google-chrome"
ln -sf ../../opt/google/chrome/google-chrome "$WORK/usr/bin/google-chrome-stable"
PATH="$WORK/usr/bin:$PATH" bash "$PROJECT_DIR/app/assets/pocketdesk-open.sh" google-chrome-stable >/dev/null 2>&1 || true
chrome_log="$HOME/.pocketdesk/logs/google-chrome-stable.log"
grep -q -- '--no-sandbox' "$chrome_log" || fail "Chrome is Chromium and needs the sandbox flags under PRoot"
grep -q -- '--disable-extensions' "$chrome_log" && fail "Chrome must keep its extensions"
rm -f "$WORK/usr/bin/wmctrl" "$WORK/usr/bin/xdotool" "$HOME/.pocketdesk/closed"

# ---- pocketdesk-desktop: what every start sets up ----------------------------------------
desktop="$PROJECT_DIR/app/assets/pocketdesk-desktop.sh"
grep -q 'show_wm_menu=1' "$desktop" || fail "a right-click on the wallpaper must open the apps menu"
grep -q 'NO_AT_BRIDGE=1' "$desktop" || fail "the accessibility bus must be switched off (every app waited on it)"
grep -q 'module-simple-protocol-tcp' "$desktop" || fail "sound must be streamed to the phone"
grep -q 'source=phone.monitor' "$desktop" || fail "the stream must carry the Phone output"
audio_port=$(grep -oE 'PORT *= *[0-9]+' "$PROJECT_DIR/app/src/com/pocketdesk/AudioBridge.java" \
  | head -n 1 | grep -oE '[0-9]+' || true)
[ -n "$audio_port" ] || fail "AudioBridge.PORT could not be read"
grep -q "port=$audio_port" "$desktop" || fail "the sound fallback port must match AudioBridge.PORT ($audio_port)"
grep -q 'module-simple-protocol-unix' "$desktop" || fail "sound must go over a private socket first"
grep -q 'rfbunixpath' "$desktop" || fail "the desktop must be served over a private socket first"
grep -q 'rfbport -1' "$desktop" || fail "the TCP display port must be off when the socket is used"
grep -q 'backgrounds/pocketdesk.jpg' "$desktop" || fail "the desktop must use the Ubuntu wallpaper"

# An app that takes a minute to open has to look like it is opening: the round watch pointer and
# a pulsing window that names the app and how long it usually takes, both gone once it appears.
opener="$PROJECT_DIR/app/assets/pocketdesk-open.sh"
grep -q 'zenity --progress --pulsate' "$opener" || fail "opening an app must show a busy indicator"
grep -q "xsetroot -cursor_name watch" "$opener" || fail "the pointer must show that the desktop is busy"
grep -q "xsetroot -cursor_name left_ptr" "$opener" || fail "the busy pointer must be put back"
grep -q 'expected_wait' "$opener" || fail "the wait message must say how long it usually takes"
grep -q 'trap .spinner_stop. EXIT' "$opener" || fail "the busy indicator must go even if the launcher dies"
awk '/if has_window; then/,/fi/' "$opener" | grep -q 'spinner_stop' \
  || fail "the busy indicator must close as soon as the app has a window"

# ---- Installing an app you downloaded yourself -------------------------------------------
# The Android moment: a file from a website, a screen that says what it is and what it needs,
# a hard stop when it cannot work here, and "Install anyway" when only trust is at stake.
installer="$PROJECT_DIR/app/assets/pocketdesk-install.sh"
[ -f "$installer" ] || fail "the app installer is missing"
grep -q 'Install anyway' "$installer" || fail "a risk the owner can judge must offer Install anyway"
grep -q 'Cannot ask you first' "$installer" || fail "nothing may be installed without asking first"
grep -q 'MimeType=application/vnd.debian.binary-package' "$PROJECT_DIR/app/assets/pocketdesk-menu.sh" \
  || fail "a downloaded package must open the installer"
grep -q 'application/vnd.debian.binary-package=pocketdesk-install.desktop' "$PROJECT_DIR/app/assets/pocketdesk-menu.sh" \
  || fail "the file-type table must send .deb files to the installer"
grep -q 'Install a downloaded app' "$PROJECT_DIR/app/assets/pocketdesk-menu.sh" \
  || fail "the menu must offer to install a downloaded app"

if command -v dpkg-deb >/dev/null 2>&1; then
  PKG="$WORK/pkgsrc"
  rm -rf "$PKG"; mkdir -p "$PKG/DEBIAN" "$PKG/usr/bin"
  printf '#!/bin/sh\nexit 0\n' > "$PKG/usr/bin/demoapp"; chmod 755 "$PKG/usr/bin/demoapp"
  write_control() {   # write_control <package> <arch> <installed-size KB>
    printf 'Package: %s\nVersion: 1.2.3\nArchitecture: %s\nMaintainer: Demo <d@example.com>\nInstalled-Size: %s\nDescription: A demo app\n' \
      "$1" "$2" "$3" > "$PKG/DEBIAN/control"
  }
  report() { POCKETDESK_SIMULATE=0 bash "$installer" --report "$1" 2>&1; }

  write_control demoapp arm64 2048
  dpkg-deb --build -Znone "$PKG" "$WORK/demo_arm64.deb" >/dev/null 2>&1
  out=$(report "$WORK/demo_arm64.deb")
  echo "$out" | grep -q '^verdict=warn' || fail "an ARM64 package must be installable with a warning: $out"
  echo "$out" | grep -q 'not signed' || fail "the owner must be told a downloaded package is unsigned"

  write_control demoapp amd64 2048
  dpkg-deb --build -Znone "$PKG" "$WORK/demo_amd64.deb" >/dev/null 2>&1
  out=$(report "$WORK/demo_amd64.deb")
  echo "$out" | grep -q '^verdict=blocked' || fail "an Intel/AMD package must be blocked: $out"
  echo "$out" | grep -qi 'ARM64 processor' || fail "the reason must name the processor"

  # A package larger than the phone's free space is blocked, with both numbers side by side.
  write_control demoapp arm64 419430400
  dpkg-deb --build -Znone "$PKG" "$WORK/demo_huge.deb" >/dev/null 2>&1
  out=$(report "$WORK/demo_huge.deb")
  echo "$out" | grep -q '^verdict=blocked' || fail "a package larger than the free space must be blocked: $out"

  # One of the four AI apps, downloaded by hand: allowed, but told where the signed copy is.
  write_control chatgpt arm64 2048
  dpkg-deb --build -Znone "$PKG" "$WORK/chatgpt.deb" >/dev/null 2>&1
  out=$(report "$WORK/chatgpt.deb" || true)
  echo "$out" | grep -q 'Apps tab' \
    || fail "a hand-downloaded copy of a published app must point at the Apps tab: $out"

  printf 'not a package' > "$WORK/notes.txt"
  out=$(report "$WORK/notes.txt" || true)
  echo "$out" | grep -qi 'not a linux app package' || fail "a non-package must be refused plainly: $out"
  printf 'x' > "$WORK/thing.AppImage"
  out=$(report "$WORK/thing.AppImage" || true)
  echo "$out" | grep -qi 'AppImage' || fail "an AppImage must be explained, not silently refused: $out"
fi

echo "PASS DesktopScripts (launcher flags, window detection, memory guard, browser choice, menu wiring, window rules, link routing, sound)"
