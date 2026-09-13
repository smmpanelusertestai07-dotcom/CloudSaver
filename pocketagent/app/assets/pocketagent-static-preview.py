#!/usr/bin/env python3
"""Loopback-only, token-protected preview of public project assets."""
import argparse
import functools
import hmac
import http.server
import os
from pathlib import Path
import stat as stat_types
import threading
import urllib.parse


class PreviewHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, root, root_fd, token, **kwargs):
        self.project_root = Path(root)
        self.root_fd = root_fd
        self.access_token = token
        super().__init__(*args, directory=str(self.project_root), **kwargs)

    def setup(self):
        self.request.settimeout(10)
        super().setup()

    def send_head(self):
        descriptor = None
        try:
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.scheme or parsed.netloc:
                raise ValueError("absolute request target")
            raw = urllib.parse.unquote(parsed.path, encoding="utf-8", errors="strict")
            parts = raw.split("/")
            native_token = self.headers.get("X-PocketAgent-Preview", "")
            if not parts or parts[0]:
                raise ValueError("invalid path")
            if valid_token(native_token) and hmac.compare_digest(native_token, self.access_token):
                relative_parts = parts[1:]
            elif len(parts) >= 3 and valid_token(parts[1]) and hmac.compare_digest(parts[1], self.access_token):
                relative_parts = parts[2:]
            else:
                raise ValueError("unauthorized")
            current = self.project_root
            clean = []
            for segment in relative_parts:
                if not segment:
                    continue
                lower = segment.lower()
                if segment.startswith(".") or "\\" in segment or "\0" in segment:
                    raise ValueError("hidden or invalid path")
                if lower.endswith((".pem", ".key", ".jks", ".p12", ".pfx", ".keystore")):
                    raise ValueError("credential file")
                if lower in {"credentials.json", "id_rsa", "id_ed25519", "id_ecdsa"}:
                    raise ValueError("credential file")
                if lower.startswith("service-account") and lower.endswith(".json"):
                    raise ValueError("credential file")
                current = current / segment
                clean.append(segment)
            # Walk directory handles rather than resolved strings: a concurrent agent edit
            # cannot replace an intermediate directory with an escaping symlink.
            descriptor = os.dup(self.root_fd)
            for segment in clean:
                next_fd = os.open(segment, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
                os.close(descriptor)
                descriptor = next_fd
            if stat_types.S_ISDIR(os.fstat(descriptor).st_mode):
                current = current / "index.html"
                index_fd = os.open("index.html", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
                os.close(descriptor)
                descriptor = index_fd
            stat = os.fstat(descriptor)
            if not stat_types.S_ISREG(stat.st_mode):
                raise ValueError("not a regular file")
            source = os.fdopen(descriptor, "rb")
            descriptor = None
        except (OSError, ValueError, UnicodeError):
            if descriptor is not None:
                os.close(descriptor)
            self.send_error(404, "Preview file not available")
            return None
        self.send_response(200)
        self.send_header("Content-Type", self.guess_type(str(current)))
        self.send_header("Content-Length", str(stat.st_size))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.end_headers()
        return source

    def log_message(self, fmt, *args):
        # Request URLs contain a bearer token: never write them to project logs.
        pass


class PreviewServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    slots = threading.BoundedSemaphore(12)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def valid_token(value):
    return len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--root", required=True)
    args = parser.parse_args()
    token = os.environ.get("POCKETAGENT_PREVIEW_TOKEN", "")
    if not valid_token(token):
        raise SystemExit("A generated preview token is required")
    root_fd = os.open(args.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    handler = functools.partial(PreviewHandler, root=args.root, root_fd=root_fd, token=token)
    server = PreviewServer(("127.0.0.1", args.port), handler)
    print("Protected project preview is ready.", flush=True)
    try:
        server.serve_forever(poll_interval=0.3)
    finally:
        server.server_close()
        os.close(root_fd)


if __name__ == "__main__":
    main()
