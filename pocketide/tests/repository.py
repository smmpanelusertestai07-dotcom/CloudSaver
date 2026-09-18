#!/usr/bin/env python3
"""The repository holds the app's source and what builds and checks it, and nothing that only
describes it.

Everything an owner needs to read is inside the app: the FAQ, the terms, the privacy text and
the open-source notices are all screens, because the APK is the only thing an owner receives
and a document on GitHub is not. So the tree carries no README, no release notes, no plans and
no other Markdown, and no file that would be a risk to publish: a keystore, a properties file
with a path or a password in it, a service-account JSON.

Nothing is exempt. CloudSaver's verification matrix was the last document standing and has gone
the same way: its counts were read off the tree by a test that existed to keep a document honest,
and a document nobody reads does not need keeping honest.
"""
import os
import re
import subprocess
import sys

app = sys.argv[1]
root = os.path.abspath(os.path.join(app, os.pardir))
problems = []

SKIPPED_DIRS = (".git", ".tooling", "build", ".gradle", ".kotlin", ".idea", ".signing",
                "node_modules", "artifacts", "__pycache__")
RISKY_NAMES = ("local.properties", "google-services.json", "service-account.json", ".env")
RISKY_SUFFIXES = (".jks", ".keystore", ".p12", ".pem", ".key")

def tracked():
    """What the repository actually carries.

    Asking git rather than the filesystem, because the two disagree in the
    one way that matters: a keystore or a local.properties sitting in a
    working copy is ignored and never published, while the same name
    committed is exactly what this gate is for. Falling back to a walk keeps
    the gate working where git is not on the path.
    """
    try:
        out = subprocess.run(["git", "-C", root, "ls-files", "-z"],
                             capture_output=True, check=True).stdout
        names = [n.decode() for n in out.split(b"\0") if n]
        if names:
            return names
    except (OSError, subprocess.CalledProcessError):
        pass
    found = []
    for here, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIPPED_DIRS]
        found += [os.path.relpath(os.path.join(here, n), root) for n in files]
    return found


for rel in tracked():
    name = os.path.basename(rel)
    lower = name.lower()
    if lower.endswith(".md"):
        problems.append("a document that belongs in the app or nowhere: " + rel)
    if lower in ("readme", "readme.txt", "changelog", "changelog.txt", "todo.txt"):
        problems.append("a document that belongs in the app or nowhere: " + rel)
    if lower in RISKY_NAMES or lower.endswith(RISKY_SUFFIXES):
        problems.append("a file that must never be published: " + rel)

# The companion extension is two source files; the build packages them, and the packaged
# .vsix never lives in the tree.
for name in ("companion/package.json", "companion/extension.js"):
    if not os.path.isfile(os.path.join(app, "app", "assets", name)):
        problems.append("the companion extension's source is missing: app/assets/" + name)
for here, dirs, files in os.walk(os.path.join(app, "app", "assets")):
    for name in files:
        if name.endswith((".vsix", ".stamp")):
            problems.append("a packaged file is in the tree instead of being built: " + name)

# The notices reach the owner through the app, not through the tree.
notices = os.path.join(app, "app", "assets", "open-source-notices.txt")
if not os.path.isfile(notices) or os.path.getsize(notices) < 2000:
    problems.append("app/assets/open-source-notices.txt is missing or too short to be the notices")
help_src = open(os.path.join(app, "app", "src", "com", "pocketide", "HelpActivity.java")).read()
if 'readAsset("open-source-notices.txt")' not in help_src:
    problems.append("Help does not read the notices from the asset the tree carries")
build = open(os.path.join(app, "build.sh")).read()
if re.search(r'\.md\b', build):
    problems.append("build.sh still refers to a Markdown document")
workflow = open(os.path.join(root, ".github", "workflows", "pocketide.yml")).read()
if "RELEASE-NOTES" in workflow or "README" in workflow:
    problems.append("the workflow still points owners at a document that is not there")
if "github.event.head_commit.message" not in workflow:
    problems.append("the release does not carry the commit's own account of what changed")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
