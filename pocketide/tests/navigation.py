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


def shell_code_of(path):
    """A shell script with its comments removed, so prose cannot satisfy a check."""
    return re.sub(r'^\s*#.*$', '', open(path).read(), flags=re.M)


def code(name):
    text = open(src + name).read()
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//.*$', '', text, flags=re.M)


problems = []
shell = code("Shell.java")
main = code("MainActivity.java")
editor = code("WorkspaceActivity.java")
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
# The editor menu, and how it presses a key.
#
# Ctrl+Shift+P through the WebView's key translation arrived as nothing on the owner's phone --
# "Commands does not work" was the report -- so the rule is: one unmodified function key, sent
# as a DOM event rather than through Android's translation, and bound by the editor's own
# keybindings file. All three halves are checked, because any one of them alone is the bug.
if "menu()" not in workspace or '"Command palette"' not in workspace:
    problems.append("the editor has no menu offering the command palette, so the 29 commands "
                    "that live only there need a keyboard shortcut a phone cannot press")
press = re.search(r'private void press\(int functionKey\) \{(.*?)\n    \}', workspace, re.S)
if not press:
    problems.append("the editor menu has no press() that sends a key to the editor")
else:
    if "dispatchKeyEvent" in press.group(1):
        problems.append("the editor menu presses its key through Android's key translation, "
                        "which is the path that swallowed Ctrl+Shift+P")
    if "KeyboardEvent" not in press.group(1):
        problems.append("the editor menu does not dispatch a DOM KeyboardEvent, so the editor's "
                        "keybinding service never sees the press")
extensions = code("Extensions.java")
for binding in ('"f1", "workbench.action.showCommands"',
                '"f3", "workbench.action.terminal.toggleTerminal"',
                '"f4", "workbench.view.extensions"'):
    if binding.replace('", "', '", "') not in extensions:
        problems.append("keybindings.json does not bind %s, so the menu row that presses it "
                        "does nothing" % binding)
if "workbench.view.extension." not in extensions:
    problems.append("nothing binds a key to an agent's own panel, so an installed agent can "
                    "only be opened by finding its icon in the activity bar")
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

# The service must ask Screen for the layout rather than read the preference, or the automatic
# value is computed, shown in Settings, and then not used.
if "Screen.layout" not in service:
    problems.append("WorkspaceService does not ask Screen for the layout, so whatever Settings "
                    "shows is not what the editor is started with")
if re.search(r'Prefs\.EDITOR_ZOOM', service):
    problems.append("WorkspaceService still reads EDITOR_ZOOM directly, bypassing the automatic "
                    "value")

# --- the zoom is applied where a browser keeps it, because the editor has no zoomLevel --------
#
# The web build of the editor has no window.zoomLevel and no zoom commands: both are Electron's.
# A release wrote the setting and bound the commands, and the whole text-size feature was inert
# while three menu rows raised "command not found". The size is the page's viewport now: the
# activity names a layout width and a scale from Screen, with the wide viewport the WebView
# needs to honour a width, and nothing writes or binds the desktop-only things.
if "Screen.scale(this)" not in editor or "initial-scale=" not in editor:
    problems.append("WorkspaceActivity does not apply the zoom as the page's viewport, so the "
                    "text size worked out in Screen changes nothing on screen")
if "setUseWideViewPort(true)" not in editor:
    problems.append("the WebView is not given a wide viewport, so the layout width named in the "
                    "viewport rule is ignored and the zoom cannot take effect")
if "window.zoomLevel" in shell_code_of(app + "/app/assets/pocketide-editor.sh"):
    problems.append("the editor script still writes window.zoomLevel, which the web build "
                    "ignores; the text size lives in the WebView's viewport")
if "workbench.action.zoom" in code("Extensions.java"):
    problems.append("keybindings still bind the desktop-only zoom commands, which the web build "
                    "answers with 'command not found'")

# --- the keys and the menu come from one read, and the owner's own bindings survive ------------
if "Extensions.writeKeybindings(this)" not in editor:
    problems.append("the editor screen builds its menu from a list the keybindings were not "
                    "written from, so an agent installed since the start can open another's panel")
if code("AgentsPane.java").count("Extensions.writeKeybindings(host)") < 2:
    problems.append("installing or removing an agent does not rewrite the keybindings, so F5 to "
                    "F7 keep pointing at what was installed when the editor started")
if ": ownersBindings(file))" not in code("Extensions.java"):
    problems.append("writeKeybindings starts from nothing, so a shortcut the owner added in the "
                    "editor is thrown away at every start")
if "json.loads" not in shell_code_of(app + "/app/assets/pocketide-editor.sh") \
        or "setdefault" not in shell_code_of(app + "/app/assets/pocketide-editor.sh"):
    problems.append("write_settings writes settings.json from scratch, so a theme or font the "
                    "owner chose in the editor, and any agent's stored settings, are lost at "
                    "every start")

