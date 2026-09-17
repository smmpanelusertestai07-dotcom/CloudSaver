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

# --- automatic means security fixes and the editor, each behind its own switch ---------------
#
# The editor moves on its own only when its own switch is on, only when a newer release is
# actually known, and only while nothing is running -- and through the same staged, verified,
# reversible swap the manual row uses. A blanket upgrade of every Ubuntu package is never
# automatic: that is a decision about someone's machine and stays theirs.
# The run itself lives in runQuietly, which the foreground service calls; maybeRunInBackground
# only decides whether today is the day and hands over. Both are read: the decision must hand
# over to the service (a bare thread from a screen is what left dpkg to be killed mid-apt),
# and the run must do exactly the two things Settings promises.
auto = re.search(r'static void runQuietly\s*\([^)]*\)\s*\{(.*?)\n    \}', updates, re.S)
decide = re.search(r'maybeRunInBackground\s*\([^)]*\)\s*\{(.*?)\n    \}', updates, re.S)
if not decide or "WorkspaceService.update(context)" not in decide.group(1):
    problems.append("Updates.maybeRunInBackground does not hand the run to the service")
if not auto:
    problems.append("Updates.runQuietly cannot be read")
else:
    body = auto.group(1)
    for forbidden, why in (('"ubuntu-all"', "every package, not just the security ones"),
                           ('"all"', "everything at once"),
                           ('"extensions"', "the extensions, which the editor already keeps current")):
        if forbidden in body:
            problems.append("the automatic background run applies %s. Settings does not say "
                            "so." % why)
    if '"ubuntu"' not in body:
        problems.append("the automatic background run applies nothing at all, so the switch "
                        "Settings shows as On does nothing")
    if '"editor"' not in body:
        problems.append("the automatic background run never updates the editor, so the "
                        "'Editor updates: Automatic' row in Settings is a promise nothing keeps")
    else:
        editor_at = body.index('"editor"')
        window = body[max(0, editor_at - 600):editor_at]
        for needed, why in (("automaticEditor(", "its own switch"),
                            ("editorOutOfDate()", "a newer release actually being known"),
                            ("editorRunning()", "the editor being closed")):
            if needed not in window:
                problems.append("the automatic editor update does not check %s first" % why)
if "Updates.setAutomaticEditor" not in settings:
    problems.append("Settings cannot switch the automatic editor update off")
if "Prefs.AUTO_UPDATE_EDITOR" not in updates:
    problems.append("there is no stored setting behind the editor-updates switch")

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

# --- a failed check must not pass for an answer ------------------------------------------------
#
# The script runs apt-get update and prints apt_list=0 when it did not work, precisely so the
# app can tell "nothing is waiting" from "nobody answered". Nothing read it once, and the
# consequence was the worst shape a bug can have: a check made while Ubuntu's servers were
# unreachable reported zero security updates, which on the screen is the same sentence as
# "everything is up to date" -- and then stamped the clock and did not look again for a day.
if "apt_list" not in script:
    problems.append("the script no longer reports whether apt actually answered")
if "apt_list" not in updates:
    problems.append("Updates.java never reads apt_list, so a check that could not reach "
                    "Ubuntu's servers is recorded as 'everything is up to date' and blocks the "
                    "next check for a day")
if "UPDATE_TRIED_AT" not in updates:
    problems.append("a failed check is not distinguished from a successful one in the throttle, "
                    "so it is either retried on every return to the app or not for a day")

# --- nothing replaces the editor underneath a running one --------------------------------------
#
# Both ends, because they are seconds apart and the owner can open the editor in between. The
# "Everything, now" row used to walk straight past the Java guard while its own dialog text
# promised it would not.
if "editor_is_running" not in script:
    problems.append("the script will replace the editor tree while a code-server is reading "
                    "from it, which breaks the session and loses whatever was unsaved")
guard = re.search(r'private void updateNow\([^)]*\)\s*\{(.*?)\n    \}', settings, re.S)
if not guard:
    problems.append("SettingsPane.updateNow cannot be read")
