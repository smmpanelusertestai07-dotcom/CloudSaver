#!/usr/bin/env python3
"""Installs the agents' test browser: Chromium (Chrome for Testing) and two MCP servers,
Microsoft's Playwright MCP and Google's Chrome DevTools MCP.

Every download is checked before it is used:
  - the npm packages come from package-lock.json beside this script, each tarball checked
    against the lockfile's sha512 integrity (what `npm ci` does; these packages have no install
    scripts, so nothing runs while unpacking);
  - Chromium is Playwright's Chrome for Testing build for linux-arm64. Playwright publishes no
    checksum for it, so PocketIDE pins the SHA-256 it recorded for this exact file;
  - the system libraries Chromium needs come from Ubuntu's signed archive (apt), through
    Playwright's own `install-deps`.

Usage: install.py --prefix DIR --browsers DIR --node PATH
Prints "STEP <text>" as it goes, then "INSTALLED", or "FAILED <reason>" and exits 1.
"""

import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

REGISTRY = "https://registry.npmjs.org/"
CHROMIUM = {
    "revision": "1246",
    "version": "154.0.8037.0",
    "url": "https://cdn.playwright.dev/builds/cft/154.0.8037.0/linux-arm64/chrome-linux-arm64.zip",
    "sha256": "51f69faf816adcb9264905daa31a7068138e7beefc4d4206edf552311be287d8",
    "bytes": 196474766,
}
TIMEOUT_S = 60
CHUNK = 1024 * 1024


class Failure(Exception):
    pass


def step(text):
    print("STEP " + text, flush=True)


