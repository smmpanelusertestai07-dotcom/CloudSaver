"""The CI tools: the YAML reader, the pin reader, the GPL notice, the agent download and the secrets file."""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from tests.support import REPO, TOOLS

import pins
import yaml_lite

sys.path.insert(0, str(TOOLS / "proot"))
sys.path.insert(0, str(TOOLS / "engine"))
import agents  # noqa: E402
import android_policy  # noqa: E402
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
    def test_reads_the_pins_the_app_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "pins.json")
            path.write_text('{"pins": {"ubuntuBase-arm64": {"url": "https://example.org/base.tar.gz", '
                            '"sha256": "%s", "bytes": 5}}}' % ("a" * 64))
            found = pins.all_pins(path)
        self.assertEqual([("ubuntuBase-arm64", "https://example.org/base.tar.gz", "a" * 64, 5)],
                         [(p.name, p.url, p.sha256, p.bytes) for p in found])

    def test_a_missing_file_says_what_writes_it(self):
        with self.assertRaises(FileNotFoundError) as missing:
            pins.all_pins(Path("/nonexistent/pins.json"))
        self.assertIn("LinuxPinsTest", str(missing.exception))


class AgentDownload(unittest.TestCase):
    def test_engine_ranges_as_extensions_write_them(self):
        vscode = (1, 139, 1)
        for engine, accepted in (("^1.94.0", True), ("^1.140.0", False), ("^2.0.0", False), (">=1.80.0", True),
                                 (">=1.140.0", False), ("1.139.1", True), ("1.139.0", False)):
            with self.subTest(engine=engine):
                self.assertEqual(accepted, agents.engine_accepts(engine, vscode))

    def test_a_pre_release_numbered_above_the_release_is_passed_over(self):
        # Codex numbers its pre-releases (26.5908...) above its releases (26.908...).
        page = {"totalSize": 2, "extensions": [
            {"version": "26.5908.31748", "preRelease": True, "targetPlatform": "linux-arm64", "verified": True,
             "engines": {"vscode": "^1.96.2"}},
            {"version": "26.908.40401", "preRelease": False, "targetPlatform": "linux-arm64", "verified": True,
             "engines": {"vscode": "^1.96.2"}},
        ]}
        with mock.patch.object(agents, "get", return_value=json.dumps(page).encode()):
            self.assertEqual("26.908.40401", agents.release("openai.chatgpt", "linux-arm64", (1, 139, 1))["version"])


