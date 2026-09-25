#!/usr/bin/env python3
"""Tells the phone that an agent needs the owner, or finished a turn, so it can show a
notification. Claude Code runs it as a Notification hook (the event arrives on stdin); Codex
runs it as its notify program (the event is the last argument). It never fails the agent.

Usage: notify.py <agent-id> [event-json]
"""

import json
import os
import socket
import sys

SOCKET_PATH = os.environ.get("POCKETIDE_PHONE_SOCKET", "/run/pocketide/phone.sock")
TIMEOUT_S = 5
MAX_TEXT = 200


def read_event(argv):
    raw = argv[1] if len(argv) > 1 else sys.stdin.read(64 * 1024)
    try:
        event = json.loads(raw)
    except ValueError:
        return {}
    return event if isinstance(event, dict) else {}


def describe(event):
    """(kind, text) for the phone, or None when there is nothing to say."""
    if event.get("hook_event_name") == "Notification" or "message" in event:
        return "needs_you", str(event.get("message") or "")
    if event.get("type") == "agent-turn-complete":
        return "turn_done", str(event.get("last-assistant-message") or "")
    return None


def main(argv):
    if not argv:
        return 0
    described = describe(read_event(argv))
    if described is None:
        return 0
    kind, text = described
    request = {
        "id": 1,
        "op": "notify",
        "args": {"kind": kind, "text": " ".join(text.split())[:MAX_TEXT], "cwd": os.getcwd()},
    }
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as phone:
            phone.settimeout(TIMEOUT_S)
            phone.connect(SOCKET_PATH)
            phone.sendall(json.dumps(request).encode("utf-8") + b"\n")
            phone.makefile("rb").readline(64 * 1024)
    except OSError:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