def download(url, target, expected_bytes=None):
    """Downloads url to target; returns the file's sha256 and sha512 digests."""
    sha256 = hashlib.sha256()
    sha512 = hashlib.sha512()
    size = 0
    request = urllib.request.Request(url, headers={"User-Agent": "PocketIDE"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response, open(target, "wb") as out:
            while True:
                chunk = response.read(CHUNK)
                if not chunk:
                    break
                size += len(chunk)
                if expected_bytes is not None and size > expected_bytes:
                    raise Failure("%s is larger than expected" % url)
                sha256.update(chunk)
                sha512.update(chunk)
                out.write(chunk)
    except OSError as problem:
        raise Failure("could not download %s (%s)" % (url, problem))
    if expected_bytes is not None and size != expected_bytes:
        raise Failure("%s arrived incomplete" % url)
    return sha256.hexdigest(), sha512.digest()


def lock_packages(lock_path):
    with open(lock_path, encoding="utf-8") as source:
        lock = json.load(source)
    packages = []
    for key, entry in lock.get("packages", {}).items():
        if not key.startswith("node_modules/"):
            continue
        name = key[len("node_modules/"):]
        resolved = entry.get("resolved", "")
        integrity = entry.get("integrity", "")
        if "/node_modules/" in name or not resolved.startswith(REGISTRY) or not integrity.startswith("sha512-"):
            raise Failure("the lockfile entry for %s is not one this installer accepts" % name)
        if entry.get("hasInstallScript"):
            raise Failure("%s has an install script, which this installer does not run" % name)
        packages.append((name, entry["version"], resolved, integrity))
    return packages


def installed_version(folder):
    try:
        with open(os.path.join(folder, "package.json"), encoding="utf-8") as source:
            return json.load(source).get("version")
    except (OSError, ValueError):
        return None


def safe_members(archive):
    """Tar members that stay inside the package folder: no links out, no absolute paths."""
    for member in archive.getmembers():
        name = member.name
        if not name.startswith("package/") or name.startswith("/") or ".." in name.split("/"):
            continue
        if not (member.isfile() or member.isdir()):
            continue
        member.name = name[len("package/"):]
        if member.name:
            member.mode = 0o755 if member.mode & 0o111 or member.isdir() else 0o644
            yield member


def install_packages(prefix, lock_path):
    modules = os.path.join(prefix, "node_modules")
    os.makedirs(modules, exist_ok=True)
    for name, version, url, integrity in lock_packages(lock_path):
        target = os.path.join(modules, name)
        if installed_version(target) == version:
            continue
        step("Downloading %s %s" % (name, version))
        with tempfile.TemporaryDirectory(dir=prefix) as work:
            tarball = os.path.join(work, "package.tgz")
            _, sha512 = download(url, tarball)
            if "sha512-" + base64.b64encode(sha512).decode("ascii") != integrity:
                raise Failure("%s did not match its lockfile checksum and was discarded" % name)
            staging = os.path.join(work, "package")
            with tarfile.open(tarball, "r:gz") as archive:
                archive.extractall(staging, members=list(safe_members(archive)), filter="data")
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.rmtree(target, ignore_errors=True)
            os.replace(staging, target)


def install_chromium(browsers):
    folder = os.path.join(browsers, "chromium-" + CHROMIUM["revision"])
    marker = os.path.join(folder, "INSTALLATION_COMPLETE")
    if os.path.isfile(marker) and os.path.isfile(os.path.join(folder, "chrome-linux-arm64", "chrome")):
        return
    os.makedirs(browsers, exist_ok=True)
    step("Downloading Chromium %s (%d MB)" % (CHROMIUM["version"], CHROMIUM["bytes"] // 1_000_000))
    with tempfile.TemporaryDirectory(dir=browsers) as work:
        archive_path = os.path.join(work, "chrome.zip")
        sha256, _ = download(CHROMIUM["url"], archive_path, CHROMIUM["bytes"])
        if sha256 != CHROMIUM["sha256"]:
            raise Failure("Chromium did not match its pinned checksum and was discarded")
        step("Unpacking Chromium")
        staging = os.path.join(work, "chromium")
        with zipfile.ZipFile(archive_path) as archive:
            for info in archive.infolist():
                parts = info.filename.split("/")
                if info.filename.startswith("/") or ".." in parts or parts[0] != "chrome-linux-arm64":
                    raise Failure("the Chromium archive has an unexpected entry")
                path = archive.extract(info, staging)
                mode = (info.external_attr >> 16) & 0o777
                if mode and not info.is_dir():
                    os.chmod(path, mode)
        with open(os.path.join(staging, "INSTALLATION_COMPLETE"), "w", encoding="utf-8"):
            pass
        shutil.rmtree(folder, ignore_errors=True)
        os.replace(staging, folder)


def install_system_libraries(node, prefix, browsers):
    step("Installing the libraries Chromium needs (Ubuntu packages)")
    cli = os.path.join(prefix, "node_modules", "playwright-core", "cli.js")
    environment = dict(os.environ, PLAYWRIGHT_BROWSERS_PATH=browsers, DEBIAN_FRONTEND="noninteractive")
    result = subprocess.run([node, cli, "install-deps", "chromium"], env=environment, check=False)
    if result.returncode != 0:
        raise Failure("the system libraries for Chromium could not be installed")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Install the agents' test browser")
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--browsers", required=True)
    parser.add_argument("--node", required=True)
    options = parser.parse_args(argv)
    here = os.path.dirname(os.path.abspath(__file__))
    try:
        if not os.access(options.node, os.X_OK):
            raise Failure("Node was not found at %s" % options.node)
        os.makedirs(options.prefix, exist_ok=True)
        install_packages(options.prefix, os.path.join(here, "package-lock.json"))
        install_chromium(options.browsers)
        install_system_libraries(options.node, options.prefix, options.browsers)
        with open(os.path.join(options.prefix, "installed.json"), "w", encoding="utf-8") as out:
            json.dump({"chromium": CHROMIUM["version"], "revision": CHROMIUM["revision"]}, out)
    except (Failure, OSError, ValueError, tarfile.TarError, zipfile.BadZipFile) as problem:
        print("FAILED %s" % problem, flush=True)
        return 1
    print("INSTALLED", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