elif "editorInTheWay" not in guard.group(1):
    problems.append("updateNow starts an update without checking whether the editor is open, so "
                    "'Everything, now' can pull the tree out from under a running editor -- "
                    "which its own dialog text promises it will not do")

# --- a half-finished swap heals itself ---------------------------------------------------------
#
# The swap is two renames with a gap between them where /opt/code-server does not exist. A kill
# landing in that gap leaves the working editor beside the hole under .previous, and without
# this the app sees no editor and offers to download 224 MB again.
# Matched on the CONDITION rather than the function name, because a name is the one thing a
# refactor changes for free -- an earlier version of this check passed against a function
# renamed to no_recover_editor, which is exactly the regression it exists to catch.
RESTORE = '[ ! -x "$BIN" ] && [ -x "/opt/code-server.previous/bin/code-server" ]'
if RESTORE not in script:
    problems.append("nothing puts a half-swapped editor back, so an app kill during the swap "
                    "costs a 224 MB re-download of an editor that is already on the disk")
elif script.count("recover_editor") < 3:
    problems.append("the recovery exists but is not called from the paths that need it: the "
                    "check, the editor update, and the extension update all reach a workspace "
                    "that may be mid-swap")
if RESTORE not in editor_script:
    problems.append("starting the editor does not recover a half-swapped tree, so the one route "
                    "an owner actually takes back into the app cannot heal it")

# --- the PRoot process is always reaped --------------------------------------------------------
#
# Workspace.run() destroys it in a finally. Without the same here, a broken pipe out of readLine
# leaves apt or dpkg running under PRoot holding the package lock, and every later install fails
# pointing at a process the owner cannot find or stop.
for method in ("doRun", "collect"):
    body = re.search(r'private static \w+ ' + method + r'\(.*?\n    \}', updates, re.S)
    if not body:
        problems.append("Updates.%s cannot be read" % method)
    elif "process.destroy()" not in body.group(0):
        problems.append("Updates.%s never destroys the PRoot process, so an aborted read "
                        "orphans apt holding the package lock" % method)

# --- the support date is one date, everywhere ---------------------------------------------------
#
# Three places carry it and all three shipped a month late: the script prints it, Updates.java
# defaults and falls back to it, and the FAQ states it in prose. The sentence on the
# Settings screen says "that is Canonical's published date for this release, not an estimate" --
# which is exactly the sentence that cannot afford to be a month out.
#
# The values are Canonical's release-cycle page for 24.04 LTS: standard security maintenance to
# May 2029, Expanded Security Maintenance (the Ubuntu Pro entitlement) to May 2034, and a
# separate PAID Legacy add-on to May 2039. Written here so a future bump has one place to
# change and one test to satisfy.
STANDARD_ISO = "2029-05"
STANDARD_WORDS = "May 2029"
PRO_WORDS = "May 2034"

if 'ubuntu_supported_until=%s' % STANDARD_ISO not in script:
    problems.append("the script does not report %s as the standard-support date" % STANDARD_ISO)
for name, text in (("Updates.java", updates),
                   ("Texts.java", code(src + "Texts.java"))):
    if STANDARD_WORDS not in text:
        problems.append("%s does not carry %s as the standard-support date" % (name, STANDARD_WORDS))
    if re.search(r'(June|April|July) 2029', text):
        problems.append("%s still carries a standard-support date other than %s"
                        % (name, STANDARD_WORDS))
faq = code(src + "Texts.java")
if PRO_WORDS not in faq:
    problems.append("the FAQ does not give %s as the Ubuntu Pro date; 2036 was there once and "
                    "is not a date Canonical publishes for this release" % PRO_WORDS)
if "2036" in faq or "2036" in script:
    problems.append("2036 is quoted as an Ubuntu 24.04 date somewhere; Pro covers it to 2034 "
                    "and the separate paid Legacy add-on to 2039")

