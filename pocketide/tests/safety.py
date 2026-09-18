#!/usr/bin/env python3
"""The app lock and the phone's files, checked against the claims made about them.

Both of these are promises rather than features: "locked" and "off by default" are things an
owner believes because the app says so, and neither is visible from the screen when it is
wrong. A lock that covers four screens out of five looks exactly like a lock that covers five.

Every check here corresponds to a specific way of being wrong that would still look right:

  1. A lock raised on the home screen only. The editor is reachable from the recent-apps list
     without passing the home screen at all, so a lock that is not on BOTH windows is a lock
     with a door left open. This is the bug the app this was carried from actually shipped.

  2. FLAG_SECURE applied only while the locked screen is showing. Android takes the recents
     thumbnail as a window goes to the background -- before the lock can be raised on the way
     back -- so the editor sits in the thumbnail of a "locked" app. It has to be applied when
     the lock is ENABLED, which is what applyWindowSecurity() exists for.

  3. BiometricPrompt called without checking USE_BIOMETRIC was granted. Some Android builds
     throw SecurityException out of authenticate() without it, even on the way to the PIN
     fallback, which turns an app lock into a crash on resume.

  4. The phone's storage bound into the workspace unconditionally. The switch is the entire
     safety property: bound always, every photo on the phone is inside a workspace an agent
     runs shell commands in.

  5. The phone's files defaulting to on. A default of true would be a permission granted by
     someone who never asked for it.
"""
import os
import re
import sys

app = sys.argv[1]
src = app + "/app/src/com/pocketide/"


def read(name):
    return open(src + name).read()


def code(text):
    """The file with its comments removed, so prose cannot satisfy a check."""
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//.*$', '', text, flags=re.M)


problems = []

lock = code(read("AppLock.java"))
main = code(read("MainActivity.java"))
editor = code(read("WorkspaceActivity.java"))
workspace = code(read("Workspace.java"))
files = code(read("PhoneFiles.java"))
manifest = open(app + "/app/AndroidManifest.xml").read()

# --- 1. both windows raise it -------------------------------------------------------------
for name, text in (("MainActivity", main), ("WorkspaceActivity", editor)):
    if "AppLock.isLocked" not in text or "AppLock.show" not in text:
        problems.append("%s never raises the app lock; the editor is reachable from the "
                        "recent-apps list without passing the other screen" % name)
    if "AppLock.handleResult" not in text:
        problems.append("%s does not hand onActivityResult to AppLock, so the PIN screen's "
                        "answer is dropped and the lock never comes down" % name)

# --- 2. FLAG_SECURE follows the setting, not the overlay ------------------------------------
if "applyWindowSecurity" not in lock:
    problems.append("AppLock has no applyWindowSecurity(); FLAG_SECURE can then only be applied "
                    "while the locked screen shows, which is after the recents thumbnail")
else:
    guard = re.search(r'static void applyWindowSecurity\(Activity activity\) \{(.*?)\n    \}',
                      lock, re.S)
    if not guard or "enabled(activity)" not in guard.group(1):
        problems.append("applyWindowSecurity() does not key off enabled(); it must apply "
                        "FLAG_SECURE whenever the lock is ON, not while it is showing")
for name, text in (("MainActivity", main), ("WorkspaceActivity", editor)):
    if "applyWindowSecurity" not in text:
        problems.append("%s never calls AppLock.applyWindowSecurity(), so its contents can be "
                        "read from the recent-apps preview of a locked app" % name)

# --- 3. the permission is checked before the prompt is built --------------------------------
if "USE_BIOMETRIC" not in lock:
    problems.append("AppLock calls BiometricPrompt without checking USE_BIOMETRIC was granted; "
                    "some builds throw out of authenticate() without it")
else:
    prompt_at = lock.find("static void prompt(")
    build_at = lock.find("BiometricPrompt.Builder", prompt_at)
    check_at = lock.find("USE_BIOMETRIC", prompt_at)
    if check_at < 0 or build_at < 0 or check_at > build_at:
        problems.append("the USE_BIOMETRIC check comes after the prompt is built, which is the "
                        "same as not checking")
if "USE_BIOMETRIC" not in manifest:
    problems.append("USE_BIOMETRIC is used in code but not declared in the manifest")

# --- 4. the bind is conditional -------------------------------------------------------------
bind = re.search(r'PhoneFiles\.root\(\)', workspace)
if not bind:
    problems.append("Workspace never binds the phone's storage, so the setting does nothing")
else:
    window = workspace[max(0, bind.start() - 400):bind.start()]
    if "PhoneFiles.enabled" not in window:
        problems.append("the phone's storage is bound without checking PhoneFiles.enabled(); "
                        "bound unconditionally, every photo on the phone is inside a workspace "
                        "an agent runs shell commands in")

# --- 5. and it is off until asked for --------------------------------------------------------
default = re.search(r'Prefs\.PHONE_FILES,\s*(\w+)\)', files)
if not default or default.group(1) != "false":
    problems.append("the phone's files do not default to off")
if "isExternalStorageManager" not in files:
    problems.append("PhoneFiles does not check whether Android actually granted the access, so "
                    "the app would claim a folder exists that does not")


# --- 6. an errand is timed --------------------------------------------------------------------
#
# expectReturn() lets the owner go to the phone's own Settings without a fingerprint on the way
# back. That decision used to be made once, at the moment of leaving, so a phone put down for
# an hour in the middle of an errand came back unlocked. AppLock has to look again on return.
if "leftForErrand" not in lock or "ERRAND_MS" not in lock:
    problems.append("AppLock never re-checks how long an errand took; an owner who left for the "
                    "phone's Settings and came back an hour later finds the app unlocked")
elif "AppLock.leftForErrand()" not in code(read("App.java")):
    problems.append("App does not tell AppLock when the app went out on an errand, so the "
                    "errand timer never starts")

# --- 7. the service's death is told apart from an idle app being closed -----------------------
#
# The Task-Manager and low-memory notices say Linux had no chance to shut down. That is only
# true when Linux was running, and the only way to know after the process is gone is a flag the
# service writes while it runs and the next start reads before the service can write it again.
service = code(read("WorkspaceService.java"))
exits = code(read("Exits.java"))
application = code(read("App.java"))
if "Prefs.LINUX_WAS_RUNNING, true" not in service or "Prefs.LINUX_WAS_RUNNING, false" not in service:
    problems.append("WorkspaceService does not write LINUX_WAS_RUNNING on start and stop, so a "
                    "kill cannot be told apart from an idle app being closed")
if "noteStart" not in exits or "Exits.noteStart(this)" not in application:
    problems.append("Exits.noteStart() is missing or App never calls it, so the flag is read "
                    "after the service may already have rewritten it")
for reason in ("REASON_USER_REQUESTED", "REASON_LOW_MEMORY"):
    at = exits.find("case ApplicationExitInfo." + reason + ":")
    if at < 0 or "linuxWasRunning" not in exits[at:at + 400]:
        problems.append("Exits reports %s without checking whether Linux was running, so "
                        "closing an idle app produces a notice about a Linux that was not "
                        "there" % reason)

# --- 8. a live dialog can be closed ------------------------------------------------------------
#
# A button added to a dialog that is already showing is never laid out. The "Done" button has
# to exist before show() and be revealed at the end, or a finished install cannot be closed.
dialogs = code(read("Dialogs.java"))
live_at = dialogs.find("static Live live(")
live_done = dialogs.find('.setPositiveButton("Done"', live_at)
live_show = dialogs.find("show(activity, dialog, dark)", live_at)
if live_at < 0 or live_done < 0 or live_show < 0 or live_done > live_show:
    problems.append("Dialogs.live() adds its Done button after show(), where it never appears")
if "dialog.setButton(" in dialogs:
    problems.append("Dialogs still calls setButton() on a dialog that is already showing")

