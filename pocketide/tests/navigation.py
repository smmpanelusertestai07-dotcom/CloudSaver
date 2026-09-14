#!/usr/bin/env python3
"""The bottom bar and the editor's own sizing, against the specs they claim to follow.

Two different kinds of claim are checked here.

The bar claims to be Material 3 Expressive's navigation bar. That is a public specification
with numbers in it, and a bar that says it follows one while using its own numbers is the sort
of thing nobody notices in review and everybody notices as "this app feels off". Three to five
destinations, 64 dp tall, 24 dp icons, a 56 x 32 indicator, and every destination reachable by
a 48 dp target.

It also checks the bug a screenshot caught: the bar was FIXED at 64 dp while the column inside
it came to about 68, so the bottom of every label on the screen was sliced off. A height that
cannot grow is the bug, not the number -- so the bar has to hold 64 as a minimum and let its
content decide the rest, which is also what makes it survive a phone set to large text.

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

for name, expected in (("NAV_BAR_DP", 64), ("TOP_BAR_DP", 64), ("ICON_DP", 24),
                       ("INDICATOR_W_DP", 56), ("INDICATOR_H_DP", 32)):
    found = re.search(name + r'\s*=\s*(\d+)', shell)
    if not found:
        problems.append("Shell does not define %s, so the bar is not built to a spec at all"
                        % name)
    elif int(found.group(1)) != expected:
        problems.append("Shell.%s is %s; Material 3's navigation bar specifies %d"
                        % (name, found.group(1), expected))

# --- the bar can grow, and the labels cannot be cut off ---------------------------------------
if "setMinimumHeight" not in shell:
    problems.append("the bottom bar sets no minimum height, so either it is fixed -- which is "
                    "what sliced the bottom off every label -- or it has no spec height at all")
for constant, which in (("NAV_BAR_DP", "bottom"), ("TOP_BAR_DP", "top")):
    if re.search(r'MATCH_PARENT,\s*Ui\.dp\(host,\s*' + constant + r'\)', shell):
        problems.append("the %s bar is laid out at a FIXED %s height. Its content is taller "
                        "than that at a large font scale, and the overflow is silently clipped "
                        "rather than reported -- which is exactly how every destination's label "
                        "lost its descenders." % (which, constant))

# --- it is a floating bar, not a slab ---------------------------------------------------------
#
# Claimed on screen and in the commit, so it is checked: inset from the sides, lifted off the
# gesture bar, rounded, and lit. A "rounded" bar whose corners are square where the screen ends
# is the thing this replaced.
for name, why in (("BAR_SIDE_DP", "the gutter that makes it float rather than span"),
                  ("BAR_LIFT_DP", "the lift that keeps it off the gesture bar"),
                  ("BAR_RADIUS_DP", "the corner radius")):
    if name not in shell:
        problems.append("Shell does not define %s -- %s" % (name, why))
if "floatingGlass" not in shell:
    problems.append("the bottom bar does not use the floating glass surface, so it is a flat "
                    "slab wearing a rounded corner")
if "setClipToOutline(true)" not in shell:
    problems.append("the bar is not clipped to its own outline, so a ripple at either end "
                    "squares the capsule off when it is pressed")

# --- the tagline is somewhere an owner can read it ---------------------------------------------
#
# It existed only on the opening frame, for six-tenths of a second, which is a flicker rather
# than a tagline. The top bar's subtitle slot is where it lives now.
strings = open(app + "/app/res/values/strings.xml").read()
if "tagline_short" not in strings:
    problems.append("there is no short tagline string for the top bar")
if "R.string.tagline_short" not in shell:
    problems.append("the top bar does not show the tagline, so the only place it appears is the "
                    "opening frame, for six-tenths of a second")

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

# --- nothing slow on the thread that draws -----------------------------------------------
#
# Starting PRoot and running a script inside it takes seconds. On the drawing thread that is an
# Application Not Responding dialog, and Settings is the one screen an owner opens when
# something is already going wrong -- freezing it there is the worst possible moment.
#
# This shipped once: the Settings screen called Tools.read() straight from build() to fill in
# whether the browser was installed. The check below finds every call that has to be on a
# background thread and confirms it is inside one.

SLOW = ("Workspace.start(", "Tools.read(", "Tools.install(", "Tools.smokeTest(",
        "Workspace.sizeBytes(", "Workspace.install(", "Registry.search(", "Registry.details(")


def thread_spans(text):
    """Character ranges covered by a `new Thread(...)` construction, by brace counting."""
    spans = []
    at = 0
    while True:
        at = text.find("new Thread(", at)
        if at < 0:
            return spans
        depth, i, started = 0, at, False
        while i < len(text):
            if text[i] == "(":
                depth += 1
                started = True
            elif text[i] == ")":
                depth -= 1
                if started and depth == 0:
                    break
            i += 1
        spans.append((at, i))
        at = i + 1


import os
for name in sorted(os.listdir(src)):
    if not name.endswith(".java"):
        continue
    if not (name.endswith("Pane.java") or name.endswith("Activity.java")):
        continue
    text = code(name)
    spans = thread_spans(text)
    for call in SLOW:
        at = 0
        while True:
            at = text.find(call, at)
            if at < 0:
                break
            inside = any(lo <= at <= hi for lo, hi in spans)
            if not inside:
                line = text[:at].count("\n") + 1
                problems.append(
                    "%s:%d calls %s outside a background thread. It starts PRoot and waits, "
                    "which on the drawing thread is an ANR." % (name, line, call.rstrip("(")))
            at += len(call)

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
