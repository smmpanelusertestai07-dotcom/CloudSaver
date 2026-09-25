"""room.py, notify.py and the browser installer, run as the room runs them. The room's
xdg-open is the bridge module's (PhoneGuestTools), tested with the bridge."""

import importlib.util
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest

from tests.rooms_support import FakePhone, script


def run(name, args, home=None, env=None, stdin=None):
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    if home is not None:
        environment["HOME"] = home
    environment.update(env or {})
    return subprocess.run(
        [sys.executable, script(name)] + args, env=environment, input=stdin, capture_output=True, timeout=30,
    )


class RoomLauncherTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="home-")

    def tearDown(self):
        shutil.rmtree(self.home)

    def claude_state(self):
        with open(os.path.join(self.home, ".claude.json")) as source:
            return json.load(source)

    def test_claude_servers_are_merged_and_everything_else_is_kept(self):
        with open(os.path.join(self.home, ".claude.json"), "w") as out:
            json.dump({"oauthAccount": {"emailAddress": "o@example.com"}, "mcpServers": {"mine": {"command": "x"}, "old": {"command": "y"}}}, out)
        entries = {"pocketide": {"type": "stdio", "command": "python3", "args": ["/opt/pocketide/mcp.py"], "env": {}}, "old": None}
        result = run("room.py", ["claude", "--", "/bin/true"], self.home, {"POCKETIDE_CLAUDE_MCP": json.dumps(entries)})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        state = self.claude_state()
        self.assertEqual({"emailAddress": "o@example.com"}, state["oauthAccount"])
        self.assertEqual({"command": "x"}, state["mcpServers"]["mine"])
        self.assertEqual(entries["pocketide"], state["mcpServers"]["pocketide"])
        self.assertNotIn("old", state["mcpServers"])
        mode = stat.S_IMODE(os.stat(os.path.join(self.home, ".claude.json")).st_mode)
        self.assertEqual(0o600, mode)

    def test_a_fresh_home_gets_the_file(self):
        entries = {"pocketide": {"type": "stdio", "command": "python3", "args": [], "env": {}}}
        run("room.py", ["claude", "--", "/bin/true"], self.home, {"POCKETIDE_CLAUDE_MCP": json.dumps(entries)})
        self.assertEqual({"pocketide": entries["pocketide"]}, self.claude_state()["mcpServers"])

    def test_an_unreadable_state_file_is_left_alone(self):
        path = os.path.join(self.home, ".claude.json")
        with open(path, "w") as out:
            out.write("{broken")
        result = run("room.py", ["claude", "--", "/bin/true"], self.home, {"POCKETIDE_CLAUDE_MCP": "{\"pocketide\": null}"})
        self.assertEqual(0, result.returncode)
        with open(path) as source:
            self.assertEqual("{broken", source.read())

    def test_the_engine_does_not_inherit_the_entries_and_runs_with_a_private_umask(self):
        result = run(
            "room.py", ["codex", "--", "/bin/sh", "-c", "umask; echo \"[${POCKETIDE_CLAUDE_MCP-}]\""],
            self.home, {"POCKETIDE_CLAUDE_MCP": "{}"},
        )
        self.assertEqual(0, result.returncode)
        lines = result.stdout.decode().split()
        self.assertEqual("0077", lines[0])
        self.assertEqual("[]", lines[1])

    def test_the_hub_gets_the_cli_sign_in_only_when_it_has_none(self):
        gemini = os.path.join(self.home, ".gemini")
        os.makedirs(os.path.join(gemini, "antigravity"))
        with open(os.path.join(gemini, "antigravity", "antigravity-oauth-token"), "w") as out:
            out.write("cli-token")
        run("room.py", ["antigravity", "--", "/bin/true"], self.home)
        hub = os.path.join(gemini, "jetski-standalone-oauth-token")
        with open(hub) as source:
            self.assertEqual("cli-token", source.read())
        self.assertEqual(0o600, stat.S_IMODE(os.stat(hub).st_mode))
        with open(hub, "w") as out:
            out.write("hub-token")
        run("room.py", ["antigravity", "--", "/bin/true"], self.home)
        with open(hub) as source:
            self.assertEqual("hub-token", source.read())

    def test_a_missing_program_is_reported(self):
        result = run("room.py", ["codex", "--", "/nonexistent/engine"], self.home)
        self.assertEqual(127, result.returncode)
        self.assertIn(b"could not start", result.stdout)