# --- 9. the Android 12+ splash style restates what it replaces ---------------------------------
#
# A style in values-v31 REPLACES the one in values. Without the bar items the light theme's
# dark clock is drawn over the violet splash on every phone from Android 12 on.
v31 = open(app + "/app/res/values-v31/styles.xml").read()
for item in ("windowLightStatusBar", "windowLightNavigationBar", "windowBackground"):
    if item not in v31:
        problems.append("values-v31/styles.xml does not restate android:%s, which the base "
                        "splash style sets and this one silently drops" % item)


# --- 10. the browser engine's process dying must not take the app with it -------------------
#
# The one that produced "app open karte hi apne aap close ho raha". A WebView runs its page in a
# separate renderer process; when Android kills that process for memory -- and Visual Studio
# Code with an extension host and three agent panels is the most expensive thing on the phone --
# an app that does not handle the death is killed with it. No dialog, no report, no log.
gone = re.search(r'public boolean onRenderProcessGone\(.*?\n            \}', editor, re.S)
if not gone:
    problems.append("WorkspaceActivity does not override onRenderProcessGone, so Android kills "
                    "the whole app when the editor's renderer is reclaimed for memory")
elif "return true" not in gone.group(0):
    problems.append("onRenderProcessGone does not return true, which tells Android the app did "
                    "NOT handle the death -- and Android then kills the app anyway")
if "FLAG_KEEP_SCREEN_ON" not in editor:
    problems.append("the editor screen lets the phone sleep while an agent is working; the "
                    "service's wake lock is the CPU's and does not cover the screen")

# --- 12. stopping Linux takes the whole container with it ------------------------------------
#
# Process.destroy() signals PRoot and does not wait. A tracer that is killed leaves its tracees
# detached and still running, so a compiler outlives the Stop button.
service_text = code(read("WorkspaceService.java"))
if "sweep(" not in service_text or "Running.workspace" not in service_text:
    problems.append("WorkspaceService kills PRoot without sweeping the workspace's own "
                    "processes, so whatever it was tracing is reparented and keeps running")
for signal, why in ((" 3)", "SIGQUIT, which is what PRoot answers by killing its tracees"),
                    (" 9)", "SIGKILL as the backstop for anything that outlived its tracer")):
    if "sweep(%s" % signal.strip().rstrip(")") not in service_text:
        problems.append("the stop path never sends %s" % why)


# --- 13. heat pauses the workspace instead of letting the phone kill it --------------------------
#
# Held with SIGSTOP at critical, released with SIGCONT at moderate or below -- not one notch
# down, which would flap. And never while nothing is running: a listener that stops processes
# that are not the workspace's is a listener that stops the wrong thing.
if "addThermalStatusListener" not in service_text:
    problems.append("WorkspaceService never listens for the phone's thermal status, so a hot "
                    "phone kills the set-up or the build instead of pausing it")
else:
    heat = re.search(r'private void onHeat\(int status\) \{(.*?)\n    \}', service_text, re.S)
    if not heat:
        problems.append("WorkspaceService has no onHeat() to act on the thermal status")
    else:
        body = heat.group(1)
        if "sweep(19)" not in body or "THERMAL_STATUS_CRITICAL" not in body:
            problems.append("the workspace is not held with SIGSTOP at critical heat")
        if "sweep(18)" not in body or "THERMAL_STATUS_MODERATE" not in body:
            problems.append("the workspace is not released with SIGCONT once the phone has "
                            "cooled to moderate; releasing one notch below critical flaps")
        if not body.lstrip().startswith("if (!busy) return;"):
            problems.append("onHeat() acts when nothing is running, so it can stop processes "
                            "that are not the workspace's")


# --- 14. every executable the Android layer installs is pinned, and the provider is narrow -----
#
# The tools script downloads five executables into the workspace: Google's command-line tools
# and four aarch64 rebuilds of the tools Google ships as x86-64 only. Each has a SHA-256 in the
# script and is refused when it does not match -- the same rule the editor's tarball lives by.
tools_script = open(app + "/app/assets/pocketide-tools.sh").read()
for name in ("CMDLINE_SHA256", "ARM_SHA256_aapt2", "ARM_SHA256_aidl", "ARM_SHA256_zipalign",
             "ARM_SHA256_split_select"):
    if not re.search(name + r'="[0-9a-f]{64}"', tools_script):
        problems.append("pocketide-tools.sh has no 64-hex SHA-256 pin named %s, so that "
                        "executable would be installed unverified" % name)
fetch = re.search(r'fetch_pinned\(\) \{(.*?)\n\}', tools_script, re.S)
if not fetch:
    problems.append("pocketide-tools.sh has no fetch_pinned(); downloads are not checked")
else:
    body = fetch.group(1)
    # The rm that matters is the one AFTER the digest comparison; the retry branch has its own.
    if "sha256sum" not in body or not re.search(
            r'!= "\$expected" \]; then\s*\n\s*rm -f "\$target"', body):
        problems.append("fetch_pinned() does not delete a download whose digest does not match, "
                        "so the next attempt trusts a file this one refused")
android_fn = re.search(r'install_android\(\) \{(.*?)\n\}', tools_script, re.S)
if not android_fn:
    problems.append("pocketide-tools.sh has no install_android()")
else:
    body = android_fn.group(1)
    if body.find("fetch_pinned") < 0 or body.find("chmod +x") < body.find("fetch_pinned"):
        problems.append("install_android() makes a tool executable before it has been "
                        "checked against its pin")
    if "aapt2FromMavenOverride" not in body:
        problems.append("install_android() never writes android.aapt2FromMavenOverride, so "
                        "Gradle fetches its own x86-64 aapt2 and the build still stops")
    if 'aapt2" version' not in body:
        problems.append("install_android() never proves the replaced aapt2 runs on this phone")
# The provider that hands an APK to the installer: not exported, .apk only, under ~/projects
# only, read-only, through canonical paths.
provider = re.search(r'<provider(.*?)/>', manifest, re.S)
if not provider or 'android:exported="false"' not in provider.group(1):
    problems.append("the Built provider is exported, so any app could ask it for a file")
elif 'android:grantUriPermissions="true"' not in provider.group(1):
    problems.append("the Built provider cannot grant a URI, so the installer is refused the file")
built = code(read("Built.java"))
resolve = re.search(r'private File resolve\(Uri uri\)(.*?)\n    \}', built, re.S)
if not resolve:
    problems.append("Built has no resolve() to restrict what it serves")
else:
    body = resolve.group(1)
    if body.count("getCanonicalPath()") < 2:
        problems.append("Built.resolve(): both the root and the candidate must be canonicalised, "
                        "or a ../ in the URI, or a symlink under ~/projects, walks out of it")
    for must, why in (("getCanonicalPath", "paths are not canonicalised, so ../ walks out of ~/projects"),
                      ('endsWith(".apk")', "files other than an APK can be served"),
                      ("Workspace.projects", "the provider is not rooted at ~/projects")):
        if must not in body:
            problems.append("Built.resolve(): " + why)
if 'if (!"r".equals(mode))' not in built:
    problems.append("Built.openFile() accepts a write mode")

# --- 15. the package lists every install depends on, and the phone as its own test device ------
#
# Set-up and the nightly update delete /var/lib/apt/lists to save 60 MB. Every apt-get install
# in the tools script therefore has to refresh the list first, or it fails on every fresh
# workspace with "Unable to locate package" -- which is what a review found the JDK step doing.
# Structural: the refresh has to come BEFORE the install inside the same function.
#
# Read with the comments stripped, like the Java. A review satisfied an earlier version of
# these checks with "# refresh_packages is done elsewhere", which is prose, not a refresh.
def shell_code(text):
    return re.sub(r'^\s*#.*$', '', text, flags=re.M)


tools_code = shell_code(tools_script)
APT_INSTALL = re.compile(r'\bapt(?:-get)?\s+(?:\S+\s+)*install\b')
for fn_name in re.findall(r'^(install_[a-z_]+)\(\) \{', tools_code, re.M):
    fn = re.search(r'^' + fn_name + r'\(\) \{(.*?)\n\}', tools_code, re.S | re.M)
    body = fn.group(1) if fn else ""
    install = APT_INSTALL.search(body)
    if not install:
        continue
    refresh_at = body.find("refresh_packages")
    if refresh_at < 0 or refresh_at > install.start():
        problems.append("%s() runs apt-get install without refreshing the package list first, "
                        "which fails on every fresh workspace because set-up deletes the "
                        "lists" % fn_name)
