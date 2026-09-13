#!/usr/bin/env bash
# What has to stay true in this app, checked on every push.
#
# Most of this app is other people's software: Ubuntu, Node, code-server, and each publisher's
# own extension or daemon. There is very little of our own logic to unit-test. What there is to
# protect is the honesty of the thing -- that a door which has never been opened says so, that
# nothing claims a publisher supports something they do not, that the scripts are valid shell
# before they are shipped to a phone, and that no key or token rides along in the APK.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

SRC="app/src/com/pocketagent/doors"
ASSETS="app/assets"

fail() { echo "FAIL $1"; exit 1; }

# ---------------------------------------------------------------- shell, before it reaches a phone
for script in "$ASSETS"/*.sh; do
  bash -n "$script" || fail "AssetScriptSyntax: $script does not parse"
done
echo "PASS AssetScriptSyntax ($(ls "$ASSETS"/*.sh | wc -l | tr -d ' ') scripts)"

# ---------------------------------------------------------------- the catalog tells the truth
# Every agent's start line must name a script that exists, or be empty for a door that is not
# wired up. A start line pointing at a missing script would fail on the phone, not here.
while read -r start; do
  [ -z "$start" ] && continue
  name=${start%% *}
  [ -f "$ASSETS/$name" ] || fail "Doors: $name is named in the catalog but not shipped"
done < <(grep -oE '"doors-[a-z]+\.sh [a-z]+( [a-z]+)?"' "$SRC/Doors.java" | tr -d '"')
echo "PASS DoorScripts (every start line points at a shipped script)"

# No agent may be marked proven until somebody has actually run it on a phone. This is the
# gate that stops a confident claim sneaking into a build that has never been tested.
if grep -qE 'true\);\s*$' "$SRC/Doors.java" && grep -c 'proven' "$SRC/Doors.java" >/dev/null; then
  proven=$(grep -cE '^\s+(true|false)\),?$' "$SRC/Doors.java" || true)
fi
if grep -oE '\btrue\),$' "$SRC/Doors.java" | grep -q .; then
  fail "DoorHonesty: an agent is marked proven; nothing in this build has been proven on a phone"
fi
echo "PASS DoorHonesty (no agent claims to be proven)"

# Three agents, and the reason for three is on a screen. This replaced a check that only said
# "Cursor must not claim a headless route" -- once Cursor was removed outright that check passed
# by having nothing to look at, which is the weakest kind of green. What matters now is that a
# maker who was left out is left out in writing, where the owner can read the reason and judge it.
count=$(grep -c 'new Agent(' "$SRC/Doors.java" || true)
[ "$count" = "3" ] || fail "ThreeLabs: the catalog holds $count agents; this build is scoped to three"
for id in antigravity claude codex; do
  grep -q "new Agent(\"$id\"" "$SRC/Doors.java" \
    || fail "ThreeLabs: $id is missing from the catalog"
done
# The entry itself, not a mention of the name. Written as a plain grep first, this stayed green
# when an entry was deleted, because the same name still appeared in another entry's prose --
# the third time in this project a check has passed on a comment instead of the thing it meant.
for gone in Cursor "xAI" "Meta"; do
  grep -q "new Missing(\"$gone" "$SRC/Reasons.java" \
    || fail "ThreeLabs: $gone was dropped from the catalog without a reason written down"
done
echo "PASS ThreeLabs (three agents, and every exclusion is explained in writing)"

# The reason has to be reachable, not just present in a source file nobody opens.
grep -q 'ReasonsActivity' "$SRC/HomeActivity.java" \
  || fail "ReasonGiven: nothing on the home screen leads to the reason"
grep -q 'ReasonsActivity' app/AndroidManifest.xml \
  || fail "ReasonGiven: the screen is not registered, so opening it would crash"
grep -q 'linux-arm64' "$SRC/Reasons.java" \
  || fail "ReasonGiven: the bar for a fourth maker does not mention the architecture it must build for"
grep -q 'NOT_A_VERDICT' "$SRC/ReasonsActivity.java" \
  || fail "ReasonGiven: the screen omits the line saying this is not a ranking of models"
grep -q 'JetBrains' "$SRC/Reasons.java" \
  || fail "ReasonGiven: a figure is quoted with no source named"
echo "PASS ReasonGiven (the reason is on screen, with its sources and its limits)"

# ---------------------------------------------------------------- the name is ours, the agents are theirs
# Each of the three asks that its mark not become part of somebody else's product name, and an
# app called after them would read as official, which this is not. So no maker's name may appear
# in the app's own name -- and the makers are named on the home screen instead, where naming them
# is the opposite claim: this is what it runs.
label=$(grep -oE '<string name="app_name">[^<]*</string>' app/res/values/strings.xml | sed 's/.*>\(.*\)<.*/\1/')
[ -n "$label" ] || fail "TheirNamesNotOurs: the app has no name"
for mark in Claude Codex Antigravity Anthropic OpenAI Google ChatGPT Gemini; do
  printf '%s' "$label" | grep -qi "$mark" \
    && fail "TheirNamesNotOurs: the app calls itself \"$label\", which carries $mark's mark"
