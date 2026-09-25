#!/usr/bin/env python3
"""Starts a room's engine (code-server or Antigravity's hub) after the steps that must happen
inside the room itself, then becomes that engine (exec), so stopping the room stops it.

Usage: room.py <agent-id> -- <program> [arguments...]

The steps touch files the app must never read, so they run here, in Linux:
  - files created from now on are private to the room (umask 077);
  - Claude: PocketIDE's MCP servers are merged into ~/.claude.json, which also holds the
    account's sign-in state (POCKETIDE_CLAUDE_MCP carries the entries; null removes one);
  - Antigravity: if the hub has no sign-in yet but the CLI has one, the CLI's is copied for the
    hub (owner-only), because some agy versions keep the two in different files.
"""

import json
import os
import shutil
import sys
import tempfile

HOME = os.path.expanduser("~")
CLAUDE_STATE = os.path.join(HOME, ".claude.json")
HUB_TOKEN = os.path.join(HOME, ".gemini", "jetski-standalone-oauth-token")
CLI_TOKEN = os.path.join(HOME, ".gemini", "antigravity", "antigravity-oauth-token")


def say(text):
    print("[room] " + text, flush=True)


def regular_file(path):
    return os.path.isfile(path) and not os.path.islink(path)


def write_private(path, data):
    folder = os.path.dirname(path)
    handle, temporary = tempfile.mkstemp(prefix=".pocketide-", dir=folder)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as out:
            out.write(data)
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def merge_claude_servers(entries):
    """Sets or removes PocketIDE's entries in ~/.claude.json, keeping everything else."""
    if os.path.lexists(CLAUDE_STATE) and not regular_file(CLAUDE_STATE):
        say("~/.claude.json is not a plain file; MCP servers were not registered.")
        return
    state = {}
    if os.path.exists(CLAUDE_STATE):
        try:
            with open(CLAUDE_STATE, encoding="utf-8") as source:
                state = json.load(source)
        except (OSError, ValueError):
            say("~/.claude.json could not be read; it was left as it is.")
            return
        if not isinstance(state, dict):
            say("~/.claude.json is not a JSON object; it was left as it is.")
            return
    servers = state.get("mcpServers")
    if not isinstance(servers, dict):
        servers = {}
    changed = False
    for name, entry in entries.items():
        if entry is None:
            changed = servers.pop(name, None) is not None or changed
        elif servers.get(name) != entry:
            servers[name] = entry
            changed = True
    if not changed and "mcpServers" in state:
        return
    state["mcpServers"] = servers
    write_private(CLAUDE_STATE, json.dumps(state, indent=2) + "\n")
    say("Registered PocketIDE's tools with Claude Code.")


def share_antigravity_sign_in():
    if os.path.lexists(HUB_TOKEN) or not regular_file(CLI_TOKEN):
        return
    folder = os.path.dirname(HUB_TOKEN)
    handle, temporary = tempfile.mkstemp(prefix=".pocketide-", dir=folder)
    os.close(handle)
    try:
        shutil.copyfile(CLI_TOKEN, temporary, follow_symlinks=False)
        os.chmod(temporary, 0o600)
        os.replace(temporary, HUB_TOKEN)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise
    say("The hub had no sign-in; the Antigravity CLI's sign-in was copied for it.")


def main(argv):
    if len(argv) < 3 or argv[1] != "--":
        print("usage: room.py <agent-id> -- <program> [arguments...]", file=sys.stderr)
        return 2
    agent, command = argv[0], argv[2:]
    os.umask(0o077)
    claude_entries = os.environ.pop("POCKETIDE_CLAUDE_MCP", None)
    try:
        if agent == "claude" and claude_entries:
            merge_claude_servers(json.loads(claude_entries))
        if agent == "antigravity":
            share_antigravity_sign_in()
    except (OSError, ValueError) as problem:
        # The engine still starts: a room without PocketIDE's tools beats no room at all.
        say("A set-up step did not finish (%s)." % problem.__class__.__name__)
    try:
        os.execvp(command[0], command)
    except OSError as problem:
        say("%s could not start (%s)." % (command[0], problem.strerror or problem.__class__.__name__))
        return 127


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
