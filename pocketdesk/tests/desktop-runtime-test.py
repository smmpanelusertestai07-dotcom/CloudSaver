#!/usr/bin/env python3
"""Exercise startup deadlines with a slow X probe and an unresponsive audio server."""
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


PROJECT = Path(__file__).resolve().parents[1]
DESKTOP = (PROJECT / 'app/assets/pocketdesk-desktop.sh').read_text()


def function(name):
    start = DESKTOP.index(name + '() {')
    end = DESKTOP.index('\n}', start) + 2
    return DESKTOP[start:end]


class DesktopRuntimeTest(unittest.TestCase):
    def test_slow_display_probe_uses_elapsed_deadline(self):
        # A real slow probe previously multiplied the supposed startup time limit. The
        # tested function is taken directly from the shipping shell asset.
        command = function('wait_for_display') + '''
sleep 20 &
VNC_PID=$!
trap 'kill "$VNC_PID" 2>/dev/null; wait "$VNC_PID" 2>/dev/null' EXIT
display_ready() { sleep 1.2; return 1; }
wait_for_display 1
'''
        started = time.monotonic()
        result = subprocess.run(['bash', '-c', command], timeout=5)
        elapsed = time.monotonic() - started
        self.assertEqual(result.returncode, 1)
        self.assertLess(elapsed, 3)

    def test_failed_display_process_does_not_consume_timeout(self):
        command = function('wait_for_display') + '''
VNC_PID=99999999
display_ready() { sleep 20; return 1; }
wait_for_display 90
'''
        started = time.monotonic()
        result = subprocess.run(['bash', '-c', command], timeout=3)
        self.assertEqual(result.returncode, 1)
        self.assertLess(time.monotonic() - started, 2)

    def test_display_start_precedes_menu_and_optional_cli_registration(self):
        # A live PRoot previously exhausted Android's startup deadline in these stages.
        display = DESKTOP.index('start_display -rfbunixpath')
        self.assertLess(display, DESKTOP.index('/usr/local/bin/pocketdesk-menu || true'))
        self.assertLess(display, DESKTOP.index('claude mcp add --scope user'))

    def test_unresponsive_audio_server_is_bounded(self):
        with tempfile.TemporaryDirectory(prefix='pd-audio-') as folder:
            home = Path(folder)
            bin_dir = home / 'bin'
            bin_dir.mkdir()
            (bin_dir / 'pulseaudio').write_text('#!/bin/sh\nexit 0\n')
            (bin_dir / 'pactl').write_text('#!/bin/sh\nexec sleep 20\n')
            for executable in bin_dir.iterdir():
                executable.chmod(0o755)
            env = dict(os.environ, HOME=folder,
                       PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
            started = time.monotonic()
            result = subprocess.run(['bash', '-c', function('start_audio') + '\nstart_audio'],
                                    env=env, timeout=9)
            self.assertEqual(result.returncode, 0)
            self.assertLess(time.monotonic() - started, 8)


if __name__ == '__main__':
    unittest.main()
