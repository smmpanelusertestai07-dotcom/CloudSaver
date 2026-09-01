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

# A leftover instance with no window still owns the single-instance socket, so a fresh launch
# hands over its request and exits 0 at once -- "success" -- and nothing appears. The launcher
# must find that instance by the directory its binary lives in (ChatGPT's launcher path never
# appears in the running process) and end it before starting. xdotool is stubbed to report no
# window so the check runs here.
printf '#!/bin/sh\nexit 0\n' > "$WORK/usr/bin/xdotool"; chmod +x "$WORK/usr/bin/xdotool"
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
rm -f "$WORK/usr/bin/xdotool"

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
grep -q '^Exec=/usr/local/bin/pocketdesk-open --label "ChatGPT" chatgpt$' "$entry" \
  || fail "the desktop icon must launch through pocketdesk-open"
grep -q '^Icon=chatgpt$' "$entry" || fail "the package's own icon name must be kept"
grep -q '%U' "$entry" && fail "field codes must not survive into the launch command"

claude="$WORK/coder/Desktop/com.anthropic.Claude.desktop"
[ -f "$claude" ] || fail "Claude should get a desktop icon, looked for $claude"
grep -q 'Desktop Action' "$claude" && fail "extra action groups would start the app unwrapped"
grep -q '^Actions=' "$claude" && fail "Actions= must be dropped along with its groups"
grep -q '^Exec=/usr/local/bin/pocketdesk-open --label "Claude" claude-desktop$' "$claude" \
  || fail "Claude's launcher must go through pocketdesk-open too"

menu="$WORK/coder/.config/openbox/menu.xml"
grep -q 'pocketdesk-open --label "ChatGPT" chatgpt' "$menu" || fail "the menu must use the wrapper"
grep -q 'Should Not Appear' "$menu" && fail "NoDisplay entries must stay out of the menu"

grep -q 'launcher_item_app = .*pocketdesk-chatgpt.desktop' "$WORK/coder/.config/tint2/tint2rc" \
  || fail "the panel launcher must point at the wrapped entry"

echo "PASS DesktopScripts (launcher flags, menu wiring)"
