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

# ---------------------------------------------------------------- Anthropic, without being asked
# This is the whole reason Claude needs no second option. Anthropic document one key --
# remoteControlAtStartup in ~/.claude/settings.json -- which turns Remote Control on for every
# session, so their phone app shows this session with nobody opening a menu or typing a command.
# Take that key away and "one method" quietly becomes "one method plus an instruction", which is
# exactly the menu this build removed.
grep -q 'remoteControlAtStartup' "$ASSETS/doors-workspace.sh" \
  || fail "ClaudeDoor: Remote Control is not switched on at startup, so the phone app sees nothing"
# Written into the file, not merely named in a comment. This is the third gate in this project
# to be written as a plain grep and pass on the prose explaining the thing it was meant to check.
grep -qE '^\s*data\["remoteControlAtStartup"\] = True' "$ASSETS/doors-workspace.sh" \
  || fail "ClaudeDoor: the startup key is talked about but never written to the settings file"
# And before the editor opens, not after: the first session is the one that has to appear.
prep_at=$(grep -n 'claude_remote_at_startup$' "$ASSETS/doors-workspace.sh" | tail -n1 | cut -d: -f1)
serve_at=$(grep -n 'bin/code-server" \\' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1)
[ -n "$prep_at" ] && [ -n "$serve_at" ] && [ "$prep_at" -lt "$serve_at" ] \
  || fail "ClaudeDoor: Remote Control is switched on after the editor starts, so the first session misses it"
grep -q 'auth status' "$ASSETS/doors-workspace.sh" \
  || fail "ClaudeDoor: sign-in is assumed rather than asked of the CLI"
grep -q 'CLAUDE_MIN' "$ASSETS/doors-workspace.sh" \
  || fail "ClaudeDoor: no floor on the version, and Remote Control did not exist in older builds"
# The extension is Anthropic's own identifier on Open VSX, spelled the way they own it.
grep -q 'EXT_claude="Anthropic.claude-code"' "$ASSETS/doors-workspace.sh" \
  || fail "ClaudeDoor: the extension installed is not the one Anthropic publish"
echo "PASS ClaudeDoor (Remote Control on at startup, before the editor, with the version checked)"

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
# Every agent opens the same address, and it is written once. Three identical strings would pass
# any grep while still being three things somebody could edit apart, so this asks for the shared
# name rather than the value it happens to hold today.
for id in claude codex antigravity; do
  [ "$(python3 tests/agents.py "$SRC/Doors.java" "$id" surface)" = "WORKSPACE" ] \
    || fail "OneWayIn: $id has a surface of its own instead of the one workspace"
done
grep -q 'WORKSPACE = "http://127.0.0.1:8391/"' "$SRC/Doors.java" \
  || fail "OneWayIn: the one workspace address is not the loopback address the server binds"