# The aapt2 line lands on a line of its own even when the owner's file has no final newline:
# the guard's CONDITION, not merely its presence, in the lines before the append.
tools_lines = tools_code.split("\n")
appends = [i for i, line in enumerate(tools_lines)
           if '"$expected_line" >> "$properties"' in line]
if not appends:
    problems.append("install_android() no longer appends the exact aapt2 line it verified")
for i in appends:
    if not any('wc -l)" -eq 0 ]' in earlier for earlier in tools_lines[max(0, i - 8):i]):
        problems.append("install_android() appends the aapt2 line without first making sure "
                        "the owner's gradle.properties ends with a newline, so it can land "
                        "on the tail of their last line where Gradle sees neither")
EXACT_LINE = 'grep -qxF "android.aapt2FromMavenOverride='
android_fn = re.search(r'^install_android\(\) \{(.*?)\n\}', tools_code, re.S | re.M)
android_body = android_fn.group(1) if android_fn else ""
# install_android spells the line once, into expected_line, and tests THAT exactly (-x -F).
if ('expected_line="android.aapt2FromMavenOverride=$bt/aapt2"' not in android_body
        or 'grep -qxF "$expected_line"' not in android_body):
    problems.append("install_android() accepts any aapt2 line in gradle.properties, so a line "
                    "naming a build-tools version that is gone keeps the new one unused")
if "fix_build_tools" not in android_body:
    problems.append("install_android() repairs only its own build-tools directory; the one the "
                    "Android Gradle Plugin adds keeps its x86-64 aidl")
check_fn = re.search(r'^check\(\) \{(.*?)\n\}', tools_code, re.S | re.M)
check_body = check_fn.group(1) if check_fn else ""
if EXACT_LINE not in check_body:
    problems.append("check() reports android_sdk=yes without the exact line that makes Gradle use "
                    "the aapt2 it checked, so Settings says installed while every build still "
                    "fetches the x86-64 one")
if "adb --version" in check_body or "adb=" in check_body:
    problems.append("check() runs or reports an adb inside Linux; there is none, and the app's "
                    "own is not this Linux's to run")
phone_fn = re.search(r'^install_phone_command\(\) \{(.*?)\n\}', tools_code, re.S | re.M)
phone_body = phone_fn.group(1) if phone_fn else ""
if ("install -m 0755 /opt/pocketide/pocketide-phone.py /usr/local/bin/phone" not in phone_body
        or "cat > /usr/local/bin/adb <<'SHIM'" not in phone_body
        or "rm -f /etc/profile.d/pocketide-adb.sh" not in phone_body):
    problems.append("install_phone_command() does not install the phone command from the app's "
                    "asset, put the adb shim in place, and remove the 2.1.x profile line")
if APT_INSTALL.search(phone_body) or re.search(r'apt-get install[^\n]*\badb\b', tools_code):
    problems.append("the tools script installs adb inside Linux, where an agent can replace it")
for repair in ("link_adb", "fix_build_tools"):
    if repair not in check_body:
        problems.append("check() does not run %s, so what Gradle undid overnight stays undone "
                        "until the next install" % repair)
if not re.search(r'^fix_build_tools\(\) \{', tools_code, re.M):
    problems.append("pocketide-tools.sh has no fix_build_tools()")
# The phone pairs with ITSELF: adb is pointed at loopback and nowhere else, only an
# advertisement that resolves to one of this phone's own addresses is taken, the code is six
# digits before it reaches a command line, and the receiver the code arrives through is not
# exported. Every one of those is the difference between a test device and an open door.
if not os.path.exists(src + "Phone.java"):
    problems.append("there is no Phone.java, so the phone cannot be paired with itself")
