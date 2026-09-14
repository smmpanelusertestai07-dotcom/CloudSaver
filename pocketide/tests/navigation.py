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
import os
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

# --- and the page scrolls UNDER it ---------------------------------------------------------------
#
# A capsule the page stops at is a slab with round corners. What makes it float is the page
# passing underneath: the bar is laid over the page, the page is padded by the bar's measured
# height so its last row can be scrolled clear, and a ScrollView keeps drawing into that padding.
if "Gravity.BOTTOM" not in shell:
    problems.append("the bottom bar takes a row of its own under the page instead of being laid "
                    "over it, so nothing ever passes beneath the glass")
if "padUnderBar" not in shell or "setClipToPadding(false)" not in shell:
    problems.append("the frame does not pad the page by the bar's height and let it draw into "
                    "the padding, so either the last row hides under the bar or nothing "
                    "scrolls under it")
if re.search(r'holder\.setBackgroundColor\(Ui\.(bg|card)', shell):
    problems.append("the bar's holder paints a solid colour across the screen, so the page "
                    "cannot show around the capsule and the bar is a slab again")
if "FLOATING_ALPHA_LIGHT" not in code("Ui.java"):
    problems.append("the floating glass has no opacity constants, so tests/contrast.py cannot "
                    "measure the bar's words over what is really behind them")
if "slot = new FrameLayout" in main:
    problems.append("MainActivity wraps the pane in a FrameLayout before handing it to the "
                    "frame, which hides the ScrollView the frame has to pad")

# --- the editor action says what it is -----------------------------------------------------------
#
# A bare glyph in the top-right corner read as decoration: an owner called it "the code-looking
# thing" and did not know it opened anything. A tonal circle around the same glyph did not fix
# that. A word beside it does.
if "tonalButton(" not in main or '"Editor"' not in main:
    problems.append("the top bar's editor action is not a labelled tonal button; a glyph with "
                    "no word beside it is the control an owner reported as decoration")

# --- the editor's own toolbar --------------------------------------------------------------------
workspace = code("WorkspaceActivity.java")
if re.search(r'MATCH_PARENT,\s*Ui\.dp\(this,\s*Shell\.NAV_BAR_DP\)', workspace):
    problems.append("the editor's toolbar is laid out at a FIXED 64 dp, which clips its labels "
                    "at a large font scale exactly as the navigation bar once did")
if "KEYCODE_F1" not in workspace:
    problems.append("the Commands button does not send F1. Ctrl+Shift+P through the WebView "
                    "arrived as nothing on the owner's phone; F1 is the palette's other "
                    "binding and a single unmodified key")
if "ic_cursor" not in workspace or not os.path.exists(app + "/app/res/drawable/ic_cursor.xml"):
    problems.append("the Cursor button does not use the pointer icon")
if "setSupportZoom(true)" not in workspace or "setBuiltInZoomControls(true)" not in workspace:
    problems.append("pinch zoom is off in the editor; the owner asked for it by name")
if "user-scalable=yes" not in workspace:
    problems.append("the workbench's own viewport forbids scaling and nothing loosens it, so "
                    "the zoom setting alone does nothing")
if "setTextZoom(100)" not in workspace:
    problems.append("the WebView is left at its default text zoom, which applies the phone's "
                    "font scale a second time on top of the zoom Screen.java already computed")

# --- the set-up transcript scrolls ---------------------------------------------------------------
if "Ui.innerScroll" not in code("SetupActivity.java"):
    problems.append("the set-up transcript is a plain ScrollView inside the page's ScrollView, "
                    "which never moves: the page takes every drag first")

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

# --- every screen handles the system bars, not just this one -----------------------------------
#
# targetSdk 35 means Android 15 draws EVERY window edge to edge whether it asks to or not. The
# main screen was fixed after an owner reported the clock over its title -- and the fix went
# only there. Theme.fitContent() was written for the editor and had zero call sites, so the
# editor's toolbar still sat under the gesture handle, and Set up and About still drew their
# back bars under the clock.
import os as _os
SCREENS = {
    "MainActivity.java": "Shell.frame",      # reaches Theme.fitBars through the shell
    "WorkspaceActivity.java": "Theme.fitScreen",
    "SetupActivity.java": "Theme.fitScreen",
    "HelpActivity.java": "Theme.fitScreen",
}
for name, expected in SCREENS.items():
    text = code(name)
    if expected not in text:
        problems.append("%s never reaches the inset handling (%s). On Android 15 its window "
                        "starts at y=0 and ends behind the gesture bar." % (name, expected))
if "static void fitScreen" not in code("Theme.java"):
    problems.append("Theme has no fitScreen, so a screen with no bar of its own has no way to "
                    "ask for the insets it needs")

