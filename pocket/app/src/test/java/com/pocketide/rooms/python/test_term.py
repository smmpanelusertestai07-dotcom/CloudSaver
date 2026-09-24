"""term.py as the bridge and as a stranger would reach it: the secret check and the WebSocket."""

import base64
import hashlib
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest

from support import script

SECRET = "a" * 64
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def http_get(port, path, headers=None):
    lines = ["GET %s HTTP/1.1" % path, "Host: localhost:%d" % port, "Connection: close"]
    lines += ["%s: %s" % item for item in (headers or {}).items()]
    with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
        connection.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("ascii"))
        data = b""
        while True:
            chunk = connection.recv(65536)
            if not chunk:
                break
            data += chunk
    head, _, body = data.partition(b"\r\n\r\n")
    status = int(head.split(b" ")[1])
    return status, head.decode("latin-1"), body


def masked_frame(opcode, payload):
    mask = os.urandom(4)
    head = bytearray([0x80 | opcode])
    if len(payload) < 126:
        head.append(0x80 | len(payload))
    else:
        head.append(0x80 | 126)
        head += struct.pack("!H", len(payload))
    body = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    return bytes(head) + mask + body


def read_frame(connection):
    def exactly(count):
        data = b""
        while len(data) < count:
            chunk = connection.recv(count - len(data))
            if not chunk:
                raise ConnectionError("closed")
            data += chunk
        return data

    first, second = exactly(2)
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", exactly(2))[0]
    elif length == 127:
        length = struct.unpack("!Q", exactly(8))[0]
    return first & 0x0F, exactly(length)


class TerminalServerTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="home-")
        self.secret_file = os.path.join(self.home, ".terminal.secret")
        with open(self.secret_file, "w") as out:
            out.write(SECRET)
        self.port = free_port()
        environment = dict(os.environ, HOME=self.home, PYTHONDONTWRITEBYTECODE="1", PS1="$ ")
        self.server = subprocess.Popen(
            [
                sys.executable, script("term.py"),
                "--port", str(self.port),
                "--cwd", self.home,
                "--web", os.path.join(os.path.dirname(script("term.py")), "..", "web", "terminal"),
                "--secret-file", self.secret_file,
            ],
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        deadline = time.monotonic() + 10
        while True:
            try:
                socket.create_connection(("127.0.0.1", self.port), timeout=1).close()
                break
            except OSError:
                if self.server.poll() is not None:
                    self.fail("term.py exited: %s" % self.server.stdout.read())
                self.assertLess(time.monotonic(), deadline)
                time.sleep(0.05)

    def tearDown(self):
        self.server.terminate()
        self.server.wait(timeout=10)
        self.server.stdout.close()
        for name in os.listdir(self.home):
            path = os.path.join(self.home, name)
            if os.path.isfile(path):
                os.unlink(path)
        os.rmdir(self.home)

    def test_the_secret_file_is_read_once_and_deleted(self):
        self.assertFalse(os.path.exists(self.secret_file))

    def test_requests_without_the_secret_are_refused(self):
        for path in ["/", "/index.html", "/xterm.js", "/ws", "/../../etc/passwd"]:
            status, _, _ = http_get(self.port, path)
            self.assertEqual(403, status, path)
        status, _, _ = http_get(self.port, "/", {"X-PocketIDE-Secret": "b" * 64})
        self.assertEqual(403, status)

    def test_the_page_is_served_with_the_secret(self):
        status, head, body = http_get(self.port, "/", {"X-PocketIDE-Secret": SECRET})
        self.assertEqual(200, status)
        self.assertIn(b"xterm.js", body)
        self.assertIn("Content-Security-Policy", head)
        status, _, body = http_get(self.port, "/xterm.js", {"X-PocketIDE-Secret": SECRET})
        self.assertEqual(200, status)
        self.assertIn(b"Terminal", body)
        self.assertEqual(404, http_get(self.port, "/secret.txt", {"X-PocketIDE-Secret": SECRET})[0])

    def test_websocket_handshake_and_shell(self):
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        with socket.create_connection(("127.0.0.1", self.port), timeout=10) as connection:
            request = (
                "GET /ws HTTP/1.1\r\nHost: localhost:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nX-PocketIDE-Secret: %s\r\n\r\n" % (self.port, key, SECRET)
            )
            connection.sendall(request.encode("ascii"))
            head = b""
            while b"\r\n\r\n" not in head:
                head += connection.recv(1)
            self.assertTrue(head.startswith(b"HTTP/1.1 101"), head)
            accept = base64.b64encode(hashlib.sha1((key + GUID).encode("ascii")).digest()).decode("ascii")
            self.assertIn(("Sec-WebSocket-Accept: %s" % accept).encode("ascii"), head)

            connection.sendall(masked_frame(0x1, json.dumps({"t": "r", "c": 80, "r": 24}).encode()))
            connection.sendall(masked_frame(0x1, json.dumps({"t": "i", "d": "echo pocket-$((20+22))\n"}).encode()))
            output = b""
            deadline = time.monotonic() + 10
            while b"pocket-42" not in output:
                self.assertLess(time.monotonic(), deadline, output)
                opcode, payload = read_frame(connection)
                if opcode == 0x2:
                    output += payload

            connection.sendall(masked_frame(0x9, b"hi"))
            while True:
                opcode, payload = read_frame(connection)
                if opcode == 0xA:
                    self.assertEqual(b"hi", payload)
                    break

            connection.sendall(masked_frame(0x1, json.dumps({"t": "i", "d": "exit\n"}).encode()))
            while True:
                opcode, payload = read_frame(connection)
                if opcode == 0x8:
                    self.assertEqual(1000, struct.unpack("!H", payload[:2])[0])
                    self.assertIn(b"The shell ended", payload)
                    break

    def test_the_shell_never_sees_the_secret(self):
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        with socket.create_connection(("127.0.0.1", self.port), timeout=10) as connection:
            connection.sendall((
                "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nX-PocketIDE-Secret: %s\r\n\r\n" % (key, SECRET)
            ).encode("ascii"))
            head = b""
            while b"\r\n\r\n" not in head:
                head += connection.recv(1)
            connection.sendall(masked_frame(0x1, json.dumps({"t": "i", "d": "env | grep -c aaaaaaaa; echo done-$((1+1))\n"}).encode()))
            output = b""
            deadline = time.monotonic() + 10
            while b"done-2" not in output:
                self.assertLess(time.monotonic(), deadline, output)
                opcode, payload = read_frame(connection)
                if opcode == 0x2:
                    output += payload
            self.assertRegex(output.replace(b"\r", b""), rb"(^|[^0-9])0\ndone-2")

    def test_unmasked_client_frames_end_the_connection(self):
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        with socket.create_connection(("127.0.0.1", self.port), timeout=10) as connection:
            connection.sendall((
                "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nX-PocketIDE-Secret: %s\r\n\r\n" % (key, SECRET)
            ).encode("ascii"))
            head = b""
            while b"\r\n\r\n" not in head:
                head += connection.recv(1)
            connection.sendall(bytes([0x81, 0x02]) + b"hi")
            connection.settimeout(10)
            while True:
                try:
                    chunk = connection.recv(65536)
                except ConnectionResetError:
                    break
                if not chunk:
                    break


class TerminalStartTest(unittest.TestCase):
    def test_refuses_to_start_without_a_secret(self):
        folder = tempfile.mkdtemp(prefix="term-")
        try:
            result = subprocess.run(
                [sys.executable, script("term.py"), "--port", "0", "--cwd", folder, "--web", folder,
                 "--secret-file", os.path.join(folder, "missing")],
                capture_output=True, timeout=10, env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"),
            )
            self.assertEqual(2, result.returncode)
            self.assertIn(b"refusing", result.stderr)
        finally:
            os.rmdir(folder)


if __name__ == "__main__":
    unittest.main()
