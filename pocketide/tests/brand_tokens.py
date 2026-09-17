#!/usr/bin/env python3
"""tokens.json, Brand.java and colors.xml must agree about every brand colour.

A mark drawn in one violet beside a screen painted in another is the fault nobody notices in
review and everybody notices on a home screen."""
import json, re, sys

app = sys.argv[1]
tokens = json.load(open(f"{app}/branding/tokens.json"))["brand"]
brand = open(f"{app}/app/src/com/pocketide/Brand.java").read()
colors = open(f"{app}/app/res/values/colors.xml").read()

java = {name.lower(): value.upper()
        for name, value in re.findall(r'(\w+) = Color\.parseColor\("(#[0-9A-Fa-f]{6})"\)', brand)}
xml = {name.replace("brand_", ""): "#" + value[-6:].upper()
       for name, value in re.findall(r'<color name="(brand_\w+)">(#[0-9A-Fa-f]{8})</color>', colors)}

problems = []
for key, value in tokens.items():
    want = value.upper()
    if key in java and java[key] != want:
        problems.append(f"Brand.java {key} is {java[key]}, tokens.json says {want}")
    if key in xml and xml[key] != want:
        problems.append(f"colors.xml {key} is {xml[key]}, tokens.json says {want}")
    if key not in java and key not in xml:
        problems.append(f"{key} is in tokens.json but nowhere in the app")

for problem in problems:
    print(f"  {problem}", file=sys.stderr)
sys.exit(1 if problems else 0)
