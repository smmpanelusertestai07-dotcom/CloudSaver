#!/usr/bin/env python3
"""The repository holds the app's source and what builds and checks it, and nothing that only
describes it.

Everything an owner needs to read is inside the app: the FAQ, the terms, the privacy text and
the open-source notices are all screens, because the APK is the only thing an owner receives
and a document on GitHub is not. So the tree carries no README, no release notes, no plans and
no other Markdown, and no file that would be a risk to publish: a keystore, a properties file
with a path or a password in it, a service-account JSON.

One document stays and is named here on purpose: RELEASE_MATRIX.md belongs to CloudSaver, the
other app in this repository, whose MatrixHonestyTest reads it and whose release step ships it.
It is test evidence rather than a description, and it is that project's to move.
"""
import os
import re
import sys

app = sys.argv[1]
root = os.path.abspath(os.path.join(app, os.pardir))
problems = []

ALLOWED_DOCUMENTS = ("RELEASE_MATRIX.md",)
SKIPPED_DIRS = (".git", ".tooling", "build", ".gradle", ".kotlin", ".idea", ".signing",
                "node_modules", "artifacts", "__pycache__")
RISKY_NAMES = ("local.properties", "google-services.json", "service-account.json", ".env")
RISKY_SUFFIXES = (".jks", ".keystore", ".p12", ".pem", ".key")

for here, dirs, files in os.walk(root):
    dirs[:] = [d for d in dirs if d not in SKIPPED_DIRS]
    for name in files:
        rel = os.path.relpath(os.path.join(here, name), root)
        lower = name.lower()
        if lower.endswith(".md") and rel not in ALLOWED_DOCUMENTS:
            problems.append("a document that belongs in the app or nowhere: " + rel)
        if lower in ("readme", "readme.txt", "changelog", "changelog.txt", "todo.txt"):
            problems.append("a document that belongs in the app or nowhere: " + rel)
        if lower in RISKY_NAMES or lower.endswith(RISKY_SUFFIXES):
            problems.append("a file that must never be published: " + rel)

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
