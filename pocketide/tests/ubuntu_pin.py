#!/usr/bin/env python3
"""The Ubuntu pin has to agree with itself, everywhere it is written down.

There are five places the base image's identity appears: the URL it is fetched from, the SHA-256
it is checked against, the label the set-up screen shows while it downloads, the open-source
notices the app carries, and anywhere else in the app's own source or its shell scripts that
names a point release. A point release moves all five or none -- a half-done bump ships a screen
that says one version while the phone downloads another, and nothing else would notice.

Two of those five used to be documents in this folder, a README table and an open-source
notices file. Neither reached anyone who installs the app, so both moved into the app itself,
and this gate followed them: the notices are Texts.NOTICES now, and the sweep below replaces
"the files I happened to list" with "every file that could carry the number".

The checksum itself is not re-fetched here, because a gate that reaches the network fails on a
phone-less CI runner with no signal. It was verified by hand against Canonical's own SHA256SUMS
at https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/SHA256SUMS and by hashing the
downloaded bytes. What this holds is that the five places cannot drift apart afterwards.
"""
import glob
import os
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

# The two places that have to spell the point release out in full: the label the set-up screen
# shows while it downloads, and the open-source notices, which name the exact tarball Canonical
# published. Both ship inside the APK, which is the only thing anyone receives.
for name in ("/app/src/com/pocketide/Stage.java",
             "/app/src/com/pocketide/Texts.java"):
    text = open(app + name).read()
    if "24.04" in text and version not in text:
        problems.append("%s names a different Ubuntu point release than %s"
                        % (name.lstrip("/"), version))

# And nowhere else in the app's source or its shell scripts may a point release of this series
# be written down that is not the one actually downloaded. Naming the series alone ("24.04 LTS")
# is fine and several files do it; naming 24.04.4 while the phone fetches 24.04.5 is the drift
# this gate exists to catch, and it is caught wherever it is written rather than only in a list
# of files someone remembered to keep up to date.
series = version.rsplit(".", 1)[0]
elsewhere = re.compile(r'(?<![\d.])' + re.escape(series) + r'\.\d+')
for path in sorted(glob.glob(app + "/app/src/com/pocketide/*.java")
                   + glob.glob(app + "/app/assets/*.sh")
                   + glob.glob(app + "/tests/*.sh")
                   + [app + "/build.sh"]):
    for found in sorted(set(elsewhere.findall(open(path).read()))):
        if found != version:
            problems.append("%s names Ubuntu %s while the image fetched is %s"
                            % (os.path.relpath(path, app), found, version))

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