# --- a theme change while the app is on screen is handled --------------------------------------
#
# Every activity declares uiMode in configChanges, so Android does not recreate them when the
# phone's Dark theme is flipped from the quick-settings tile. Nothing overrode
# onConfigurationChanged, so the app went on painting the old palette while every other app
# flipped -- and setSystemBarsAppearance is sticky, so the clock stayed the wrong colour too.
manifest = open(app + "/app/AndroidManifest.xml").read()
declares_uimode = len(re.findall(r'configChanges="[^"]*uiMode', manifest))
handlers = 0
for name in sorted(_os.listdir(src)):
    if not name.endswith("Activity.java"):
        continue
    # The BODY, not the file. Every activity calls Theme.apply in onCreate, so searching the
    # whole file finds that one and passes an activity whose handler does nothing -- which is
    # what this check caught itself doing before it was tightened.
    body = re.search(r'onConfigurationChanged\([^)]*\)\s*\{(.*?)\n    \}', code(name), re.S)
    if body and "Theme.apply(this)" in body.group(1):
        handlers += 1
if declares_uimode and handlers < declares_uimode:
    problems.append("%d activities opt out of being recreated for a theme change but only %d "
                    "re-apply the theme when one happens, so the rest keep painting the old "
                    "palette -- and the system bar icons stay the wrong colour, because "
                    "setSystemBarsAppearance is sticky per window"
                    % (declares_uimode, handlers))

# --- a control that is pressed shows that it was ------------------------------------------------
#
# RippleDrawable with no explicit mask masks the ripple against the composite of its content
# layers, and nearly every call passes a TRANSPARENT GradientDrawable as that content -- which
# multiplies the ripple away entirely. Every settings row, every permission row and the back
# button on two screens did nothing visible when pressed.
uisrc = code("Ui.java")
tappable = re.search(r'static RippleDrawable tappable\(.*?\n    \}', uisrc, re.S)
if not tappable:
    problems.append("Ui.tappable cannot be read")
elif re.search(r'new RippleDrawable\(\s*ColorStateList[^;]*?,\s*base,\s*null\s*\)',
               tappable.group(0), re.S):
    problems.append("Ui.tappable passes a null ripple mask. With a transparent content layer "
                    "-- which is what almost every call site passes -- that produces no ripple "
                    "at all, so no tappable row in the app responds to being pressed.")
elif "getCornerRadius" not in tappable.group(0):
    problems.append("Ui.tappable does not take its mask's radius from the base, so a rounded "
                    "row gets a square ripple")

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


# --- Back on Android 13 and later -------------------------------------------------------------
#
# The manifest opts this app into predictive back, and an app that has opted in never has
# onBackPressed() called on Android 13 or later. Every screen with its own idea of Back must
# register it through Back as well, or a new phone closes the app where an old one goes Home.
manifest_text = open(app + "/app/AndroidManifest.xml").read()
if 'enableOnBackInvokedCallback="true"' in manifest_text:
    if (not os.path.exists(src + "Back.java")
            or "registerOnBackInvokedCallback" not in code("Back.java")):
        problems.append("the manifest opts in to predictive back but nothing registers an "
                        "OnBackInvokedCallback, so every onBackPressed() is dead on Android 13+")
    for name in ("MainActivity.java", "WorkspaceActivity.java"):
        text = code(name)
        if "onBackPressed()" in text and "Back.register(" not in text:
            problems.append("%s overrides onBackPressed() without registering through Back; on "
                            "Android 13 and later that override never runs" % name)

# --- the keyboard is an inset too ------------------------------------------------------------
#
# On Android 15 an app drawn edge to edge is not resized for the keyboard by adjustResize
# alone; fitBars has to read the ime() inset or the search box and the editor's toolbar sit
# under the keyboard.
theme = code("Theme.java")
fit = re.search(r'static void fitBars\(.*?\n    \}', theme, re.S)
if not fit or "WindowInsets.Type.ime()" not in fit.group(0):
    problems.append("Theme.fitBars ignores the keyboard inset (WindowInsets.Type.ime()), so on "
                    "Android 15 the keyboard covers whatever is at the bottom of the screen")

# --- the recommended list is filled when the screen is built ---------------------------------
agents_build = re.search(r'public View build\(Activity activity\) \{(.*?)\n    \}',
                         code("AgentsPane.java"), re.S)
if not agents_build or "refreshRecommended()" not in agents_build.group(1):
    problems.append("AgentsPane.build() never calls refreshRecommended(), so the three "
                    "recommended agents are an empty card until something is installed")

# --- coming back does not throw the search away ----------------------------------------------
if "rebuildOnReturn" not in code("Pane.java"):
    problems.append("Pane has no rebuildOnReturn(), so every pane is rebuilt on every return "
                    "and Agents loses whatever was typed into its search box")
elif "public boolean rebuildOnReturn() { return false; }" not in code("AgentsPane.java"):
    problems.append("AgentsPane does not opt out of the rebuild on return, so leaving the app "
                    "for a moment empties the search box and its results")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
