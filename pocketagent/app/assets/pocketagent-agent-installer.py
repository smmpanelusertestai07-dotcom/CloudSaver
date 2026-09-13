#!/usr/bin/env python3
"""Install pinned, unmodified publisher engines in PocketAgent's private Ubuntu.

No credentials are read or written by this installer. Browser sign-in belongs to
the engine; the small BROWSER helper only asks Android to display its login URL.
Sources reviewed 2026-09-12: OpenAI npm, Cursor ACP registry, Anthropic npm.
"""

import base64
import collections
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import queue
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import uuid


BASE = Path("/opt/pocketagent/agents")
RECEIPTS = Path("/var/lib/pocketagent/agents")
CONFIG = {
    "codex": {
        "version": "0.154.0", "package": "@openai/codex", "binary": "codex",
        "integrity": "sha512-FV/x1OHXYv/ifjf3mXj9ThTTAWcUZN6cGIRQRhRxkKNOPuImu1WW0c8ev1vUkE9XGH90dEnYG1tBjIkxRikg0w==",
        "docs": "https://learn.chatgpt.com/docs/app-server", "protocol": "codex-app-server",
    },
    "cursor": {
        "version": "2026.09.08", "binary": "dist-package/cursor-agent",
        "archive": "https://downloads.cursor.com/lab/2026.09.08-6caf4ff/linux/arm64/agent-cli-package.tar.gz",
        "args": ["acp"], "docs": "https://cursor.com/docs/cli/acp", "protocol": "acp",
    },
    "claude": {
        "version": "2.1.269", "package": "@anthropic-ai/claude-code", "binary": "claude",
        "integrity": "sha512-osSbRU1KjlAfhVSgso7g+KxCr5DLNlfm7xeRPpm/c9s+7HGqQLHbkUYvjepbe6TiHJa9iSeirW/t7vW4sZhTIQ==",
        "docs": "https://code.claude.com/docs/en/legal-and-compliance", "protocol": "claude-code-cli",
    },
}
ALLOWED_DOWNLOAD_HOSTS = {"downloads.cursor.com", "registry.npmjs.org"}
MAX_ARCHIVE = 700 * 1024 * 1024
MAX_UNPACKED = 2 * 1024 * 1024 * 1024


def progress(message):
    print(message, flush=True)


def checked_url(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in ALLOWED_DOWNLOAD_HOSTS
            or parsed.username or parsed.password or parsed.port not in (None, 443)):
        raise RuntimeError("The download did not come from an approved publisher address.")
    return url


class PublisherRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        checked_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url, target, size_limit=MAX_ARCHIVE):
    opener = urllib.request.build_opener(PublisherRedirects())
    checked_url(url)
    for attempt in range(3):
        digest = hashlib.sha256()
        downloaded = 0
        last_report = 0
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "PocketAgent/14 AgentSetup"})
            with opener.open(request, timeout=45) as response, target.open("wb") as output:
                checked_url(response.geturl())
                expected = int(response.headers.get("Content-Length", "0"))
                if expected > size_limit:
                    raise RuntimeError("The agent download is larger than this build supports.")
                while True:
                    block = response.read(256 * 1024)
                    if not block:
                        break
                    downloaded += len(block)
                    if downloaded > size_limit:
                        raise RuntimeError("The agent download exceeded its size limit.")
                    output.write(block)
                    digest.update(block)
                    if downloaded - last_report >= 10 * 1024 * 1024:
                        progress("Downloading agent: %d MB" % (downloaded // (1024 * 1024)))
                        last_report = downloaded
                output.flush()
                os.fsync(output.fileno())
                if expected and downloaded != expected:
                    raise RuntimeError("The download was incomplete.")
            if not downloaded:
                raise RuntimeError("The publisher returned an empty download.")
            return digest.hexdigest()
        except (OSError, ValueError) as error:
            target.unlink(missing_ok=True)
            if attempt == 2:
                raise RuntimeError("Download failed. Check your connection and retry.") from error
            progress("Connection interrupted. Retrying download (%d/3)." % (attempt + 2))
    raise RuntimeError("Download failed.")


def safe_extract(archive, destination):
    """Reject path traversal, escaping links, devices and archive bombs before extraction."""
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > 100000 or sum(member.size for member in members) > MAX_UNPACKED:
            raise RuntimeError("The agent archive exceeds its extraction limits.")
        root = destination.resolve()
        for member in members:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or not member.name:
                raise RuntimeError("The agent archive contains an unsafe path.")
            if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise RuntimeError("The agent archive contains unsupported special files.")
            if member.issym() or member.islnk():
                link = PurePosixPath(member.linkname)
                if link.is_absolute():
                    raise RuntimeError("The agent archive contains an absolute link.")
                parent = (root / member.name).parent if member.issym() else root
                resolved = (parent / member.linkname).resolve()
                if resolved != root and root not in resolved.parents:
                    raise RuntimeError("The agent archive contains an escaping link.")
        # Ubuntu 24.04 ships Python 3.12: the data filter also validates link chains
        # against members already extracted and removes privileged mode bits.
        source.extractall(destination, members=members, filter="data")


def run_logged(command, timeout=1800, env=None):
    recent = collections.deque(maxlen=6)
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, env=env, start_new_session=True)
    # read on a daemon thread so a silent hung installer still has a deadline.
    lines = queue.Queue(maxsize=500)

    def collect():
        try:
            for line in process.stdout:
                try:
                    lines.put(line, timeout=2)
                except queue.Full:
                    pass
        finally:
            try:
                lines.put(None, timeout=2)
            except queue.Full:
                pass

    threading.Thread(target=collect, daemon=True).start()
    deadline = time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                if process.poll() is not None:
                    break
                continue
            if line is None:
                break
            clean = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", line).strip()[:1200]
            if clean:
                recent.append(clean)
                progress(clean)
        else:
            raise RuntimeError("Agent installation took too long. Retry on a stable connection.")
        code = process.wait(timeout=10)
        if code:
            raise RuntimeError("Publisher installer failed (exit %d): %s" % (code, " · ".join(recent)[-1000:]))
    finally:
        stop_child(process)


def stop_child(process):
    import signal
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except (OSError, ProcessLookupError):
            process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except (OSError, ProcessLookupError):
                process.kill()
            process.wait(timeout=5)


def npm_install(provider, config, stage):
    if not shutil.which("node") or not shutil.which("npm"):
        raise RuntimeError("Node.js is missing. Run workspace setup again.")
    node_version = subprocess.check_output(["node", "--version"], text=True, timeout=30).strip()
    if int(node_version.lstrip("v").split(".")[0]) < 22:
        raise RuntimeError("This agent needs Node.js 22 or newer. Update the Ubuntu workspace.")
    package = config["package"] + "@" + config["version"]
    progress("Installing publisher package " + package)
    # npm validates each downloaded tarball against registry integrity metadata.
    # The top-level release is additionally pinned to the digest reviewed for this APK.
    metadata_file = stage / "publisher.json"
    metadata_url = "https://registry.npmjs.org/" + urllib.parse.quote(config["package"], safe="@/") + "/" + config["version"]
    download(metadata_url, metadata_file, 2 * 1024 * 1024)
    metadata = json.loads(metadata_file.read_text())
    if (metadata.get("name") != config["package"] or metadata.get("version") != config["version"]
            or metadata.get("dist", {}).get("integrity") != config["integrity"]):
        raise RuntimeError("The publisher package no longer matches the pinned release integrity.")
    checked_url(metadata["dist"]["tarball"])
    package_archive = stage / "publisher-package.tgz"
    download(metadata["dist"]["tarball"], package_archive)
    package_hash = hashlib.sha512()
    with package_archive.open("rb") as stream:
        for block in iter(lambda: stream.read(256 * 1024), b""):
            package_hash.update(block)
    observed_integrity = "sha512-" + base64.b64encode(package_hash.digest()).decode("ascii")
    if observed_integrity != config["integrity"]:
        raise RuntimeError("Downloaded publisher package failed its pinned integrity check.")
    env = os.environ.copy()
    env.update({"npm_config_registry": "https://registry.npmjs.org/",
                "npm_config_fetch_retries": "3", "npm_config_fetch_timeout": "120000",
                "npm_config_update_notifier": "false", "npm_config_audit": "false",
                "npm_config_fund": "false"})
    run_logged(["npm", "install", "--prefix", str(stage), "--save-exact", "--include=optional",
                "--no-audit", "--no-fund", "--registry=https://registry.npmjs.org/", str(package_archive)], env=env)
    lock = json.loads((stage / "package-lock.json").read_text())
    installed = lock.get("packages", {}).get("node_modules/" + config["package"], {})
    if installed.get("version") != config["version"] or installed.get("integrity") != config["integrity"]:
        raise RuntimeError("Installed package integrity does not match the reviewed release.")
    executable = stage / "node_modules" / ".bin" / config["binary"]
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise RuntimeError("The publisher's Linux ARM64 executable was not installed.")
    return executable.relative_to(stage), config["integrity"]


def write_atomic(path, text, mode=0o700):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name("." + path.name + "." + uuid.uuid4().hex)
    try:
        with temporary.open("w", encoding="utf-8") as output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        temporary.chmod(mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def install_browser_helpers():
    helper = """#!/usr/bin/python3
import sys
from urllib.parse import urlsplit
for value in sys.argv[1:]:
    url = urlsplit(value)
    if url.scheme == 'https' and url.hostname and not url.username and not url.password and '\\n' not in value and '\\r' not in value:
        print('POCKETAGENT_AUTH_URL:' + value, file=sys.stderr, flush=True)
        sys.exit(0)
sys.exit(1)
"""
    write_atomic(Path("/usr/local/bin/pocketagent-auth-open"), helper)
    # This PATH shim only affects native agent sessions. The existing desktop's
    # real xdg-open and its file associations remain usable.
    write_atomic(Path("/opt/pocketagent/browser-bin/xdg-open"),
                 '#!/bin/sh\nexec /usr/local/bin/pocketagent-auth-open "$@"\n')


def wrapper_text(executable, args):
    return ("#!/bin/sh\n"
            "export BROWSER=/usr/local/bin/pocketagent-auth-open\n"
            "export PATH=/opt/pocketagent/browser-bin:$PATH\n"
            + "exec " + shlex.join([str(executable)] + args) + ' "$@"\n')


def verify_protocol(command, provider):
    if provider == "claude":
        result = subprocess.run(command + ["--version"], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, timeout=60)
        if result.returncode or CONFIG[provider]["version"] not in result.stdout:
            raise RuntimeError("Claude Code could not run on this phone's Ubuntu workspace.")
        progress("Claude Code executable verified. Account sign-in is still required.")
        return
    if provider == "codex":
        command = command + ["app-server"]
        request = {"id": 1, "method": "initialize", "params": {
            "clientInfo": {"name": "pocketagent", "title": "PocketAgent", "version": "14.0.0"}}}
    else:
        request = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": 1, "clientInfo": {"name": "pocketagent", "version": "14.0.0"},
            "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False}}}
    child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True, start_new_session=True)
    output = queue.Queue(maxsize=100)
    diagnostics = collections.deque(maxlen=4)

    def read_stdout():
        for line in child.stdout:
            try:
                output.put(line, timeout=1)
            except queue.Full:
                pass

    def read_stderr():
        for line in child.stderr:
            # Do not persist login URLs or anything resembling an auth token.
            if "http" not in line.lower() and "token" not in line.lower():
                diagnostics.append(line.strip()[:300])

    threading.Thread(target=read_stdout, daemon=True).start()
    threading.Thread(target=read_stderr, daemon=True).start()
    try:
        child.stdin.write(json.dumps(request) + "\n")
        child.stdin.flush()
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            try:
                line = output.get(timeout=1)
            except queue.Empty:
                if child.poll() is not None:
                    break
                continue
            try:
                message = json.loads(line)
            except (ValueError, TypeError):
                continue
            if isinstance(message, dict) and message.get("id") == 1:
                if isinstance(message.get("result"), dict):
                    progress("Agent protocol verified. Account sign-in is still required.")
                    return
                raise RuntimeError("The agent rejected PocketAgent's protocol handshake.")
        detail = " · ".join(diagnostics)
        raise RuntimeError("The agent did not start its native connection on this phone. " + detail)
    finally:
        child.stdin.close()
        stop_child(child)


def install(provider):
    if provider == "antigravity":
        raise RuntimeError(
            "Antigravity is not connected yet. Its CLI streams its own newline-delimited event "
            "format rather than the protocol this build speaks, and the adapter for it is not "
            "written. This is PocketAgent's missing work, not a refusal by Google.")
    if provider not in CONFIG:
        raise RuntimeError("Unknown provider.")
    config = CONFIG[provider]
    architecture = subprocess.check_output(["dpkg", "--print-architecture"], text=True, timeout=10).strip()
    if architecture != "arm64":
        raise RuntimeError("This installation needs an ARM64 Ubuntu workspace.")
    if shutil.disk_usage("/opt").free < 1500 * 1024 * 1024:
        raise RuntimeError("Free at least 1.5 GB of phone storage before installing an agent.")
    BASE.mkdir(parents=True, exist_ok=True)
    RECEIPTS.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix="." + provider + "-", dir=BASE))
    final = BASE / (provider + "-" + config["version"] + "-" + uuid.uuid4().hex[:8])
    committed = False
    published = False
    try:
        if "package" in config:
            relative_entry, digest = npm_install(provider, config, stage)
            integrity_kind = "npm-registry-integrity-pinned"
        else:
            progress("Downloading the official Linux ARM64 agent.")
            archive = stage / "publisher-download.tar.gz"
            digest = download(config["archive"], archive)
            progress("Checking and unpacking publisher files.")
            payload = stage / "payload"
            payload.mkdir()
            safe_extract(archive, payload)
            archive.unlink()
            relative_entry = Path("payload") / config["binary"]
            integrity_kind = "publisher-https-no-published-checksum"
        executable = stage / relative_entry
        if not executable.is_file():
            raise RuntimeError("The agent archive did not contain its documented executable.")
        executable.chmod(executable.stat().st_mode | stat.S_IXUSR)
        install_browser_helpers()
        # Verify from the final path, before changing the working install's wrapper.
        stage.rename(final)
        executable = final / relative_entry
        candidate = final / "pocketagent-launch"
        write_atomic(candidate, wrapper_text(executable, config.get("args", [])))
        progress("Checking this phone can start the agent. No model request is sent.")
        verify_protocol([str(candidate)], provider)
        receipt = {
            "schema": 1, "provider": provider, "version": config["version"],
            "entry": str(executable), "verified": True, "protocol": config["protocol"],
            "source": config.get("archive", config.get("package")),
            "integrityKind": integrity_kind, "digest": digest,
            "documentation": config["docs"], "installedAt": int(time.time()),
            "accountVerified": False,
        }
        write_atomic(Path("/usr/local/bin/pocketagent-" + provider), candidate.read_text())
        # If receipt publication is interrupted, retain the verified target that
        # the already-published wrapper uses. A later retry can safely finish setup.
        published = True
        write_atomic(RECEIPTS / (provider + ".json"), json.dumps(receipt, indent=2) + "\n", 0o600)
        committed = True
        progress("Installed " + provider + " " + config["version"] + ". Ready for account connection.")
    finally:
        if stage.exists():
            shutil.rmtree(stage)
        if not committed and not published and final.exists():
            shutil.rmtree(final)


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise RuntimeError("Choose an agent in PocketAgent.")
        install(sys.argv[1])
    except (Exception, KeyboardInterrupt) as failure:
        progress("ERROR: " + str(failure).replace("\n", " ")[:1800])
        sys.exit(1)
