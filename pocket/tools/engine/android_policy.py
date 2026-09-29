#!/usr/bin/env python3
"""The syscalls Android lets an app use, for the engine test to run the computer under.

Android puts every app process under a seccomp filter: a syscall its C library (bionic) does not
use is blocked with SIGSYS, and PRoot has to run such a call another way. The filter is built
from bionic's syscall lists (libc/SYSCALLS.TXT plus the app allow and block lists); this tool
reads the lists of the oldest Android the app supports, pinned by commit and SHA-256, and prints
the numbers of the allowed syscalls on this machine's architecture, comma-separated, for
android-seccomp.c.

Usage: android_policy.py [--api 29] [--cache DIR]
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

# Android 10 (API 29, the app's minSdk): bionic's android10-release branch, from Google's mirror.
LISTS = {
    29: {
        "url": "https://raw.githubusercontent.com/aosp-mirror/platform_bionic/290c0cb5044b643e5d6cbcb1a5b275541ca3a89e/libc/",
        "allow": {
            "SYSCALLS.TXT": "d7538c6d4850d5cff188502762b9180a792415071bdc52b39ce507db91622994",
            "SECCOMP_WHITELIST_COMMON.TXT": "905d9ce1b731dca57a14635720a623f317ca999c4a9ac22c2481665dc8f81c79",
            "SECCOMP_WHITELIST_APP.TXT": "46b2fe70fa645154344c3792737fa547212076e3505205f555d071bcd0057497",
        },
        "block": {
            "SECCOMP_BLACKLIST_COMMON.TXT": "aafbcd20704317ff2c9f49130461299edbc37d40307e52879aa6ecbbc9ff905f",
            "SECCOMP_BLACKLIST_APP.TXT": "1d5d8ca60e74325bec9538f858dd35d233287f19ff91a9a88bb309ea085f4621",
        },
    },
}
ARCHES = {"aarch64": "arm64", "arm64": "arm64", "x86_64": "x86_64"}
# One entry: "return_type func[|alias...][:syscall[:socketcall]](args) arches".
ENTRY = re.compile(r"^(.*?)\((.*)\)\s+(\S+)\s*$")


def syscalls(text: str, arch: str) -> set[str]:
    """The syscall names a bionic list gives for [arch] ("arm64" or "x86_64")."""
    names = set()
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        match = ENTRY.match(line) if line else None
        if not match:
            continue
        spec = match.group(1).split()[-1]
        parts = spec.split(":")
        name = (parts[1] if len(parts) > 1 else parts[0]).split("|")[0]
        arches = match.group(3).split(",")
        if "all" in arches or arch in arches or "lp64" in arches:
            names.add(name)
    return names


def allowed(texts: dict[str, str], lists: dict, arch: str) -> set[str]:
    names: set[str] = set()
    for name in lists["allow"]:
        names |= syscalls(texts[name], arch)
    for name in lists["block"]:
        names -= syscalls(texts[name], arch)
    return names


def fetch(lists: dict, cache: Path) -> dict[str, str]:
    """Every list, from the cache or the pinned commit, checked against its SHA-256."""
    cache.mkdir(parents=True, exist_ok=True)
    texts = {}
    for name, sha256 in {**lists["allow"], **lists["block"]}.items():
        path = cache / name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != sha256:
            with urllib.request.urlopen(lists["url"] + name, timeout=60) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != sha256:
                raise ValueError(f"{name} does not match its pinned SHA-256")
            path.write_bytes(data)
        texts[name] = path.read_text(encoding="utf-8")
    return texts


def numbers(names: set[str]) -> list[int]:
    """The numbers of [names] on this machine, from its kernel headers (names it lacks are left out)."""
    lines = ["#include <stdio.h>", "#include <sys/syscall.h>", "int main(void) {"]
    for name in sorted(names):
        lines += [f"#ifdef __NR_{name}", f'  printf("%d\\n", __NR_{name});', "#endif"]
    lines += ["  return 0;", "}"]
    with tempfile.TemporaryDirectory() as work:
        source = Path(work, "nr.c")
        source.write_text("\n".join(lines) + "\n", encoding="utf-8")
        subprocess.run([os.environ.get("CC", "cc"), "-o", str(Path(work, "nr")), str(source)], check=True)
        out = subprocess.run([str(Path(work, "nr"))], check=True, capture_output=True, text=True).stdout
    return sorted({int(n) for n in out.split()})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--api", type=int, default=29, choices=sorted(LISTS))
    parser.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "pocketide-bionic")
    args = parser.parse_args()
    arch = ARCHES.get(os.uname().machine)
    if arch is None:
        print(f"android_policy.py: no Android policy for {os.uname().machine}", file=sys.stderr)
        return 1
    lists = LISTS[args.api]
    try:
        names = allowed(fetch(lists, args.cache / str(args.api)), lists, arch)
        print(",".join(str(n) for n in numbers(names)))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"android_policy.py: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