done
# And the three are named where somebody can read them before spending a gigabyte of mobile data.
for agent in "CLAUDE CODE" "CODEX" "ANTIGRAVITY"; do
  grep -q "$agent" "$SRC/HomeActivity.java" \
    || fail "TheirNamesNotOurs: the home screen does not say it runs $agent"
done
# "Doors" named three doors to pick between. There is one workspace, so the word is a description
# of something that was deleted; it may stay in the application ID, which cannot change without
# costing whoever installed this their whole workspace, but not in what the owner reads.
for shown in app/res/values/strings.xml README.md RELEASE-NOTES.md; do
  head -n 40 "$shown" | grep -q 'PocketAgent Doors' \
    && fail "TheirNamesNotOurs: $shown still calls the app Doors, a design it no longer has"
done
grep -q '^APP_BASENAME="PocketAgent"$' build.sh \
  || fail "TheirNamesNotOurs: the APK is still filed under a name the app does not use"
echo "PASS TheirNamesNotOurs (our name is ours, and theirs are named as what it runs)"

# ---------------------------------------------------------------- one way in, per agent
# The point of this build. Three doors meant a menu, and a menu is a question somebody has to
# answer before they can start working -- when for each maker exactly one route is the best their
# own publishing supports. So: one script, one start line each, one address, and nothing shipped
# that could become a second route later.
starts=$(grep -oE '"doors-[a-z]+\.sh start [a-z]+"' "$SRC/Doors.java" | sort -u)
[ "$(printf '%s\n' "$starts" | wc -l | tr -d ' ')" = "3" ] \
  || fail "OneWayIn: there are not exactly three start lines; a route was added or lost"
for id in claude codex antigravity; do
  printf '%s\n' "$starts" | grep -q "\"doors-workspace.sh start $id\"" \
    || fail "OneWayIn: $id does not start through the one workspace script"