# --- the app's own theme and marks -------------------------------------------------------------
if "Theme_Material_Dialog_Alert" not in code("Dialogs.java"):
    problems.append("dialogs take the phone's night mode and the maker's skin instead of the "
                    "app's own light or dark")
for variant in ("values", "values-night"):
    if "android:colorAccent" not in open(app + "/app/res/" + variant + "/styles.xml").read():
        problems.append("%s/styles.xml leaves the platform accent to the wallpaper, so the "
                        "search box's cursor and selection come out in another colour" % variant)
animated = open(app + "/app/res/drawable/splash_mark_animated.xml").read()
if not re.search(r'<target android:name="root">.*?propertyName="alpha"', animated, re.S):
    problems.append("the splash fade animates alpha on a vector group, which has none, so it "
                    "never plays")
if "SDK_INT >= 31) return;" not in code("BrandFrame.java"):
    problems.append("a second splash is drawn after Android 12's own")
shortcuts_xml = open(app + "/app/res/xml/shortcuts.xml").read()
shortcut_icon = re.search(r'android:icon="@drawable/(\w+)"', shortcuts_xml)
if not shortcut_icon or "<adaptive-icon" not in open(
        app + "/app/res/drawable/" + shortcut_icon.group(1) + ".xml").read():
    problems.append("the long-press shortcut's icon is a bare glyph the launcher wraps in a "
                    "white disc")
for stray in os.listdir(app + "/app/res/drawable-nodpi"):
    if stray.startswith("logo_"):
        problems.append("a product mark ships in the app (%s); none is shown and none should be"
                        % stray)
if "listing.verified && Agents.official(listing.namespace)" not in code("AgentsPane.java"):
    problems.append("search can say 'official' about a version the registry has not verified")


# --- the phone bridge works without Developer options, as far as Android allows -----------------
#
# Wireless debugging costs an owner their Developer options. Android lets an app install another
# app and open it without any of that, so the two operations that can work unpaired do, and the
# three that cannot say so rather than failing silently.
broker_src = code("PhoneBroker.java")
if "Installer.install(service" not in broker_src or "Installer.launch(service" not in broker_src:
    problems.append("phone install and phone launch demand a paired phone, though Android lets "
                    "an app install and open another app with no Developer options at all")
if "private boolean connected()" not in broker_src:
    problems.append("the bridge cannot tell a running adb server from a connected phone, so it "
                    "would take the adb path with nothing on the end of it")
installer_src = code("Installer.java")
if "MODE_FULL_INSTALL" not in installer_src or "STATUS_PENDING_USER_ACTION" not in installer_src:
    problems.append("the unpaired install does not go through a PackageInstaller session, so "
                    "nothing reports back to the terminal and this app is not the installer of "
                    "record")
if "getLaunchIntentForPackage" not in installer_src:
    problems.append("there is no way to open an installed app without adb")
if "<queries" in open(app + "/app/AndroidManifest.xml").read():
    problems.append("the manifest asks to see other packages; being the installer of record is "
                    "what makes the apps built here visible, and nothing else should be")

# --- the community row is never sold as official -------------------------------------------------
#
# One row on the Agents screen is somebody else's extension, listed because it answers "an open
# model, a new model, or one running here" and nothing official does. Everything about it has to
# say so: its own list, its own heading, the word on the row, the dialog that installs it.
agents_src = code("Agents.java")
pane_src = code("AgentsPane.java")
if "kilocode.kilo-code" not in agents_src:
    problems.append("there is no bring-your-own-model row, so an owner who wants an open model "
                    "is told nothing")
else:
    # The official list's own body, and official() reading only that: those two together are
    # what keeps "official" true. Splitting on the word COMMUNITY would not -- a list renamed
    # COMMUNITYX splits the same way and the check would pass on a Kilo moved into ALL.
    all_list = re.search(r'static final List<Agent> ALL\s*=(.*?\n    \);)', agents_src, re.S)
    if not all_list:
        problems.append("the official agent list cannot be read")
    elif "kilocode" in all_list.group(1):
        problems.append("the community extension is in the official list, so Agents.official() "
                        "would call it official")
    official_fn = re.search(r'static boolean official\(String publisherName\) \{(.*?)\n    \}',
                            agents_src, re.S)
    if not official_fn or "COMMUNITY" in official_fn.group(1):
        problems.append("Agents.official() reads the community list, so a community publisher "
                        "would be called official")
    if "Agents.COMMUNITY" not in pane_src or "community, not official" not in pane_src:
        problems.append("the community row is not under a heading that says it is not official")
    if "community \u00b7 " not in pane_src.replace("\u00b7", "\u00b7"):
        problems.append("the community row does not say community where the others say official")
    if "Not official: this is an independent extension" not in pane_src:
        problems.append("the install dialog for a community extension does not say it is not "
                        "official")

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


