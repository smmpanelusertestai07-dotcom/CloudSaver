#!/usr/bin/env python3
"""The `>_` terminal of a PocketIDE room: a web page and a WebSocket connected to a shell.

Serves the terminal page (xterm.js) and, at /ws, a WebSocket (RFC 6455, framed here with the
standard library) whose other end is `bash -l` on a pseudo-terminal in the session's worktree.

Other apps on the phone can reach 127.0.0.1 too, so every request must carry the header
X-PocketIDE-Secret with this launch's secret. The app's port bridge adds it to what the
terminal's WebView sends; anything that connects to this port directly gets 403 and no shell.

The secret arrives in a file (--secret-file), which is read once and deleted: arguments and
environment variables can be read through /proc by other programs.

Usage: term.py --port N --cwd DIR --web DIR --secret-file FILE
"""

import argparse
import base64
import errno
import fcntl
import hashlib
import hmac
import http.server
import json
import os
import pty
import select
import signal
import socket
import struct
import sys
import termios
import threading
import time

SECRET_HEADER = "X-PocketIDE-Secret"
WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_MESSAGE = 1024 * 1024
MAX_SHELLS = 4
READ_CHUNK = 65536

STATIC = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/terminal.js": ("terminal.js", "text/javascript; charset=utf-8"),
    "/terminal.css": ("terminal.css", "text/css; charset=utf-8"),
    "/xterm.js": ("xterm.js", "text/javascript; charset=utf-8"),
    "/xterm.css": ("xterm.css", "text/css; charset=utf-8"),
    "/addon-fit.js": ("addon-fit.js", "text/javascript; charset=utf-8"),
}

# xterm.js styles its rows with inline style attributes, hence 'unsafe-inline' for styles only.
SECURITY_HEADERS = {
    "Content-Security-Policy": (
        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
        "connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; form-action 'none'"
    ),
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-cache",
}

OP_CONTINUATION, OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA


class ClosedError(Exception):
    """The other end went away or broke the protocol."""


