#!/usr/bin/env python3
"""No fixed width wider than a phone screen.

The reference device is 720x1600, which is about 400 density-independent pixels across. A
hard-coded width past that produces a screen that scrolls sideways, which this app treats as a
bug rather than a preference."""
import glob, os, re, sys

src = sys.argv[1]
problems = []
for path in glob.glob(f"{src}/*.java"):
    text = open(path).read()
    for found in re.finditer(r'\bUi\.dp\(\s*\w+\s*,\s*(\d+(?:\.\d+)?)f?\s*\)', text):
        value = float(found.group(1))
        # Ui.CONTENT_MAX_DP is a maximum, not a fixed size, and is allowed to exceed a phone.
        if value > 400 and "CONTENT_MAX" not in text[max(0, found.start() - 80):found.start()]:
            line = text[:found.start()].count("\n") + 1
            problems.append(f"{os.path.basename(path)}:{line}: fixed {value}dp is wider than a phone")

for problem in problems:
    print(f"  {problem}", file=sys.stderr)
sys.exit(1 if problems else 0)
