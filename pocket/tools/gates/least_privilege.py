#!/usr/bin/env python3
"""The GitHub App holds Administration: write only to create repositories (plan §5.3).

That one permission would also let the app delete a repository, change its visibility, archive
it or transfer it away, and the plan promises none of that ever happens: the keyring check only
reads visibility and collaborators, then re-keys. This gate reads the Kotlin sources and fails
when a code path could do any of those things:
  - an HTTP DELETE whose URL is a repository itself (repos/{owner}/{name}); deleting things
    inside a repository, such as a session branch or an Actions secret, is fine;
  - a PATCH to a repository that sends "private", "visibility" or "archived";
  - a transfer (repos/{owner}/{name}/transfer), or GraphQL's deleteRepository,
    archiveRepository, transferRepository or updateRepository mutations.

A call is judged from the lines around it (the statement building the request), so the rule
is a source-text rule: a reviewer can see why it fired. A reviewed exception goes in
least_privilege_allow.txt as "<path relative to com/pocketide>: <the exact trimmed line>".
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import common

ALLOW_FILE = Path(__file__).with_name("least_privilege_allow.txt")
WINDOW = 6
METHOD_DELETE = re.compile(r'"DELETE"|@DELETE\b|\.delete\(\s*\)|\.delete\(\s*(null|body|[\w.]*RequestBody)|\bdelete\(\s*"')
METHOD_PATCH = re.compile(r'"PATCH"|@PATCH\b|\.patch\(|\bpatch\(\s*"')
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
TEMPLATE = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*|\{[A-Za-z_][A-Za-z0-9_]*\}")
# "repos/{}/{}" or "repos/{}" when one variable holds owner/name.
REPO_ROOT = re.compile(r"(^|/)repos/\{\}(/\{\})?/?(\?.*)?$")
TRANSFER = re.compile(r"(^|/)repos/\{\}(/\{\})?/transfer\b")
VISIBILITY_FIELDS = re.compile(r'"(private|visibility|archived)"')
GRAPHQL = re.compile(r"\b(deleteRepository|archiveRepository|transferRepository|updateRepository)\b")


def normalise(literal: str) -> str:
    """A Kotlin string template (or a {placeholder} path) with every $name, ${…} and {name} as {}."""
    return TEMPLATE.sub("{}", literal)


def literals(lines: list[str]) -> list[str]:
    return [normalise(m.group(1)) for line in lines for m in STRING.finditer(line)]


def is_comment(line: str) -> bool:
    stripped = line.lstrip()
    return stripped.startswith(("//", "*", "/*"))


def scan(relative: str, text: str, allowed: set[str]) -> list[str]:
    lines = text.splitlines()
    problems = []
    for index, line in enumerate(lines):
        if is_comment(line) or f"{relative}: {line.strip()}" in allowed:
            continue
        window = [l for l in lines[max(0, index - WINDOW):index + WINDOW + 1] if not is_comment(l)]
        found = literals(window)
        where = f"{relative}:{index + 1}"
        if METHOD_DELETE.search(line) and any(REPO_ROOT.search(s) for s in found):
            problems.append(f"{where}: sends DELETE to a repository itself; PocketIDE never deletes a repository")
        if METHOD_PATCH.search(line) and any(REPO_ROOT.search(s) for s in found) \
                and any(VISIBILITY_FIELDS.search(l) for l in window):
            problems.append(f"{where}: PATCHes a repository's visibility or archived state; "
                            "the keyring check only reads visibility and re-keys")
        for s in literals([line]):
            if TRANSFER.search(s):
                problems.append(f"{where}: transfers a repository; PocketIDE never does")
        if GRAPHQL.search(line):
            problems.append(f"{where}: uses a GraphQL repository mutation "
                            f"({GRAPHQL.search(line).group(1)}); PocketIDE never changes a repository that way")
    return problems


def read_allowed(path: Path = ALLOW_FILE) -> set[str]:
    if not path.is_file():
        return set()
    return {l.strip() for l in path.read_text(encoding="utf-8").splitlines()
            if l.strip() and not l.lstrip().startswith("#")}


def check(root: Path = common.POCKET, allow: Path = ALLOW_FILE) -> common.Report:
    report = common.Report()
    base = common.kotlin_sources(root)
    allowed = read_allowed(allow)
    files = common.files_under(base, [".kt"])
    if not files:
        report.fail(f"no Kotlin sources under {base}")
    for path in files:
        for problem in scan(path.relative_to(base).as_posix(), path.read_text(encoding="utf-8"), allowed):
            report.fail(problem)
    return report


if __name__ == "__main__":
    sys.exit(common.run_standalone("least privilege (GitHub Administration)", check))