class PhoneClientsTest(unittest.TestCase):
    def setUp(self):
        self.phone = FakePhone(lambda request: {"ok": True, "result": {}})
        self.env = {"POCKETIDE_PHONE_SOCKET": self.phone.path}

    def tearDown(self):
        self.phone.close()

    def test_notify_reads_claude_hooks_and_codex_events(self):
        run("notify.py", ["claude"], env=self.env, stdin=json.dumps({"hook_event_name": "Notification", "message": "Claude needs\nyour permission"}).encode())
        self.assertEqual("needs_you", self.phone.requests[-1]["args"]["kind"])
        self.assertEqual("Claude needs your permission", self.phone.requests[-1]["args"]["text"])
        run("notify.py", ["codex", json.dumps({"type": "agent-turn-complete", "last-assistant-message": "x" * 500})], env=self.env)
        self.assertEqual("turn_done", self.phone.requests[-1]["args"]["kind"])
        self.assertEqual(200, len(self.phone.requests[-1]["args"]["text"]))
        count = len(self.phone.requests)
        self.assertEqual(0, run("notify.py", ["codex", "not json"], env=self.env).returncode)
        self.assertEqual(count, len(self.phone.requests))

    def test_notify_never_fails_the_agent(self):
        result = run("notify.py", ["claude"], env={"POCKETIDE_PHONE_SOCKET": "/nonexistent"}, stdin=b'{"message": "hi"}')
        self.assertEqual(0, result.returncode)


class BrowserInstallerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("install", script(os.path.join("browser", "install.py")))
        cls.install = importlib.util.module_from_spec(spec)
        # No __pycache__ beside the asset: the build would ship it into the computer.
        writes_bytecode = sys.dont_write_bytecode
        sys.dont_write_bytecode = True
        try:
            spec.loader.exec_module(cls.install)
        finally:
            sys.dont_write_bytecode = writes_bytecode

    def write_lock(self, packages):
        folder = tempfile.mkdtemp(prefix="lock-")
        self.addCleanup(shutil.rmtree, folder)
        path = os.path.join(folder, "package-lock.json")
        with open(path, "w") as out:
            json.dump({"lockfileVersion": 3, "packages": packages}, out)
        return path

    def test_the_shipped_lockfile_is_accepted(self):
        packages = self.install.lock_packages(script(os.path.join("browser", "package-lock.json")))
        names = {name for name, _, _, _ in packages}
        self.assertEqual({"@playwright/mcp", "chrome-devtools-mcp", "playwright", "playwright-core"}, names)

    def test_entries_from_elsewhere_or_without_a_checksum_are_refused(self):
        for entry in [
            {"version": "1", "resolved": "https://evil.example/x.tgz", "integrity": "sha512-x"},
            {"version": "1", "resolved": "https://registry.npmjs.org/x/-/x-1.tgz", "integrity": "sha1-x"},
            {"version": "1", "resolved": "https://registry.npmjs.org/x/-/x-1.tgz", "integrity": "sha512-x", "hasInstallScript": True},
        ]:
            with self.assertRaises(self.install.Failure):
                self.install.lock_packages(self.write_lock({"node_modules/x": entry}))

    def test_tar_members_that_leave_the_package_are_skipped(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
            for name, kind in [("package/index.js", tarfile.REGTYPE), ("package/../../evil", tarfile.REGTYPE), ("package/link", tarfile.SYMTYPE), ("other/x", tarfile.REGTYPE)]:
                info = tarfile.TarInfo(name)
                info.type = kind
                if kind == tarfile.SYMTYPE:
                    info.linkname = "/etc/passwd"
                archive.addfile(info, io.BytesIO(b""))
        buffer.seek(0)
        with tarfile.open(fileobj=buffer, mode="r:gz") as archive:
            self.assertEqual(["index.js"], [member.name for member in self.install.safe_members(archive)])


if __name__ == "__main__":
    unittest.main()
