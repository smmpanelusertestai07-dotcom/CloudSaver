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

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
