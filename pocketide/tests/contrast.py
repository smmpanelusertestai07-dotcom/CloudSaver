#!/usr/bin/env python3
"""Every colour the app puts text in, against the ground it is actually drawn on.

The app shipped one green, one amber and one red used unchanged on both a cream card and a
near-black one, and each of them failed somewhere. The worst was the failure red at 2.99:1 on
the dark card -- under the floor for large text, let alone body text -- and it was the colour of
the "Remove everything" button, which is the one button in the app that must be unmistakable.

A single value cannot work for both: a green dark enough to read on cream is too dark on
near-black, and a red light enough for near-black is too light on cream. So there are now two of
each, and this recomputes all of them on every build rather than trusting that someone checked
once.

The ratios are WCAG 2.x relative luminance, which is the same formula every accessibility tool
uses. 4.5:1 is the AA floor for body text; these are labels and small values, so body text is
the right bar.
"""
import re
import sys

app = sys.argv[1]
ui = open(app + "/app/src/com/pocketide/Ui.java").read()

FLOOR = 4.5


def parse(hex_or_rgb):
    text = hex_or_rgb.lstrip("#")
    return tuple(int(text[i:i + 2], 16) for i in (0, 2, 4))


def luminance(colour):
    def channel(value):
        v = value / 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = colour
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def ratio(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def constant(name):
    """A Color.rgb(r, g, b) or Color.parseColor("#RRGGBB") constant out of Ui.java."""
    rgb = re.search(name + r'\s*=\s*Color\.rgb\((\d+),\s*(\d+),\s*(\d+)\)', ui)
    if rgb:
        return tuple(int(g) for g in rgb.groups())
    parsed = re.search(name + r'\s*=\s*Color\.parseColor\("(#[0-9A-Fa-f]{6})"\)', ui)
    if parsed:
        return parse(parsed.group(1))
    return None


grounds = {
    "light": {"bg": constant("LIGHT_BG"), "card": constant("LIGHT_CARD")},
    "dark": {"bg": constant("DARK_BG"), "card": constant("DARK_CARD")},
}

# Every foreground that carries meaning, and the theme it belongs to.
foregrounds = {
    "light": ["LIGHT_TEXT", "LIGHT_MUTED", "RUNNING_LIGHT", "NEEDS_YOU_LIGHT", "FAILED_LIGHT"],
    "dark": ["DARK_TEXT", "DARK_MUTED", "RUNNING_DARK", "NEEDS_YOU_DARK", "FAILED_DARK"],
}

problems = []
missing = []
rows = []
for theme, names in foregrounds.items():
    ground = grounds[theme]
    if not ground["bg"] or not ground["card"]:
        missing.append("the %s background or card colour is not readable from Ui.java" % theme)
        continue
    for name in names:
        colour = constant(name)
        if colour is None:
            missing.append("%s is not defined in Ui.java" % name)
            continue
        on_bg = ratio(colour, ground["bg"])
        on_card = ratio(colour, ground["card"])
        worst = min(on_bg, on_card)
        rows.append("    %-18s %-6s page %5.2f:1  card %5.2f:1  %s"
                    % (name, theme, on_bg, on_card, "ok" if worst >= FLOOR else "FAILS"))
        if worst < FLOOR:
            problems.append(
                "%s measures %.2f:1 on the %s %s, under the %s:1 floor for text. A colour that "
                "cannot be read is not a status, it is decoration."
                % (name, worst, theme, "page" if on_bg < on_card else "card", FLOOR))

# ------------------------------------------------------------------------------------------
# The navigation bar, over what is actually behind it.
#
# Everything above compares a colour against a flat card or page. The bottom bar is neither: it
# is a gradient with a translucent pill on it, and the active icon sits on the pill. Checking
# that icon against the card colour would have passed it -- and it was failing. The accent on
# its own indicator measures 3.28:1 in the light theme, on the one control in the app whose job
# is to say which screen you are looking at, and the active LABEL was 3.97:1 for the same
# reason. Both shipped in 1.6.0 and neither was visible to any check that existed.
#
# So this composites: the pill is the accent at its real alpha over the glass gradient, and the
# icon and label are measured against the result. Both gradient stops are checked, because a
# gradient has two ends and the label sits nearer one of them.

brand = open(app + "/app/src/com/pocketide/Brand.java").read()
shell = open(app + "/app/src/com/pocketide/Shell.java").read()


def brand_colour(name):
    found = re.search(name + r'\s*=\s*Color\.parseColor\("(#[0-9A-Fa-f]{6})"\)', brand)
    return parse(found.group(1)) if found else None


def glass_stops(theme):
    """The gradient stops Ui.glass paints, read out of the method rather than assumed."""
    body = re.search(r'static GradientDrawable glass\(.*?\n    \}', ui, re.S)
    if not body:
        return []
    pairs = re.findall(
        r'dark \? Color\.rgb\((\d+), (\d+), (\d+)\) : Color\.rgb\((\d+), (\d+), (\d+)\)',
        body.group(0))
    stops = []
    for group in pairs:
        values = [int(v) for v in group]
        stops.append(tuple(values[0:3]) if theme == "dark" else tuple(values[3:6]))
    return stops


def composite(front, alpha, back):
    k = alpha / 255
    return tuple(round(k * front[i] + (1 - k) * back[i]) for i in range(3))


def indicator_alpha(theme):
    """Read from Shell.java, so changing the indicator there is caught here."""
    found = re.search(r'Ui\.alpha\(Ui\.accent\(dark\), dark \? (\d+) : (\d+)\)', shell)
    if not found:
        return None
    return int(found.group(1) if theme == "dark" else found.group(2))


ACCENT = {"light": brand_colour("ACCENT"), "dark": brand_colour("ACCENT_ON_DARK")}
# Ui.onAccentContainer(dark) -- kept in step with the Java rather than repeated as a literal.
ON_CONTAINER = {"light": brand_colour("TILE_FLAT"), "dark": brand_colour("MARK")}
LABEL = {"light": constant("LIGHT_TEXT"), "dark": constant("DARK_TEXT")}
INACTIVE = {"light": constant("LIGHT_MUTED"), "dark": constant("DARK_MUTED")}

if "Ui.onAccentContainer(dark)" not in shell:
    problems.append("the navigation bar no longer uses the on-container tone for its active "
                    "icon. The accent measures 3.28:1 on its own indicator in the light theme.")
if re.search(r'Ui\.medium\(context, tab\.label, 12f, Ui\.accent\(dark\)\)', shell):
    problems.append("the active destination's label is the accent again, which measures "
                    "3.97:1 on the lower half of the light capsule")

for theme in ("light", "dark"):
    stops = glass_stops(theme)
    alpha = indicator_alpha(theme)
    if not stops or alpha is None or not ACCENT[theme] or not ON_CONTAINER[theme]:
        missing.append("the %s navigation bar's own colours cannot be read" % theme)
        continue
    for stop in stops:
        pill = composite(ACCENT[theme], alpha, stop)
        # An icon is non-text, whose floor in WCAG 2.2 is 3:1; a label is text and takes 4.5.
        for what, colour, ground, floor in (
                ("active icon, on its indicator", ON_CONTAINER[theme], pill, 3.0),
                ("active label", LABEL[theme], stop, FLOOR),
                ("inactive label", INACTIVE[theme], stop, FLOOR)):
            measured = ratio(colour, ground)
            rows.append("    bar %-14s %-6s %5.2f:1  %s"
                        % (what.split(",")[0], theme, measured,
                           "ok" if measured >= floor else "FAILS"))
            if measured < floor:
                problems.append("the bar's %s measures %.2f:1 in the %s theme, under the "
                                "%.1f:1 floor" % (what, measured, theme, floor))

for row in rows:
    print(row)
for problem in missing + problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if (problems or missing) else 0)
