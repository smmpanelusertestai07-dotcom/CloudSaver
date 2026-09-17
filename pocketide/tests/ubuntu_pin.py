#!/usr/bin/env python3
"""The Ubuntu pin has to agree with itself, everywhere it is written down.

There are five places the base image's identity appears: the URL it is fetched from, the SHA-256
it is checked against, the label the set-up screen shows while it downloads, the README table,
and the open-source notices. A point release moves all five or none -- a half-done bump ships a
screen that says one version while the phone downloads another, and nothing else would notice.

The checksum itself is not re-fetched here, because a gate that reaches the network fails on a
phone-less CI runner with no signal. It was verified by hand against Canonical's own SHA256SUMS
at https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/SHA256SUMS and by hashing the
downloaded bytes. What this holds is that the five places cannot drift apart afterwards.
"""
import re
import sys

app = sys.argv[1]
workspace = open(app + "/app/src/com/pocketide/Workspace.java").read()
problems = []

url = re.search(r'ubuntu-base-([0-9.]+)-base-arm64\.tar\.gz', workspace)
if not url:
    print("  Workspace does not name an ubuntu-base arm64 archive", file=sys.stderr)
    sys.exit(1)
version = url.group(1)

digest = re.search(r'IMAGE_SHA256\s*=\s*\n?\s*"([0-9a-fA-F]*)"', workspace)
if not digest or len(digest.group(1)) != 64:
    problems.append("IMAGE_SHA256 is not a 64-character hex digest")

if "cdimage.ubuntu.com" not in workspace:
    problems.append("the image is not fetched from Canonical's own mirror")

label = re.search(r'IMAGE_LABEL\s*=\s*"([^"]*)"', workspace)
if not label:
    problems.append("Workspace has no IMAGE_LABEL")
elif version not in label.group(1):
    problems.append('IMAGE_LABEL says "%s" but the URL fetches %s'
                    % (label.group(1), version))

for name in ("/app/src/com/pocketide/Stage.java",
             "/README.md",
             "/OPEN_SOURCE_NOTICES.md",
             # Generated into the assets by build.sh, so absent before the first build.
             "/app/assets/open-source-notices.md"):
    try:
        text = open(app + name).read()
    except FileNotFoundError:
        continue
    if "24.04" in text and version not in text:
        problems.append("%s names a different Ubuntu point release than %s"
                        % (name.lstrip("/"), version))

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