# And there is nothing else to start. A second start script in assets is how a menu comes back.
extra=$(ls "$ASSETS"/*.sh | grep -v 'doors-bootstrap.sh\|doors-workspace.sh' || true)
[ -z "$extra" ] || fail "OneWayIn: a second startable script is shipped -- $extra"
# -o, not -c: both names sit on one line in SCRIPTS, so counting lines counts one.
shipped=$(grep -oE '"doors-[a-z]+\.sh"' "$SRC/Doors.java" | sort -u | wc -l | tr -d ' ')
[ "$shipped" = "2" ] \
  || fail "OneWayIn: the catalog ships $shipped scripts; this build has two"
echo "PASS OneWayIn (one script, one start line each, one address, nothing else to choose)"

# ---------------------------------------------------------------- a phone surface only where one exists
# An agent may only claim a second place to see the same session if its maker actually publishes
# one. OpenAI's Remote Control hosts on macOS -- Windows is listed as coming, Linux is not listed
# at all -- so there is nothing to offer for Codex, and offering something would be inventing it.
# Read by field, not by grep. The first cut of this looked for an empty string inside the Codex
# entry -- and the entry has two empty strings in a row, the surface and the sentence describing
# it. Filling the surface in left the other one there, so the check stayed green while the app
# claimed OpenAI publish something they do not. Fourth time in this project. tests/agents.py
# reads the constructor by position instead.
field() { python3 tests/agents.py "$SRC/Doors.java" "$1" "$2"; }
[ -z "$(field codex phone)" ] \
  || fail "PhoneSurface: Codex claims a phone surface; OpenAI host Remote Control on macOS only"
[ -z "$(field codex phoneIs)" ] \
  || fail "PhoneSurface: Codex describes a phone surface it does not have"
[ "$(field claude phone)" = "https://claude.ai/code" ] \
  || fail "PhoneSurface: Claude is not pointed at the address Anthropic publish"
[ "$(field antigravity phone)" = "https://antigravity.google.com" ] \
  || fail "PhoneSurface: Antigravity is not pointed at the dashboard Google publish"
# A surface that is described but cannot be opened, or opened but never described, is worse than
# none: the owner is either sent somewhere unannounced or told about somewhere they cannot reach.
for id in claude codex antigravity; do
  if [ -n "$(field $id phone)" ] && [ -z "$(field $id phoneIs)" ]; then
    fail "PhoneSurface: $id opens a link with nothing saying what it is"
  fi
done
echo "PASS PhoneSurface (a second surface only where its maker publishes one, and it is described)"

# ---------------------------------------------------------------- nothing silently switches it off
# Anthropic's own requirements page names four variables, each of which disables the feature-flag
# evaluation Remote Control depends on. Set any of them and the session starts, works, and simply
# never appears on the phone -- the exact failure this app has no way to explain. A fifth,
# ANTHROPIC_BASE_URL, points the API somewhere else and is refused outright.
for killer in DISABLE_TELEMETRY DO_NOT_TRACK CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC DISABLE_GROWTHBOOK; do
  if grep -REn "^[^#]*(export +)?$killer=" "$ASSETS" "$SRC"; then
    fail "RemoteControlSurvives: $killer is set, which switches Remote Control off without saying so"
  fi
  grep -q "unset .*$killer" "$ASSETS/doors-workspace.sh" \
    || fail "RemoteControlSurvives: $killer is never cleared, so anything could leave it set"
done
grep -q 'unset ANTHROPIC_BASE_URL' "$ASSETS/doors-workspace.sh" \
  || fail "RemoteControlSurvives: the API host is not cleared; a custom one is refused outright"
# The version floor is the one the mechanism needs, not the one the feature first shipped in.
grep -q 'CLAUDE_MIN="2.1.203"' "$ASSETS/doors-workspace.sh" \
  || fail "RemoteControlSurvives: the floor is not the version that honours remoteControlAtStartup"
echo "PASS RemoteControlSurvives (nothing in this build switches Remote Control off behind the owner)"

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

# ---------------------------------------------------------------- integrity of what is downloaded
grep -q 'sha512sum --check' "$ASSETS/doors-workspace.sh" \
  || fail "Integrity: the Antigravity download is not checksum-verified"
grep -q 'sha256sum' "$ASSETS/doors-workspace.sh" \
  || fail "Integrity: the code-server download records no digest"
grep -q "IMAGE_SHA256" "$SRC/Ubuntu.java" \
  || fail "Integrity: the Ubuntu image is not checksum-verified"
# Every fetch that leaves the phone must be HTTPS; a plain http:// download of code would be a
# hole big enough to replace an agent with anything. Loopback is the one exception, because a
# certificate for 127.0.0.1 is a certificate nobody can verify, and that traffic never leaves.
if grep -REn "curl[^|]*[\"' ]http://" "$ASSETS" | grep -vE '127\.0\.0\.1|localhost' | grep -q .; then
  fail "Integrity: a download that leaves the phone uses plain HTTP"
fi
# Ubuntu's base image ships no certificate store, so the very first apt fetch cannot be HTTPS.
# What matters is the order, not the flags: the shipped source list is removed (it points at a
# host that serves no arm64, which is what made the first set-up fail), certificates are
# installed, and apt switches to HTTPS -- in that order. Pinning the exact apt flags here is
# what broke this gate once already, so the checks below look for the step, not its spelling.
line_of() { grep -nE "$1" "$ASSETS/doors-bootstrap.sh" 2>/dev/null | head -n1 | cut -d: -f1; }
drop=$(line_of 'rm -f /etc/apt/sources\.list\.d/')
plain=$(line_of 'deb http://ports\.ubuntu\.com')
certs=$(line_of 'apt-get install .*ca-certificates')
switch=$(line_of 'https://ports\.ubuntu\.com.*sources\.list|sed .*ports\.ubuntu\.com')
for step in drop plain certs switch; do
  eval "value=\$$step"
  [ -n "$value" ] || fail "Integrity: the bootstrap has no '$step' step"
done
[ "$drop" -lt "$plain" ] && [ "$plain" -lt "$certs" ] && [ "$certs" -lt "$switch" ] \
  || fail "Integrity: the bootstrap's apt steps are out of order (drop $drop, plain $plain, certs $certs, switch $switch)"
# The architecture is named before anything is fetched, and an empty index is caught before an
# install can fail with "no installation candidate" -- the error that stopped the first attempt.
grep -q 'dpkg --print-architecture' "$ASSETS/doors-bootstrap.sh" \
  || fail "Integrity: the bootstrap does not check the architecture before fetching"
grep -q 'apt-cache policy' "$ASSETS/doors-bootstrap.sh" \
  || fail "Integrity: nothing proves the package index arrived before the first install"
echo "PASS Integrity (checksums; HTTPS off the phone, and apt on HTTPS from its second fetch)"

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

# ---------------------------------------------------------------- the loopback stays loopback
grep -q 'bind-addr "127.0.0.1' "$ASSETS/doors-workspace.sh" \
  || fail "Loopback: code-server is not bound to the loopback address"
if grep -q 'bind-addr "0.0.0.0' "$ASSETS/doors-workspace.sh"; then
  fail "Loopback: code-server would listen on the network"
fi
# And the address the app opens has to be that same loopback address, not a host name that
# could resolve anywhere. The catalog writes it once; this is that one place.
grep -q 'WORKSPACE = "http://127.0.0.1:8391/"' "$SRC/Doors.java" \
  || fail "Loopback: the address the app opens is not the loopback address the server binds"
grep -q '<domain includeSubdomains="false">127.0.0.1</domain>' app/res/xml/network_security_config.xml \
  || fail "Loopback: cleartext is not restricted to the loopback address"
grep -q 'cleartextTrafficPermitted="false"' app/res/xml/network_security_config.xml \
  || fail "Loopback: cleartext is not refused by default"
echo "PASS Loopback (the editor answers only this phone, and only there is cleartext allowed)"

# ---------------------------------------------------------------- sign-in happens, and first
# Google's own words: run agy, complete the sign-in flow, then exit, and only then start the
# daemon. The first cut of this app skipped that entirely and would have failed at step one.
grep -q 'doors-workspace.sh login antigravity' "$SRC/Doors.java" \
  || fail "Login: Antigravity has no sign-in step, so its daemon would start with no credentials"
grep -q 'NEEDLOGIN' "$ASSETS/doors-workspace.sh" \
  || fail "Login: the script does not tell the app when sign-in is missing"
grep -q 'NEEDLOGIN' "$SRC/DoorService.java" \
  || fail "Login: the app does not act on a missing sign-in"
grep -q 'ACTION_INPUT' "$SRC/DoorService.java" \
  || fail "Login: nothing can type the code back, so a sign-in could never finish"
# Only the maker that has a sign-in command may ever send that word. NEEDLOGIN makes the app run
# the agent's login line, and for an agent with an empty one that was `bash /opt/doors/` -- a
# shell error, shown to the owner as though their agent had failed. Anthropic and OpenAI sign in
# inside their own panel in the editor, so for them the script says so in plain words instead.
if grep -n 'NEEDLOGIN' "$ASSETS/doors-workspace.sh" | grep -qiE 'claude|chatgpt|codex|editor'; then
  fail "Login: NEEDLOGIN is sent for an agent whose sign-in happens inside the editor"
fi
grep -q 'agent.signsInSeparately()' "$SRC/DoorService.java" \
  || fail "Login: the service would run a sign-in command for an agent that has none"
grep -q 'signsInSeparately()' "$SRC/DoorActivity.java" \
  || fail "Login: the screen asks for a separate sign-in without checking there is one"
echo "PASS Login (sign-in runs first where a maker needs it, and nowhere else)"

# ---------------------------------------------------------------- sign-in leaves the app
grep -q 'accounts.google.com' "$SRC/DoorActivity.java" \
  || fail "SignIn: Google sign-in is not handed to the real browser"
grep -q 'FLAG_ACTIVITY_NEW_TASK' "$SRC/DoorActivity.java" \
  || fail "SignIn: the sign-in hand-off does not open a browser"
echo "PASS SignIn (passwords are typed in the browser, never in this app's window)"

# ---------------------------------------------------------------- every address was checked
# This gate exists because of a real 404 on a real phone. An earlier build sent the owner to
# https://antigravity.google/remote, an address assembled from a plausible guess: the docs live
# on antigravity.google, so a /remote path there looked right. Google's dashboard is on
# antigravity.google.com, a different domain, and the guess cost the owner a set-up run.
# So the address is written once, checked here, and the shape of the old mistake is banned.
DASH="https://antigravity.google.com"
grep -q "\"$DASH\"," "$SRC/Doors.java" \
  || fail "VerifiedUrls: Antigravity's phone surface is not the dashboard address this app verified"
grep -q '"https://claude.ai/code"' "$SRC/Doors.java" \
  || fail "VerifiedUrls: Anthropic's phone surface is not the address this app verified"
if grep -REn 'antigravity\.google/[a-z]' "$SRC" "$ASSETS" | grep -vi 'docs\|cli/install' | grep -q .; then
  fail "VerifiedUrls: a path on antigravity.google is being used as an interface address again"
fi
# Every READY line hands the app an address to open, so each one has to be an address this
# repository can name, not one built at run time from whatever the daemon happened to print.
# There is one thing to open now -- the workspace on this phone -- so a READY naming anything
# else is an address assembled at run time, which is how a 404 reached the owner's screen once.
if grep -n 'say "READY' "$ASSETS"/*.sh | grep -v '127\.0\.0\.1:\${PORT}' | grep -q .; then
  fail "VerifiedUrls: a READY line names an address that is not the workspace on this phone"
fi
echo "PASS VerifiedUrls (every address this app opens is one that was checked)"

# ---------------------------------------------------------------- only flags the CLI really has
# The same failure in a different costume. An earlier build fell back to
# `agy remote-control start --foreground`, a flag that reads like it should exist and does not:
# the 1.2.2 arm64 binary carries no such flag, and remote-control takes start, status, stop and
# --name. It would have been rejected as an unknown flag on the owner's phone.
if grep -n 'remote-control' "$ASSETS/doors-workspace.sh" | grep -qE '\-\-foreground|--daemon|--detach'; then
  fail "AgyFlags: the script passes a remote-control flag the CLI does not have"
fi
# Nothing but the three subcommands Google document may follow `remote-control`.
stray=$(grep -oE 'remote-control [a-z][a-z-]*' "$ASSETS/doors-workspace.sh" \
        | sort -u | grep -vE 'remote-control (start|status|stop)$' || true)
[ -z "$stray" ] || fail "AgyFlags: undocumented subcommand -- $stray"
grep -q 'remote-control start' "$ASSETS/doors-workspace.sh" \
  || fail "AgyFlags: the documented start command is missing"
grep -q 'remote-control status' "$ASSETS/doors-workspace.sh" \
  || fail "AgyFlags: nothing asks the daemon whether it is actually running"
echo "PASS AgyFlags (start, status and stop -- the three the CLI documents)"

# ---------------------------------------------------------------- unpacking is not assumed
# The Antigravity archive holds exactly one entry: a file called "antigravity" at the root.
# --strip-components=1 strips its only path component, so tar extracts nothing AND exits zero,
# and the check after it reports an archive that "did not contain the binary" when it was never
# unpacked at all. That shipped in 15.2.0 and cost the owner a run.
# The tar command itself, not a line that mentions the flag. Written as a plain grep first, this
# failed on the comment above the fix that explains why the flag is wrong -- the same shape of
# mistake as a gate that passes because a comment names the file it was meant to check for.
# Both archives live in one script now, which is exactly when they are most likely to be
# "tidied" into each other. So each tar line is found by the archive it opens, not by being
# the only tar in its file.
agy_tar=$(grep -E '^[^#]*\btar\b' "$ASSETS/doors-workspace.sh" | grep 'cli.tar.gz' || true)
srv_tar=$(grep -E '^[^#]*\btar\b' "$ASSETS/doors-workspace.sh" | grep '\$ARCHIVE' || true)
[ -n "$agy_tar" ] || fail "Unpacks: nothing unpacks the Antigravity archive"
[ -n "$srv_tar" ] || fail "Unpacks: nothing unpacks the code-server archive"
if printf '%s' "$agy_tar" | grep -q 'strip-components'; then
  fail "Unpacks: the Antigravity archive is a single root file; stripping a component extracts nothing"
fi
grep -qE "name agy -o -name antigravity" "$ASSETS/doors-workspace.sh" \
  || fail "Unpacks: the binary is assumed to be at a path instead of found by name"
grep -q 'cannot be run' "$ASSETS/doors-workspace.sh" \
  || fail "Unpacks: nothing checks the unpacked binary is executable"
# code-server's archive does have a top-level directory, so there the flag is right.
printf '%s' "$srv_tar" | grep -q 'strip-components=1' \
  || fail "Unpacks: code-server's archive does have a wrapping directory and needs it stripped"
echo "PASS Unpacks (each archive is unpacked the way that archive is actually shaped)"

# ---------------------------------------------------------------- a long download says how long
# 231 MB for the ChatGPT extension, 99 MB for Claude Code, and Open VSX sends no progress. One
# unchanging line for an hour is indistinguishable from a hang, and someone on mobile data has a
# right to know the number while they can still decide to wait for Wi-Fi.
grep -q 'extension_size' "$ASSETS/doors-workspace.sh" \
  || fail "BigDownloads: the extension download never says how big it is"
grep -q 'It is not stuck' "$ASSETS/doors-workspace.sh" \
  || fail "BigDownloads: a silent hour is never explained as normal"
echo "PASS BigDownloads (the size is said out loud before the data is spent)"

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

# ---------------------------------------------------------------- nothing is left to detach
# proot runs with --kill-on-exit, so when the script it was given finishes, every process inside
# the workspace is killed with it. A door that started something and returned would kill what it
# had just started, and the app would announce a working agent that no longer existed.
grep -q 'kill-on-exit' "$SRC/Ubuntu.java" \
  || fail "StaysInSession: the assumption this gate is built on is gone; re-check what proot does now"
grep -q 'wait "\$server"' "$ASSETS/doors-workspace.sh" \
  || fail "StaysInSession: the workspace returns while its server is supposed to be running"
# Anthropic say it plainly: "Remote Control runs as a local process. If you close the terminal,
# quit VS Code, or otherwise stop the claude process, the session goes offline." The workspace is
# that process's home, so everything has to start inside the session this script holds open --
# announced ready first, held last.
hold_at=$(grep -n 'wait "\$server"' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1)
ready_at=$(grep -n 'say "READY' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1)
agy_at=$(grep -n 'remote-control start' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1)
[ -n "$hold_at" ] && [ -n "$ready_at" ] && [ "$ready_at" -lt "$hold_at" ] \
  || fail "StaysInSession: the workspace is announced ready after the session holding it has ended"
[ -n "$agy_at" ] && [ "$agy_at" -lt "$hold_at" ] \
  || fail "StaysInSession: Google's daemon starts outside the session the workspace holds open"
if grep -q 'Running in the background' "$SRC/DoorService.java"; then
  fail "StaysInSession: the app still claims a door keeps running after its session has ended"
fi
echo "PASS StaysInSession (a door holds its session open for as long as it is running)"

# ---------------------------------------------------------------- sign-in gets a real terminal
# The CLI's own words, read out of the published binary: "Launch the CLI without arguments to
# sign in", and an auth step called "Selecting sign-in method". That is an interactive screen,
# and an interactive program handed a pipe either refuses to draw or draws nothing at all.
# `script` allocates a pty and is part of Ubuntu's base system.
grep -q 'script --quiet --return --command' "$ASSETS/doors-workspace.sh" \
  || fail "SignInTerminal: the sign-in is run on a pipe, where an interactive screen cannot draw"
grep -q 'on_a_terminal "\$agy"' "$ASSETS/doors-workspace.sh" \
  || fail "SignInTerminal: Google's sign-in is not the thing being given the terminal"
if grep -nE '^[^#]*(agy|\$agy)' "$ASSETS/doors-workspace.sh" | grep -qE 'agy" (auth|login) '; then
  fail "SignInTerminal: a sign-in subcommand is used; this CLI signs in with no arguments at all"
fi
grep -q 'ACTION_INPUT' "$SRC/DoorService.java" \
  || fail "SignInTerminal: nothing can answer the questions the sign-in asks"
echo "PASS SignInTerminal (sign-in runs on a pty, with a way to answer it)"

# ---------------------------------------------------------------- a question stays readable
# A sign-in is a conversation. The status line holds one line, so showing the conversation there
# meant every line replaced the one before -- including the question being asked.
grep -q 'conversation' "$SRC/DoorActivity.java" \
  || fail "Conversation: what a door says is not kept, so a question scrolls away as it arrives"
grep -q 'render()' "$SRC/DoorActivity.java" \
  || fail "Conversation: the conversation is never drawn"
echo "PASS Conversation (what a door asks stays on screen while it is asking)"

# ---------------------------------------------------------------- the account button works
# The editor's extension opens its account page in a new window, and a WebView given no answer
# to that request discards it silently. The "Sign in" button looked dead and there was no way to
# reach an account at all -- the door was running and unusable.
grep -q 'setSupportMultipleWindows(true)' "$SRC/DoorActivity.java" \
  || fail "AccountReachable: the window a sign-in opens is refused before anything can see it"
grep -q 'onCreateWindow' "$SRC/DoorActivity.java" \
  || fail "AccountReachable: nothing answers the request to open a sign-in window"
grep -q 'openOutside' "$SRC/DoorActivity.java" \
  || fail "AccountReachable: a sign-in window is not handed to the phone's browser"
echo "PASS AccountReachable (a sign-in window reaches the browser that can complete it)"

# ---------------------------------------------------------------- it fits the screen it is on
# A desktop editor at its own default size shows about a third of itself on 720 pixels, with the
# rest off the right-hand edge. Zooming the page out blurs the text; asking the editor to draw
# smaller does not.
grep -q 'phone_defaults' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: the editor is started at its desktop size on a phone"
grep -q '"window.zoomLevel"' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: nothing reduces the editor's own drawing size"
grep -q '"editor.wordWrap": "on"' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: code runs off the side of a screen that cannot scroll sideways"
# Written once, so settings the owner changed are never overwritten by an update.
grep -q 'settings.json" \] && return 0' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: these defaults would overwrite settings the owner changed"
# And they must be valid JSON, or the editor silently ignores the whole file.
python3 -c 'import json,re,sys; b=re.search(r"<<.JSON.\n(.*?)\nJSON", open(sys.argv[1]).read(), re.S); sys.exit(0 if b and isinstance(json.loads(b.group(1)), dict) else 1)' "$ASSETS/doors-workspace.sh" \
  || fail "FitsTheScreen: the settings written for the editor are not valid JSON"
echo "PASS FitsTheScreen (the editor is sized for this phone, once, and left alone after)"

# ---------------------------------------------------------------- a publisher's site is not framed
# Google refuse an OAuth sign-in inside an embedded view, so a dashboard shown in this app's own
# window can never be signed in. Their own instruction is to open it in a browser and add it to
# the home screen. A loopback editor is the other case and stays in the window.
grep -q 'phoneOpensOutside' "$SRC/Doors.java" \
  || fail "BrowserSurface: nothing decides where a maker's own phone surface opens"
grep -q 'openOutside(Uri.parse(surface))' "$SRC/DoorActivity.java" \
  || fail "BrowserSurface: a maker's own site is not handed to the phone's real browser"
# The editor is a server on this phone and belongs in this window; a maker's own site never does.
if grep -qE 'web\.loadUrl\(agent\.phone\)' "$SRC/DoorActivity.java"; then
  fail "BrowserSurface: a maker's own address is loaded into this app's window again"
fi
echo "PASS BrowserSurface (the maker's site opens in a real browser, the editor stays here)"

# ---------------------------------------------------------------- a download is proved, not assumed
# MODULE_NOT_FOUND with an empty require stack, on a real phone. code-server's launcher runs
# `lib/node <root>` and node resolves that through package.json's main, out/node/entry.js. The
# archive is 220 MB over mobile data and the old fifteen-minute deadline cut it short, so the
# launcher survived and everything node needed did not. Nothing is trusted now until it is there.
# The list itself, not a mention of it. Written as a plain grep first, this passed while the
# real check was deleted, because the explanation above it names the same file.
grep -qE '^NEEDED=.*out/node/entry\.js' "$ASSETS/doors-workspace.sh" \
  || fail "ServerTree: the file node actually starts is not in the list that must be present"
grep -qE '^NEEDED=.*lib/node' "$ASSETS/doors-workspace.sh" \
  || fail "ServerTree: the bundled node is not in the list that must be present"
grep -q 'continue-at' "$ASSETS/doors-workspace.sh" \
  || fail "ServerTree: a cut-off download restarts from zero instead of resuming"
grep -q 'bin/code-server" --version' "$ASSETS/doors-workspace.sh" \
  || fail "ServerTree: the unpacked server is never asked to prove it runs"
# The digest is recorded only after the tree has proved itself. The other order makes a
# truncated download the trusted baseline and refuses every complete one after it.
proof_at=$(grep -n 'bin/code-server" --version' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1)
pin_at=$(grep -n '> "\$pinned"' "$ASSETS/doors-workspace.sh" | head -n1 | cut -d: -f1)
[ -n "$proof_at" ] && [ -n "$pin_at" ] && [ "$proof_at" -lt "$pin_at" ] \
  || fail "ServerTree: the download becomes the trusted baseline before it is proved to run"
if grep -oE 'max-time [0-9]+' "$ASSETS/doors-workspace.sh" | awk '{ if ($2 > 60 && $2 < 1800) bad=1 } END { exit !bad }'; then
  fail "ServerTree: a 220 MB download is given a deadline a phone on mobile data cannot meet"
fi
grep -q 'df -Pm' "$ASSETS/doors-workspace.sh" \
  || fail "ServerTree: nothing checks there is room before spending an hour of someone's data"
echo "PASS ServerTree (resumable, complete, and proved to run before it is trusted)"

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
