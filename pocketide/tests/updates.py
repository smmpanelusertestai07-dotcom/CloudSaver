#!/usr/bin/env python3
"""The update path, against every promise the app makes about it.

A workspace that is never updated is the failure this whole feature exists to prevent, and it
fails silently: nothing on a screen says "your openssl is fourteen months old". So the checks
here are for the ways this could be broken while still looking finished.

The first is the one that would break everything and show nothing. The scripts are copied out
of the APK into the workspace by Workspace.SCRIPTS, and a script that is not in that list is
never written at all -- so every update command would fail with "No such file", from a Settings
row that looks exactly as it does when it works.

The rest are promises made in words on a screen: that the editor is staged and verified before
anything installed is touched and put back if the swap goes wrong; that what happens
automatically is security updates and only security updates; that the automatic run waits for
Wi-Fi; and that the editor keeps its extensions current by itself.
"""
import re
import sys

app = sys.argv[1]
src = app + "/app/src/com/pocketide/"
assets = app + "/app/assets/"


def read(path):
    return open(path).read()


def code(path):
    """Source with its comments stripped, so a promise in prose never passes for one in code."""
    text = read(path)
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    text = re.sub(r'//.*$', '', text, flags=re.M)
    return re.sub(r'^\s*#(?![!]).*$', '', text, flags=re.M)


problems = []

workspace = code(src + "Workspace.java")
updates = code(src + "Updates.java")
settings = code(src + "SettingsPane.java")
main = code(src + "MainActivity.java")
script = code(assets + "pocketide-update.sh")
editor_script = read(assets + "pocketide-editor.sh")

# --- the script actually reaches the workspace -----------------------------------------------
scripts = re.search(r'SCRIPTS\s*=\s*\{(.*?)\}', workspace, re.S)
if not scripts:
    problems.append("Workspace.SCRIPTS cannot be read, so nothing can be said about what is "
                    "copied into the workspace")
elif "pocketide-update.sh" not in scripts.group(1):
    problems.append("pocketide-update.sh is not in Workspace.SCRIPTS, so it is never written "
                    "into the workspace and every update command fails with 'No such file' "
                    "from a Settings row that looks exactly like a working one")

# --- the editor update is staged, verified and reversible ------------------------------------
if "code-server.new" not in script:
    problems.append("the editor update does not unpack into a staging tree, so a bad download "
                    "replaces a working editor before anything has checked it")
if "code-server.previous" not in script:
    problems.append("the editor update keeps no previous tree, so a failed swap leaves no "
                    "editor at all")
if 'mv "/opt/code-server.previous" "$INSTALL_DIR"' not in script:
    problems.append("nothing ever puts the previous editor back, so the rollback the Settings "
                    "screen promises does not exist")
if '"$staged" != "$latest"' not in script:
    problems.append("the staged editor's own reported version is never compared against the "
                    "version that was asked for, which is the check that catches a substituted "
                    "build after everything else has passed")
if "$STAGE_DIR/bin/code-server" not in script:
    problems.append("nothing confirms the staged tree contains a runnable editor before the "
                    "installed one is moved aside")

# --- automatic means security, and nothing else ----------------------------------------------
auto = re.search(r'maybeRunInBackground\s*\([^)]*\)\s*\{(.*?)\n    \}', updates, re.S)
if not auto:
    problems.append("Updates.maybeRunInBackground cannot be read")
else:
    body = auto.group(1)
    for forbidden, why in (('"ubuntu-all"', "every package, not just the security ones"),
                           ('"editor"', "the editor's own version"),
                           ('"all"', "everything at once"),
                           ('"extensions"', "the extensions")):
        if forbidden in body:
            problems.append("the automatic background run applies %s. Settings says only "
                            "Ubuntu's security fixes are taken without being asked." % why)
    if '"ubuntu"' not in body:
        problems.append("the automatic background run applies nothing at all, so the switch "
                        "Settings shows as On does nothing")

if "isWifi" not in updates:
    problems.append("the automatic run never checks for Wi-Fi, so it spends a mobile data "
                    "allowance on a download nobody asked for")
if "CHECK_EVERY_MS" not in updates or "UPDATE_CHECKED_AT" not in updates:
    problems.append("there is no throttle on the automatic check, so it runs on every single "
                    "return to the app")
if "editorRunning()" not in updates:
    problems.append("the automatic run does not check whether the editor is open, so it can "
                    "restart things under someone who is using them")
if "Updates.maybeRunInBackground" not in main:
    problems.append("nothing ever calls Updates.maybeRunInBackground, so automatic updates "
                    "never happen however the switch is set")

# --- the switch is real ----------------------------------------------------------------------
if "Prefs.AUTO_UPDATE" not in updates:
    problems.append("there is no stored setting behind the automatic-updates switch")
if "Updates.setAutomatic" not in settings:
    problems.append("Settings shows the automatic-updates state but cannot change it")

# --- the editor keeps its extensions current --------------------------------------------------
blocks = re.findall(r'settings\.json.*?\n\{(.*?)\n\}', editor_script, re.S)
if len(blocks) < 2:
    problems.append("the editor's settings blocks cannot be read; there should be one for the "
                    "phone layout and one for the desktop layout")
for index, block in enumerate(blocks):
    for setting in ('"extensions.autoUpdate": true', '"extensions.autoCheckUpdates": true'):
        if setting not in block:
            problems.append("settings block %d does not set %s, so extensions installed there "
                            "stay at whatever version they were on the day they arrived"
                            % (index + 1, setting.split('"')[1]))

# code-server is a tarball, not a package: its own updater is compiled out, so leaving
# update.mode on would offer an update that could never install. It must stay "none" AND the
# app must be the thing that updates the editor instead.
if '"update.mode": "none"' not in editor_script:
    problems.append("update.mode is not 'none'; code-server cannot update itself and the "
                    "notification it would show leads nowhere")
if "update_editor" not in script:
    problems.append("update.mode is off and nothing else updates the editor either, so the "
                    "editor is pinned for ever")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
