#!/usr/bin/env python3
"""Checks the window rules the workspace writes are XML openbox will actually read.

A malformed rc.xml is not an error openbox reports: it ignores the file and gives every window
a desktop's title bar and a desktop's position, which on a phone is the bug this file exists to
prevent. So the block is parsed here, before it ever reaches a phone.
"""
import re
import sys
import xml.dom.minidom

text = open(sys.argv[1], encoding="utf-8").read()
block = re.search(r"<\?xml.*?</openbox_config>", text, re.S)
if not block:
    raise SystemExit("no openbox rules found in " + sys.argv[1])
xml.dom.minidom.parseString(block.group(0))