# --- held sideways ---------------------------------------------------------------------------
#
# Three separate things have to be true before an owner can use the editor in landscape, and
# each one alone is a bug that looks like the other two.
for name in ("values", "values-night", "values-v31"):
    styles = open(app + "/app/res/%s/styles.xml" % name).read()
    if "windowLayoutInDisplayCutoutMode" not in styles:
        problems.append("%s/styles.xml does not set windowLayoutInDisplayCutoutMode, so a "
                        "phone with a camera notch paints a black band down one whole side of "
                        "the screen in landscape" % name)
if not os.path.exists(src + "Rotation.java"):
    problems.append("there is no rotation setting, so an owner whose phone has auto-rotate "
                    "switched off can never see the editor in landscape at all")
else:
    rotation = code("Rotation.java")
    if "SCREEN_ORIENTATION_SENSOR_LANDSCAPE" not in rotation:
        problems.append("the landscape choice pins one side up instead of following the phone, "
                        "so half the time it has to be turned the way the app picked")
    if "SCREEN_ORIENTATION_UNSPECIFIED" not in rotation:
        problems.append("the default rotation overrules the phone's own rotation lock on the "
                        "strength of a setting nobody chose")
    for name in ("MainActivity.java", "WorkspaceActivity.java"):
        if "Rotation.apply(this)" not in code(name):
            problems.append("%s never applies the rotation setting" % name)
        if "Rotation.apply(this)" not in re.search(
                r'protected void onStart\(\) \{(.*?)\n    \}', code(name), re.S).group(1):
            problems.append("%s applies rotation only once, so changing it in Settings does "
                            "not reach a screen that is already open" % name)

# --- a modifier is a key, not a flag ----------------------------------------------------------
#
# A synthetic KeyEvent carrying META_CTRL_ON and nothing else is not what a keyboard sends and
# not what the browser engine believes: a real Ctrl+C is Ctrl down, C down, C up, Ctrl up. The
# key row's Ctrl used to light up and do nothing for exactly this reason.
chord = re.search(r'public void key\(int keyCode, int metaState\) \{(.*?)\n    \}',
                  workspace, re.S)
if not chord:
    problems.append("WorkspaceActivity has no key() for the key row to send through")
else:
    body = chord.group(1)
    for modifier in ("KEYCODE_CTRL_LEFT", "KEYCODE_ALT_LEFT", "KEYCODE_SHIFT_LEFT"):
        if modifier not in body:
            problems.append("key() never presses %s as a real key, so any chord using it "
                            "arrives as the unmodified key" % modifier)
keybar = code("KeyBar.java")
if "META_SHIFT_ON" not in keybar or "META_ALT_ON" not in keybar:
    problems.append("the key row offers only Ctrl, so Ctrl+Shift+P cannot be typed on a phone")
if "snapshot()" not in keybar or "restore(" not in keybar:
    problems.append("the key row cannot save its state, so turning the phone closes it and "
                    "drops whatever modifier was held")

# --- the editor's own window --------------------------------------------------------------------
if "onShowFileChooser" not in workspace:
    problems.append("no file chooser, so an agent panel's own attach-a-file button is inert")
if "setDownloadListener" not in workspace:
    problems.append("no download listener, so a download offered inside the editor is dropped "
                    "with nothing shown")

# --- the editor is never squeezed narrower than it can lay itself out in -----------------------
#
# Computed here rather than asserted, because the fault was arithmetic: the font scale was ADDED
# to the worked-out zoom with nothing holding the result, so a 360 dp phone set to 1.3x text
# reached the cap and left the workbench 228 effective pixels. That is what an owner photographed
# running off the side of the screen.
screen_src = code("Screen.java")


def number(name):
    found = re.search(name + r'\s*=\s*([0-9.]+)f?;', screen_src)
    return float(found.group(1)) if found else None


# The constants are only half of it. This gate replicates the arithmetic in Python, so it
# would go on passing if Screen.java stopped applying its own floor -- which is precisely the
# bug it was written for. So the shape of the Java is asserted too.
automatic = re.search(r'static int automaticZoomTenths\(Context context\) \{(.*?)\n    \}',
                      screen_src, re.S)
if not automatic:
    problems.append("Screen has no automaticZoomTenths for the editor's width to come from")
else:
    body = automatic.group(1)
    if "FLOOR_EFFECTIVE_DP" not in body:
        problems.append("the zoom is worked out without applying the floor, so a large system "
                        "text size can squeeze the workbench until it overflows the screen")
    if body.find("fontScale") > body.find("FLOOR_EFFECTIVE_DP"):
        problems.append("the floor is applied before the font scale is added, which is the "
                        "same as not applying it: the addition is what breaks the floor")
    if "Math.floor(zoom * 10)" not in body:
        problems.append("the zoom is rounded to the nearest tenth, which can round UP past the "
                        "floor the line above it just enforced")

