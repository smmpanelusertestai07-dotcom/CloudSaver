"""The CI tools: the YAML reader, the pin reader, the GPL notice and the secrets file."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tests.support import REPO, TOOLS

import pins
import yaml_lite

sys.path.insert(0, str(TOOLS / "proot"))
import gpl_notice  # noqa: E402


def load_script(name: str):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), TOOLS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


secrets_file = load_script("make-secrets-file")


def plain(node):
    """PyYAML's reading in yaml_lite's terms: every scalar a string, the YAML 1.1 'on' key back to "on"."""
    if isinstance(node, dict):
        return {("on" if key is True else str(key)): plain(value) for key, value in node.items()}
    if isinstance(node, list):
        return [plain(value) for value in node]
    if isinstance(node, bool):
        return "true" if node else "false"
    return node if node is None else str(node)


class YamlLite(unittest.TestCase):
    def test_reads_the_real_workflow_exactly_like_pyyaml(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML is not installed")
        text = (REPO / ".github/workflows/pocket.yml").read_text(encoding="utf-8")
        self.assertEqual(plain(yaml.safe_load(text)), yaml_lite.load(text))

    def test_block_scalars_and_chomping(self):
        text = "a: |\n  one\n  two\n\nb: >-\n  x\n  y\nc: |+\n  keep\n\n"
        self.assertEqual({"a": "one\ntwo\n", "b": "x y", "c": "keep\n\n"}, yaml_lite.load(text))

    def test_lists_of_maps_flow_lists_and_comments(self):
        text = "on: [push, 'workflow_dispatch']  # both\nsteps:\n  - uses: a/b@c # v1\n    with:\n      k: \"v # not a comment\"\n  - run: x\n"
        self.assertEqual({"on": ["push", "workflow_dispatch"],
                          "steps": [{"uses": "a/b@c", "with": {"k": "v # not a comment"}}, {"run": "x"}]},
                         yaml_lite.load(text))

    def test_bad_indentation_is_an_error(self):
        with self.assertRaises(yaml_lite.YamlError):
            yaml_lite.load("a:\n  b: 1\n    c: 2\n")

    def test_duplicate_keys_are_an_error(self):
        with self.assertRaises(yaml_lite.YamlError):
            yaml_lite.load("a: 1\na: 2\n")


class Pins(unittest.TestCase):
    KOTLIN = '''
object LinuxPins {
    val ubuntuBase = PinnedDownload(
        url = "https://example.org/base.tar.gz",
        sha256 = "%s",
        bytes = 29_936_675,
    )
    val codeServer = CodeServerPin(version = "4.1", url = "https://github.com/o/r/releases/download/v1/x.tgz",
        sha256 = "%s", bytes = 5L)
    val computed = PinnedDownload(url = base + name, sha256 = digest, bytes = size)
}
''' % ("a" * 64, "b" * 64)

    def test_reads_literal_pins_and_skips_computed_ones(self):
        found = pins.parse(self.KOTLIN, "LinuxPins.kt")
        self.assertEqual(["ubuntuBase", "codeServer"], [p.name for p in found])
        self.assertEqual(("https://example.org/base.tar.gz", "a" * 64, 29_936_675),
                         (found[0].url, found[0].sha256, found[0].bytes))
        self.assertEqual(5, found[1].bytes)

    def test_the_app_pins_the_ubuntu_base(self):
        names = {p.name for p in pins.all_pins()}
        self.assertIn("ubuntuBase", names)


class GplNotice(unittest.TestCase):
    RELEASES = [
        {"version": "5.1.107.94", "build": True, "origin": "Built by CI.",
         "proot": {"license": "GPL-2.0", "tag": "v5.1.107.94", "commit": "58c3b428", "url": "https://x/p.zip", "sha256": "c" * 64},
         "talloc": {"license": "LGPL-3.0-or-later", "version": "2.4.3", "url": "https://x/t.tar.gz", "sha256": "d" * 64},
         "libandroid_shmem": {"license": "BSD-3-Clause", "version": "0.7", "url": "https://x/s.tar.gz", "sha256": "e" * 64}},
        {"version": "5.1.107.92", "build": False, "origin": "Prebuilt.",
         "proot": {"license": "GPL-2.0", "tag": "v5.1.107.92", "commit": "7266fb3e", "url": "https://x/tree"},
         "talloc": {"license": "LGPL-3.0-or-later", "version": "unknown", "url": "https://x/t"},
         "libandroid_shmem": {"license": "BSD-3-Clause", "version": "unknown", "url": "https://x/s"}},
    ]

    def test_finds_the_release_by_the_version_inside_the_binary(self):
        binary = b"\x00ELF...proot v5.1.107.94\x00 1.2.3 \x00"
        release = gpl_notice.shipped_release(binary, self.RELEASES)
        text = gpl_notice.notice(release, "3.0.0")
        self.assertIn("PRoot 5.1.107.94", text)
        self.assertIn("commit 58c3b428", text)
        self.assertIn("talloc, LGPL-3.0-or-later", text)
        self.assertIn("attached to this release", text)

    def test_an_unknown_proot_blocks_the_release(self):
        with self.assertRaises(gpl_notice.NoticeError):
            gpl_notice.shipped_release(b"proot 5.1.107.99", self.RELEASES)

    def test_the_shipped_proot_is_known(self):
        libproot = (TOOLS.parent / "app/src/main/jniLibs/arm64-v8a/libproot.so").read_bytes()
        self.assertTrue(gpl_notice.shipped_release(libproot, gpl_notice.releases()))

    def test_exactly_one_release_is_built_from_source(self):
        self.assertEqual(1, sum(1 for r in gpl_notice.releases() if r.get("build")))


class SecretsFile(unittest.TestCase):
    FACTS = secrets_file.KeyFacts("QUJD", "store-pass", "key-pass", "pocketide", "AA:BB", "CC:DD")

    def test_lists_every_value_and_step(self):
        text = secrets_file.render(self.FACTS, "owner/repo", "release.jks")
        for expected in ("POCKETIDE_KEYSTORE_B64=QUJD", "POCKETIDE_STORE_PASS=store-pass", "POCKETIDE_KEY_PASS=key-pass",
                         "POCKETIDE_KEY_ALIAS=pocketide", "POCKETIDE_GITHUB_APP_CLIENT_ID=", "POCKETIDE_GITHUB_APP_SLUG=",
                         "Package name: com.pocketide", "SHA-1:        AA:BB", "SHA-256:      CC:DD",
                         "Enable Device Flow", "Plan: Read-only", "drive.appdata", "In production",
                         "https://github.com/owner/repo/settings/secrets/actions", "Run workflow"):
            self.assertIn(expected, text)

    def test_the_file_is_private_and_never_overwritten_silently(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work, "secrets.txt")
            secrets_file.write_private(path, "x", overwrite=False)
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            with self.assertRaises(secrets_file.SetupError):
                secrets_file.write_private(path, "y", overwrite=False)

    def test_refuses_to_run_in_ci(self):
        errors = io.StringIO()
        with tempfile.TemporaryDirectory() as work, mock.patch.dict(os.environ, {"CI": "true"}), \
                contextlib.redirect_stderr(errors):
            out = Path(work, "never.txt")
            self.assertEqual(1, secrets_file.main(["missing.jks", "--out", str(out)]))
            self.assertFalse(out.exists())
        self.assertIn("not in CI", errors.getvalue())

    @unittest.skipUnless(shutil.which("keytool"), "keytool is not installed")
    def test_a_new_key_gives_matching_fingerprints(self):
        with tempfile.TemporaryDirectory() as work, mock.patch.dict(os.environ, {}, clear=False):
            for name in ("CI", "GITHUB_ACTIONS"):
                os.environ.pop(name, None)
            keystore, out = Path(work, "k.p12"), Path(work, "s.txt")
            with mock.patch("builtins.print"):
                self.assertEqual(0, secrets_file.main([str(keystore), "--create", "--out", str(out)]))
            text = out.read_text()
            password = next(l.split("=", 1)[1] for l in text.splitlines() if l.startswith("POCKETIDE_STORE_PASS="))
            listing = subprocess.run(["keytool", "-list", "-v", "-keystore", str(keystore), "-storepass", password],
                                     capture_output=True, text=True, check=True).stdout
            sha1 = next(l.split("SHA1:")[1].strip() for l in listing.splitlines() if "SHA1:" in l)
            self.assertIn(f"SHA-1:        {sha1}", text)


class RoomTools(unittest.TestCase):
    """The Python the rooms run inside Linux (assets/rooms), when that module has shipped it."""

    def test_every_room_tool_compiles(self):
        tools = sorted((TOOLS.parent / "app/src/main/assets/rooms").rglob("*.py"))
        if not tools:
            self.skipTest("no Python under assets/rooms yet")
        for path in tools:
            with self.subTest(tool=path.name):
                compile(path.read_text(encoding="utf-8"), str(path), "exec")


if __name__ == "__main__":
    unittest.main()
