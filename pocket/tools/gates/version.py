#!/usr/bin/env python3
"""The app's version, read from app/build.gradle.kts without running Gradle.

3.x must install over 2.6.0 (versionCode 260) as an update, so versionCode is at least 300 and
versionName is 3.<minor>.<patch>. The release tag is pocketide-v<versionName>, and a published
tag is never replaced, so this is also the number to raise for a new release.

Usage: version.py            check the version
       version.py --print    print versionName (for the workflow)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import common

MIN_CODE = 300
NAME_SHAPE = re.compile(r"3\.\d+\.\d+")


def read(root: Path) -> tuple[list[int], list[str]]:
    text = (common.app(root) / "build.gradle.kts").read_text(encoding="utf-8")
    codes = [int(v) for v in re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", text, re.M)]
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]*)"\s*$', text, re.M)
    return codes, names


def check(root: Path = common.POCKET) -> common.Report:
    report = common.Report()
    codes, names = read(root)
    if len(codes) != 1:
        report.fail(f"app/build.gradle.kts must set versionCode exactly once as a number (found {len(codes)})")
    elif codes[0] < MIN_CODE:
        report.fail(f"versionCode {codes[0]} is below {MIN_CODE}; it would not install over 2.6.0 (260)")
    if len(names) != 1:
        report.fail(f'app/build.gradle.kts must set versionName = "3.x.y" exactly once (found {len(names)})')
    elif not NAME_SHAPE.fullmatch(names[0]):
        report.fail(f"versionName '{names[0]}' is not 3.<minor>.<patch>")
    if report.ok:
        report.note(f"versionName {names[0]}, versionCode {codes[0]}")
    return report


def version_name(root: Path = common.POCKET) -> str:
    report = check(root)
    if not report.ok:
        raise SystemExit("\n".join(report.problems))
    return read(root)[1][0]


if __name__ == "__main__":
    if sys.argv[1:] == ["--print"]:
        print(version_name())
        sys.exit(0)
    sys.exit(common.run_standalone("version", check))