floor = number("FLOOR_EFFECTIVE_DP")
target = number("MIN_EFFECTIVE_DP")
step = number("STEP")
lo, hi = number("MIN_ZOOM"), number("MAX_ZOOM")
if None in (floor, target, step, lo, hi):
    problems.append("Screen.java no longer states the widths and zoom limits this gate checks")
else:
    import math
    worst = None
    for width in (320, 360, 393, 411, 432, 480, 600, 673, 800):
        for scale in (0.85, 1.0, 1.15, 1.3, 1.5, 1.8, 2.0):
            zoom = math.log(width / target) / math.log(step)
            zoom += math.log(scale) / math.log(step)
            widest = math.log(width / floor) / math.log(step)
            zoom = min(zoom, widest)
            zoom = max(lo, min(hi, zoom))
            effective = width / (step ** (math.floor(zoom * 10) / 10.0))
            if worst is None or effective < worst[0]:
                worst = (effective, width, scale)
    if worst[0] < floor - 1:
        problems.append("at %d dp and text scale %.2f the editor is left %.0f effective pixels, "
                        "under the %.0f it needs to lay itself out -- the workbench overflows "
                        "the screen there" % (worst[1], worst[2], worst[0], floor))


# --- the app shows what the editor has, not what the app remembers doing ----------------------
#
# An owner installed Antigravity from inside the editor -- the ordinary way -- and every screen
# in the app went on saying it was not installed, because the app was reading a list only it
# ever wrote. Two separate faults, and either one alone reproduces the report.
registry = code("Registry.java")
installed_fn = re.search(r'static List<String> installed\(Context context\) \{(.*?)\n    \}',
                         registry, re.S)
if not installed_fn or "Extensions.ids" not in installed_fn.group(1):
    problems.append("Registry.installed() does not ask the editor what it has, so anything "
                    "installed from inside the editor is invisible to every screen in the app")
if not os.path.exists(src + "Extensions.java"):
    problems.append("there is nothing that reads the editor's own record of its extensions")
else:
    ext = code("Extensions.java")
    if "equalsIgnoreCase" not in ext:
        problems.append("extension identifiers are compared case-sensitively; the registry "
                        "publishes Google.google-antigravity and the editor records it lower "
                        "cased, so the same extension never matches itself")
for name in ("AgentsPane.java", "HomePane.java"):
    text = code(name)
    for wrong in ("present.contains(agent.id)", "present.contains(listing.id)"):
        if wrong in text:
            problems.append("%s compares extension identifiers with contains(), which is "
                            "case-sensitive and therefore always false for Google's" % name)


# --- the long-press shortcut -----------------------------------------------------------------
shortcuts = app + "/app/res/xml/shortcuts.xml"
if not os.path.exists(shortcuts):
    problems.append("there is no launcher shortcut to the editor")
else:
    if "com.pocketide.MainActivity" not in open(shortcuts).read():
        problems.append("the shortcut targets the editor directly, skipping the app lock and "
                        "the Set up screen that Home would have raised first")
    if "android.app.shortcuts" not in open(app + "/app/AndroidManifest.xml").read():
        problems.append("shortcuts.xml exists but the manifest never points at it, so the "
                        "launcher never shows it")
    # Both call sites, not the method's existence: a cold start arrives through onCreate and
    # a tap while the app is already open arrives through onNewIntent, and a handler that is
    # defined but reached from only one of them opens Home and stops there half the time.
    if ("continueToEditorIfAsked(getIntent())" not in main
            or "continueToEditorIfAsked(intent)" not in main):
        problems.append("MainActivity does not act on the shortcut's extra from both onCreate "
                        "and onNewIntent, so the shortcut opens Home and stops there")


# --- the phone as a test device is a row, and Settings notices when it changes -----------------
settings = code("SettingsPane.java")
# The row itself, not any mention of the words: the dialogs behind it use the same title.
if not re.search(r'Ui\.row\(host, dark, R\.drawable\.\w+, "Test on this phone"', settings):
    problems.append("Settings has no Test on this phone row, so the adb layer is unreachable "
                    "from the app")
refresh = re.search(r'private void refreshTools\(final Tools\.State drawn\) \{(.*?)\n    \}',
                    settings, re.S)
if (not refresh or "drawn.sdk != found.sdk" not in refresh.group(1)
        or "drawn.adb != found.adb" not in refresh.group(1)):
    problems.append("refreshTools() ignores the SDK and adb when deciding whether to redraw, so "
                    "the row goes on saying JDK only after the toolchain has installed")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