class AndroidPolicy(unittest.TestCase):
    LISTS = {"allow": {"SYSCALLS.TXT": "", "WHITELIST.TXT": ""}, "block": {"BLACKLIST.TXT": ""}}
    TEXTS = {
        "SYSCALLS.TXT": "\n".join([
            "# comment",
            "int __openat:openat(int, const char*, int, mode_t) all",
            "int fstatat64|fstatat:newfstatat(int, const char*, struct stat*, int) arm64,x86_64",
            "ssize_t pread64|pread(int, void*, size_t, off_t) lp64",
            "int __llseek:_llseek(int, unsigned long, unsigned long, off64_t*, int) arm,x86",
            "int renameat(int, const char*, int, const char*) all",
        ]),
        "WHITELIST.TXT": "int rename(const char*, const char*) x86_64\nint setresuid(uid_t, uid_t, uid_t) lp64",
        "BLACKLIST.TXT": "int setresuid(uid_t, uid_t, uid_t) lp64",
    }

    def test_reads_bionic_s_syscall_lists(self):
        self.assertEqual({"openat", "newfstatat", "pread64", "renameat", "rename"},
                         android_policy.allowed(self.TEXTS, self.LISTS, "x86_64"))
        self.assertEqual({"openat", "newfstatat", "pread64", "renameat"},
                         android_policy.allowed(self.TEXTS, self.LISTS, "arm64"))

    def test_the_pinned_lists_are_android_10_s(self):
        lists = android_policy.LISTS[29]
        self.assertIn("290c0cb5044b643e5d6cbcb1a5b275541ca3a89e", lists["url"])
        self.assertEqual({"SYSCALLS.TXT", "SECCOMP_WHITELIST_COMMON.TXT", "SECCOMP_WHITELIST_APP.TXT"}, set(lists["allow"]))
        self.assertTrue(all(len(sha) == 64 for sha in {**lists["allow"], **lists["block"]}.values()))

    def test_numbers_come_from_this_machine_s_kernel_headers(self):
        if shutil.which(os.environ.get("CC", "cc")) is None:
            self.skipTest("no C compiler")
        found = android_policy.numbers({"openat", "no_such_syscall"})
        self.assertEqual(1, len(found))
        self.assertEqual({"x86_64": 257, "aarch64": 56}.get(os.uname().machine, found[0]), found[0])


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
        text = gpl_notice.notice(release, "5.0.0")
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

    def test_every_patch_to_proot_is_here_and_says_what_it_fixes(self):
        for release in gpl_notice.releases():
            for patch in release.get("patches", []):
                text = (TOOLS / "proot" / patch).read_text(encoding="utf-8")
                head = text.split("--- a/", 1)[0].strip()
                self.assertTrue(head, f"{patch} starts with no word on what it fixes")
                self.assertIn("+++ b/src/", text, f"{patch} is not a patch -p1 of PRoot's source")
        built = [r for r in gpl_notice.releases() if r.get("build")][0]
        self.assertEqual(["patches/x86_64-seccomp-sysnum.patch", "patches/link2symlink-count-on-success.patch",
                          "patches/arm-seccomp-sysarg1.patch"],
                         built.get("patches", []))

    def test_the_notice_lists_the_patches(self):
        release = dict(self.RELEASES[0], patches=["patches/fix.patch"])
        self.assertIn("  patches/fix.patch", gpl_notice.notice(release, "5.0.0"))

    def test_a_gpl_part_without_a_pinned_archive_blocks_the_release(self):
        with self.assertRaises(gpl_notice.NoticeError) as caught:
            gpl_notice.check_pinned(self.RELEASES[1])
        self.assertIn("proot (GPL-2.0)", str(caught.exception))
        self.assertIn("talloc (LGPL-3.0-or-later)", str(caught.exception))
        self.assertNotIn("libandroid_shmem (", str(caught.exception))
        gpl_notice.check_pinned(self.RELEASES[0])

    def test_an_unpinned_recipe_blocks_the_release(self):
        release = {**self.RELEASES[0], "talloc": {**self.RELEASES[0]["talloc"], "recipe": {"url": "https://x/build.sh"}}}
        with self.assertRaises(gpl_notice.NoticeError):
            gpl_notice.check_pinned(release)

    def test_the_shipped_release_pins_every_gpl_source(self):
        libproot = (TOOLS.parent / "app/src/main/jniLibs/arm64-v8a/libproot.so").read_bytes()
        gpl_notice.check_pinned(gpl_notice.shipped_release(libproot, gpl_notice.releases()))

    def test_the_shipped_libraries_are_the_recorded_binaries(self):
        libraries = {p.name: p.read_bytes() for p in (TOOLS.parent / "app/src/main/jniLibs/arm64-v8a").glob("*.so")}
        release = gpl_notice.shipped_release(libraries["libproot.so"], gpl_notice.releases())
        self.assertEqual(set(libraries), set(release["binaries"]))
        gpl_notice.check_binaries(libraries, release)

    def test_a_changed_or_extra_library_blocks_the_release(self):
        release = {**self.RELEASES[1], "binaries": {"libproot.so": hashlib.sha256(b"proot").hexdigest()}}
        gpl_notice.check_binaries({"libproot.so": b"proot"}, release)
        for libraries in ({"libproot.so": b"rebuilt"}, {}, {"libproot.so": b"proot", "libother.so": b"x"}):
            with self.subTest(libraries=sorted(libraries)), self.assertRaises(gpl_notice.NoticeError):
                gpl_notice.check_binaries(libraries, release)

    def test_sources_and_recipes_are_all_fetched(self):
        release = {**self.RELEASES[0], "proot": {**self.RELEASES[0]["proot"], "recipe": {"url": "https://x/build.sh", "sha256": "f" * 64}}}
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(gpl_notice, "download_verified") as download:
            saved = gpl_notice.fetch_sources(release, Path(tmp))
        self.assertEqual(["proot-5.1.107.94-source.zip", "proot-5.1.107.94-termux-build.sh",
                          "talloc-2.4.3-source.tar.gz", "libandroid-shmem-0.7-source.tar.gz"], [p.name for p in saved])
        self.assertEqual(("https://x/build.sh", "f" * 64), download.call_args_list[1].args[:2])

    def test_an_unreachable_host_is_retried_then_its_mirror_is_used(self):
        good = b"talloc source"
        pinned = hashlib.sha256(good).hexdigest()

        class Body(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        def fake_urlopen(request, timeout):
            if "www.samba.org" in request.full_url:
                raise gpl_notice.urllib.error.URLError(ConnectionRefusedError(111, "Connection refused"))
            return Body(good)

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(gpl_notice.urllib.request, "urlopen", side_effect=fake_urlopen) as urlopen, \
                mock.patch.object(gpl_notice.time, "sleep") as sleep:
            out = Path(tmp) / "talloc.tar.gz"
            gpl_notice.download_verified("https://www.samba.org/ftp/t.tar.gz", pinned, out,
                                         mirrors=("https://download.samba.org/pub/t.tar.gz",))
            self.assertEqual(good, out.read_bytes())
        self.assertEqual(4, urlopen.call_count)  # three tries on the dead host, then the mirror
        self.assertEqual(2, sleep.call_count)

    def test_a_wrong_checksum_moves_on_and_no_match_anywhere_is_refused(self):
        class Body(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(gpl_notice.urllib.request, "urlopen", side_effect=lambda r, timeout: Body(b"changed")) as urlopen, \
                mock.patch.object(gpl_notice.time, "sleep"):
            out = Path(tmp) / "x"
            with self.assertRaises(gpl_notice.NoticeError) as refused:
                gpl_notice.download_verified("https://a/x", "0" * 64, out, mirrors=("https://b/x",))
            self.assertFalse(out.exists())
        self.assertEqual(2, urlopen.call_count)  # one try each: a checksum is not a network error
        self.assertIn("not the pinned", str(refused.exception))

    def test_the_release_notice_for_the_shipped_apk(self):
        jni = TOOLS.parent / "app/src/main/jniLibs/arm64-v8a"
        with tempfile.TemporaryDirectory() as tmp:
            apk, out = Path(tmp) / "app.apk", Path(tmp) / "GPL-SOURCE.txt"
            with zipfile.ZipFile(apk, "w") as archive:
                for lib in jni.glob("*.so"):
                    archive.write(lib, f"lib/arm64-v8a/{lib.name}")
            argv = ["gpl_notice.py", str(apk), "--app-version", "5.0.0", "--out", str(out)]
            with mock.patch.object(sys, "argv", argv):
                self.assertEqual(0, gpl_notice.main())
            text = out.read_text(encoding="utf-8")
        self.assertIn("PRoot 5.1.107.92", text)
        self.assertIn("talloc-2.4.3.tar.gz", text)
        self.assertIn("f12d165dfd34062be3da5e838fbeec429f1b7b9ba5b95371fbd253fb4a9cafc2  libproot.so", text)
        self.assertIn("packages/libtalloc/build.sh", text)

    def test_the_apk_s_other_libraries_are_not_proot_s(self):
        jni = TOOLS.parent / "app/src/main/jniLibs/arm64-v8a"
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for lib in jni.glob("*.so"):
                    archive.write(lib, f"lib/arm64-v8a/{lib.name}")
                archive.writestr("lib/arm64-v8a/libandroidx.graphics.path.so", b"AndroidX")
            libraries = gpl_notice.native_libraries(apk)
        self.assertNotIn("libandroidx.graphics.path.so", libraries)
        gpl_notice.check_binaries(libraries, gpl_notice.shipped_release(libraries["libproot.so"], gpl_notice.releases()))

    def test_an_apk_with_a_rebuilt_proot_under_an_old_version_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libproot.so", b"rebuilt proot 5.1.107.92")
            argv = ["gpl_notice.py", str(apk), "--app-version", "5.0.0"]
            with mock.patch.object(sys, "argv", argv), contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(1, gpl_notice.main())
        self.assertIn("libproot.so is not the recorded build", err.getvalue())



class SecretsFile(unittest.TestCase):
    FACTS = secrets_file.KeyFacts("QUJD", "store-pass", "key-pass", "pocketide", "AA:BB", "CC:DD")

    def test_lists_every_value_and_step(self):
        text = secrets_file.render(self.FACTS, "owner/repo", "release.jks")
        for expected in ("Name:\nPOCKETIDE_KEYSTORE_B64\nSecret:\nQUJD\n", "Name:\nPOCKETIDE_STORE_PASS\nSecret:\nstore-pass\n",
                         "Name:\nPOCKETIDE_KEY_PASS\nSecret:\nkey-pass\n", "Name:\nPOCKETIDE_KEY_ALIAS\nSecret:\npocketide\n",
                         "App: PocketIDE", "GitHub repository: owner/repo", "Package: com.pocketide", "SHA-1:   AA:BB",
                         "SHA-256: CC:DD", "https://github.com/owner/repo/settings/secrets/actions/new", "Run workflow"):
            self.assertIn(expected, text)
        # Each secret stands apart from the next, and no version number dates the file.
        self.assertIn("Secret:\nQUJD\n\n\n2) The key store password", text)
        self.assertNotIn("PocketIDE 4", text)
        # Version 5 signs in to nothing: no GitHub App, and no variables to fill in.
        for gone in ("GitHub App", "VARIABLES", "CLIENT_ID", "Device Flow"):
            self.assertNotIn(gone, text)
        # Every link is a full address, so it opens with a tap in any text viewer.
        for line in text.splitlines():
            if "github.com/" in line:
                self.assertTrue(line.startswith("https://"), line)

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
            lines = text.splitlines()
            at = lines.index("POCKETIDE_STORE_PASS")
            password = lines[at + 2]
            listing = subprocess.run(["keytool", "-list", "-v", "-keystore", str(keystore), "-storepass", password],
                                     capture_output=True, text=True, check=True).stdout
            sha1 = next(l.split("SHA1:")[1].strip() for l in listing.splitlines() if "SHA1:" in l)
            self.assertIn(f"SHA-1:   {sha1}", text)


if __name__ == "__main__":
    unittest.main()
