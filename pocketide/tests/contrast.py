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

for row in rows:
    print(row)
for problem in missing + problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if (problems or missing) else 0)
