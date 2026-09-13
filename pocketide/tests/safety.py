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

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