# --- the Android layer does not promise what it does not install --------------------------------
#
# The dialog said "Installs a JDK so Java and Kotlin Android projects can be built into a real,
# installable APK", and the layer installs a JDK and nothing else -- no SDK, and no aarch64
# replacements for the four Google tools that ship x86-64 only. The script even contradicted
# itself about it inside one file.
tools = read(assets + "pocketide-tools.sh")
settings_raw = read(src + "SettingsPane.java")
if "are replaced with" in tools:
    problems.append("pocketide-tools.sh claims Google's x86-64 tools 'are replaced with' "
                    "aarch64 rebuilds. Nothing in this app supplies them.")
for text, where in ((settings_raw, "the Settings dialog"), (read(src + "Tools.java"), "Tools")):
    if re.search(r'Installs a JDK so [^"]*installable\s*"?\s*\+?\s*"?\s*APK', text):
        problems.append("%s still promises an installable APK from a layer that installs only "
                        "a JDK" % where)
if "Android SDK" not in settings_raw:
    problems.append("the Settings dialog never mentions that the Android SDK is not installed, "
                    "which is the first thing a Gradle build stops on")

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
# The merge path, which is the one a phone with python3 (every phone: set-up installs it)
# actually takes, sets the same two keys as policy on every start.
for setting in ('"extensions.autoUpdate": True', '"extensions.autoCheckUpdates": True'):
    if setting not in editor_script:
        problems.append("the merged settings path does not set %s, so extensions stop updating "
                        "on every phone that has python3" % setting.split('"')[1])

# code-server is a tarball, not a package: its own updater is compiled out, so leaving
# update.mode on would offer an update that could never install. It must stay "none" AND the
# app must be the thing that updates the editor instead.
if '"update.mode": "none"' not in editor_script:
    problems.append("update.mode is not 'none'; code-server cannot update itself and the "
                    "notification it would show leads nowhere")
if "update_editor" not in script:
    problems.append("update.mode is off and nothing else updates the editor either, so the "
                    "editor is pinned for ever")

# --- the app itself knows when it is out of date -----------------------------------------------
#
# The workflow publishes releases under one tag prefix and the app looks for one. If the two
# ever differ, the app says "newest" for ever while releases pile up under a name it never
# reads. So both strings are parsed and compared, rather than trusted to match.
app_updates = code(src + "AppUpdates.java")
workflow = read(app + "/../.github/workflows/pocketide.yml")
prefix_java = re.search(r'TAG_PREFIX\s*=\s*"([^"]+)"', app_updates)
prefix_yaml = re.search(r'TAG="([^"$]+)\$\{VERSION\}"', workflow)
if not prefix_java:
    problems.append("AppUpdates.java does not name the release tag prefix it looks for")
if not prefix_yaml:
    problems.append("pocketide.yml does not publish a release under a tag built from a prefix "
                    "and the version, so the app has nothing to find")
if prefix_java and prefix_yaml and prefix_java.group(1) != prefix_yaml.group(1):
    problems.append("the app looks for releases tagged %r and the workflow publishes them as "
                    "%r; the app would report 'newest' for ever"
                    % (prefix_java.group(1), prefix_yaml.group(1)))
if prefix_java and prefix_java.group(1).startswith("v"):
    problems.append("the PocketIDE release prefix starts with 'v', which is CloudSaver's own "
                    "tag prefix in the same repository; the two must never collide")
if "stable_key == 'true'" not in workflow:
    problems.append("the release job does not require the repository's own signing key, so it "
                    "would publish an APK signed with a throwaway key that cannot install "
                    "over any other build")
if "AppUpdates.maybeCheckInBackground" not in main:
    problems.append("nothing ever asks whether a newer PocketIDE exists")
if "AppUpdates.setEnabled" not in settings:
    problems.append("Settings cannot turn the app's own update check off")
if "GitHub" not in code(src + "Texts.java"):
    problems.append("the privacy text does not admit the once-a-day request to GitHub")
if "GitHub Releases" not in code(src + "Texts.java"):
    problems.append("Help does not tell an owner where the published APK is")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
