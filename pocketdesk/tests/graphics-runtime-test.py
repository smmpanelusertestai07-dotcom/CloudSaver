#!/usr/bin/env python3
"""EGL/Mesa dependency and bounded repair regression tests; no ARM64 GUI claim."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / "app/assets"
spec = importlib.util.spec_from_file_location("graphics", ASSETS / "pocketdesk-graphics.py")
graphics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graphics)


class GraphicsRuntime(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.vendor = self.root / "usr/share/glvnd/egl_vendor.d/50_mesa.json"
        self.vendor.parent.mkdir(parents=True)
        self.vendor.write_text(json.dumps({"ICD": {"library_path": "libEGL_mesa.so.0"}}))

    def tearDown(self):
        self.tmp.cleanup()

    def probe(self, missing=None):
        def load(name, mode):
            if missing and missing in name:
                raise OSError("cannot open shared object file: No such file or directory")
            return object()
        return graphics.check_runtime(self.root, load, "aarch64-linux-gnu")

    def test_dispatch_library_alone_is_insufficient(self):
        for missing in ("libEGL.so.1", "libEGL_mesa.so.0", "swrast_dri.so"):
            with self.subTest(missing=missing):
                self.assertTrue(self.probe(missing))
        self.assertEqual([], self.probe())

    def test_vendor_configuration_must_be_usable(self):
        for content in ("broken json", "{}", '{"ICD":{"library_path":null}}'):
            self.vendor.write_text(content)
            self.assertTrue(self.probe())

    def test_native_loader_failure_is_reported(self):
        # Use the actual platform loader on an absent software driver. It must not pass just
        # because the caller says that package is installed or the GL dispatch library exists.
        errors = graphics.check_runtime(self.root, multiarch="missing-test-architecture")
        self.assertTrue(any("software rendering driver" in error for error in errors))

    def layer_fixture(self):
        fakebin = self.root / "bin"
        fakebin.mkdir()
        # Synthetic, complete Hangover files are accepted only inside the existing test root.
        for directory, names in {
            "usr/lib/wine/aarch64-unix": ["ntdll.so", "win32u.so", "winex11.so", "ws2_32.so"],
            "usr/lib/wine/aarch64-windows": [
                "ntdll.dll", "kernel32.dll", "kernelbase.dll", "wineboot.exe", "cmd.exe",
                "reg.exe", "rpcss.exe", "winex11.drv", "winmm.dll", "ws2_32.dll",
                "rundll32.exe", "setupapi.dll", "ole32.dll", "oleaut32.dll", "rpcrt4.dll",
                "services.exe", "svchost.exe",
                "libarm64ecfex.dll", "libwow64fex.dll", "wowbox64.dll"],
            "usr/lib/wine/i386-windows": ["rundll32.exe", "setupapi.dll"],
            "usr/share/wine": ["wine.inf"],
        }.items():
            parent = self.root / directory
            parent.mkdir(parents=True)
            for name in names:
                (parent / name).write_bytes(b"fixture")
        for name in ("wine", "wineserver"):
            path = self.root / "usr/bin" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('#!/bin/sh\necho "wine-11-test (Hangover)"\n')
            path.chmod(0o755)
        scripts = {
            "dpkg-query": '''#!/bin/bash
case "$1" in
 -W) printf 'install ok installed' ;;
 -L) printf '/usr/bin/wine\\n/usr/bin/wineserver\\n' ;;
 *) exit 2 ;;
esac
''',
            "python3": '''#!/bin/bash
echo probe >> "$PD_TEST_ROOT/probes"
[ -z "${PD_TEST_PROBE_EXIT:-}" ] || exit "$PD_TEST_PROBE_EXIT"
[ ! -f "$PD_TEST_ROOT/still-broken" ] && [ -f "$PD_TEST_ROOT/repaired" ]
''',
            "apt-get": '''#!/bin/bash
printf '%s\\n' "$*" >> "$PD_TEST_ROOT/apt-calls"
touch "$PD_TEST_ROOT/repaired"
''',
            "curl": '''#!/bin/bash
touch "$PD_TEST_ROOT/unexpected-download"
exit 90
''',
            "chown": "#!/bin/sh\nexit 0\n",
        }
        for name, script in scripts.items():
            path = fakebin / name
            path.write_text(script)
            path.chmod(0o755)
        cache = self.root / "var/cache/pocketdesk/windows"
        cache.mkdir(parents=True)
        (cache / "bundle.part").write_bytes(b"resumable layer bytes")
        saved = self.root / "home/coder/Downloads/ChatGPT-arm64.msix"
        saved.parent.mkdir(parents=True)
        saved.write_bytes(b"existing publisher package")
        prefix = self.root / "home/coder/.pocketdesk/windows/old/.prefix/user.reg"
        prefix.parent.mkdir(parents=True)
        prefix.write_bytes(b"previous app sign-in")
        return dict(os.environ, PATH=str(fakebin) + ":" + os.environ["PATH"],
                    POCKETDESK_TEST_ROOT=str(self.root), PD_STATE=str(self.root / "state"),
                    PD_TEST_ROOT=str(self.root))


if __name__ == "__main__":
    unittest.main()
