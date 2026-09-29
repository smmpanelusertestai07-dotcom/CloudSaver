#!/usr/bin/env python3
"""Downloads the official agents the app installs, for one platform, from Open VSX.

Each download is kept only when it matches the SHA-256 Open VSX publishes for it and comes from a
publisher Open VSX has verified (the app checks each one's signature as well). The agents are the
ones the app lists for its companion (ide-files/agents.json, which a unit test writes).

Usage: agents.py AGENTS_JSON --target linux-arm64 --vscode 1.139.1 --out DIR
(prints each file it saved)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

API = "https://open-vsx.org/api"
TIMEOUT = 120
ATTEMPTS = 4
PAGE = 50
MAX_VERSIONS = 1000


class AgentError(Exception):
    pass


def get(url: str) -> bytes:
    """The body at url; a missing page (404) is raised at once, anything else is tried again."""
    for attempt in range(ATTEMPTS):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "pocketide-ci"})
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404 or attempt == ATTEMPTS - 1:
                raise
        except OSError:
            if attempt == ATTEMPTS - 1:
                raise
        time.sleep(5 * (attempt + 1))
    raise AssertionError("unreachable")


def engine_accepts(engine: str, vscode: tuple[int, ...]) -> bool:
    """A VS Code engine range as extensions write it (^1.94.0, >=1.80.0, 1.94.0) against a version."""
    engine = engine.strip()
    bound = tuple(int(part) for part in engine.lstrip("^>=~").split("-")[0].split(".")[:3])
    if engine.startswith("^"):
        return vscode[0] == bound[0] and vscode >= bound
    if engine.startswith(">="):
        return vscode >= bound
    return vscode == bound


def release(extension_id: str, target: str, vscode: tuple[int, ...]) -> dict:
    """What the app installs: the highest release, not a pre-release, built for target (else for
    every platform), from its verified publisher, whose engine range takes code-server's VS Code.
    Open VSX orders by number, and Codex numbers its pre-releases above its releases."""
    namespace, name = extension_id.split(".", 1)
    for platform in (target, "universal"):
        offset = 0
        while True:
            page = json.loads(get(f"{API}/-/query?namespaceName={namespace}&extensionName={name}"
                                  f"&targetPlatform={platform}&includeAllVersions=true&size={PAGE}&offset={offset}"))
            versions = page.get("extensions") or []
            for meta in versions:
                version = str(meta.get("version", ""))
                engine = (meta.get("engines") or {}).get("vscode", "")
                if (meta.get("targetPlatform") or "universal") == platform and not meta.get("preRelease") \
                        and "-" not in version and meta.get("verified") and engine \
                        and engine_accepts(engine, vscode):
                    return meta
            offset += len(versions)
            if not versions or offset >= page.get("totalSize", 0) or offset >= MAX_VERSIONS:
                break
    raise AgentError(f"{extension_id}: Open VSX has no release for {target} that code-server "
                     f"{'.'.join(map(str, vscode))} can run")


def download(extension_id: str, target: str, vscode: tuple[int, ...], out: Path) -> Path:
    meta = release(extension_id, target, vscode)
    if not meta.get("verified"):
        raise AgentError(f"{extension_id}: Open VSX does not list its publisher as verified")
    files = meta.get("files") or {}
    if not files.get("download") or not files.get("sha256"):
        raise AgentError(f"{extension_id} {meta.get('version')}: Open VSX lists no download or checksum")
    data = get(files["download"])
    published = get(files["sha256"]).decode().split()[0].lower()
    actual = hashlib.sha256(data).hexdigest()
    if actual != published:
        raise AgentError(f"{extension_id} {meta.get('version')}: the download hashes to {actual}, "
                         f"Open VSX publishes {published}")
    platform = meta.get("targetPlatform") or "universal"
    path = out / f"{extension_id}-{meta['version']}@{platform}.vsix"
    path.write_bytes(data)
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("agents", type=Path, help="the app's agents.json")
    parser.add_argument("--target", required=True, help="Open VSX platform, for example linux-arm64")
    parser.add_argument("--vscode", required=True, help="code-server's VS Code version, for example 1.139.1")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    try:
        vscode = tuple(int(part) for part in args.vscode.split(".")[:3])
        agents = json.loads(args.agents.read_text(encoding="utf-8"))
        for agent in agents:
            print(download(agent["id"], args.target, vscode, args.out))
    except (AgentError, OSError, ValueError, KeyError) as error:
        print(f"agents.py: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
