#!/usr/bin/env python3
"""Starts a room's engine (code-server or Antigravity's hub) after the steps that must happen
inside the room itself, then becomes that engine (exec), so stopping the room stops it.

Usage: room.py <agent-id> -- <program> [arguments...]

The steps touch files the app must never read, so they run here, in Linux:
  - files created from now on are private to the room (umask 077);
  - Claude: PocketIDE's MCP servers are merged into ~/.claude.json, which also holds the
    account's sign-in state (POCKETIDE_CLAUDE_MCP carries the entries; null removes one).
    Everything else there that can start a program (Claude's own or a project's MCP servers, a
    project's approval of its .mcp.json servers or of tools) is taken out unless the owner kept
    it (POCKETIDE_CLAUDE_KEEP lists the SHA-256 of each kept one): it is held in a file in the
    room and listed for the owner (POCKETIDE_HELD_REPORT), and a kept one comes back only while
    what was held still has that SHA-256. When that cannot be done, Claude is not started;
  - Antigravity: if the hub has no sign-in yet but the CLI has one, the CLI's is copied for the
    hub (owner-only), because some agy versions keep the two in different files.
"""

import hashlib
import json
import os
import shutil
import sys
import tempfile

HOME = os.path.expanduser("~")
CLAUDE_STATE = os.path.join(HOME, ".claude.json")
# Both hold what ~/.claude.json held, sign-in included: the app counts them as sign-in files.
UNREADABLE_STATE = os.path.join(HOME, ".claude.json.pocketide-unreadable")
HELD_SETTINGS = os.path.join(HOME, ".claude", ".pocketide-held-servers.json")
HUB_TOKEN = os.path.join(HOME, ".gemini", "jetski-standalone-oauth-token")
CLI_TOKEN = os.path.join(HOME, ".gemini", "antigravity", "antigravity-oauth-token")

# A project's approvals in ~/.claude.json: of its own servers (.mcp.json), and of tools.
PROJECT_LISTS = ("enabledMcpjsonServers", "allowedTools")
PROJECT_SWITCHES = ("enableAllProjectMcpServers",)
MAX_HELD = 200
MAX_REPORTED = 100


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


def canonical(scope, field, name, value):
    """
    One setting as the text its SHA-256 is taken of: its scope ("" for Claude's own, else the
    project's folder), where it is, its name and its value. ASCII only, so the app hashes the
    very same bytes.
    """
    return json.dumps([scope, field, name, value], sort_keys=True, separators=(",", ":"))


def fingerprint(text):
    return hashlib.sha256(text.encode("utf-8", "surrogatepass")).hexdigest()


def read_state():
    """
    ~/.claude.json as Claude reads it (through a link, a byte-order mark skipped, broken UTF-8
    replaced), and whether it must be written even if nothing in it changes. A link is replaced
    by a file of its own. Anything PocketIDE cannot read as settings is moved aside and started
    again, because Claude might still find servers in it.
    """
    if not os.path.lexists(CLAUDE_STATE):
        return {}, True
    state = None
    if os.path.isfile(CLAUDE_STATE):
        try:
            with open(CLAUDE_STATE, encoding="utf-8-sig", errors="replace") as source:
                state = json.load(source)
        except (OSError, ValueError, RecursionError):
            state = None
    if isinstance(state, dict):
        return state, os.path.islink(CLAUDE_STATE)
    os.replace(CLAUDE_STATE, UNREADABLE_STATE)
    say("~/.claude.json could not be read as Claude's settings; it was moved to ~/.claude.json.pocketide-unreadable.")
    return {}, True


def read_held():
    """What was taken out before: SHA-256 -> the setting's canonical text."""
    if not regular_file(HELD_SETTINGS):
        return {}
    try:
        with open(HELD_SETTINGS, encoding="utf-8") as source:
            held = json.load(source)
    except (OSError, ValueError, RecursionError):
        return {}
    return {k: v for k, v in held.items() if isinstance(k, str) and isinstance(v, str)} if isinstance(held, dict) else {}


def write_held(held):
    if held:
        os.makedirs(os.path.dirname(HELD_SETTINGS), exist_ok=True)
        # The oldest go first; a kept one is never here for long (it goes back into ~/.claude.json).
        newest = dict(list(held.items())[-MAX_HELD:])
        write_private(HELD_SETTINGS, json.dumps(newest, indent=2) + "\n")
    elif os.path.lexists(HELD_SETTINGS):
        os.unlink(HELD_SETTINGS)


def take_out(state, entries, decide):
    """
    Takes out of [state] every setting that can start a program and that [decide] does not let
    stay; PocketIDE's own servers ([entries]) are left for the caller. A place holding the wrong
    kind of value counts as one setting, which cannot be put back. True when [state] changed.
    """
    changed = False

    def servers(holder, scope):
        nonlocal changed
        table = holder.get("mcpServers")
        if table is None:
            return
        if not isinstance(table, dict):
            decide(scope, "mcpServers", "", table, False)
            holder["mcpServers"] = {}
            changed = True
            return
        for name, server in list(table.items()):
            if not scope and name in entries:
                continue
            if not decide(scope, "mcpServers", name, server, bool(name)):
                del table[name]
                changed = True

    servers(state, "")
    projects = state.get("projects")
    if not isinstance(projects, dict):
        return changed
    for folder, project in projects.items():
        if not isinstance(project, dict):
            continue
        servers(project, folder)
        for field in PROJECT_LISTS:
            items = project.get(field)
            if items is None:
                continue
            if not isinstance(items, list):
                decide(folder, field, "", items, False)
                del project[field]
                changed = True
                continue
            staying = [item for item in items if decide(folder, field, item, None, True)]
            if len(staying) != len(items):
                project[field] = staying
                changed = True
        for field in PROJECT_SWITCHES:
            value = project.get(field)
            if value in (None, False):
                continue
            if not decide(folder, field, "", value, True):
                del project[field]
                changed = True
    return changed


