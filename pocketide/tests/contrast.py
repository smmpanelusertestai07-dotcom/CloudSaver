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
    """The capsule's gradient stops, read out of Ui.floatingGlass rather than assumed, and
    composited over the page at the capsule's own opacity -- because the bar is see-through,
    and what is really behind its words is the capsule over the page, not the capsule alone."""
    body = re.search(r'static GradientDrawable floatingGlass\(.*?\n    \}', ui, re.S)
    if not body:
        return []
    # Only the two fill stops -- the lines wrapped in alpha(..., opacity). The hairline stroke
    # on the line below them is an edge, not a ground anything is written on, and matching it
    # as a stop is how this gate once measured the labels against a 1 dp line.
    pairs = re.findall(
        r'alpha\(dark \? Color\.rgb\((\d+), (\d+), (\d+)\) : Color\.rgb\((\d+), (\d+), (\d+)\), '
        r'opacity\)',
        body.group(0))
    opacity = re.search(r'FLOATING_ALPHA_%s\s*=\s*(\d+)' % theme.upper(), ui)
    page = grounds[theme]["bg"]
    if not opacity or not page:
        return []
    stops = []
    for group in pairs:
        values = [int(v) for v in group]
        stop = tuple(values[0:3]) if theme == "dark" else tuple(values[3:6])
        stops.append(composite(stop, int(opacity.group(1)), page))
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

# ------------------------------------------------------------------------------------------
# The Editor button in the top bar: a word and a glyph on a tonal container.
#
# The container is the accent at low opacity over the PAGE, and what sits on it is the
# on-container tone -- the same pairing as the navigation bar's indicator, measured the same
# way, because a tonal button whose word cannot be read is the top-right decoration again.
tonal = re.search(r'static LinearLayout tonalButton\(.*?\n    \}', ui, re.S)
tonal_alpha = re.search(r'alpha\(accent\(dark\), dark \? (\d+) : (\d+)\)',
                        tonal.group(0)) if tonal else None
if not tonal or not tonal_alpha:
    missing.append("the tonal button's container opacity cannot be read from Ui.java")
else:
    for theme in ("light", "dark"):
        page = grounds[theme]["bg"]
        alpha = int(tonal_alpha.group(1) if theme == "dark" else tonal_alpha.group(2))
        if not page or not ACCENT[theme] or not ON_CONTAINER[theme]:
            continue
        container = composite(ACCENT[theme], alpha, page)
        measured = ratio(ON_CONTAINER[theme], container)
        rows.append("    %-18s %-6s %5.2f:1  %s" % ("tonal button", theme, measured,
                                                    "ok" if measured >= FLOOR else "FAILS"))
        if measured < FLOOR:
            problems.append("the Editor button's word measures %.2f:1 on its container in the "
                            "%s theme, under the %.1f:1 floor" % (measured, theme, FLOOR))


# ------------------------------------------------------------------------------------------
# The accent used as WORDS: dialog buttons and the "why" link, on the card they sit on.
#
# Ui.link() exists because the accent itself, as 15 sp text on the light card, is under the
# floor. Read from the Java so that changing the colour there is what changes the measurement.
brand = open(app + "/app/src/com/pocketide/Brand.java").read()


def brand_constant(name):
    found = re.search(name + r'\s*=\s*Color\.parseColor\("(#[0-9A-Fa-f]{6})"\)', brand)
    return parse(found.group(1)) if found else None


link = re.search(r'static int link\(boolean dark\) \{ return dark \? Brand\.(\w+)(?:\(true\))? '
                 r': Brand\.(\w+); \}', ui)
if not link:
    missing.append("Ui.link(dark) is missing or not in the form the gate reads")
else:
    dark_name, light_name = link.group(1), link.group(2)
    dark_colour = (brand_constant("ACCENT_ON_DARK") if dark_name == "accent"
                   else brand_constant(dark_name))
    light_colour = (brand_constant("ACCENT") if light_name == "accent"
                    else brand_constant(light_name))
    for theme, colour in (("light", light_colour), ("dark", dark_colour)):
        ground = grounds[theme]["card"]
        if not colour or not ground:
            missing.append("the link colour for the %s theme cannot be read" % theme)
            continue
        measured = ratio(colour, ground)
        rows.append("    %-18s %-6s %5.2f:1  %s" % ("link words", theme, measured,
                                                    "ok" if measured >= FLOOR else "FAILS"))
        if measured < FLOOR:
            problems.append("Ui.link() measures %.2f:1 on the %s card, under the %.1f:1 floor "
                            "for a dialog button" % (measured, theme, FLOOR))
if "Ui.link(dark)" not in open(app + "/app/src/com/pocketide/Dialogs.java").read():
    problems.append("Dialogs colours its buttons with something other than Ui.link(dark), so "
                    "the measurement above is not of the colour on screen")

for row in rows:
    print(row)
for problem in missing + problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if (problems or missing) else 0)
