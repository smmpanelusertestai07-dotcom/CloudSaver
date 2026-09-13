#!/usr/bin/env python3
"""No XML comment may contain a double hyphen.

XML forbids "--" inside a comment, and aapt2 reports it as `xml parser error: not well-formed
(invalid token)` with a line number of 0. There is nothing in that message to point at the
comment, so the whole file gets read line by line looking for the fault.

This has cost this project three separate builds, every time for the same reason: an em dash
written as two hyphens, which is exactly how anyone writing English prose types one. So the
check is here rather than in anyone's memory. The fix is a real em dash.
"""
import os
import re
import sys

app = sys.argv[1]
problems = []
checked = 0
for root, _, names in os.walk(app + "/app/res"):
    for name in sorted(names):
        if not name.endswith(".xml"):
            continue
        path = os.path.join(root, name)
        text = open(path, encoding="utf-8").read()
        for comment in re.findall(r'<!--(.*?)-->', text, re.S):
            checked += 1
            if "--" in comment:
                line = text[:text.index(comment)].count("\n") + 1
                problems.append("%s:%d has a comment containing '--'. XML forbids it and aapt2 "
                                "reports it with no line number. Use an em dash."
                                % (os.path.relpath(path, app), line))

# The manifest too, which is where it happened the first time.
manifest = app + "/app/AndroidManifest.xml"
text = open(manifest, encoding="utf-8").read()
for comment in re.findall(r'<!--(.*?)-->', text, re.S):
    checked += 1
    if "--" in comment:
        problems.append("AndroidManifest.xml has a comment containing '--'")

print("  %d XML comments checked" % checked)
for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
