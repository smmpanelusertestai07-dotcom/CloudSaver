#!/usr/bin/env python3
"""PIDE-READY must be printed only after the port is known to answer.

An earlier project in this repository printed its ready marker before the server was listening
and spent a release convinced a working server had failed."""
import re, sys

text = open(sys.argv[1]).read()
lines = text.splitlines()

marker_line = next((i for i, line in enumerate(lines) if "PIDE-READY" in line and "say" in line), None)
if marker_line is None:
    print("  no PIDE-READY announcement found", file=sys.stderr)
    sys.exit(1)

# The probe that proves the port answers has to come before it, in the same block.
window = "\n".join(lines[max(0, marker_line - 12):marker_line])
if "/dev/tcp/" not in window:
    print("  PIDE-READY is not preceded by a check that the port answers", file=sys.stderr)
    sys.exit(1)
sys.exit(0)