else:
    phone = code(read("Phone.java"))
    if 'LOOPBACK = "127.0.0.1"' not in phone:
        problems.append("Phone does not fix adb's host at 127.0.0.1")
    # The call sites, not every mention: arguments to adb, never a command line, and the
    # host is LOOPBACK and nothing else.
    for command in ("pair", "connect"):
        calls = list(re.finditer(r'run\(service, "' + command + r'", ', phone))
        if not calls:
            problems.append("Phone never runs adb %s" % command)
        for hit in calls:
            after = phone[hit.end():hit.end() + 12]
            if not after.startswith('LOOPBACK'):
                problems.append("Phone runs adb %s against something other than LOOPBACK"
                                % command)
    if re.search(r'"adb (pair|connect) " \+', phone):
        problems.append("Phone builds an adb command line out of strings; arguments go to adb "
                        "as arguments or a typed value becomes a second command")
    if "isThisPhone(r.getHost())" not in phone:
        problems.append("Phone.discover() takes any advertisement it hears; a laptop on the same "
                        "Wi-Fi advertising adb could be what gets paired with")
    if "waiting.poll()" not in phone:
        problems.append("Phone.discover() resolves only the first advertisement it hears, so a "
                        "neighbour's adb answering first hides this phone's own for good")
    connect_fn = re.search(r'static boolean connect\(WorkspaceService service, boolean loud\) '
                           r'\{(.*?)\n    \}', phone, re.S)
    if not connect_fn or "Prefs.PHONE_PAIRED, true" not in connect_fn.group(1):
        problems.append("a successful connect does not record the phone as paired, so a phone "
                        "paired by hand says tap to pair forever")
    # A server has to be answering before adb is run, or the one adb forks dies with the
    # PRoot that ran it and "connected" is true for the length of one command.
    pair_fn = re.search(r'static void pair\(WorkspaceService service, String code\) \{(.*?)'
                        r'\n    \}', phone, re.S)
    for name, fn in (("pair", pair_fn), ("connect", connect_fn)):
        if not fn or "service.ensureAdbServer()" not in fn.group(1):
            problems.append("Phone.%s() runs adb without making sure a server is answering, so "
                            "adb installed after the editor started forks one that dies with "
                            "the command" % name)
    # A failure the owner can put right puts the reply box back.
    if not pair_fn or pair_fn.group(1).count("askForCode(service,") < 3:
        problems.append("Phone.pair() replaces the reply box with a plain notification on a "
                        "retryable failure, so the owner is told to type into nothing")
    # All three notification switches, before the owner is sent to read a code.
    pane = code(read("SettingsPane.java"))
    if "if (!Phone.canNotify(host))" not in pane:
        problems.append("Settings sends the owner to pair without checking that a notification "
                        "can appear at all; the permission alone is not the whole answer")
    if 'code.matches("\\\\d{6}")' not in phone:
        problems.append("the pairing code is not checked to be six digits before it reaches a "
                        "command line")
    # ON NO NETWORK PORT. The server answers on a socket in the app's own storage; a port on
    # loopback would be every app's. The app sets it for every PRoot, the app tests the socket
    # rather than a port, and no script or class names adb's port at all.
    if '"ADB_SERVER_SOCKET", "localfilesystem:/root/.android/adb.sock"' not in phone:
        problems.append("Phone.environment() does not set ADB_SERVER_SOCKET, so adb's server "
                        "listens on a TCP port every app on the phone can reach")
    if "ADB_SERVER_SOCKET" in workspace:
        problems.append("Workspace names adb's socket, so the Linux the agent works in is told "
                        "where the app's adb server answers")
    if "LocalSocket" not in phone or "Namespace.FILESYSTEM" not in phone:
        problems.append("Phone does not test adb's socket, so it cannot tell whether the server "
                        "it relies on is the private one")
    editor_script = open(app + "/app/assets/pocketide-editor.sh").read()
    for name, text in (("Phone.java", phone), ("Workspace.java", workspace),
                       ("pocketide-tools.sh", tools_code),
                       ("pocketide-editor.sh", shell_code(editor_script))):
        if "5037" in text:
            problems.append("%s names adb's TCP port, which the private socket exists to "
                            "replace" % name)
    receiver = re.search(r'<receiver(.*?)/>', manifest, re.S)
    if not receiver or 'android:exported="false"' not in receiver.group(1):
        problems.append("PhoneReceiver is exported, so any app could hand the service a code")
    service = code(read("WorkspaceService.java"))
    branch = re.search(r'ACTION_PHONE_PAIR\.equals\(action\) \|\| ACTION_PHONE_CONNECT'
                       r'\.equals\(action\)\) \{(.*?)return START_NOT_STICKY;\s*\}',
                       service, re.S)
    if not branch or "if (!editorRunning)" not in branch.group(1):
        problems.append("the service pairs or connects without the editor running, into an adb "
                        "server that is gone by the time the terminal asks")
    if "synchronized boolean ensureAdbServer()" not in service:
        problems.append("ensureAdbServer() is not synchronized, so two quick taps start two "
                        "servers and the first keeps running on a socket nobody can reach")
    if "resolvingSince" not in phone:
        problems.append("a resolve that never calls back holds the discovery queue until the "
                        "deadline, and this phone's own advertisement behind it is never tried")
    if "od -An -tx1 -j18 -N1" not in tools_code:
        problems.append("the tools script decides whether a build tool runs by an exit code PRoot "
                        "does not give, so foreign build-tools are never repaired")
    # THE DOOR, NOT THE KEY. The editor's PRoot never starts adb and never sees the key: the
    # server is the app's own, in a directory bound only into the app's adb commands, and the
    # editor's PRoot is given the bridge directory instead. The bridge does a short list of
    # things, each to a package it installed itself, and a screenshot or an input only while
    # that package is on the screen.
    if "adb start-server" in shell_code(editor_script):
        problems.append("the editor script starts adb inside the editor's PRoot, which puts the "
                        "phone's key and server where every agent can use them")
    if "Workspace.start(this, command, PhoneBroker.editorBinds(this))" not in service:
        problems.append("the editor is started without the bridge bind, so the phone command "
                        "has nothing to talk to; or with more than the bridge")
    if '":/root/.android"' not in phone or "androidDir(context)" not in phone:
        problems.append("adb's key directory is not bound from the app's own storage, so the "
                        "key and the socket sit in the rootfs where the terminal can read them")
    if 'Phone.start(this, "server", "nodaemon")' not in service:
        problems.append("the app's adb server is not started through Phone.start, the one door "
                        "to the private adb root")
    if "adb pair 127.0.0.1" in phone.split("static final String STEPS")[-1]:
        problems.append("the Help still hands the owner the by-hand pairing that would give the "
                        "terminal the whole key")
    broker = code(read("PhoneBroker.java"))
    ops = re.search(r'static final String\[\] OPS = \{(.*?)\};', broker, re.S)
    ops_list = re.findall(r'"([a-z]+)"', ops.group(1)) if ops else []
    if not ops_list:
        problems.append("the phone bridge has no OPS list")
    for forbidden in ("shell", "pull", "push", "forward", "reverse", "root", "tcpip", "sync",
                      "backup", "restore", "sideload"):
        if forbidden in ops_list:
            problems.append("the phone bridge offers %s, which is the whole key again" % forbidden)
    # And every op in the list is one the switch handles, and nothing reaches adb shell with
    # an argument the agent typed unchecked: the shell argument is always a string this class
    # wrote, with validated pieces in it.
    for op in ops_list:
        if 'case "%s":' % op not in broker:
            problems.append("phone %s is listed but not handled" % op)
    for hit in re.finditer(r'adbTo\(reply, "shell", ([^\n]+)', broker):
        if 'args.get(' in hit.group(1):
            problems.append("an argument the agent typed reaches adb shell unvalidated: "
                            + hit.group(1)[:60])
    for op in ("uninstall", "launch", "stop", "clear", "instrument", "log", "screenshot"):
        if not re.search(r'case "%s":\s*\n\s*return forAllowed\(' % op, broker):
            problems.append("phone %s is not restricted to packages the bridge installed" % op)
    if not re.search(r'case "tap":\s*\n\s*case "text":\s*\n\s*case "key":\s*\n\s*return forAllowed\(',
                     broker):
        problems.append("phone tap, text and key are not restricted to packages the bridge "
                        "installed")
    for needs_screen in ("screenshot", "input"):
        body = re.search(r'private int ' + needs_screen + r'\((.*?)\n    \}', broker, re.S)
        if not body or "onlyOnScreen(pkg, " not in body.group(1):
            problems.append("phone %s reaches the screen without checking that the package is "
                            "the one on it" % needs_screen)
    only = re.search(r'static String onlyOnScreen\(String pkg, String action\) \{(.*?)\n    \}',
                     broker, re.S)
    front = re.search(r'static final String FRONT = (.*?);', broker, re.S)
    if (not only or not front or "topResumedActivity|mResumedActivity" not in front.group(1)
            or 'return FRONT + "; "' not in only.group(1)
            or "case \\\"$t\\\" in *' u0 \" + pkg + \"/'*) \" + action" not in only.group(1)
            or "exit 3" not in only.group(1)):
        problems.append("the screen check and the action are not one command on the phone, so "
                        "another app can come to the front between them")
    if ('projectFile(args.get(0), cwd, ".apk")' not in broker
            or "getPackageArchiveInfo" not in broker):
        problems.append("phone install takes something other than an APK under ~/projects that "
                        "Android can read")
    if 'GUEST_SOCKET = GUEST_DIR + "/phone.sock"' not in broker or 'GUEST_DIR = "/run/pocketide"' not in broker:
        problems.append("the bridge socket is not where the phone command looks")
    cli_path = app + "/app/assets/pocketide-phone.py"
    cli = open(cli_path).read() if os.path.exists(cli_path) else ""
    if 'SOCK = "/run/pocketide/phone.sock"' not in cli:
        problems.append("the phone command asset is missing, or points somewhere other than the "
                        "bridge")
    if '"pocketide-phone.py"' not in workspace:
        problems.append("Workspace does not write the phone command with the other scripts, so "
                        "the terminal's copy is whatever an older build left")
    if "pocketide-tools.sh phone" not in shell_code(editor_script):
        problems.append("the editor's start does not install the phone command, so a fresh "
                        "workspace has no door to the phone")

# --- 16. permissions and power, honestly ---------------------------------------------------------
#
# Nothing requested that no code needs; the rows say what is true; a long job cannot be killed
# quietly by the daily update running outside the service.
manifest_code = re.sub(r"<!--.*?-->", "", manifest, flags=re.S)
if "android.permission.VIBRATE" in manifest_code:
    problems.append("VIBRATE is requested, and nothing needs it: the key row's haptics go "
                    "through View.performHapticFeedback")
if 'android:requestLegacyExternalStorage="true"' not in manifest_code:
    problems.append("requestLegacyExternalStorage is not set, so ~/phone cannot read the shared "
                    "storage on Android 10")
permissions_src = code(read("Permissions.java"))
allowed_fn = re.search(r'static boolean notificationsAllowed\(Context context\) \{(.*?)\n    \}',
                       permissions_src, re.S)
if not allowed_fn or "areNotificationsEnabled()" not in allowed_fn.group(1):
    problems.append("the Notifications row says Allowed on Android 10 to 12 whatever the app's "
                    "own switch says")
updates_src = code(read("Updates.java"))
maybe = re.search(r'static void maybeRunInBackground\(final Context context\) \{(.*?)\n    \}',
                  updates_src, re.S)
if not maybe or "WorkspaceService.update(context)" not in maybe.group(1) \
        or "new Thread" in maybe.group(1):
    problems.append("the daily update runs on a bare thread from a screen instead of under the "
                    "service's wake lock and notification")
if "ACTION_UPDATE" not in service or "Updates.runQuietly(this" not in service:
    problems.append("the service has no update job")
