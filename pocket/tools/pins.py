#!/usr/bin/env python3
"""The downloads the app pins, read straight from its Kotlin sources.

The app is the single source of every pinned URL and checksum (for example linux/LinuxPins.kt:
PinnedDownload(url = "…", sha256 = "…", bytes = …)). CI reads them from there rather than
repeating them, so the engine test builds the same Ubuntu the phone does, and the pins check
verifies exactly what the app will fetch.

Usage: pins.py                list every pin as "name url sha256 bytes"
       pins.py --get NAME     print "url sha256 bytes" for one pin (e.g. ubuntuBase)
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

SOURCES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "java"
DECLARATION = re.compile(r"\bval\s+(\w+)\s*(?::\s*\w+\s*)?=\s*\w+\s*\(")
ARGUMENT = re.compile(r'\b(url|sha256|bytes)\s*=\s*("(?P<text>[^"$]*)"|(?P<number>[\d_]+)L?)')


@dataclass(frozen=True)
class Pin:
    name: str
    url: str
    sha256: str
    bytes: int | None
    source: str


def _call_body(text: str, start: int) -> str:
    """The argument list of the call whose "(" sits just before start."""
    depth = 1
    for index in range(start, len(text)):
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
            if depth == 0:
                return text[start:index]
    return text[start:]


def parse(text: str, source: str = "") -> list[Pin]:
    pins = []
    for match in DECLARATION.finditer(text):
        values = {}
        for argument in ARGUMENT.finditer(_call_body(text, match.end())):
            key = argument.group(1)
            values[key] = argument.group("text") if argument.group("text") is not None \
                else int(argument.group("number").replace("_", ""))
        if isinstance(values.get("url"), str) and re.fullmatch(r"[0-9a-f]{64}", str(values.get("sha256", ""))):
            size = values.get("bytes")
            pins.append(Pin(match.group(1), values["url"], values["sha256"],
                            size if isinstance(size, int) else None, source))
    return pins


def all_pins(sources: Path = SOURCES) -> list[Pin]:
    pins = []
    for path in sorted(sources.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        if "sha256" in text:
            pins.extend(parse(text, str(path.relative_to(sources))))
    return pins


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--get", metavar="NAME", help="print one pin as 'url sha256 bytes'")
    args = parser.parse_args()
    pins = all_pins()
    if args.get:
        found = [p for p in pins if p.name == args.get]
        if len(found) != 1:
            print(f"expected one pin named {args.get} in the Kotlin sources, found {len(found)}", file=sys.stderr)
            return 1
        pin = found[0]
        print(pin.url, pin.sha256, pin.bytes if pin.bytes is not None else "-")
        return 0
    for pin in pins:
        print(pin.name, pin.url, pin.sha256, pin.bytes if pin.bytes is not None else "-")
    return 0


if __name__ == "__main__":
    sys.exit(main())
