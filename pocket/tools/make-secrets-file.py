#!/usr/bin/env python3
"""Writes the owner's PocketIDE secrets file: every value GitHub Actions needs, and where it goes.

Run it on your own computer, never in CI: the file it writes holds the signing key and its
passwords. Nothing secret is printed; the file is created readable by you alone (0600), and never
inside a git working tree, so it cannot be committed by accident.

  make-secrets-file.py pocketide-release.jks --repo OWNER/NAME
  make-secrets-file.py new-key.p12 --create --repo OWNER/NAME     (makes a new key first)

Passwords come from the environment (POCKETIDE_STORE_PASS, POCKETIDE_KEY_PASS), then from
--store-pass/--key-pass (visible to other programs on this computer while it runs), then from a
prompt. The key alias comes from --alias, POCKETIDE_KEY_ALIAS, or the store's only key.
Needs keytool (from any JDK).
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import getpass
import hashlib
import os
import re
import secrets
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

PACKAGE = "com.pocketide"
WORKFLOW = ".github/workflows/pocket.yml"
DEFAULT_ALIAS = "pocketide"


class SetupError(Exception):
    pass


@dataclass
class KeyFacts:
    keystore_b64: str
    store_pass: str
    key_pass: str
    alias: str
    sha1: str
    sha256: str


def keytool(*args: str, env: dict[str, str]) -> str:
    result = subprocess.run(["keytool", *args], capture_output=True, text=True, env={**os.environ, **env})
    if result.returncode != 0:
        # keytool's own message never contains the password; show its first line.
        message = (result.stdout + result.stderr).strip().splitlines()
        raise SetupError(message[0] if message else "keytool failed")
    return result.stdout


def create_keystore(path: Path, alias: str) -> str:
    if path.exists():
        raise SetupError(f"{path} already exists; --create makes a new key and never replaces one")
    password = secrets.token_urlsafe(24)
    keytool("-genkeypair", "-keystore", str(path), "-storetype", "PKCS12", "-alias", alias,
            "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000",
            "-dname", "CN=PocketIDE release, O=PocketIDE",
            "-storepass:env", "PI_STORE", "-keypass:env", "PI_STORE", env={"PI_STORE": password})
    os.chmod(path, 0o600)
    return password


def only_alias(path: Path, store_pass: str) -> str:
    listing = keytool("-list", "-keystore", str(path), "-storepass:env", "PI_STORE", env={"PI_STORE": store_pass})
    # "<alias>, <date, which has commas>, PrivateKeyEntry,"
    aliases = re.findall(r"^([^,\n]+),.*\bPrivateKeyEntry\b", listing, re.M)
    if len(aliases) != 1:
        raise SetupError(f"the key store holds {len(aliases)} keys; name the one to use with --alias")
    return aliases[0].strip()


def fingerprints(path: Path, alias: str, store_pass: str) -> tuple[str, str]:
    pem = keytool("-exportcert", "-rfc", "-keystore", str(path), "-alias", alias,
                  "-storepass:env", "PI_STORE", env={"PI_STORE": store_pass})
    body = re.search(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", pem, re.S)
    if not body:
        raise SetupError(f"no certificate for alias {alias}")
    der = base64.b64decode("".join(body.group(1).split()))
    return _colons(hashlib.sha1(der).digest()), _colons(hashlib.sha256(der).digest())


def _colons(digest: bytes) -> str:
    """AB:CD:… as the Google Cloud console and keytool write fingerprints."""
    return ":".join(f"{b:02X}" for b in digest)


def check_key_password(path: Path, alias: str, store_pass: str, key_pass: str) -> None:
    """A certificate request needs the private key itself, so it fails on a wrong key password.

    keytool opens a PKCS12 key with the store password whatever -keypass says, while apksigner
    uses the key password given; so for PKCS12 the two must simply be the same.
    """
    listing = keytool("-list", "-keystore", str(path), "-storepass:env", "PI_STORE", env={"PI_STORE": store_pass})
    if re.search(r"^Keystore type:\s*PKCS12", listing, re.M | re.I) and key_pass != store_pass:
        raise SetupError("in a PKCS12 key store the key password is the store password; give the same one twice")
    with tempfile.TemporaryDirectory() as work:
        try:
            keytool("-certreq", "-keystore", str(path), "-alias", alias, "-file", str(Path(work, "req")),
                    "-storepass:env", "PI_STORE", "-keypass:env", "PI_KEY",
                    env={"PI_STORE": store_pass, "PI_KEY": key_pass})
        except SetupError as error:
            raise SetupError(f"the key password does not open key '{alias}' ({error})") from None


def inside_git_tree(path: Path) -> bool:
    return any((parent / ".git").exists() for parent in [path, *path.parents])


def secret(value: str | None, env_name: str, prompt: str) -> str:
    return os.environ.get(env_name) or value or getpass.getpass(prompt)


def gather(args: argparse.Namespace) -> KeyFacts:
    path = args.keystore
    alias = args.alias or os.environ.get("POCKETIDE_KEY_ALIAS")
    if args.create:
        alias = alias or DEFAULT_ALIAS
        store_pass = key_pass = create_keystore(path, alias)
        print(f"Made a new signing key in {path}. Back that file up somewhere private: an app")
        print("signed with it can only ever be updated by an APK signed with it.")
    else:
        if not path.is_file():
            raise SetupError(f"{path} does not exist (add --create to make a new key there)")
        store_pass = secret(args.store_pass, "POCKETIDE_STORE_PASS", "Key store password: ")
        alias = alias or only_alias(path, store_pass)
        key_pass = os.environ.get("POCKETIDE_KEY_PASS") or args.key_pass \
            or getpass.getpass("Key password (Enter if it is the same): ") or store_pass
    check_key_password(path, alias, store_pass, key_pass)
    sha1, sha256 = fingerprints(path, alias, store_pass)
    b64 = base64.b64encode(path.read_bytes()).decode()
    return KeyFacts(b64, store_pass, key_pass, alias, sha1, sha256)


def render(facts: KeyFacts, repo: str | None, keystore_name: str) -> str:
    today = dt.datetime.now(dt.timezone.utc).strftime("%d %b %Y")
    site = f"https://github.com/{repo}" if repo else "https://github.com/<owner>/<repository>"
    return f"""PocketIDE 3: secrets and settings for GitHub Actions