if "isPowerSaveMode()" not in service:
    problems.append("a job starts with power saving on and nothing says so")
settings_src = code(read("SettingsPane.java"))
if '"Data Saver"' in settings_src:
    problems.append("the Data Saver row is back; a foreground job is not governed by it")
if '"Not needed: this app never starts itself · "' not in settings_src:
    problems.append("the Auto-launch row does not say it is not needed")
if '"Keep working with the screen off"' not in settings_src:
    problems.append("the battery exemption row is not named for what it does")

# --- 17. nothing the workspace can write is ever run with the key in reach ---------------------
#
# A review of the first 2.2.0 draft found the hole that undid the whole door: the app's adb
# ran out of the Linux rootfs through bash -lc, so /usr/bin/adb, /etc/profile.d, ~/.profile
# and /bin/bash -- every one of them the agent's to write -- ran with the key bound in. adb
# now has a root of its own, assembled at build time from pinned packages, run by absolute
# path with no shell. Each clause below is one way that could quietly come back.
workspace_src = code(read("Workspace.java"))
private = re.search(r'static Process startPrivate\((.*?)\n    \}', workspace_src, re.S)
if not private:
    problems.append("Workspace has no startPrivate(), so the app's adb has no root of its own")
else:
    body = private.group(1)
    for shell in ("/bin/bash", "-lc", "/usr/bin/env", "sh -c"):
        if shell in body:
            problems.append("startPrivate() puts a shell (%s) in front of the program it runs; "
                            "a shell reads files, and the files are the agent's" % shell)
    if "args.addAll(argv)" not in body or "env.clear()" not in body:
        problems.append("startPrivate() does not run the caller's argv with an environment "
                        "set by the app alone")
    if 'args.add("-r");\n        args.add(root.getAbsolutePath())' not in body:
        problems.append("startPrivate() is not rooted at the root it is given")
    if "root(context)" in body or "PhoneFiles" in body or "proc-fakes" in body:
        problems.append("startPrivate() reaches for the Linux rootfs, the phone's files or the "
                        "rootfs's fakes; the private root gets none of them")
for name in ("PhoneBroker.java", "Phone.java"):
    text = code(read(name))
    if re.search(r'Workspace\.(start|run)\(', text):
        problems.append("%s starts a PRoot in the Linux rootfs; adb runs only through "
                        "Phone.start, in its own root" % name)
    if "process.destroy()" in text and name == "PhoneBroker.java":
        problems.append("PhoneBroker destroys a PRoot with SIGTERM, which PRoot ignores; "
                        "Workspace.quit sends the SIGQUIT it answers")
if ('"/usr/bin/adb"' not in phone or "Workspace.startPrivate(context, root(context), argv, "
        "binds(context), environment())" not in phone):
    problems.append("Phone.start() does not run /usr/bin/adb by absolute path in the private "
                    "root with the app's binds and environment")
if '"ADB_MDNS", "0"' not in phone:
    problems.append("the app's adb is left advertising and scanning on the network")
prepare = re.search(r'static synchronized boolean prepareRoot\(Context context\) \{(.*?)\n    \}',
                    phone, re.S)
if (not prepare or 'readAsset(context, "adb-root.stamp")' not in prepare.group(1)
        or "getCanonicalPath().startsWith(rootPath)" not in prepare.group(1)
        or 'getAssets().open("adb-root.zip")' not in prepare.group(1)):
    problems.append("Phone.prepareRoot() does not unpack the APK's adb root against its stamp "
                    "with every entry kept inside the root")
build_sh = open(app + "/build.sh").read()
debs = re.search(r'ADB_ROOT_DEBS=\((.*?)\n\)', build_sh, re.S)
entries = re.findall(r'"([^"]+)"', debs.group(1)) if debs else []
if len(entries) < 19:
    problems.append("build.sh pins fewer than the nineteen packages adb's root needs")
for entry in entries:
    if not re.match(r'^[a-z0-9+.-]+\|pool/[a-z0-9+./_-]+\.deb\|[0-9a-f]{64}\|[0-9]+$', entry):
        problems.append("build.sh entry is not name|path|sha256|size: %s" % entry[:40])
assemble = re.search(r'^assemble_adb_root\(\) \{(.*?)\n\}', build_sh, re.S | re.M)
if (not assemble or 'if [[ "$got" != "$sha" ]]; then' not in assemble.group(1)
        or "Refusing to build" not in assemble.group(1)
        or "dpkg-deb -x" not in assemble.group(1)
        or 'adb-root.zip' not in assemble.group(1) or 'adb-root.stamp' not in assemble.group(1)):
    problems.append("build.sh does not verify every package against its pin before it goes "
                    "into adb's root, or does not write the zip and its stamp")
if "ports.ubuntu.com/ubuntu-ports" not in build_sh:
    problems.append("adb's packages are not fetched from Ubuntu's own ports archive")
# The door's smaller gaps, each found by the same review.
install_fn = re.search(r'private int install\(List<String> args, String cwd, Reply reply\)(.*?)'
                       r'\n    \}', broker, re.S)
install_body = install_fn.group(1) if install_fn else ""
if ('File staged = stage(apk, ".apk");' not in install_body
        or "staged.getAbsolutePath(), 0);" not in install_body
        or '"/stage/" + staged.getName()' not in install_body
        or "Installer.install(service, staged," not in install_body):
    problems.append("phone install reads or installs the agent's own file rather than a copy "
                    "in the app's storage, so the file can change between the two")
first_allow = install_body.find("allow(info.packageName")
first_install = install_body.find('adbTo(reply, "install"')
if first_allow < 0 or first_install < 0 or first_allow < first_install:
    problems.append("phone install allows a package before it is on the phone")
allows = [m.start() for m in re.finditer(r'allow\(info\.packageName', install_body)]
adb_success = install_body.find("if (code == 0) {")
unpaired_failure = install_body.find("if (!failure.isEmpty()) {")
if (len(allows) != 2 or adb_success < 0 or unpaired_failure < 0
        or not adb_success < allows[0] < unpaired_failure < allows[1]):
    problems.append("the allow-list does not grow only on the two success paths")
project_fn = re.search(r'private File projectFile\((.*?)\n    \}', broker, re.S)
if (not project_fn or "plainDirectory(home)" not in project_fn.group(1)
        or "plainDirectory(projects)" not in project_fn.group(1)
        or "LinkOption.NOFOLLOW_LINKS" not in broker):
    problems.append("projectFile() follows a link at ~/projects or /root, so a link to / makes "
                    "the whole host 'under ~/projects'")
if ("new ThreadPoolExecutor(AT_ONCE, AT_ONCE" not in broker
        or "catch (RejectedExecutionException full)" not in broker):
    problems.append("the bridge grows a thread per request instead of serving a few and "
                    "saying busy")
serve_fn = re.search(r'private void serve\(LocalSocket client\) \{(.*?)\n    \}', broker, re.S)
serve_body = serve_fn.group(1) if serve_fn else ""
adb_to = re.search(r'private int adbTo\(Reply reply, String\.\.\. adbArguments\)(.*?)\n    \}', broker, re.S)
shot = re.search(r'private int screenshot\((.*?)\n    \}', broker, re.S)
left = re.search(r'void left\(\) \{(.*?)\n        \}', broker, re.S)
if ("client.setSoTimeout(15_000);" not in serve_body or "reply.left();" not in serve_body
        or not adb_to or "reply.watch(process);" not in adb_to.group(1)
        or not shot or "reply.watch(process);" not in shot.group(1)
        or not left or "Workspace.quit(process)" not in left.group(1)):
    problems.append("a client that leaves mid-stream is not noticed: a phone log left with "
                    "Ctrl-C keeps its worker, its PRoot and the phone's logcat until the "
                    "editor restarts, and four of those are a bridge that only says busy")
if "SHUT_WR" in cli:
    problems.append("the phone command shuts its write side after the request, so the bridge "
                    "cannot tell a client that has finished asking from one that has left")
if "if (never instanceof Pending) closeQuietly(((Pending) never).client);" not in broker:
    problems.append("requests still queued when the door closes are left waiting on nothing")
