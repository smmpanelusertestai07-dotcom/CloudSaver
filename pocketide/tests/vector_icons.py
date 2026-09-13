#!/usr/bin/env python3
"""Every vector icon must draw inside the box it declares.

An Android VectorDrawable declares a viewport -- 24 by 24 here -- and anything the path draws
outside it is silently clipped. Nothing warns: the build succeeds, the resource loads, and the
icon simply appears with a corner sliced off at whatever size it is shown.

One shipped that way. ic_pulse's path ran to y = 27.2 on a 24-high viewport, so the bottom of
the heartbeat was cut. It was found by tracing the path rather than by looking at it, because at
24 dp on a phone a clipped glyph looks like a glyph.

The trace below implements the SVG path subset Android supports, relative commands included --
which is the part that makes eyeballing the numbers useless. A path written with lowercase
commands is a list of DELTAS, so "-11.3" in the data can be a perfectly ordinary point and
"22" can be off the edge. Only accumulating them tells you which.

Bezier control points are included in the box deliberately. A curve does not reach its control
points, so this is stricter than the true outline -- but a control point outside the viewport is
a curve bending toward the edge, and being told about it early costs nothing.
"""
import os
import re
import sys

app = sys.argv[1]
TOKEN = re.compile(r'([MmLlHhVvCcSsQqTtAaZz])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)')

# How many numbers each command consumes per repetition.
ARITY = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7, 'Z': 0}


def points(data):
    """Every point the path visits or bends toward, in absolute coordinates."""
    tokens = []
    for command, number in TOKEN.findall(data):
        tokens.append(command if command else float(number))

    seen = []
    x = y = 0.0
    start_x = start_y = 0.0
    command = None
    i = 0
    while i < len(tokens):
        if isinstance(tokens[i], str):
            command = tokens[i]
            i += 1
            if command in 'Zz':
                x, y = start_x, start_y
                seen.append((x, y))
                continue
        if command is None:
            return None
        upper = command.upper()
        relative = command.islower()
        need = ARITY[upper]
        if i + need > len(tokens):
            break
        args = tokens[i:i + need]
        if any(isinstance(a, str) for a in args):
            break
        i += need

        if upper == 'H':
            x = x + args[0] if relative else args[0]
        elif upper == 'V':
            y = y + args[0] if relative else args[0]
        elif upper == 'A':
            # Only the end point of an arc is a point the path reaches.
            x = x + args[5] if relative else args[5]
            y = y + args[6] if relative else args[6]
        else:
            pairs = [(args[k], args[k + 1]) for k in range(0, need, 2)]
            for dx, dy in pairs:
                px = x + dx if relative else dx
                py = y + dy if relative else dy
                seen.append((px, py))
            x, y = (x + pairs[-1][0], y + pairs[-1][1]) if relative else pairs[-1]
        seen.append((x, y))
        if upper == 'M':
            start_x, start_y = x, y
        # A repeated coordinate list after M means an implicit L.
        if upper == 'M':
            command = 'l' if relative else 'L'
    return seen


problems = []
checked = 0
for folder in sorted(os.listdir(app + "/app/res")):
    if not folder.startswith("drawable"):
        continue
    path = app + "/app/res/" + folder
    for name in sorted(os.listdir(path)):
        if not name.endswith(".xml"):
            continue
        text = open(path + "/" + name).read()
        width = re.search(r'viewportWidth="([\d.]+)"', text)
        height = re.search(r'viewportHeight="([\d.]+)"', text)
        if not width or not height:
            continue
        vw, vh = float(width.group(1)), float(height.group(1))
        for data in re.findall(r'android:pathData="([^"]+)"', text):
            checked += 1
            visited = points(data)
            if not visited:
                problems.append("%s/%s: path data could not be parsed" % (folder, name))
                continue
            xs = [p[0] for p in visited]
            ys = [p[1] for p in visited]
            slack = 0.51                      # half a unit, for rounded joins drawn on the edge
            if min(xs) < -slack or max(xs) > vw + slack \
                    or min(ys) < -slack or max(ys) > vh + slack:
                problems.append(
                    "%s/%s draws %.2f..%.2f by %.2f..%.2f in a %gx%g viewport, so it is clipped"
                    % (folder, name, min(xs), max(xs), min(ys), max(ys), vw, vh))

print("  %d vector paths traced" % checked)
for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
