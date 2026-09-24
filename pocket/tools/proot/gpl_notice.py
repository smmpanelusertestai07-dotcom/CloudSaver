#!/usr/bin/env python3
"""The GPL notice published beside each release, for the PRoot actually inside the APK.

It reads the version string inside the APK's lib/arm64-v8a/libproot.so, finds that release in
sources.json, and writes where its exact source is: PRoot (GPL-2.0), talloc (LGPL-3.0-or-later)
and libandroid-shmem (BSD-3-Clause). An APK whose PRoot is not in sources.json fails, because
publishing a GPL binary without its source is not an option. With --sources DIR it also
downloads the archives that have a pinned SHA-256 into DIR, verified, to attach to the release.

Usage: gpl_notice.py APK --app-version X.Y.Z [--out FILE] [--sources DIR]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

SOURCES_JSON = Path(__file__).with_name("sources.json")
LIBPROOT = "lib/arm64-v8a/libproot.so"
VERSION = re.compile(rb"(?<![\d.])(\d+\.\d+\.\d+\.\d+)(?![\d.])")


class NoticeError(Exception):
    pass


def releases(path: Path = SOURCES_JSON) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8"))["releases"]


def shipped_release(libproot: bytes, known: list[dict]) -> dict:
    versions = {v.decode() for v in VERSION.findall(libproot)}
    matches = [r for r in known if r["version"] in versions]
    if len(matches) != 1:
        found = ", ".join(sorted(versions)) or "none"
        raise NoticeError(f"libproot.so carries version strings [{found}], which name "
                          f"{len(matches)} releases in sources.json; add its source there before releasing")
    return matches[0]


def notice(release: dict, app_version: str) -> str:
    proot, talloc, shmem = release["proot"], release["talloc"], release["libandroid_shmem"]
    lines = [
        f"PocketIDE {app_version}: source for the GPL and LGPL parts inside the APK",
        "",
        "PocketIDE's own code is under the Apache License 2.0. The APK also carries PRoot, which",
        "runs the Ubuntu computer, and the libraries PRoot uses. Their licences and exact source:",
        "",
        f"PRoot {release['version']} (libproot.so, libproot-loader.so), {proot['license']}",
        f"  Termux's PRoot, tag {proot['tag']}, commit {proot['commit']}",
        "  https://github.com/termux/proot",
        f"  {proot['url']}",
    ]
    if proot.get("sha256"):
        lines.append(f"  SHA-256 of that archive: {proot['sha256']}")
    for title, part in (("talloc", talloc), ("libandroid-shmem", shmem)):
        lines += ["", f"{title}, {part['license']}", f"  Version: {part['version']}", f"  {part['url']}"]
        if part.get("sha256"):
            lines.append(f"  SHA-256 of that archive: {part['sha256']}")
    lines += [
        "",
        f"How this PRoot was made: {release['origin']}",
    ]
    if release.get("build"):
        lines += [
            "The build script is pocket/tools/proot/build-proot.sh in this repository, at the commit",
            "this release was built from, and the archives above are attached to this release.",
        ]
    lines += [
        "",
        "The GNU General Public License, version 2: https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt",
        "The GNU Lesser General Public License, version 3: https://www.gnu.org/licenses/lgpl-3.0.txt",
        "",
    ]
    return "\n".join(lines)


def download_verified(url: str, sha256: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "pocketide-ci"})
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != sha256:
        raise NoticeError(f"{url} now hashes to {actual}, not the pinned {sha256}")
    destination.write_bytes(data)


def fetch_sources(release: dict, directory: Path) -> list[Path]:
    directory.mkdir(parents=True, exist_ok=True)
    saved = []
    for key in ("proot", "talloc", "libandroid_shmem"):
        part = release[key]
        if part.get("sha256"):
            name = f"{key.replace('_', '-')}-{part.get('version') or release['version']}-source{_suffix(part['url'])}"
            path = directory / name
            download_verified(part["url"], part["sha256"], path)
            saved.append(path)
    return saved


def _suffix(url: str) -> str:
    for suffix in (".tar.gz", ".tar.xz", ".zip"):
        if url.endswith(suffix):
            return suffix
    return ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk", type=Path)
    parser.add_argument("--app-version", required=True)
    parser.add_argument("--out", type=Path, help="write the notice here instead of printing it")
    parser.add_argument("--sources", type=Path, help="download the pinned source archives into this folder")
    args = parser.parse_args()
    try:
        with zipfile.ZipFile(args.apk) as apk:
            libproot = apk.read(LIBPROOT)
        release = shipped_release(libproot, releases())
        text = notice(release, args.app_version)
        if args.out:
            args.out.write_text(text, encoding="utf-8")
        else:
            print(text)
        if args.sources:
            for path in fetch_sources(release, args.sources):
                print(f"source attached: {path.name}", file=sys.stderr)
    except (NoticeError, KeyError, OSError, zipfile.BadZipFile) as error:
        print(f"gpl_notice: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
