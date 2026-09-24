#!/usr/bin/env python3
"""Re-checks every download the app pins against the publisher's own record, from CI.

A pin was verified once, by hand, on the day it was set. This makes that check repeatable, and
catches a publisher that replaced or withdrew a file before an owner's phone finds out.

  cdimage.ubuntu.com   SHA256SUMS beside the file, its signature verified with gpgv against
                       Ubuntu's archive keyring
  GitHub releases      the asset's digest and size from the REST API (the digest GitHub
                       computed at upload; code-server publishes no checksum file)
  open-vsx.org         the registry's own .sha256 for the file
  anything else        downloaded and hashed here

Environment: GITHUB_TOKEN (optional) raises the API rate limit.
Usage: check-pins.py
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pins  # noqa: E402

UBUNTU_KEYRING = Path("/usr/share/keyrings/ubuntu-archive-keyring.gpg")
TIMEOUT = 60


class Mismatch(Exception):
    pass


def fetch(url: str, headers: dict[str, str] | None = None) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "pocketide-ci", **(headers or {})})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return response.read()


def stream_sha256(url: str) -> tuple[str, int]:
    digest, size = hashlib.sha256(), 0
    request = urllib.request.Request(url, headers={"User-Agent": "pocketide-ci"})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        while chunk := response.read(1 << 20):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def check_ubuntu(pin: pins.Pin) -> str:
    base, name = pin.url.rsplit("/", 1)
    sums, signature = fetch(f"{base}/SHA256SUMS"), fetch(f"{base}/SHA256SUMS.gpg")
    if not UBUNTU_KEYRING.is_file():
        raise Mismatch(f"{UBUNTU_KEYRING} is missing, so SHA256SUMS cannot be verified")
    with tempfile.TemporaryDirectory() as work:
        sums_path, sig_path = Path(work, "SHA256SUMS"), Path(work, "SHA256SUMS.gpg")
        sums_path.write_bytes(sums)
        sig_path.write_bytes(signature)
        result = subprocess.run(["gpgv", "--keyring", str(UBUNTU_KEYRING), str(sig_path), str(sums_path)],
                                capture_output=True, text=True)
        if result.returncode != 0:
            raise Mismatch(f"SHA256SUMS signature does not verify: {result.stderr.strip()}")
    for line in sums.decode().splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip("*") == name:
            if parts[0] != pin.sha256:
                raise Mismatch(f"Ubuntu's signed SHA256SUMS says {parts[0]}")
            return "matches Ubuntu's signed SHA256SUMS"
    raise Mismatch(f"{name} is not listed in Ubuntu's SHA256SUMS")


def check_github_release(pin: pins.Pin) -> str:
    match = re.fullmatch(r"https://github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/([^/]+)", pin.url)
    if not match:
        raise Mismatch("not a GitHub release asset URL")
    owner, repo, tag, name = match.groups()
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    release = json.loads(fetch(f"https://api.github.com/repos/{owner}/{repo}/releases/tags/{tag}", headers))
    assets = [a for a in release.get("assets", []) if a.get("name") == name]
    if not assets:
        raise Mismatch(f"release {tag} of {owner}/{repo} has no asset {name}")
    asset = assets[0]
    digest = asset.get("digest") or ""
    if not digest.startswith("sha256:"):
        raise Mismatch("GitHub reports no sha256 digest for the asset")
    if digest.removeprefix("sha256:") != pin.sha256:
        raise Mismatch(f"GitHub's asset digest is {digest}")
    if pin.bytes is not None and asset.get("size") != pin.bytes:
        raise Mismatch(f"GitHub's asset is {asset.get('size')} bytes, the pin says {pin.bytes}")
    return "matches the release asset digest from GitHub's API"


def check_open_vsx(pin: pins.Pin) -> str:
    published = fetch(pin.url + ".sha256").decode().strip().split()[0].lower()
    if published != pin.sha256:
        raise Mismatch(f"Open VSX publishes {published}")
    return "matches Open VSX's .sha256"


def check_by_download(pin: pins.Pin) -> str:
    digest, size = stream_sha256(pin.url)
    if digest != pin.sha256:
        raise Mismatch(f"the file downloaded now hashes to {digest}")
    if pin.bytes is not None and size != pin.bytes:
        raise Mismatch(f"the file downloaded now is {size} bytes, the pin says {pin.bytes}")
    return "downloaded and hashed"


def verifier(url: str):
    host = urlparse(url).hostname or ""
    if host == "cdimage.ubuntu.com":
        return check_ubuntu
    if host == "github.com" and "/releases/download/" in url:
        return check_github_release
    if host == "open-vsx.org" and url.endswith(".vsix"):
        return check_open_vsx
    return check_by_download


def main() -> int:
    found = pins.all_pins()
    if not found:
        print("No pinned downloads found in the Kotlin sources.", file=sys.stderr)
        return 1
    failures = 0
    for pin in found:
        try:
            how = verifier(pin.url)(pin)
            print(f"[ok  ] {pin.name} ({pin.source}): {how}")
        except (Mismatch, OSError, ValueError) as error:
            failures += 1
            print(f"[FAIL] {pin.name} ({pin.source}): {pin.url}\n         {error}")
    print()
    print(f"{len(found) - failures} of {len(found)} pins verified.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