done
# There is no address to compare any more -- the editor is a program on this phone, not a page --
# so what has to hold is that the three reach it the same way: one script, one argument each,
# and no second thing anywhere that could be started instead.
# And there is nothing else to start. A second start script in assets is how a menu comes back.
extra=$(ls "$ASSETS"/*.sh | grep -v 'doors-bootstrap.sh\|doors-workspace.sh' || true)
[ -z "$extra" ] || fail "OneWayIn: a second startable script is shipped -- $extra"
# -o, not -c: both names sit on one line in SCRIPTS, so counting lines counts one.
shipped=$(grep -oE '"doors-[a-z]+\.sh"' "$SRC/Doors.java" | sort -u | wc -l | tr -d ' ')
[ "$shipped" = "2" ] \
  || fail "OneWayIn: the catalog ships $shipped scripts; this build has two"
echo "PASS OneWayIn (one script, one start line each, one address, nothing else to choose)"

# ---------------------------------------------------------------- nothing silently switches it off
# Anthropic's own requirements page names four variables, each of which disables the feature-flag
# evaluation Remote Control depends on. Set any of them and the session starts, works, and simply
# never appears on the phone -- the exact failure this app has no way to explain. A fifth,
# ANTHROPIC_BASE_URL, points the API somewhere else and is refused outright.
for killer in DISABLE_TELEMETRY DO_NOT_TRACK CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC DISABLE_GROWTHBOOK; do
  if grep -REn "^[^#]*(export +)?$killer=" "$ASSETS" "$SRC"; then
    fail "NothingDisabled: $killer is set, which switches Remote Control off without saying so"
  fi
  grep -q "unset .*$killer" "$ASSETS/doors-workspace.sh" \
    || fail "NothingDisabled: $killer is never cleared, so anything could leave it set"
done
grep -q 'unset ANTHROPIC_BASE_URL' "$ASSETS/doors-workspace.sh" \
  || fail "NothingDisabled: the API host is not cleared; a custom one gets features refused outright"
echo "PASS NothingDisabled (nothing in this build switches a maker's features off behind the owner)"

# ---------------------------------------------------------------- what it costs, in their words
# This app told people Codex was included in "Every ChatGPT plan, Free included". OpenAI's own
# extension says: "included in ChatGPT Plus, Pro, Business, Edu, and Enterprise plans" -- Free is
# not on that list. Someone on a free account would have spent 450 MB of mobile data to find out.
# The publisher's list is the only thing allowed to answer this question.
if grep -qi 'Free included\|free tier.*ChatGPT\|ChatGPT.*Free plan' "$SRC/Doors.java"; then
  fail "PlansAreTheirs: Codex is described as free; its publisher lists paid plans only"
fi
grep -q 'ChatGPT Plus, Pro, Business, Edu or Enterprise' "$SRC/Doors.java" \
  || fail "PlansAreTheirs: the plans named for Codex are not the ones its publisher names"
# And it must not be sold as the macOS application, which is a different product.
# One unsplit phrase, because a Java string broken across two lines defeats any pattern that
# tries to span the break -- which is how this check first failed on text that was already there.
grep -q 'Codex application for macOS' "$SRC/Doors.java" \
  || fail "PlansAreTheirs: nothing says this is the VS Code extension rather than the Mac app"
echo "PASS PlansAreTheirs (what an agent costs is quoted from its publisher, not guessed)"

# ---------------------------------------------------------------- the workspace can resolve a name
# Android gives a container no resolver, and without one every fetch fails with "Temporary
# failure resolving" -- which is how the second set-up on a real phone died. The resolver has to
# be written before every start, and it has to follow the phone between mobile data and Wi-Fi,
# because a set-up runs for long enough to cross that boundary.
grep -q 'Dns.refresh(context)' "$SRC/Ubuntu.java" \
  || fail "Resolver: nothing writes the phone's DNS into the workspace before a command runs"
grep -q 'dns.start()' "$SRC/App.java" \
  || fail "Resolver: the resolver does not follow the phone between networks"
grep -q 'preferIPv4' "$SRC/Ubuntu.java" \
  || fail "Resolver: IPv4 is not preferred, so a mobile AAAA answer can stall every fetch"
# Only the phone's own servers, ever. A silent public fallback would route someone's lookups
# through a third party without telling them.
if grep -qE '8\.8\.8\.8|1\.1\.1\.1|9\.9\.9\.9' "$SRC/ResolverConfig.java" "$SRC/Dns.java" "$ASSETS"/*.sh; then
  fail "Resolver: a public DNS server is hard-coded; only the phone's own servers may be used"
fi
echo "PASS Resolver (the phone's own DNS, written before every start and followed across networks)"

# ---------------------------------------------------------------- this app's own words come first
# The editor announces itself with "READY http://127.0.0.1:8391/". A generic scan for a link used
# to run before the marker checks, so it claimed that line, showed a sign-in strip for a server
# that needs no sign-in, and skipped the READY that opens the door -- the server was running and
# the app reported that it had failed. A marker is protocol; a guess about a line is not.
ready_at=$(grep -n 'startsWith("READY ")' "$SRC/DoorService.java" | head -n1 | cut -d: -f1)
link_at=$(grep -n 'firstLink(clean)' "$SRC/DoorService.java" | head -n1 | cut -d: -f1)
[ -n "$ready_at" ] && [ -n "$link_at" ] \
  || fail "MarkerOrder: the READY marker or the link scan is missing"
[ "$ready_at" -lt "$link_at" ] \
  || fail "MarkerOrder: a line is scanned for links before READY is read, which swallows READY"
echo "PASS MarkerOrder (a marker is read before any line is guessed at)"

# ---------------------------------------------------------------- the phone stays awake to finish
# Unpacking a base image and configuring a few hundred packages takes tens of minutes under
# PRoot, and dpkg frozen halfway has to be repaired before anything else can be installed.
grep -q 'FLAG_KEEP_SCREEN_ON' "$SRC/SetupActivity.java" \
  || fail "StaysAwake: the screen can sleep during set-up"
grep -q 'PARTIAL_WAKE_LOCK' "$SRC/SetupActivity.java" \
  || fail "StaysAwake: nothing keeps the install running if the phone tries to doze"
grep -q 'releaseAwake' "$SRC/SetupActivity.java" \
  || fail "StaysAwake: the wake lock is never released"
echo "PASS StaysAwake (the install holds the phone awake, and lets go when it ends)"

# ---------------------------------------------------------------- one agent at a time
grep -q 'runningAgent' "$SRC/DoorService.java" \
  || fail "OneAtATime: nothing tracks which agent is running"
grep -q 'foregroundServiceType="specialUse"' app/AndroidManifest.xml \
  || fail "OneAtATime: the agent service is not a foreground service"
grep -q 'addAction' "$SRC/DoorService.java" \
  || fail "OneAtATime: the notification has no way to stop the agent"
echo "PASS OneAtATime (a visible, stoppable service, one agent at a time)"

# ---------------------------------------------------------------- one editor, three agents
# The whole design, in one check. Google publishes no extension on Open VSX -- 507 results there
# for "antigravity" and every one of them somebody else's -- but they do publish the editor, for
# arm64, and its own product.json says its marketplace is Open VSX, which is exactly where
# Anthropic and OpenAI publish. So the other two makers' interfaces install into Google's
# interface. Two of the three are extensions; the third is the editor. Never all three, never
# none: an extension id on Google's entry would be one this app invented.
field() { python3 tests/agents.py "$SRC/Doors.java" "$1" "$2"; }
[ "$(field claude extension)" = "Anthropic.claude-code" ] \
  || fail "OneEditor: Claude is not pointed at the extension Anthropic publish"
[ "$(field codex extension)" = "openai.chatgpt" ] \
  || fail "OneEditor: Codex is not pointed at the extension OpenAI publish"
[ -z "$(field antigravity extension)" ] \
  || fail "OneEditor: Antigravity is given an extension id; Google publish none, they publish the editor"
for id in claude codex antigravity; do
  grep -q "EXT_${id}=" "$ASSETS/doors-workspace.sh" && installed=yes || installed=no
  [ "$id" = antigravity ] && want=no || want=yes
  [ "$installed" = "$want" ] \
    || fail "OneEditor: the script and the catalog disagree about whether $id is an extension"
done
# And both extension identifiers must be the ones the makers own, spelled their way.
grep -q 'EXT_claude="Anthropic.claude-code"' "$ASSETS/doors-workspace.sh" \
  || fail "OneEditor: the extension installed for Anthropic is not the one they publish"
grep -q 'EXT_codex="openai.chatgpt"' "$ASSETS/doors-workspace.sh" \
  || fail "OneEditor: the extension installed for OpenAI is not the one they publish"
echo "PASS OneEditor (two extensions and the editor they install into, each its maker's own)"

# ---------------------------------------------------------------- nowhere else to go
# Earlier builds offered a second place to see the same session for two of the three --
# Anthropic's phone app, Google's web dashboard -- and nothing for the other. That is a menu
# wearing the word "extra", and it is also two shapes where there should be one. Nothing in the
# catalog may name an address any more: the editor is on this phone and this app shows it.
# The entries, not the file. Written as a plain grep first, this failed on the comment at the
# top of the catalog, which quotes the one line of Google's product.json that the whole design
# rests on -- prose explaining a decision, not an address being offered. Fifth time a check in
# this project has landed on the words around the thing instead of the thing.
for id in claude codex antigravity; do
  for what in name start extension cost limit; do
    case "$(python3 tests/agents.py "$SRC/Doors.java" "$id" "$what")" in
      *http://*|*https://*)
        fail "NowhereElse: $id's $what names an address; there is one editor and it is here" ;;
    esac
  done
done
# And no screen may send the owner to one.
for gone in claude.ai/code antigravity.google.com chatgpt.com/codex; do
  if grep -n "$gone" "$SRC"/*Activity.java "$SRC/DoorService.java" 2>/dev/null; then
    fail "NowhereElse: $gone is offered again as a second surface"
  fi
done
# The screens must have no way to leave for a maker's own site at all.
if grep -n 'ACTION_VIEW' "$SRC/DoorActivity.java"; then
  fail "NowhereElse: the editor screen can still hand the owner off to a browser"
fi
echo "PASS NowhereElse (one editor, and no second surface for anybody)"

# ---------------------------------------------------------------- the editor is a GUI, not a prompt
# The requirement this build exists to meet: every maker's own graphical interface, and no
# command line used as one. The previous design reached Google's agent by typing `agy` in a
# terminal, which is not the interface Google builds -- they build an editor.
if grep -nE '^[^#]*\bagy\b' "$ASSETS/doors-workspace.sh"; then
  fail "OfficialGui: Google's agent is reached through their CLI again instead of their editor"
fi
if grep -nE '^[^#]*(claude|codex) (--|-p )' "$ASSETS/doors-workspace.sh"; then
  fail "OfficialGui: an agent is driven from a command line instead of its maker's own interface"
fi
# The editor is what is started, and it is Google's own program.
grep -q 'AG_BIN=/usr/share/antigravity/bin/antigravity' "$ASSETS/doors-workspace.sh" \
  || fail "OfficialGui: the program started is not the one Google's package installs"
echo "PASS OfficialGui (each maker's own graphical interface; no command line used as one)"

# ---------------------------------------------------------------- the editor comes from Google
# An apt repository rather than a downloaded file, because `apt upgrade` is then the update path
# and it is Google's own. Verified live against the repository's index before this was written:
# suite antigravity-debian, component main, Architectures "all amd64 arm64", package antigravity
# published for arm64 with many versions in the pool.
grep -q 'AG_REPO="https://us-central1-apt.pkg.dev/projects/antigravity-auto-updater-dev"' "$ASSETS/doors-workspace.sh" \
  || fail "GoogleRepo: the editor is not taken from Google's own repository"
grep -q 'AG_SUITE="antigravity-debian"' "$ASSETS/doors-workspace.sh" \
  || fail "GoogleRepo: the suite is not the one that repository publishes"
# signed-by, so this key vouches for this repository and nothing else. Without it a key added
# for one publisher can sign packages claiming to come from any other.
grep -q 'signed-by=%s' "$ASSETS/doors-workspace.sh" \
  || fail "GoogleRepo: the key is trusted for every repository instead of only Google's"
grep -q 'arch=arm64' "$ASSETS/doors-workspace.sh" \
  || fail "GoogleRepo: the source line does not ask for this phone's architecture"
grep -q 'apt-cache policy antigravity' "$ASSETS/doors-workspace.sh" \
  || fail "GoogleRepo: nothing proves an arm64 build is on offer before the download starts"
# Every fetch that leaves the phone must be HTTPS.
if grep -REn "curl[^|]*[\"' ]http://" "$ASSETS" | grep -vE '127\.0\.0\.1|localhost' | grep -q .; then
  fail "GoogleRepo: a download that leaves the phone uses plain HTTP"
fi
grep -q "IMAGE_SHA256" "$SRC/Ubuntu.java" \
  || fail "GoogleRepo: the Ubuntu image is not checksum-verified"
echo "PASS GoogleRepo (Google's own signed repository, pinned to itself, arm64, checked first)"

# ---------------------------------------------------------------- the screen is nobody else's
# Android does not keep loopback apart between applications. A display on 127.0.0.1 with no
# password is one any other app on this phone could open and watch the owner work -- and this
# session has no password, because asking for one every time would be worse than useless when
# the only client is this app. A unix socket in the app's private storage cannot be opened by
# anything else at all. The port stays only as a fallback for a display server without the
# socket option, and then it is bound to localhost and the app is told which it got.
grep -q 'rfbunixpath' "$ASSETS/doors-workspace.sh" \
  || fail "PrivateScreen: the display is not offered on a private socket"
grep -qE '^SOCK="\$STATE/' "$ASSETS/doors-workspace.sh" \
  || fail "PrivateScreen: the socket is not inside this app's own private storage"
grep -q 'chmod 700 "$STATE"' "$ASSETS/doors-workspace.sh" \
  || fail "PrivateScreen: the directory holding the socket is not private"
# If the port is ever used, it may not be offered to the network.
grep -n 'rfbport' "$ASSETS/doors-workspace.sh" | grep -q 'localhost' \
  || fail "PrivateScreen: the fallback port would be offered off this phone"
# The app must be told which of the two it got rather than guessing and falling back onto a
# port that anything could be listening on.
grep -q 'say "READY unix:' "$ASSETS/doors-workspace.sh" \
  || fail "PrivateScreen: the app is never told the display is on a private socket"
grep -q 'where.startsWith("unix:")' "$SRC/DoorActivity.java" \
  || fail "PrivateScreen: the app ignores which kind of display it was given"
echo "PASS PrivateScreen (a private socket no other app on this phone can open)"

# ---------------------------------------------------------------- Electron, on a phone, without crashing
# Every one of these was earned in the other app in this repository, on this same phone, and
# none of them is a preference. Antigravity's terminal draws with WebGL; WebGL here is software
# on the processor; with --in-process-gpu a fault in that path takes the whole editor down with
# SIGSEGV. There is no graphics chip for it to use, so nothing is lost.
# The command, not the paragraph explaining it. Written as a plain grep first, this stayed green
# with --no-zygote deleted from the launch, because the comment above it names the flag and says
# why it is there. Sixth time a check in this project has matched the words around the thing.
launch=$(sed -n '/^  "\$AG_BIN" \\$/,/^    "\$WORK"/p' "$ASSETS/doors-workspace.sh" | grep -v '^\s*#')
[ -n "$launch" ] || fail "ElectronFlags: the editor's launch command could not be found to check"
for flag in --no-sandbox --no-zygote --in-process-gpu --disable-dev-shm-usage --disable-3d-apis; do
  printf '%s' "$launch" | grep -q -- "$flag" \
    || fail "ElectronFlags: $flag is not on the launch command, and it was added for a real crash"
done
grep -q '"terminal.integrated.gpuAcceleration": "off"' "$ASSETS/doors-workspace.sh" \
  || fail "ElectronFlags: the editor's own setting for this is not written, so an update undoes the flag"
grep -q 'PROOT_NO_SECCOMP' "$SRC/Ubuntu.java" \
  || fail "ElectronFlags: seccomp is on, which is what made Chromium's syscalls fail under proot"
echo "PASS ElectronFlags (the flags a VS Code fork needs on this phone, and the setting behind them)"

# ---------------------------------------------------------------- ready means ready
# An Electron application that dies on its first frame exits within a second or two. Announcing
# READY before checking is how a crash becomes "connected to a screen with nothing on it" -- the
# viewer draws a black rectangle and the app reports everything is fine.
alive_at=$(grep -n 'kill -0 "$EDITOR_PID"' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1 || true)
ready_at=$(grep -n 'say "READY' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1 || true)
[ -n "$alive_at" ] && [ -n "$ready_at" ] && [ "$alive_at" -lt "$ready_at" ] \
  || fail "ScreenFirst: READY is announced before anything checks the editor is still alive"
# And the display has to have answered before the editor is started into it.
display_at=$(grep -n 'start_display$' "$ASSETS/doors-workspace.sh" | tail -n1 | cut -d: -f1 || true)
editor_at=$(grep -n 'EDITOR_PID=\$!' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1 || true)
[ -n "$display_at" ] && [ -n "$editor_at" ] && [ "$display_at" -lt "$editor_at" ] \
  || fail "ScreenFirst: the editor is started before there is a screen for it to draw on"
echo "PASS ScreenFirst (a screen, then an editor, then proof it lived, then READY)"

# ---------------------------------------------------------------- nothing is left to detach
# proot runs with --kill-on-exit, so when the script it was given finishes, every process inside
# the workspace is killed with it. A script that started the editor and returned would kill what
# it had just started, and the app would announce a working agent that no longer existed.
grep -q 'kill-on-exit' "$SRC/Ubuntu.java" \
  || fail "StaysInSession: the assumption this gate is built on is gone; re-check what proot does now"
grep -q 'wait "\$EDITOR_PID"' "$ASSETS/doors-workspace.sh" \
  || fail "StaysInSession: the script returns while the editor is supposed to be running"
hold_at=$(grep -n 'wait "\$EDITOR_PID"' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1 || true)
[ "$ready_at" -lt "$hold_at" ] \
  || fail "StaysInSession: the workspace is announced ready after the session holding it has ended"
if grep -q 'Running in the background' "$SRC/DoorService.java"; then
  fail "StaysInSession: the app still claims something keeps running after its session has ended"
fi
echo "PASS StaysInSession (one held session, and everything that runs is inside it)"

# ---------------------------------------------------------------- it fits the screen it is on
# A desktop editor at its own default size shows about a third of itself on 720 pixels. Written
# once, so settings the owner changes afterwards are theirs.
grep -q 'phone_defaults' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: the editor is started at its desktop size on a phone"
grep -q '"window.zoomLevel"' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: nothing reduces the editor's own drawing size"
grep -q '"editor.wordWrap": "on"' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: code runs off the side of a screen that cannot scroll sideways"
grep -q 'settings.json" \] && return 0' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: these defaults would overwrite settings the owner changed"
python3 -c 'import json,re,sys; b=re.search(r"<<.JSON.\n(.*?)\nJSON", open(sys.argv[1]).read(), re.S); sys.exit(0 if b and isinstance(json.loads(b.group(1)), dict) else 1)' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: the settings written for the editor are not valid JSON"
# The editor updates through apt, so it must not also update itself: two updaters fighting over
# one installation is how an editor ends up half-replaced and unable to start.
grep -q '"update.mode": "manual"' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: the editor would update itself behind the package manager's back"
echo "PASS FitsTheScreen (sized for this phone, written once, and left alone after)"

# ---------------------------------------------------------------- a window manager, not bare windows
# Antigravity is a desktop application. Without a window manager its windows have no frames, no
# focus and no way to be moved, and its dialogs open behind the editor where nobody can answer
# them -- which is indistinguishable from an editor that has frozen.
# A line that starts it, not a line that mentions it. The same mistake as the flags above: the
# comment and the stop path both name openbox, so deleting the one line that runs it changed
# nothing this check could see.
wm_at=$(grep -nE '^[^#]*\bopenbox\b[^|]*&\s*$' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1 || true)
[ -n "$wm_at" ] \
  || fail "WindowManager: nothing starts a window manager, so the editor's dialogs cannot be answered"
[ "$wm_at" -lt "$editor_at" ] \
  || fail "WindowManager: the window manager starts after the editor it is supposed to manage"
echo "PASS WindowManager (windows have frames and dialogs come to the front)"

# ---------------------------------------------------------------- versions agree
build_name=$(grep -oE 'VERSION_NAME="[0-9.]+"' build.sh | cut -d'"' -f2)
build_code=$(grep -oE 'VERSION_CODE="[0-9]+"' build.sh | cut -d'"' -f2)
grep -qi "version \*\*$build_name\*\*" RELEASE-NOTES.md \
  || fail "VersionAgreement: RELEASE-NOTES does not name version $build_name"
[ -n "$build_code" ] || fail "VersionAgreement: no version code"
echo "PASS VersionAgreement ($build_name, code $build_code)"

# ---------------------------------------------------------------- nothing secret ships
if find "$ASSETS" app/res -type f \( -name '*.pem' -o -name '*.key' -o -name '.env*' \
      -o -name 'id_rsa*' -o -name '*.jks' \) | grep -q .; then
  fail "NoSecrets: a key or credential file is inside the APK's assets"
fi
echo "PASS NoSecrets (no credential files in the packaged assets)"

# ---------------------------------------------------------------- the key stays in the repository
# The repository root ignores *.jks, and on this folder's first commit it quietly took the
# signing key with it. An APK signed by a key that is not in the repository cannot be replaced
# by the next build, which would cost whoever installed it their whole workspace.
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  git ls-files --error-unmatch .signing/pocketagent-local.jks >/dev/null 2>&1 \
    || fail "SigningKey: the signing key is not tracked; every build would install as a new app"
  echo "PASS SigningKey (the key that signs this app is in the repository)"
else
  echo "SKIP SigningKey (not a git checkout)"
fi

# ---------------------------------------------------------------- it compiles
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -f "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" ]; then
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" compile --dir app/res -o "$work/res.zip" >/dev/null
  "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" link -o "$work/app.apk" \
    -I "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
    --manifest app/AndroidManifest.xml --java "$work/gen" \
    --min-sdk-version 29 --target-sdk-version 35 --auto-add-overlay "$work/res.zip" >/dev/null
  mapfile -t SOURCES < <(find "$SRC" "$work/gen" -name '*.java' | sort)
  # core-lambda-stubs is what lets source 8 lambdas compile against android.jar; without it
  # every lambda in the app is an error, and the failure would look like a code problem.
  javac -encoding UTF-8 -source 8 -target 8 -nowarn \
    -bootclasspath "$ANDROID_SDK_ROOT/platforms/android-35/android.jar:$ANDROID_SDK_ROOT/build-tools/35.0.0/core-lambda-stubs.jar" \
    -d "$work/classes" "${SOURCES[@]}" \
    || fail "Compiles: the app does not compile"
  echo "PASS Compiles (${#SOURCES[@]} sources)"
else
  echo "SKIP Compiles (no Android SDK in ANDROID_SDK_ROOT)"
fi

echo
echo "All checks passed."
