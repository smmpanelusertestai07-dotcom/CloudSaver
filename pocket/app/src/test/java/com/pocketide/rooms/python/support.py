"""Helpers for the room script tests: where the scripts are, and a fake phone socket."""

import json
import os
import socket
import tempfile
import threading

ASSETS = os.environ.get("ROOMS_ASSETS") or os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "..", "..", "main", "assets", "rooms")
)


def script(name):
    return os.path.join(ASSETS, name)


class FakePhone:
    """A Unix socket that answers each request line like the app's phone bridge."""

    def __init__(self, answer):
        self.answer = answer
        self.requests = []
        self.folder = tempfile.mkdtemp(prefix="phone-")
        self.path = os.path.join(self.folder, "phone.sock")
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.server.bind(self.path)
        self.server.listen(8)
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self):
        while True:
            try:
                connection, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(connection,), daemon=True).start()

    def handle(self, connection):
        with connection:
            reader = connection.makefile("rb")
            for line in reader:
                request = json.loads(line.decode("utf-8"))
                self.requests.append(request)
                reply = self.answer(request)
                reply["id"] = request["id"]
                connection.sendall(json.dumps(reply).encode("utf-8") + b"\n")

    def close(self):
        self.server.close()
        for remove, path in ((os.unlink, self.path), (os.rmdir, self.folder)):
            try:
                remove(path)
            except OSError:
                pass
