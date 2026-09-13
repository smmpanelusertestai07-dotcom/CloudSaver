#!/usr/bin/env python3
"""The bottom bar and the editor's own sizing, against the specs they claim to follow.

Two different kinds of claim are checked here.

The bar claims to be Material 3's navigation bar. That is a public specification with numbers
in it, and a bar that says it follows one while using its own numbers is the sort of thing
nobody notices in review and everybody notices as "this app feels off". Three to five
destinations, 80 dp tall, 24 dp icons, and every destination reachable by a 48 dp target.

The editor claims to size itself to the phone. The failure that claim hides is a constant: the
release before this one gave every phone ever made window.zoomLevel 1.5, which on a 360 dp
screen left Visual Studio Code 275 effective pixels to lay itself out in. A constant looks
exactly like a calculation from the outside, so the check is that the value actually depends on
the screen.
"""
import re
import sys

app = sys.argv[1]
src = app + "/app/src/com/pocketide/"


def code(name):
    text = open(src + name).read()
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//.*$', '', text, flags=re.M)


problems = []
shell = code("Shell.java")
main = code("MainActivity.java")
screen = code("Screen.java")
service = code("WorkspaceService.java")

# --- Material 3's navigation bar ------------------------------------------------------------
tabs = re.findall(r'new Shell\.Tab\(', main)
if not 3 <= len(tabs) <= 5:
    problems.append("%d destinations on the bottom bar; Material 3 specifies three to five, "
                    "and anything more belongs inside one of them" % len(tabs))

for name, expected in (("NAV_BAR_DP", 80), ("TOP_BAR_DP", 64), ("ICON_DP", 24),
                       ("INDICATOR_W_DP", 64), ("INDICATOR_H_DP", 32)):
    found = re.search(name + r'\s*=\s*(\d+)', shell)
    if not found:
        problems.append("Shell does not define %s, so the bar is not built to a spec at all"
                        % name)
    elif int(found.group(1)) != expected:
        problems.append("Shell.%s is %s; Material 3's navigation bar specifies %d"
                        % (name, found.group(1), expected))

# Every destination carries a label AND a spoken description. An icon row with no labels is a
# guessing game for anyone who has not used the app, and these icons are not universal symbols.
labelled = re.findall(r'new Shell\.Tab\("([^"]+)",\s*R\.drawable\.\w+,\s*\n?\s*"([^"]+)"', main)
if len(labelled) != len(tabs):
    problems.append("%d of %d destinations lack a label or a screen-reader description"
                    % (len(tabs) - len(labelled), len(tabs)))
if "setContentDescription(tab.description)" not in shell:
    problems.append("the bar never sets a content description, so a screen reader announces "
                    "nothing for any destination")

# --- the editor's size is worked out, not chosen ---------------------------------------------
if "Math.log" not in screen:
    problems.append("Screen does not compute the zoom; a constant looks identical to a "
                    "calculation from outside and the last release shipped one")
if "MIN_EFFECTIVE_DP" not in screen:
    problems.append("Screen has no minimum effective width, which is the number the whole "
                    "calculation exists to hold")
if "fontScale" not in screen:
    problems.append("Screen ignores Android's font scale, so a phone set to large text gets an "
                    "editor that does not follow it")
if "widthDp" not in screen:
    problems.append("Screen never reads the screen's width")

# The service must ask Screen rather than read the preference, or the automatic value is
# computed, shown in Settings, and then not used.
if "Screen.zoomTenths" not in service or "Screen.layout" not in service:
    problems.append("WorkspaceService does not ask Screen for the zoom and layout, so whatever "
                    "Settings shows is not what the editor is started with")
if re.search(r'Prefs\.EDITOR_ZOOM', service):
    problems.append("WorkspaceService still reads EDITOR_ZOOM directly, bypassing the automatic "
                    "value")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