def made(holder, key, kind):
    """holder[key], made empty when missing or null; None when it holds something else."""
    if holder.get(key) is None:
        holder[key] = kind()
    return holder[key] if isinstance(holder[key], kind) else None


def holder_of(state, scope):
    """Claude's own settings ("") or a project's, made when missing; None when [state] has no room for them."""
    if not scope:
        return state
    projects = made(state, "projects", dict)
    return None if projects is None else made(projects, scope, dict)


def put_back(state, text):
    """Puts one kept setting back where it was taken from; False when it no longer fits there."""
    try:
        scope, field, name, value = json.loads(text)
    except (ValueError, TypeError, RecursionError):
        return False
    holder = holder_of(state, scope) if isinstance(scope, str) else None
    if holder is None:
        return False
    if field == "mcpServers" and isinstance(name, str) and name:
        table = made(holder, "mcpServers", dict)
        if table is None:
            return False
        table.setdefault(name, value)
        return True
    if scope and field in PROJECT_LISTS and value is None:
        items = made(holder, field, list)
        if items is None:
            return False
        if name not in items:
            items.append(name)
        return True
    if scope and field in PROJECT_SWITCHES:
        holder[field] = value
        return True
    return False


def report_taken_out(report, items):
    """Adds [items] to the list the app has not read yet (it removes the list once read)."""
    listed = []
    if regular_file(report):
        try:
            with open(report, encoding="utf-8") as source:
                listed = json.load(source)
        except (OSError, ValueError, RecursionError):
            listed = []
        if not isinstance(listed, list):
            listed = []
    known = {item.get("digest") for item in listed if isinstance(item, dict)}
    listed += [item for item in items if item["digest"] not in known]
    write_private(report, json.dumps(listed[-MAX_REPORTED:]) + "\n")


def merge_claude_servers(entries, keep=frozenset(), report=None):
    """
    Sets or removes PocketIDE's entries in ~/.claude.json and takes out everything else there
    that can start a program and that the owner has not kept (held, and listed in [report] for
    the owner); kept ones come back. The rest of the file stays as it is.
    """
    state, changed = read_state()
    held = read_held()
    taken = {}

    def decide(scope, field, name, value, keepable):
        text = canonical(scope, field, name, value)
        digest = fingerprint(text)
        if keepable and digest in keep:
            return True
        if keepable:
            held[digest] = text
        taken[digest] = {"digest": digest, "entry": text, "keepable": keepable}
        return False

    changed = take_out(state, entries, decide) or changed
    for digest in sorted(keep):
        text = held.get(digest)
        # A setting changed while it was held is not the one the owner kept.
        if text is None or fingerprint(text) != digest:
            continue
        if put_back(state, text):
            del held[digest]
            changed = True
    own = made(state, "mcpServers", dict)
    for name, entry in entries.items():
        if entry is None:
            changed = own.pop(name, None) is not None or changed
        elif own.get(name) != entry:
            own[name] = entry
            changed = True
    # Held before the file lets go of them, so nothing is lost if a write fails.
    write_held(held)
    if changed:
        write_private(CLAUDE_STATE, json.dumps(state, indent=2) + "\n")
        say("Registered PocketIDE's tools with Claude Code.")
    if taken:
        say("%d setting(s) that can start a program were taken out of ~/.claude.json; they wait for the owner in PocketIDE." % len(taken))
        if report:
            try:
                report_taken_out(report, list(taken.values()))
            except OSError as problem:
                say("They could not be listed for PocketIDE (%s)." % problem.__class__.__name__)


def kept_digests(text):
    """The SHA-256s the owner kept, from POCKETIDE_CLAUDE_KEEP; nothing when it is unreadable."""
    try:
        values = json.loads(text or "[]")
    except ValueError:
        return frozenset()
    if not isinstance(values, list):
        return frozenset()
    return frozenset(v for v in values if isinstance(v, str) and len(v) == 64)


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
    claude_keep = kept_digests(os.environ.pop("POCKETIDE_CLAUDE_KEEP", None))
    held_report = os.environ.pop("POCKETIDE_HELD_REPORT", None)
    if agent == "claude" and claude_entries:
        try:
            merge_claude_servers(json.loads(claude_entries), claude_keep, held_report)
        except Exception as problem:
            # Whatever stopped it: started now, Claude could start a server nobody approved.
            say("Claude Code was not started: its settings could not be checked for programs an agent added (%s)." % problem.__class__.__name__)
            return 1
    try:
        if agent == "antigravity":
            share_antigravity_sign_in()
    except (OSError, ValueError) as problem:
        # The engine still starts: a room without the shared sign-in beats no room at all.
        say("A set-up step did not finish (%s)." % problem.__class__.__name__)
    try:
        os.execvp(command[0], command)
    except OSError as problem:
        say("%s could not start (%s)." % (command[0], problem.strerror or problem.__class__.__name__))
        return 127


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
