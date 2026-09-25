"""mcp.py spoken to over stdio, as an agent would, with the app replaced by a fake phone socket."""

import json
import os
import subprocess
import sys
import tempfile
import unittest

from support import FakePhone, script

MODERN = "2026-07-28"


def modern_meta(version=MODERN):
    return {
        "io.modelcontextprotocol/protocolVersion": version,
        "io.modelcontextprotocol/clientCapabilities": {},
        "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1"},
    }


class McpServerTest(unittest.TestCase):
    def setUp(self):
        self.phone = FakePhone(self.answer)
        self.workdir = tempfile.mkdtemp(prefix="work-")
        environment = dict(os.environ, POCKETIDE_PHONE_SOCKET=self.phone.path, PYTHONDONTWRITEBYTECODE="1")
        self.server = subprocess.Popen(
            [sys.executable, script("mcp.py")],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            cwd=self.workdir,
            env=environment,
        )
        self.next_id = 0

    def tearDown(self):
        self.server.stdin.close()
        self.assertEqual(0, self.server.wait(timeout=10))
        self.server.stdout.close()
        self.phone.close()
        os.rmdir(self.workdir)

    def answer(self, request):
        tool = request["args"]["tool"]
        if tool == "phone_status":
            return {"ok": True, "result": {"text": "Battery 80 %."}}
        return {"ok": False, "error": "Not put on main: the check-post found a secret."}

    def send(self, message):
        self.server.stdin.write((json.dumps(message) + "\n").encode("utf-8"))
        self.server.stdin.flush()

    def receive(self):
        line = self.server.stdout.readline()
        self.assertTrue(line, "the server closed its output")
        return json.loads(line)

    def request(self, method, params=None):
        self.next_id += 1
        message = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            message["params"] = params
        self.send(message)
        reply = self.receive()
        self.assertEqual(self.next_id, reply["id"])
        return reply

    def test_legacy_client_initializes_lists_and_calls(self):
        reply = self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}})
        result = reply["result"]
        self.assertEqual("2025-06-18", result["protocolVersion"])
        self.assertIn("tools", result["capabilities"])
        self.assertEqual("pocketide", result["serverInfo"]["name"])
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})

        tools = self.request("tools/list")["result"]["tools"]
        names = {tool["name"] for tool in tools}
        self.assertEqual(
            {"phone_status", "run_build", "build_result", "open_pr", "put_on_main", "install_browser", "save_media", "preview_port"},
            names,
        )
        put_on_main = next(tool for tool in tools if tool["name"] == "put_on_main")
        self.assertIn("ONLY when the owner asked", put_on_main["description"])
        self.assertTrue(put_on_main["annotations"]["destructiveHint"])
        self.assertNotIn("resultType", self.request("ping")["result"])

        called = self.request("tools/call", {"name": "phone_status", "arguments": {}})["result"]
        self.assertFalse(called["isError"])
        self.assertEqual("Battery 80 %.", called["content"][0]["text"])
        forwarded = self.phone.requests[-1]
        self.assertEqual("mcp", forwarded["op"])
        self.assertEqual("phone_status", forwarded["args"]["tool"])
        self.assertEqual(os.path.realpath(self.workdir), os.path.realpath(forwarded["args"]["cwd"]))

    def test_unknown_legacy_version_gets_the_newest_legacy_one(self):
        result = self.request("initialize", {"protocolVersion": "1999-01-01", "capabilities": {}})["result"]
        self.assertEqual("2025-11-25", result["protocolVersion"])

    def test_modern_client_discovers_and_calls_statelessly(self):
        discovered = self.request("server/discover", {"_meta": modern_meta()})["result"]
        self.assertEqual("complete", discovered["resultType"])
        self.assertEqual([MODERN], discovered["supportedVersions"])
        self.assertEqual("pocketide", discovered["_meta"]["io.modelcontextprotocol/serverInfo"]["name"])

        listed = self.request("tools/list", {"_meta": modern_meta()})["result"]
        self.assertEqual("complete", listed["resultType"])
        self.assertEqual(8, len(listed["tools"]))

        called = self.request("tools/call", {"_meta": modern_meta(), "name": "put_on_main", "arguments": {}})["result"]
        self.assertTrue(called["isError"])
        self.assertIn("check-post", called["content"][0]["text"])
        self.assertEqual("complete", called["resultType"])

    def test_modern_request_errors(self):
        unsupported = self.request("tools/list", {"_meta": modern_meta("1900-01-01")})["error"]
        self.assertEqual(-32022, unsupported["code"])
        self.assertEqual("1900-01-01", unsupported["data"]["requested"])
        self.assertIn(MODERN, unsupported["data"]["supported"])

        meta = modern_meta()
        del meta["io.modelcontextprotocol/clientCapabilities"]
        self.assertEqual(-32602, self.request("tools/list", {"_meta": meta})["error"]["code"])

    def test_protocol_errors(self):
        self.assertEqual(-32601, self.request("resources/list")["error"]["code"])
        self.assertEqual(-32602, self.request("tools/call", {"name": "format_disk", "arguments": {}})["error"]["code"])
        self.assertEqual(-32602, self.request("tools/call", {"name": "phone_status", "arguments": [1]})["error"]["code"])
        self.server.stdin.write(b"{not json\n")
        self.server.stdin.flush()
        self.assertEqual(-32700, self.receive()["error"]["code"])

    def test_save_media_sends_an_absolute_path(self):
        self.request("initialize", {"protocolVersion": "2025-11-25", "capabilities": {}})
        self.request("tools/call", {"name": "save_media", "arguments": {"path": "shots/home.png"}})
        forwarded = self.phone.requests[-1]["args"]
        self.assertEqual(os.path.join(forwarded["cwd"], "shots/home.png"), forwarded["args"]["path"])

    def test_a_missing_phone_is_a_tool_error_not_a_crash(self):
        environment = dict(os.environ, POCKETIDE_PHONE_SOCKET="/nonexistent/phone.sock")
        lonely = subprocess.Popen([sys.executable, script("mcp.py")], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=environment)
        out, _ = lonely.communicate(
            (json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "phone_status"}}) + "\n").encode(),
            timeout=10,
        )
        result = json.loads(out.decode().splitlines()[0])["result"]
        self.assertTrue(result["isError"])
        self.assertIn("could not be reached", result["content"][0]["text"])


if __name__ == "__main__":
    unittest.main()
