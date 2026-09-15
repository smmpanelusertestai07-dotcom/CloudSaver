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
    at = exits.find(reason)
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

# --- 11. the app cannot vanish at startup without saying why ---------------------------------
boot = code(read("Boot.java")) if os.path.exists(src + "Boot.java") else ""
if "starting(" not in boot or "reached(" not in boot or "failing(" not in boot:
    problems.append("Boot does not count launches, so an app that dies before drawing has "
                    "nothing to notice that it did")
if ".commit()" not in boot:
    problems.append("Boot writes its launch count with apply(); a process killed a moment "
                    "later never gets it to disk, which is the only case it exists for")
if "Boot.starting" not in main or "Boot.failing" not in main or "Boot.reached" not in main:
    problems.append("MainActivity does not use the startup guard, so a launch that dies "
                    "silently dies silently again on every later try")
guarded = re.search(r'catch \(Throwable failure\) \{(.{0,400}?)\n        \}', main, re.S)
if not guarded or "Boot.show" not in guarded.group(1):
    problems.append("MainActivity does not catch a failure while building its first screen, so "
                    "the first failure is still a window that closes with nothing said")

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
if "adb --version" not in check_body:
    problems.append("check() does not report whether adb is installed")
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
    # The call sites, not every mention: the by-hand instructions quote the same commands.
    for command in ("adb pair ", "adb connect "):
        calls = list(re.finditer(r'run\(service, "' + re.escape(command), phone))
        if not calls:
            problems.append("Phone never runs %s" % command.strip())
        for hit in calls:
            after = phone[hit.end():hit.end() + 20]
            if not after.startswith('" + LOOPBACK'):
                problems.append("Phone runs %s against something other than LOOPBACK"
                                % command.strip())
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
    if "ADB_SERVER_SOCKET=localfilesystem:" not in workspace:
        problems.append("Workspace.start() does not set ADB_SERVER_SOCKET, so adb's server "
                        "listens on a TCP port every app on the phone can reach")
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
    # The server is started inside start_editor and BEFORE the editor is launched, comments
    # between the test and the command allowed: after the launch it would start only when the
    # editor had already stopped.
    start_fn = re.search(r'^start_editor\(\) \{(.*?)\n\}', shell_code(editor_script), re.S | re.M)
    start_body = start_fn.group(1) if start_fn else ""
    server = re.search(r'command -v adb >/dev/null 2>&1; then\s*\n(?:\s*#.*\n)*\s*adb start-server',
                       start_body)
    launch = start_body.find('"$BIN" \\')
    if not server or launch < 0 or server.start() > launch:
        problems.append("the editor script does not start the adb server inside start_editor "
                        "before the editor is launched, so a connection the app makes dies "
                        "with the one command that made it")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
