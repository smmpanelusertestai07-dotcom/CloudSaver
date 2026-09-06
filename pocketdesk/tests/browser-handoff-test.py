#!/usr/bin/env python3
"""Execute browser dispatch with fake browser binaries; never send real authentication data."""
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / 'app/assets'


class BrowserHandoff(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.processes = []
        self.env = dict(os.environ, HOME=str(self.root), PATH=str(self.bin) + ':' + os.environ['PATH'], PD_BROWSER_TEST=str(self.root))
        self.script('google-chrome', '#!/bin/sh\nexit 0\n')
        self.script('pocketdesk-open', f'''#!{sys.executable}
import json, os, sys, time
from pathlib import Path
root = Path(os.environ['PD_BROWSER_TEST'])
(root / 'args').write_text(json.dumps(sys.argv[1:]))
if os.environ.get('PD_BROWSER_EXIT'): sys.exit(int(os.environ['PD_BROWSER_EXIT']))
time.sleep(30)
''')

    def script(self, name, text):
        file = self.bin / name
        file.write_text(text)
        file.chmod(0o755)
        return file

    def tearDown(self):
        for process in self.processes:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
        self.tmp.cleanup()

    def run_script(self, script, *args, **env):
        started = time.monotonic()
        process = subprocess.Popen(['bash', str(script), *args], env=dict(self.env, **env),
                                   start_new_session=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.processes.append(process)
        output, errors = process.communicate(timeout=5)
        return process.returncode, output + errors, time.monotonic() - started

    def test_oauth_url_is_one_argument_and_browser_launch_does_not_block(self):
        url = 'https://auth.openai.com/authorize?state=a%2Fb&redirect_uri=chatgpt%3A%2F%2Fauth&x=$(touch SHOULD_NOT_EXIST)'
        status, output, elapsed = self.run_script(ASSETS / 'pocketdesk-browser.sh', url)
        self.assertEqual(status, 0)
        self.assertLess(elapsed, 3)
        self.assertEqual(json.loads((self.root / 'args').read_text()), ['--label', 'Browser', 'google-chrome', url])
        self.assertNotIn(url.encode(), output)
        self.assertNotIn(url, (self.root / '.pocketdesk/logs/browser-handoff.log').read_text())

    def test_immediate_launcher_failure_is_reported(self):
        status, _, _ = self.run_script(ASSETS / 'pocketdesk-browser.sh', 'https://auth.openai.com/', PD_BROWSER_EXIT='75')
        self.assertEqual(status, 75)

    def test_non_web_arguments_cannot_become_browser_flags(self):
        for uri in ('--user-data-dir=/tmp/other-profile', 'file:///private', 'chatgpt://auth?code=private'):
            status, _, _ = self.run_script(ASSETS / 'pocketdesk-browser.sh', uri)
            self.assertEqual(status, 2)
        self.assertFalse((self.root / 'args').exists())

    def test_xdg_routes_web_only_and_preserves_original_protocol_handler(self):
        browser = self.script('browser-recorder', '#!/bin/sh\nprintf "browser:%s" "$1"\n')
        original = self.script('xdg-recorder', '#!/bin/sh\nprintf "original:%s" "$1"\n')
        text = (ASSETS / 'pocketdesk-xdg-open.sh').read_text()
        script = self.script('xdg-under-test', text.replace('/usr/local/bin/pocketdesk-browser', str(browser))
                             .replace('/usr/bin/xdg-open', str(original)))
        for uri, route in [('https://auth.openai.com/?a=1&b=2', 'browser:'),
                           ('chatgpt://auth/callback?code=a%2Fb&state=x', 'original:'),
                           ('/home/coder/a file.txt', 'original:')]:
            status, output, _ = self.run_script(script, uri)
            self.assertEqual(status, 0)
            self.assertEqual(output.decode(), route + uri)


if __name__ == '__main__':
    unittest.main(verbosity=2)
