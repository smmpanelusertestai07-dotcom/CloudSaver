#!/usr/bin/env python3
"""The manifest and the Help screen must list exactly the same permissions.

An app whose privacy page and whose manifest disagree is worse than one with no privacy page,
because the page is what people read instead of the manifest."""
import re, sys

app = sys.argv[1]
manifest = open(f"{app}/app/AndroidManifest.xml").read()
texts = open(f"{app}/app/src/com/pocketide/Texts.java").read()

# Only real uses-permission lines, not the ones named inside a comment.
without_comments = re.sub(r"<!--.*?-->", "", manifest, flags=re.S)
declared = set(re.findall(r'uses-permission android:name="([^"]+)"', without_comments))
listed = set(re.findall(r'\{"(android\.permission\.[A-Z_]+)"', texts))

problems = []
for name in sorted(declared - listed):
    problems.append(f"{name} is requested but not listed in Help")
for name in sorted(listed - declared):
    problems.append(f"{name} is listed in Help but not requested")

for problem in problems:
    print(f"  {problem}", file=sys.stderr)
sys.exit(1 if problems else 0)