Made on {today} from {keystore_name}. Keep this file private, and delete it once every value is saved.
Screen names below are as of September 2026; if a label has moved, the path is still right.

Repository:   {site}
Workflow:     {WORKFLOW} (shown as "pocketide" in the Actions tab)
Secrets page: {site}/settings/secrets/actions
Variables:    {site}/settings/variables/actions


1. REPOSITORY SECRETS
   Settings > Secrets and variables > Actions > Secrets > New repository secret.
   One secret per line: the name before "=", the value after it.

POCKETIDE_KEYSTORE_B64={facts.keystore_b64}
POCKETIDE_STORE_PASS={facts.store_pass}
POCKETIDE_KEY_PASS={facts.key_pass}
POCKETIDE_KEY_ALIAS={facts.alias}


2. REPOSITORY VARIABLES (not secret; fill them in after step A below)
   Settings > Secrets and variables > Actions > Variables > New repository variable.

POCKETIDE_GITHUB_APP_CLIENT_ID=<the GitHub App's Client ID, which starts with "Iv">
POCKETIDE_GITHUB_APP_SLUG=<the last part of the App's public link, github.com/apps/<slug>>


3. SIGNING CERTIFICATE (not secret; for the Google Android OAuth client in step B)

Package name: {PACKAGE}
SHA-1:        {facts.sha1}
SHA-256:      {facts.sha256}


A. CREATE THE GITHUB APP (sign-in for PocketIDE; no server, no client secret)
   1. Open https://github.com/settings/apps/new
      (Settings > Developer settings > GitHub Apps > New GitHub App).
   2. GitHub App name: anything unique, for example "PocketIDE <your login>".
      Homepage URL: {site}
   3. Identifying and authorizing users: leave Callback URL empty; keep "Expire user
      authorization tokens" ticked; leave "Request user authorization (OAuth) during
      installation" unticked; tick "Enable Device Flow".
   4. Webhook: untick "Active". PocketIDE needs no webhook.
   5. Repository permissions:
        Actions: Read and write           Administration: Read and write
        Contents: Read and write          Metadata: Read-only
        Pull requests: Read and write     Secrets: Read and write
        Workflows: Read and write
      Account permissions:
        Plan: Read-only (so the app can show your real Actions usage)
   6. Where can this GitHub App be installed? Only on this account. Then Create GitHub App.
   7. On the App's page, copy the Client ID into POCKETIDE_GITHUB_APP_CLIENT_ID, and the slug
      (the last part of its public link) into POCKETIDE_GITHUB_APP_SLUG. Do not generate a
      client secret or a private key: PocketIDE uses neither.
   8. Install App > your account > Only select repositories > pick your project repositories.
      Repositories PocketIDE creates for you join the installation by themselves.


