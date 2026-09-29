#!/usr/bin/env python3
"""The downloads the app pins, as the app itself has them.

The app is the single source of every pinned URL and checksum (linux/LinuxPins.kt). A unit test
(LinuxPinsTest) writes them to app/build/engine/pins.json, so CI checks exactly what the app will
fetch, never a copy that could drift from it.

Usage: pins.py                list every pin as "name url sha256 bytes"
       pins.py --get NAME     print "url sha256 bytes" for one pin (e.g. ubuntuBase-arm64)
Environment: POCKETIDE_PINS, another pins.json.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path

DEFAULT = Path(__file__).resolve().parent.parent / "app" / "build" / "engine" / "pins.json"


@dataclass(frozen=True)
class Pin:
    name: str
    url: str
    sha256: str
    bytes: int | None
    source: str


def all_pins(path: Path | None = None) -> list[Pin]:
    path = path or Path(os.environ.get("POCKETIDE_PINS", DEFAULT))
    if not path.is_file():
        raise FileNotFoundError(f"{path} is missing: run the app's unit tests first (LinuxPinsTest writes it)")
    pins = json.loads(path.read_text(encoding="utf-8"))["pins"]
    return [Pin(name, pin["url"], pin["sha256"], pin.get("bytes"), "linux/LinuxPins.kt") for name, pin in sorted(pins.items())]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--get", metavar="NAME", help="print one pin as 'url sha256 bytes'")
    args = parser.parse_args()
    try:
        pins = all_pins()
    except (OSError, ValueError, KeyError) as error:
        print(f"pins.py: {error}", file=sys.stderr)
        return 1
    if args.get:
        found = [pin for pin in pins if pin.name == args.get]
        if len(found) != 1:
            print(f"no pin named {args.get}", file=sys.stderr)
            return 1
        pin = found[0]
        print(pin.url, pin.sha256, pin.bytes if pin.bytes is not None else "-")
        return 0
    for pin in pins:
        print(pin.name, pin.url, pin.sha256, pin.bytes if pin.bytes is not None else "-")
    return 0


if __name__ == "__main__":
    sys.exit(main())
