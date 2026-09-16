#!/usr/bin/env python3
"""phone: test the app you built, on this phone, through PocketIDE's bridge.

  phone devices                    is the phone paired and connected
  phone install <app.apk>          install an APK built under ~/projects (test APKs too)
  phone launch <package>           open it; it comes to the front of the phone
  phone stop <package>             force-stop it
  phone clear <package>            clear its data
  phone uninstall <package>
  phone instrument <test package> [runner]   run its instrumented tests (am instrument -w -r)
  phone log <package> [-d]         its log, by process id; -d dumps and returns
  phone screenshot <package> <out.png>       only while that package is on the screen
  phone tap <package> <x> <y>      a tap, only while that package is on the screen
  phone text <package> <text>      typed text, same rule
  phone key <package> <KEYCODE>    a key, same rule (KEYCODE_BACK, KEYCODE_HOME ...)
  phone allowed                    the packages this bridge may touch

Only packages installed through phone install, and only from ~/projects. The phone's own
adb access never enters this Linux: no shell, no other app, no files, no device details.
There is no adb in this Linux at all; the app's own runs in a root of its own, outside it.

install and launch need no pairing at all: Android asks you to confirm each install on its
own screen, and the app opens from there while PocketIDE is on the screen. log, screenshot,
tap, text, key, instrument and uninstall need the phone paired -- PocketIDE: Settings > The
computer > Test on this phone. The bridge answers while the editor is running.
"""
import json
import os
import socket
import sys

SOCK = "/run/pocketide/phone.sock"

args = sys.argv[1:]
if not args or args[0] in ("-h", "--help", "help"):
    print(__doc__.strip())
    sys.exit(0)
request = {"op": args[0], "args": args[1:], "cwd": os.getcwd()}
link = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
try:
    link.connect(SOCK)
except OSError:
    print("The phone bridge is not answering. Open the editor from PocketIDE, and pair the "
          "phone under Settings > The computer > Test on this phone.", file=sys.stderr)
    sys.exit(2)
link.sendall((json.dumps(request) + "\n").encode("utf-8"))
link.shutdown(socket.SHUT_WR)
code = 1
with link.makefile("rb") as stream:
    for raw in stream:
        line = raw.decode("utf-8", "replace")
        if line.startswith("\x1e"):
            try:
                code = int(line[1:].strip())
            except ValueError:
                code = 1
            break
        sys.stdout.write(line)
        sys.stdout.flush()
sys.exit(code)