B. CREATE THE GOOGLE CLOUD PROJECT (Drive's hidden app folder)
   1. Open https://console.cloud.google.com/projectcreate and create a project, e.g. "PocketIDE".
   2. APIs & Services > Library > Google Drive API > Enable.
   3. Google Auth Platform (the OAuth consent screen) > Get started: app name PocketIDE, your
      email as support and contact email, Audience: External.
   4. Data access > Add or remove scopes: tick .../auth/drive.appdata only, then Save.
      It is a non-sensitive scope: no verification is needed.
   5. Audience > Publish app, so the status reads "In production". In "Testing", Google ends
      every sign-in after 7 days.
   6. Clients > Create client > Application type: Android.
      Name: PocketIDE release. Package name: {PACKAGE}. SHA-1 certificate fingerprint: the
      SHA-1 in section 3. Create. Nothing needs to be copied back: Google recognises the app
      by its package name and signing certificate.
      (A debug build is {PACKAGE}.debug with your own debug key; it needs a second Android
      client with that key's SHA-1.)
   Google deletes OAuth clients that go unused for 6 months; using the app keeps it alive.


C. PASTE THE VALUES
   Secrets from section 1, and the two variables from section 2, on the pages listed at the top.


D. RE-RUN THE WORKFLOW
   Actions > pocketide > Run workflow > Branch: main > Run workflow.
   The build signs with your key, and the release job publishes pocketide-v<version>.


CHECKLIST
   [ ] 4 secrets saved: POCKETIDE_KEYSTORE_B64, POCKETIDE_STORE_PASS, POCKETIDE_KEY_PASS,
       POCKETIDE_KEY_ALIAS
   [ ] 2 variables saved: POCKETIDE_GITHUB_APP_CLIENT_ID, POCKETIDE_GITHUB_APP_SLUG
   [ ] GitHub App: Device Flow on, webhook off, the permissions above, installed on your repositories
   [ ] Google Cloud: Drive API on, drive.appdata scope, In production, Android client with the SHA-1
   [ ] The key store file is backed up somewhere private
   [ ] The workflow ran green on main
   [ ] This file is deleted
"""


def write_private(path: Path, text: str, overwrite: bool) -> None:
    flags = os.O_WRONLY | os.O_CREAT | (os.O_TRUNC if overwrite else os.O_EXCL)
    try:
        descriptor = os.open(path, flags, 0o600)
    except FileExistsError:
        raise SetupError(f"{path} already exists; pass --overwrite to replace it") from None
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(text)
    os.chmod(path, 0o600)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("keystore", type=Path, help="the release key store (.jks or .p12)")
    parser.add_argument("--create", action="store_true", help="make a new key store at that path first")
    parser.add_argument("--alias", help="the key's alias (default: the store's only key)")
    parser.add_argument("--store-pass", help="prefer the POCKETIDE_STORE_PASS environment variable")
    parser.add_argument("--key-pass", help="prefer the POCKETIDE_KEY_PASS environment variable")
    parser.add_argument("--repo", help="OWNER/NAME of the GitHub repository, for the links")
    parser.add_argument("--out", type=Path, default=Path.home() / "pocketide-secrets.txt")
    parser.add_argument("--overwrite", action="store_true", help="replace an existing secrets file")
    args = parser.parse_args(argv)

    try:
        if os.environ.get("GITHUB_ACTIONS") or os.environ.get("CI"):
            raise SetupError("this writes secrets; run it on your own computer, not in CI")
        out = args.out.expanduser().resolve()
        if inside_git_tree(out.parent):
            raise SetupError(f"{out} is inside a git working tree, where it could be committed; "
                             "choose another --out")
        if args.repo and not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repo):
            raise SetupError("--repo must look like OWNER/NAME")
        facts = gather(args)
        write_private(out, render(facts, args.repo, args.keystore.name), args.overwrite)
    except (SetupError, OSError) as error:
        print(f"make-secrets-file: {error}", file=sys.stderr)
        return 1
    print(f"Wrote {out} (readable by you only).")
    print(f"Certificate SHA-1:   {facts.sha1}")
    print(f"Certificate SHA-256: {facts.sha256}")
    print("Follow the steps inside it, then delete it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
