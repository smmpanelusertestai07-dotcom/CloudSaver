"""room.py, notify.py and the browser installer, run as the room runs them. The room's
xdg-open is the bridge module's (PhoneGuestTools), tested with the bridge."""

import hashlib
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

OURS = {"pocketide": {"type": "stdio", "command": "python3", "args": [], "env": {}}}


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

    def claude(self, entries=None, keep=(), report=None, program=("/bin/true",)):
        entries = OURS if entries is None else entries
        env = {"POCKETIDE_CLAUDE_MCP": json.dumps(entries), "POCKETIDE_CLAUDE_KEEP": json.dumps(list(keep))}
        if report:
            env["POCKETIDE_HELD_REPORT"] = report
        return run("room.py", ["claude", "--"] + list(program), self.home, env)

    def write_state(self, state):
        with open(os.path.join(self.home, ".claude.json"), "w") as out:
            json.dump(state, out)

    def listed(self, report):
        with open(report) as source:
            items = json.load(source)
        for item in items:
            self.assertEqual(hashlib.sha256(item["entry"].encode("ascii")).hexdigest(), item["digest"])
        return items

    def test_claude_servers_are_merged_and_everything_else_is_kept(self):
        self.write_state({"oauthAccount": {"emailAddress": "o@example.com"}, "mcpServers": {"old": {"command": "y"}}})
        entries = {"pocketide": {"type": "stdio", "command": "python3", "args": ["/opt/pocketide/mcp.py"], "env": {}}, "old": None}
        self.assertEqual(0, self.claude(entries).returncode)
        state = self.claude_state()
        self.assertEqual({"emailAddress": "o@example.com"}, state["oauthAccount"])
        self.assertEqual({"pocketide": entries["pocketide"]}, state["mcpServers"])
        mode = stat.S_IMODE(os.stat(os.path.join(self.home, ".claude.json")).st_mode)
        self.assertEqual(0o600, mode)

    def test_servers_pocketide_did_not_add_are_taken_out_until_the_owner_keeps_them(self):
        self.write_state({
            "oauthAccount": {"emailAddress": "o@example.com"},
            "mcpServers": {"mine": {"command": "x"}},
            "projects": {"/work/a/s1": {"history": [1], "mcpServers": {"evil": {"command": "sh", "args": ["-c", "curl x"]}}}},
        })
        report = os.path.join(self.home, "held-report.json")
        self.assertEqual(0, self.claude(report=report).returncode)
        state = self.claude_state()
        self.assertEqual({"pocketide"}, set(state["mcpServers"]))
        self.assertEqual({}, state["projects"]["/work/a/s1"]["mcpServers"])
        self.assertEqual([1], state["projects"]["/work/a/s1"]["history"])
        self.assertEqual({"emailAddress": "o@example.com"}, state["oauthAccount"])
        listed = self.listed(report)
        self.assertEqual(2, len(listed))
        self.assertTrue(all(item["keepable"] for item in listed))
        evil = next(item for item in listed if "evil" in item["entry"])
        self.assertEqual(["/work/a/s1", "mcpServers", "evil", {"args": ["-c", "curl x"], "command": "sh"}], json.loads(evil["entry"]))
        held = os.path.join(self.home, ".claude", ".pocketide-held-servers.json")
        self.assertEqual(0o600, stat.S_IMODE(os.stat(held).st_mode))

        # Kept: it comes back where it was; the other stays out.
        os.unlink(report)
        self.assertEqual(0, self.claude(keep=[evil["digest"]], report=report).returncode)
        state = self.claude_state()
        self.assertEqual({"command": "sh", "args": ["-c", "curl x"]}, state["projects"]["/work/a/s1"]["mcpServers"]["evil"])
        self.assertNotIn("mine", state["mcpServers"])
        self.assertFalse(os.path.exists(report), "nothing new was taken out")

        # No longer kept: out again. Changed while held: not the one the owner kept, so it stays out.
        self.claude()
        with open(held) as source:
            aside = json.load(source)
        aside[evil["digest"]] = aside[evil["digest"]].replace("curl x", "curl evil")
        with open(held, "w") as out:
            json.dump(aside, out)
        self.claude(keep=[evil["digest"]])
        self.assertEqual({}, self.claude_state()["projects"]["/work/a/s1"]["mcpServers"])

    def test_a_projects_approvals_are_taken_out_and_come_back_when_kept(self):
        project = {"enabledMcpjsonServers": ["repo-tool"], "enableAllProjectMcpServers": True, "allowedTools": ["Bash(*)"], "disabledMcpjsonServers": ["x"]}
        self.write_state({"projects": {"/work/a/s1": dict(project)}})
        report = os.path.join(self.home, "held-report.json")
        self.claude(report=report)
        state = self.claude_state()["projects"]["/work/a/s1"]
        self.assertEqual({"enabledMcpjsonServers": [], "allowedTools": [], "disabledMcpjsonServers": ["x"]}, state)
        listed = self.listed(report)
        self.assertEqual(
            {("enabledMcpjsonServers", "repo-tool"), ("enableAllProjectMcpServers", ""), ("allowedTools", "Bash(*)")},
            {tuple(json.loads(item["entry"])[1:3]) for item in listed},
        )
        self.claude(keep=[item["digest"] for item in listed])
        self.assertEqual(project, self.claude_state()["projects"]["/work/a/s1"])

    def test_a_place_holding_the_wrong_kind_of_value_is_taken_out_and_cannot_be_kept(self):
        self.write_state({"mcpServers": [{"command": "sh"}], "projects": {"/w": {"allowedTools": "Bash(*)"}}})
        report = os.path.join(self.home, "held-report.json")
        self.claude(report=report)
        state = self.claude_state()
        self.assertEqual({"pocketide"}, set(state["mcpServers"]))
        self.assertEqual({}, state["projects"]["/w"])
        listed = self.listed(report)
        self.assertEqual([False, False], [item["keepable"] for item in listed])
        self.claude(keep=[item["digest"] for item in listed])
        self.assertEqual({}, self.claude_state()["projects"]["/w"])

    def test_what_the_app_has_not_read_yet_stays_listed(self):
        report = os.path.join(self.home, "held-report.json")
        self.write_state({"mcpServers": {"one": {"command": "a"}}})
        self.claude(report=report)
        state = self.claude_state()
        state["mcpServers"]["two"] = {"command": "b"}
        self.write_state(state)
        self.claude(report=report)
        self.assertEqual(2, len(self.listed(report)))

    def test_a_linked_state_file_is_read_through_and_replaced_by_a_file(self):
        target = os.path.join(self.home, "elsewhere.json")
        with open(target, "w") as out:
            json.dump({"oauthAccount": {"emailAddress": "o@example.com"}, "mcpServers": {"evil": {"command": "sh"}}}, out)
        path = os.path.join(self.home, ".claude.json")
        os.symlink(target, path)
        self.assertEqual(0, self.claude().returncode)
        self.assertFalse(os.path.islink(path))
        state = self.claude_state()
        self.assertEqual({"pocketide"}, set(state["mcpServers"]))
        self.assertEqual({"emailAddress": "o@example.com"}, state["oauthAccount"])

    def test_a_file_claude_may_read_is_read_as_claude_reads_it(self):
        # A byte-order mark and a broken UTF-8 byte: Node reads past both, so servers there count.
        path = os.path.join(self.home, ".claude.json")
        with open(path, "wb") as out:
            out.write(b'\xef\xbb\xbf{"note": "caf\xe9", "mcpServers": {"evil": {"command": "sh"}}}')
        self.assertEqual(0, self.claude().returncode)
        self.assertEqual({"pocketide"}, set(self.claude_state()["mcpServers"]))

    def test_a_fresh_home_gets_the_file(self):
        entries = {"pocketide": {"type": "stdio", "command": "python3", "args": [], "env": {}}}
        run("room.py", ["claude", "--", "/bin/true"], self.home, {"POCKETIDE_CLAUDE_MCP": json.dumps(entries)})
        self.assertEqual({"pocketide": entries["pocketide"]}, self.claude_state()["mcpServers"])

    def test_a_state_file_pocketide_cannot_read_is_moved_aside(self):
        # Claude might still find servers in what PocketIDE cannot read (Node reads deeper nesting).
        path = os.path.join(self.home, ".claude.json")
        for text in ["{broken", '{"deep": ' + "[" * 100000 + "]" * 100000 + ', "mcpServers": {"evil": {"command": "sh"}}}']:
            with open(path, "w") as out:
                out.write(text)
            result = run("room.py", ["claude", "--", "/bin/true"], self.home, {"POCKETIDE_CLAUDE_MCP": "{\"pocketide\": null}"})
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertEqual({"mcpServers": {}}, self.claude_state())
            with open(os.path.join(self.home, ".claude.json.pocketide-unreadable")) as source:
                self.assertEqual(text, source.read())

    def test_claude_does_not_start_when_its_settings_cannot_be_checked(self):
        self.write_state({"mcpServers": {"evil": {"command": "sh"}}})
        with open(os.path.join(self.home, ".claude"), "w") as out:
            out.write("in the way of the held settings")
        result = self.claude(program=("/bin/echo", "engine-started"))
        self.assertEqual(1, result.returncode)
        self.assertNotIn(b"engine-started", result.stdout)
        self.assertIn(b"was not started", result.stdout)
        self.assertIn("evil", self.claude_state()["mcpServers"])

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

    def test_the_cli_gets_the_hubs_sign_in_only_when_it_has_none(self):
        gemini = os.path.join(self.home, ".gemini")
        os.makedirs(gemini)
        hub = os.path.join(gemini, "jetski-standalone-oauth-token")
        with open(hub, "w") as out:
            out.write("hub-token")
        result = run("room.py", ["antigravity", "--", "/bin/true"], self.home)
        cli = os.path.join(gemini, "antigravity", "antigravity-oauth-token")
        with open(cli) as source:
            self.assertEqual("hub-token", source.read())
        self.assertEqual(0o600, stat.S_IMODE(os.stat(cli).st_mode))
        self.assertIn(b"hub's sign-in was copied", result.stdout)
        with open(cli, "w") as out:
            out.write("cli-token")
        run("room.py", ["antigravity", "--", "/bin/true"], self.home)
        with open(cli) as source:
            self.assertEqual("cli-token", source.read())
        with open(hub) as source:
            self.assertEqual("hub-token", source.read())

    def test_a_linked_sign_in_is_never_copied(self):
        gemini = os.path.join(self.home, ".gemini")
        os.makedirs(gemini)
        elsewhere = os.path.join(self.home, "elsewhere")
        with open(elsewhere, "w") as out:
            out.write("not a token")
        os.symlink(elsewhere, os.path.join(gemini, "jetski-standalone-oauth-token"))
        run("room.py", ["antigravity", "--", "/bin/true"], self.home)
        self.assertFalse(os.path.lexists(os.path.join(gemini, "antigravity", "antigravity-oauth-token")))

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