guest_fn = re.search(r'private String guestPath\(File host\) \{(.*?)\n    \}', broker, re.S)
if not guest_fn or "Workspace.root(service).getCanonicalPath()" not in guest_fn.group(1):
    problems.append("guestPath() compares a canonical file against an uncanonical root, so "
                    "every reply and the allow-list carry a host path Linux does not have")
if ("GET_SIGNING_CERTIFICATES" not in broker or "certificates.put(pkg" not in broker
        or "String now = installedCertificate(pkg);" not in broker):
    problems.append("the allow-list is a list of names, so an app of the same name installed "
                    "later by the owner is handed to the bridge")
for_allowed = re.search(r'private int forAllowed\(List<String> args, Reply reply, boolean needsPhone,'
                        r'(.*?)\n    \}', broker, re.S)
checked_at = for_allowed.group(1).find("!now.equals(expected)") if for_allowed else -1
if (not for_allowed or "forget(pkg);" not in for_allowed.group(1) or checked_at < 0
        or checked_at > for_allowed.group(1).find("then.run(pkg)")):
    problems.append("forAllowed() runs the operation before it has checked the installed "
                    "package is the one the bridge installed")
timeout_path = re.search(r'if \(answer == null\) \{(.*?)\n        \}', code(read("Installer.java")), re.S)
if not timeout_path or "installer.abandonSession(id);" not in timeout_path.group(1):
    problems.append("an install nobody answered is left open, so a late tap installs the app "
                    "with nothing told and nothing allowed")
if "Os.shutdown(bound.getFileDescriptor()" not in broker:
    problems.append("closing the bridge does not wake the thread waiting in accept(), which "
                    "keeps the old socket")
if "Workspace.quit(process)" not in broker or "static void quit(Process process)" not in workspace_src \
        or "sendSignal(Integer.parseInt(pid.group(1)), 3)" not in workspace_src:
    problems.append("a PRoot the bridge ends is not sent SIGQUIT, the one signal PRoot answers")
installer_src = code(read("Installer.java"))
launch_fn = re.search(r'static String launch\(Context context, String packageName\) \{(.*?)'
                      r'\n    \}', installer_src, re.S)
if (not launch_fn or "if (!App.inFront())" not in launch_fn.group(1)
        or launch_fn.group(1).find("if (!App.inFront())")
        > launch_fn.group(1).find("context.startActivity(open)")):
    problems.append("an unpaired launch starts the activity without checking that this app is "
                    "in front, so Android drops it and the terminal is told it opened")
if "static boolean inFront()" not in code(read("App.java")):
    problems.append("App does not say whether a screen of this app is in front")
if ("if (updating && ACTION_START.equals(action))" not in service
        or "startAfterUpdate = true;" not in service):
    problems.append("a start that arrives during the daily update is dropped without a word")
if service.count("synchronized (updateLock) {") < 4:
    problems.append("the update's flags are read and written without a lock, so a start that "
                    "arrives as the update finishes is neither queued nor refused")
update_fn = re.search(r'private void runUpdate\(\) \{(.*?)\n    \}', service, re.S)
if not update_fn or "runEditor();" not in update_fn.group(1):
    problems.append("runUpdate() does not open the editor that was asked for during the update")
if "else if (hadJob) stopTidily(null);" not in service:
    problems.append("Stop during a job with no process handle -- the daily update -- sweeps "
                    "nothing, so apt keeps running")
if (service.count("synchronized (brokerLock) {") != 2
        or "private void openBroker()" not in service or "private void closeBroker()" not in service):
    problems.append("the bridge is opened and closed without a lock, so an editor stopping while "
                    "one starts can leave a door open with no service behind it")

# --- 18. a failure before the first frame is logged and skipped, never the end of the process --
app_src = code(read("App.java"))
for step in ("Exits.noteStart(this)", "watchForegroundState()", "createNotificationChannel(channel)"):
    at = app_src.find(step)
    # Inside a try: the nearest try { before it has not been closed by a catch yet.
    opened = app_src.rfind("try {", 0, at) if at >= 0 else -1
    if at < 0 or opened < 0 or "catch (" in app_src[opened:at]:
        problems.append("App.onCreate runs %s outside a try, where a failure ends the process "
                        "with nothing said" % step)
main_src = code(read("MainActivity.java"))
for method, steps in (("onStart", ("Rotation.apply(this)", "raiseLockIfNeeded()")),
                      ("onResume", ("Updates.maybeRunInBackground(this)",
                                    "AppUpdates.maybeCheckInBackground(this)"))):
    body = re.search(r'@Override protected void ' + method + r'\(\) \{(.*?)\n    \}', main_src,
                     re.S)
    body = body.group(1) if body else ""
    for step in steps:
        at = body.find(step + ";")
        if at < 0 or "try {" not in body[max(0, at - 200):at]:
            problems.append("MainActivity.%s runs %s before the first frame outside a try, where "
                            "a failure closes the app with nothing said" % (method, step))

# --- 19. where everything is, said and measured; a delete that stops at a link -----------------
#
# An owner asked where the chats and the files go. The answer has to be in Help, in the privacy
# text, in the terms and on a screen that measures it -- and the one delete the app offers must
# never walk through a link an agent left pointing at the phone's shared storage.
stored = code(read("Stored.java"))
chat_fn = re.search(r'static List<File> chatFolders\(Context context\) \{(.*?)\n    \}', stored, re.S)
chat_body = chat_fn.group(1) if chat_fn else ""
for path in ('".claude/projects"', '".claude/history.jsonl"', '".codex/sessions"',
             '".codex/history.jsonl"', '"User/globalStorage/kilocode.kilo-code/tasks"'):
    if path not in chat_body:
        problems.append("Stored.chatFolders() does not name %s, the folder that agent's own "
                        "documentation or source names" % path)
for must_not in (".credentials", "auth.json", "settings.json", "projects(context)", "config.toml"):
    if must_not in chat_body:
        problems.append("clearChats() would delete %s, which is not a chat" % must_not)
workspace_src = code(read("Workspace.java"))
delete_fn = re.search(r'static void delete\(File file\) \{(.*?)\n    \}', workspace_src, re.S)
if (not delete_fn or "isSymbolicLink(file.toPath())" not in delete_fn.group(1)
        or delete_fn.group(1).find("isSymbolicLink") > delete_fn.group(1).find("isDirectory()")):
    problems.append("Workspace.delete() follows links: a link inside Linux to the phone's "
                    "shared storage would have the photos deleted with the workspace")
size_fn = re.search(r'static long sizeOf\(File file\) \{(.*?)\n    \}', workspace_src, re.S)
if not size_fn or "isSymbolicLink(file.toPath())" not in size_fn.group(1):
    problems.append("Workspace.sizeOf() measures through links")
settings_src = code(read("SettingsPane.java"))
for row in ('"What is stored where"', '"Agent chats"'):
    if not re.search(r'Ui\.row\(host, dark, R\.drawable\.\w+, ' + re.escape(row), settings_src):
        problems.append("Settings has no %s row" % row)
chats_src = code(read("ChatsActivity.java"))
clear_fn = re.search(r'private void confirmClearAll\(\) \{(.*?)\n    \}', chats_src, re.S)
if not clear_fn or 'editorInTheWay(' not in clear_fn.group(1) \
        or "Stored.clearChats(this)" not in clear_fn.group(1):
    problems.append("Delete every chat runs with the editor open, or does not clear through "
                    "Stored.clearChats")
if "WorkspaceService.editorRunning()" not in chats_src:
    problems.append("the chats screen deletes while an agent in the editor may be writing")
texts_src = code(read("Texts.java"))
for said in ("~/.claude/projects", "~/.codex/sessions", "cleanupPeriodDays",
             "Where is everything stored", "Agent chats", "The agents' chats.",
             "9. Your data, and the agents' data.", "10. Testing on this phone."):
    if said not in texts_src:
        problems.append("Help, the privacy text or the terms no longer say: %s" % said)