def accept_key(key):
    digest = hashlib.sha1((key + WEBSOCKET_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def encode_frame(opcode, payload):
    """One unmasked, final frame (servers never mask)."""
    head = bytearray([0x80 | opcode])
    length = len(payload)
    if length < 126:
        head.append(length)
    elif length < 1 << 16:
        head.append(126)
        head += struct.pack("!H", length)
    else:
        head.append(127)
        head += struct.pack("!Q", length)
    return bytes(head) + payload


class FrameReader:
    """Reads client frames, which RFC 6455 requires to be masked."""

    def __init__(self, stream):
        self.stream = stream

    def exactly(self, count):
        data = b""
        while len(data) < count:
            chunk = self.stream.read(count - len(data))
            if not chunk:
                raise ClosedError("connection closed")
            data += chunk
        return data

    def frame(self):
        first, second = self.exactly(2)
        fin = bool(first & 0x80)
        if first & 0x70:
            raise ClosedError("reserved bits set")
        opcode = first & 0x0F
        if not second & 0x80:
            raise ClosedError("client frames must be masked")
        length = second & 0x7F
        if length == 126:
            length = struct.unpack("!H", self.exactly(2))[0]
        elif length == 127:
            length = struct.unpack("!Q", self.exactly(8))[0]
        if length > MAX_MESSAGE:
            raise ClosedError("frame too large")
        mask = self.exactly(4)
        payload = bytearray(self.exactly(length))
        for index in range(length):
            payload[index] ^= mask[index % 4]
        return fin, opcode, bytes(payload)

    def message(self, on_control):
        """The next data message as (opcode, payload); control frames go to on_control."""
        opcode = None
        parts = []
        size = 0
        while True:
            fin, frame_opcode, payload = self.frame()
            if frame_opcode >= OP_CLOSE:
                if not fin or len(payload) > 125:
                    raise ClosedError("bad control frame")
                if on_control(frame_opcode, payload):
                    return None, b""
                continue
            if frame_opcode == OP_CONTINUATION:
                if opcode is None:
                    raise ClosedError("continuation without a start")
            elif opcode is not None:
                raise ClosedError("new message before the last one ended")
            else:
                opcode = frame_opcode
            size += len(payload)
            if size > MAX_MESSAGE:
                raise ClosedError("message too large")
            parts.append(payload)
            if fin:
                return opcode, b"".join(parts)


class Shell:
    """bash -l on a pseudo-terminal, in the worktree."""

    def __init__(self, cwd, environment):
        pid, fd = pty.fork()
        if pid == 0:
            try:
                os.chdir(cwd)
            except OSError:
                os.chdir("/")
            os.execvpe("bash", ["bash", "-l"], environment)
        self.pid = pid
        self.fd = fd

    def write(self, data):
        view = memoryview(data)
        while view:
            written = os.write(self.fd, view)
            view = view[written:]

    def resize(self, cols, rows):
        cols = max(2, min(int(cols), 1000))
        rows = max(1, min(int(rows), 1000))
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

    def close(self):
        try:
            os.close(self.fd)
        except OSError:
            pass
        # A hang-up ends an interactive shell; one that ignores it is killed after a moment.
        for sig, wait_s in ((signal.SIGHUP, 2.0), (signal.SIGKILL, 5.0)):
            try:
                os.kill(self.pid, sig)
            except ProcessLookupError:
                pass
            if self.reap(wait_s):
                return

    def reap(self, wait_s):
        deadline = time.monotonic() + wait_s
        while True:
            try:
                pid, _ = os.waitpid(self.pid, os.WNOHANG)
            except ChildProcessError:
                return True
            if pid:
                return True
            if time.monotonic() > deadline:
                return False
            time.sleep(0.05)


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "PocketIDE-Terminal"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        # Requests are not logged: the room's output log is shown to the owner.
        pass

    def authorized(self):
        given = self.headers.get(SECRET_HEADER, "")
        return hmac.compare_digest(given.encode("utf-8"), self.server.secret.encode("utf-8"))

    def refuse(self, code, text):
        body = (text + "\n").encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        if not self.authorized():
            self.refuse(403, "Forbidden")
            return
        path = self.path.split("?", 1)[0]
        if path == "/ws":
            self.websocket()
            return
        entry = STATIC.get(path)
        if entry is None:
            self.refuse(404, "Not found")
            return
        name, content_type = entry
        try:
            with open(os.path.join(self.server.web, name), "rb") as source:
                body = source.read()
        except OSError:
            self.refuse(404, "Not found")
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for header, value in SECURITY_HEADERS.items():
            self.send_header(header, value)
        self.end_headers()
        self.wfile.write(body)

    def do_HEAD(self):
        self.refuse(405, "Method not allowed")

    do_POST = do_PUT = do_DELETE = do_OPTIONS = do_HEAD

    def websocket(self):
        key = self.headers.get("Sec-WebSocket-Key", "")
        upgrade = self.headers.get("Upgrade", "").lower()
        connection = self.headers.get("Connection", "").lower()
        if upgrade != "websocket" or "upgrade" not in connection or not key:
            self.refuse(400, "Expected a WebSocket upgrade")
            return
        if self.headers.get("Sec-WebSocket-Version") != "13":
            self.send_response(426)
            self.send_header("Sec-WebSocket-Version", "13")
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            return
        if not self.server.shells.acquire(blocking=False):
            self.refuse(503, "Too many terminals are open")
            return
        try:
            self.send_response(101)
            self.send_header("Upgrade", "websocket")
            self.send_header("Connection", "Upgrade")
            self.send_header("Sec-WebSocket-Accept", accept_key(key))
            self.end_headers()
            self.wfile.flush()
            Session(self.connection, self.rfile, self.server).run()
        finally:
            self.server.shells.release()
            self.close_connection = True


class Session:
    """One WebSocket and its shell."""

    def __init__(self, connection, stream, server):
        self.connection = connection
        self.reader = FrameReader(stream)
        self.server = server
        self.send_lock = threading.Lock()
        self.closed = threading.Event()
        self.shell = Shell(server.cwd, server.environment)

    def send(self, opcode, payload):
        with self.send_lock:
            try:
                self.connection.sendall(encode_frame(opcode, payload))
            except OSError:
                self.closed.set()

    def run(self):
        pump = threading.Thread(target=self.shell_to_socket, daemon=True)
        pump.start()
        try:
            self.socket_to_shell()
        except (ClosedError, OSError, ValueError):
            pass
        finally:
            self.closed.set()
            pump.join(timeout=2)
            self.shell.close()
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def shell_to_socket(self):
        while not self.closed.is_set():
            try:
                ready, _, _ = select.select([self.shell.fd], [], [], 0.5)
            except (OSError, ValueError):
                break
            if not ready:
                continue
            try:
                data = os.read(self.shell.fd, READ_CHUNK)
            except OSError as error:
                if error.errno == errno.EIO:  # the shell ended
                    data = b""
                else:
                    break
            if not data:
                self.send(OP_CLOSE, struct.pack("!H", 1000) + b"The shell ended")
                self.closed.set()
                try:
                    self.connection.shutdown(socket.SHUT_RD)
                except OSError:
                    pass
                break
            self.send(OP_BINARY, data)

    def on_control(self, opcode, payload):
        if opcode == OP_PING:
            self.send(OP_PONG, payload)
            return False
        if opcode == OP_PONG:
            return False
        self.send(OP_CLOSE, payload[:2])
        return True

    def socket_to_shell(self):
        while not self.closed.is_set():
            opcode, payload = self.reader.message(self.on_control)
            if opcode is None:
                return
            if opcode == OP_BINARY:
                self.shell.write(payload)
                continue
            if opcode != OP_TEXT:
                raise ClosedError("unknown opcode")
            self.command(json.loads(payload.decode("utf-8")))

    def command(self, message):
        if not isinstance(message, dict):
            return
        kind = message.get("t")
        if kind == "i" and isinstance(message.get("d"), str):
            self.shell.write(message["d"].encode("utf-8"))
        elif kind == "r":
            cols, rows = message.get("c"), message.get("r")
            if isinstance(cols, int) and isinstance(rows, int):
                self.shell.resize(cols, rows)


class TerminalServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, port, secret, cwd, web, environment):
        self.secret = secret
        self.cwd = cwd
        self.web = web
        self.environment = environment
        self.shells = threading.BoundedSemaphore(MAX_SHELLS)
        super().__init__(("127.0.0.1", port), Handler)


def shell_environment():
    environment = dict(os.environ)
    environment["TERM"] = "xterm-256color"
    environment["COLORTERM"] = "truecolor"
    return environment


def take_secret(path):
    """Reads the launch secret from its file and deletes the file, so nothing else can read it."""
    try:
        with open(path, encoding="ascii") as source:
            secret = source.read().strip()
    except (OSError, ValueError):
        return ""
    try:
        os.unlink(path)
    except OSError:
        pass
    return secret


def main(argv=None):
    parser = argparse.ArgumentParser(description="PocketIDE room terminal")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--cwd", required=True)
    parser.add_argument("--web", required=True)
    parser.add_argument("--secret-file", required=True)
    options = parser.parse_args(argv)
    # Files the shell creates are private to the room, as the engines' are (room.py).
    os.umask(0o077)
    secret = take_secret(options.secret_file)
    if len(secret) < 32:
        print("No terminal secret was given; refusing to start an open shell.", file=sys.stderr)
        return 2
    server = TerminalServer(options.port, secret, options.cwd, options.web, shell_environment())
    print("PocketIDE terminal on 127.0.0.1:%d" % server.server_address[1], flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
