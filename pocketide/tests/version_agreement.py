#!/usr/bin/env python3
"""build.sh and BuildFacts.java must agree about the version.

The manifest can only take the version from build.sh, and the screens can only take it from
BuildFacts. An app that reports one version to the package manager and shows another in
Settings produces bug reports nobody can act on."""
import re, sys

app = sys.argv[1]
build = open(f"{app}/build.sh").read()
facts = open(f"{app}/app/src/com/pocketide/BuildFacts.java").read()

def one(pattern, text, what):
    found = re.search(pattern, text)
    if not found:
        print(f"  could not find {what}", file=sys.stderr)
        sys.exit(1)
    return found.group(1)

build_name = one(r'VERSION_NAME="([^"]+)"', build, "VERSION_NAME in build.sh")
build_code = one(r'VERSION_CODE="(\d+)"', build, "VERSION_CODE in build.sh")
facts_name = one(r'VERSION_NAME = "([^"]+)"', facts, "VERSION_NAME in BuildFacts")
facts_code = one(r'VERSION_CODE = (\d+)', facts, "VERSION_CODE in BuildFacts")

problems = []
if build_name != facts_name:
    problems.append(f"name: build.sh {build_name} vs BuildFacts {facts_name}")
if build_code != facts_code:
    problems.append(f"code: build.sh {build_code} vs BuildFacts {facts_code}")
# The owner's own rule: a version name ends in 0 or 5.
if not build_name.endswith(("0", "5")):
    problems.append(f"version {build_name} does not end in 0 or 5")

for problem in problems:
    print(f"  {problem}", file=sys.stderr)
sys.exit(1 if problems else 0)