if 'android:allowBackup="false"' not in manifest_code:
    problems.append("the manifest allows backup, so the app's storage would leave the phone in "
                    "a Google backup while Help says nothing does")

# --- 20. the app cannot close before its first frame over the colour of the clock --------------
#
# Window.getInsetsController() reaches through the decor view, which does not exist until
# setContentView() or getDecorView() has run; Theme.apply() runs before either, and on Android
# 11 and later the platform dereferenced null there at every opening of 2.1.5 to 2.3.0. The
# controller is asked for through the decor view, and the cosmetic call is caught.
theme_src = code(read("Theme.java"))
for name in sorted(os.listdir(src)):
    if name.endswith(".java") and re.search(r"\.getInsetsController\(\)", code(read(name))):
        problems.append("%s calls Window.getInsetsController(), which is a crash before the "
                        "first frame on Android 11 and later when no decor view exists yet; ask "
                        "getDecorView().getWindowInsetsController() instead" % name)
bar_icons = re.search(r'private static void setBarIcons\(Window window, boolean dark\) \{(.*?)\n    \}',
                      theme_src, re.S)
if not bar_icons or "window.getDecorView().getWindowInsetsController()" not in bar_icons.group(1):
    problems.append("Theme.setBarIcons() does not go through the decor view for the insets "
                    "controller")
apply_fn = re.search(r'static void apply\(Activity activity\) \{(.*?)\n    \}', theme_src, re.S)
if not apply_fn or not re.search(r"try \{\s*setBarIcons\(window, dark\);\s*\} catch \(Throwable",
                                 apply_fn.group(1)):
    problems.append("Theme.apply() lets a failure in the bar-icon colouring end the process")

# --- 21. a recording under the screenshot's rule; an icon from the registry and nowhere else --
#
# phone record is a screenshot held open for up to a minute, so its rule has to be the
# screenshot's, held for the whole minute: it starts through onlyOnScreen, and a loop on the
# phone re-reads the front activity once a second and stops the recording the moment it is
# another app's. The file lives in the shell user's own scratch directory and is deleted on
# every path out. The extension icons are the registry's own, fetched over https from that one
# host with redirects refused and a size cap, and nothing in the APK is anybody's logo.
broker = code(read("PhoneBroker.java"))
if not re.search(r'case "record":\s*\n\s*return forAllowed\(', broker):
    problems.append("phone record is not restricted to packages the bridge installed")
record_fn = re.search(r'private int record\((.*?)\n    \}', broker, re.S)
record_body = record_fn.group(1) if record_fn else ""
for must in ('onlyOnScreen(pkg, recording)', 'FRONT + "; "', 'kill -2 $p',
             'screenrecord --time-limit', 'Math.min(LONGEST_RECORDING_S',
             'reply.watch(process)', 'Workspace.quit(process)'):
    if must not in record_body:
        problems.append("phone record lacks: %s" % must)
if record_body.count('adbLines("shell", "rm -f " + onPhone)') < 2:
    problems.append("phone record leaves the recording on the phone on one of its paths")
longest = re.search(r'static final int LONGEST_RECORDING_S = (\d+);', broker)
if not longest or not 1 <= int(longest.group(1)) <= 180:
    problems.append("LONGEST_RECORDING_S is missing or beyond screenrecord's own limit of 180 s")
if not re.search(r'static final String RECORDING_ON_PHONE = "/data/local/tmp/[^"/]+\.mp4";',
                 broker):
    problems.append("the recording is written somewhere other than the shell user's own "
                    "scratch directory")
ops = re.search(r'static final String\[\] OPS = \{(.*?)\};', broker, re.S)
if not ops or '"record"' not in ops.group(1):
    problems.append("record is not in OPS, so the op list and the switch disagree")
if "phone record <package> <out.mp4> [seconds]" not in broker:
    problems.append("phone help does not describe record")
phone_py = open(app + "/app/assets/pocketide-phone.py").read()
tools_sh = open(app + "/app/assets/pocketide-tools.sh").read()
if "phone record <package> <out.mp4> [seconds]" not in phone_py:
    problems.append("the phone command's own help does not describe record")
if "record" not in tools_sh.split("phone screenshot / ")[-1][:20]:
    problems.append("pocketide-tools.sh's summary of the phone command leaves record out")

icons = code(read("Icons.java"))
if 'static final String HOST = "open-vsx.org";' not in icons:
    problems.append("icons are fetched from a host other than the registry's")
allowed_fn = re.search(r'static boolean allowed\(String url\) \{(.*?)\n    \}', icons, re.S)
if (not allowed_fn or '"https".equals(parsed.getProtocol())' not in allowed_fn.group(1)
        or 'HOST.equalsIgnoreCase(parsed.getHost())' not in allowed_fn.group(1)):
    problems.append("Icons.allowed() does not insist on https and the registry's host")
load_fn = re.search(r'static void load\((.*?)\n    \}', icons, re.S)
if not load_fn or not re.search(r'if \(!allowed\(url\)[^\n]*return;', load_fn.group(1)):
    problems.append("Icons.load() goes to the network before checking the URL")
if "setInstanceFollowRedirects(false)" not in icons:
    problems.append("an icon fetch follows redirects, which could leave the registry's host")
if not re.search(r'if \(buffer\.size\(\) \+ read > LARGEST_BYTES\) return null;', icons):
    problems.append("an icon fetch is not capped in size")
if "inSampleSize" not in icons:
    problems.append("an icon is decoded at the size the publisher uploaded, not the size a row needs")
for folder in sorted(os.listdir(app + "/app/res")):
    if not folder.startswith("drawable"):
        continue
    for name in sorted(os.listdir(app + "/app/res/" + folder)):
        if not (name.startswith("ic_") or name.startswith("splash") or name == "tux.png"):
            problems.append("a drawable that is not an interface icon, the app's own splash or "
                            "Tux: %s/%s" % (folder, name))
ui = code(read("Ui.java"))
row_class = re.search(r'static final class Row extends LinearLayout \{(.*?)\n    \}', ui, re.S)
if (not row_class or "pictured = true;" not in row_class.group(1)
        or not re.search(r'void setState\(int colour\) \{\s*if \(pictured\) return;',
                         row_class.group(1))):
    problems.append("a state tint would colour an extension's own icon")
notices = open(app + "/app/assets/open-source-notices.txt").read()
for said in ("files.icon", "Icons.java", "draws no company's product mark"):
    if said not in notices:
        problems.append("the notices no longer say where the extension icons come from: %s" % said)
texts_src = code(read("Texts.java"))
for said in ("Is this the same Visual Studio Code as on a computer?",
             "What exactly can be built and tested here?",
             "How does the agent see what it built? Screenshots, recordings, the browser.",
             "How do I give the agent a file or a photo from the phone?",
             "How do I sign in to an agent, and how does the sign-in get back here?",
             "codex login --device-auth", "Paste code here if prompted",
             "That includes Flutter and React Native",
             "Windows or Linux PC cannot build or install an ",
             "Unity and Unreal are their own programs beside Visual ",
             "the copy on the phone is the one the agent resumes from",
             "How does this compare with Termux, VSCodroid, AndroidIDE and the cloud apps?",
             "ceiling of 32 helper processes", "Disable child process restrictions",
             "This app is the second "):
    if said not in texts_src:
        problems.append("Help no longer says: %s" % said)

# --- 22. a production surface: no record on any screen, plain words for a failed connection --
#
# The crash note exists for one button on the recovery screen and nothing else: no row on Home
# or in Settings shows it, the recovery screen does not print it, and a screen that has been
# drawn deletes it. A request that fails is explained in one sentence chosen by its cause,
# after a check for a connection at all, and the editor's own words about a failed install
# are never mistaken for a network failure.
for name in sorted(os.listdir(src)):
    if name.endswith(".java") and re.search(r'\b(Crash|Boot)\.', code(read(name))):
        problems.append("%s still reaches for the crash note or the recovery screen, which "
                        "are gone: a failure is Android's own message now" % name)
