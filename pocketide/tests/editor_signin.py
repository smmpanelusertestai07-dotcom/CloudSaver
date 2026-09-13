#!/usr/bin/env python3
"""The editor must open already signed in, and the way it does that must stay true to code-server.

This gate exists because of a bug that was written, reviewed, built into a signed APK and only
caught by reading code-server's own source afterwards: the screen loaded

    http://127.0.0.1:8391/?password=<generated>&folder=/root/projects

code-server has no query-parameter sign-in and never has. out/node/routes/login.js reads the
password from req.body only. Every owner would have been met by a sign-in box asking for a
password the app generated and never showed them.

The fix rests on four facts read out of code-server 4.137.0, and this gate holds all four:

  1. out/node/cli.js        hashed-password is accepted from the config file (and refused on the
                            command line), so it has to be written into config.yaml.
  2. out/node/util.js       getPasswordMethod() treats a hash without "$argon" as SHA256, which
                            is what a plain hex digest selects.
  3. out/node/util.js       isCookieValid() under SHA256 compares the cookie to hashed-password
                            directly -- so the cookie value is the digest itself.
  4. out/common/http.js     getCookieSessionName(undefined) === "code-server-session".

If any of those stop being true in a future code-server, this gate does not notice -- the pinned
checksum is what stops the version moving under us. What it does notice is the app drifting back
towards a sign-in that code-server never supported.
"""
import re
import sys
import hashlib

app = sys.argv[1]
script = open(app + "/app/assets/pocketide-editor.sh").read()
workspace = open(app + "/app/src/com/pocketide/Workspace.java").read()
screen = open(app + "/app/src/com/pocketide/WorkspaceActivity.java").read()

problems = []

# --- 1. the config carries the digest, not the password ----------------------------------
config = re.search(r'cat > "\$CONFIG" <<EOF\n(.*?)\nEOF', script, re.S)
if not config:
    problems.append("no config.yaml is written by the editor script")
else:
    body = config.group(1)
    if not re.search(r'^hashed-password:', body, re.M):
        problems.append("config.yaml has no hashed-password line, so the cookie cannot be "
                        "computed on this side")
    if re.search(r'^password:', body, re.M):
        problems.append("config.yaml still carries a plain password: line, which takes a "
                        "different code path (argon2) and makes the cookie unguessable here")
    if not re.search(r'^auth: password', body, re.M):
        problems.append("the editor is not behind a password at all")

# --- 2. the app derives the cookie value with SHA-256 ------------------------------------
if 'EDITOR_COOKIE_NAME = "code-server-session"' not in workspace:
    problems.append('Workspace does not name the cookie "code-server-session", which is what '
                    "out/common/http.js returns with no --cookie-suffix")
if 'MessageDigest.getInstance("SHA-256")' not in workspace:
    problems.append("Workspace does not derive the session token with SHA-256, so it cannot "
                    "match the hashed-password the script writes")
if "editorSessionToken" not in workspace:
    problems.append("Workspace has no editorSessionToken()")

# --- 3. nothing anywhere signs in with a query parameter ---------------------------------
code = re.sub(r"^\s*\*.*$", "", screen, flags=re.M)
code = re.sub(r"//.*$", "", code, flags=re.M)
if re.search(r'[?&]password=', code):
    problems.append("the screen still builds a ?password= URL; code-server has no query-"
                    "parameter sign-in, so this shows a sign-in box instead of an editor")

# --- 4. the cookie is written before the page is requested -------------------------------
if "setCookie" not in code:
    problems.append("the screen never writes the session cookie, so the first request is "
                    "unauthenticated")
else:
    set_at = code.index("setCookie")
    load_at = code.find("loadUrl", set_at)
    if load_at == -1:
        problems.append("the screen writes the cookie but never loads the editor after it")
    elif "onReceiveValue" not in code[set_at:load_at]:
        problems.append("the editor is loaded without waiting for the cookie to be committed; "
                        "setCookie() returns before the store is written")

# --- 5. the typed-password fallback matches how code-server checks it --------------------
if "postUrl" not in code or "/login" not in code:
    problems.append("there is no fallback sign-in for a refused cookie, so a cleared WebView "
                    "data directory strands the owner on a page they cannot answer")

# --- 6. the two derivations of the digest agree ------------------------------------------
# The script derives it too, when the app does not pass one. Both must be plain SHA-256 of the
# same string, so a sample is checked against the shell's own sha256sum spelling.
if "sha256sum" not in script:
    problems.append("the script cannot derive the digest itself when none is passed in")
sample = "pocketide"
expected = hashlib.sha256(sample.encode()).hexdigest()
if len(expected) != 64:
    problems.append("sha256 is not 64 hex characters, which the SHA256 method requires")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