if os.path.exists(src + "Crash.java") or os.path.exists(src + "Boot.java"):
    problems.append("the crash note or the recovery screen is back")
network = code(read("Network.java"))
explain = re.search(r'static String explain\(Context context, String what, Throwable failure\) \{(.*?)\n    \}',
                    network, re.S)
if not explain:
    problems.append("Network.explain() is missing")
else:
    body = explain.group(1)
    first = body.find("if (!online(context))")
    if first < 0 or first > body.find("UnknownHostException"):
        problems.append("Network.explain() does not check for a connection before guessing")
    for kind in ("UnknownHostException", "SocketTimeoutException", "SSLException", "ConnectException"):
        if kind not in body:
            problems.append("Network.explain() has no sentence for " + kind)
agents_src = code(read("AgentsPane.java"))
if agents_src.count('Network.explain(host, "The extension registry"') < 2:
    problems.append("a registry failure on the Agents screen is shown as a raw exception")
if "throw new IllegalStateException(output.toString().trim())" not in agents_src:
    problems.append("the editor's own install failure would be explained as a network failure")
strings_xml = open(app + "/app/res/values/strings.xml").read()
for tag in ("tagline", "tagline_short"):
    if '<string name="%s">Agentic development on your phone' % tag not in strings_xml:
        problems.append("the %s no longer says what the app is for" % tag)
if "hidden in Recents" not in code(read("SettingsPane.java")):
    problems.append("the lock row does not say the app is hidden in Recents while it is on")
texts_src = code(read("Texts.java"))
for said in ("GitHub Releases page",):
    if said not in texts_src:
        problems.append("Help or the privacy text no longer says: %s" % said)

# --- 23. Android's ceiling on helper processes, lifted only the documented way, only when asked --
#
# The change is a system setting made through the app's own adb: it exists only behind the
# owner's tap, only on Android 12 and later, only with the phone paired, and it is read back
# rather than believed. The same row puts it back with the exact reverse.
settings_src = code(read("SettingsPane.java"))
ceiling = re.search(r'if \(Build\.VERSION\.SDK_INT >= 31 && ready && Phone\.supported\(\)\) \{(.*?)\n        \}',
                    settings_src, re.S)
if not ceiling or '"Android\'s limit on helper processes"' not in ceiling.group(1) \
        or "offerProcessLimit(lifted)" not in ceiling.group(1):
    problems.append("the process-limit row is not confined to Android 12 and later with a phone "
                    "that can pair")
limit_fn = re.search(r'private void offerProcessLimit\(final boolean lifted\) \{(.*?)\n    \}',
                     settings_src, re.S)
limit_body = limit_fn.group(1) if limit_fn else ""
for must in ("if (!Phone.paired(host))", "if (!WorkspaceService.editorRunning())",
             "Dialogs.confirm(host,",
             "settings put global settings_enable_monitor_phantom_procs false",
             "device_config set_sync_disabled_for_tests persistent",
             "device_config put activity_manager max_phantom_processes 2147483647",
             "settings delete global settings_enable_monitor_phantom_procs",
             "device_config delete activity_manager max_phantom_processes",
             "device_config set_sync_disabled_for_tests none",
             "settings get global settings_enable_monitor_phantom_procs",
             "Prefs.PHANTOM_LIFTED"):
    if must not in limit_body:
        problems.append("lifting Android's process limit lacks: %s" % must)
if 'Phone.run(host, "shell"' not in limit_body:
    problems.append("the process limit is not changed through the app's own adb")
broker_ops = re.search(r'static final String\[\] OPS = \{(.*?)\};', code(read("PhoneBroker.java")), re.S)
if broker_ops and ("device_config" in broker_ops.group(1) or '"shell"' in broker_ops.group(1)):
    problems.append("the phone bridge would let an agent change system settings")

# --- 24. the chats screen, the companion it opens the editor through, and the cloud path ------
#
# The chats are read from each agent's own storage and deleted only with the editor closed;
# opening one is a request file the companion extension acts on, once, in a terminal. The
# companion is two source files packaged by the build, installed by the editor script once per
# build. GitHub's command line is pinned like everything else the tools script downloads.
chats_src = code(read("Chats.java"))
for must in (".claude/projects", ".codex/sessions", "kilocode.kilo-code/tasks",
             '"claude --resume " + id', '"codex resume " + id', "Workspace.delete(chat.path)"):
    if must not in chats_src:
        problems.append("Chats no longer reads or resumes the agents' own storage: %s" % must)
if re.search(r'new (java\.io\.)?(FileOutputStream|FileWriter|PrintWriter|RandomAccessFile)\b|Files\.write', chats_src):
    problems.append("Chats writes into the agents' storage, which it must only read")
screen = code(read("ChatsActivity.java"))
open_fn = re.search(r'private void open\(Chats\.Chat chat\) \{(.*?)\n    \}', screen, re.S)
if (not open_fn or 'PhoneBroker.bridgeDir(this)' not in open_fn.group(1)
        or '.put("action", "terminal")' not in open_fn.group(1)
        or "WorkspaceActivity.class" not in open_fn.group(1)):
    problems.append("opening a chat does not go through the companion's inbox in the bridge")
companion = open(app + "/app/assets/companion/extension.js").read()
if "const INBOX = '/run/pocketide/editor-inbox';" not in companion \
        or "fs.unlinkSync(file)" not in companion or "if (request) act(request);" not in companion:
    problems.append("the companion does not read its inbox in /run/pocketide, or acts before "
                    "deleting a request")
if "request.action !== 'terminal'" not in companion or "sendText(request.command, true)" not in companion:
    problems.append("the companion runs something other than a terminal command")
package = open(app + "/app/assets/companion/package.json").read()
if '"activationEvents": ["onStartupFinished"]' not in package or '"publisher": "pocketide"' not in package:
    problems.append("the companion's manifest no longer activates at start-up under pocketide")
editor_sh = open(app + "/app/assets/pocketide-editor.sh").read()
if "install_companion()" not in editor_sh or "install_companion >/dev/null 2>&1 || true" not in editor_sh \
        or 'cmp -s "$stamp" "$installed"' not in editor_sh:
    problems.append("the editor script does not install the companion once per build")
build_sh = open(app + "/build.sh").read()
if "package_companion" not in build_sh or "pocketide-companion.vsix" not in build_sh:
    problems.append("the build does not package the companion")
workspace_src = code(read("Workspace.java"))
for asset in ("pocketide-companion.vsix", "pocketide-companion.stamp", "cloud-flutter-android.yml",
              "cloud-react-native-android.yml", "cloud-ios.yml", "cloud-x86-64.yml"):
    if '"%s"' % asset not in workspace_src:
        problems.append("Workspace.SCRIPTS does not carry %s into Linux" % asset)
    if asset.startswith("cloud-") and not os.path.isfile(app + "/app/assets/" + asset):
        problems.append("the template %s is missing" % asset)
tools_sh = open(app + "/app/assets/pocketide-tools.sh").read()
gh = re.search(r'GH_VERSION="([\d.]+)"\nGH_URL="https://github\.com/cli/cli/releases/download/v\$\{GH_VERSION\}/gh_\$\{GH_VERSION\}_linux_arm64\.tar\.gz"\nGH_SHA256="([0-9a-f]{64})"', tools_sh)
if not gh:
    problems.append("GitHub's command line is not pinned to a version and a SHA-256 from GitHub's own release")
if 'fetch_pinned "$GH_URL" "$archive" "$GH_SHA256"' not in tools_sh or "  gh)          install_gh ;;" not in tools_sh:
    problems.append("gh is not downloaded through fetch_pinned, or the layer is not dispatched")
if 'echo "gh=$gh"' not in tools_sh or '"yes".equals(values.get("gh"))' not in code(read("Tools.java")):
    problems.append("the app cannot tell whether gh is installed")
if 'offerTools("gh", "GitHub\'s command line"' not in settings_src:
    problems.append("Settings offers no way to install GitHub's command line")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
