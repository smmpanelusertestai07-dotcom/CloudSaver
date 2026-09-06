#!/usr/bin/env python3
"""Real Linux launcher processes, native test app, X/notification doubles; no vendor login claim."""
import importlib.util
import os
import shutil
import signal
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / 'app/assets'
spec = importlib.util.spec_from_file_location('appprocess', ASSETS / 'pocketdesk-appprocess.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def until(check, seconds=12):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return True
        time.sleep(.05)
    return False


class LinuxStartup(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.app = self.root / 'app'
        self.bin = self.root / 'bin'
        self.app.mkdir()
        self.bin.mkdir()
        (self.root / 'home').mkdir()
        (self.app / 'chrome_100_percent.pak').touch()
        shutil.copy2(shutil.which('sleep'), self.app / 'native-app')
        self.wrapper = self.app / 'chatgpt'
        self.wrapper.write_text('''#!/bin/bash
for arg in "$@"; do
  case "$arg" in chatgpt://*)
    printf '%s\\n' "$arg" > "$PD_TEST_ROOT/callback"
    printf '%s\\n' "$@" > "$PD_TEST_ROOT/callback-arguments"
    printf '%s' "${CODEX_ELECTRON_USER_DATA_PATH:-}" > "$PD_TEST_ROOT/callback-profile"
    echo "callback: $arg"
    exit "${PD_TEST_CALLBACK_EXIT:-0}" ;;
  esac
done
echo start >> "$PD_TEST_ROOT/starts"
echo $$ > "$PD_TEST_ROOT/pid"
printf '%s\\n' "$@" > "$PD_TEST_ROOT/arguments"
printf '%s' "${PD_LAUNCH_ENV:-}" > "$PD_TEST_ROOT/launch-env"
printf '%s' "${CODEX_ELECTRON_USER_DATA_PATH:-}" > "$PD_TEST_ROOT/launch-profile"
exec "$(dirname "$0")/native-app" 300
''')
        self.wrapper.chmod(0o755)
        wm = self.bin / 'wmctrl'
        wm.write_text('''#!/bin/bash
case "$1" in
  -lp) if [ -f "$PD_TEST_ROOT/visible" ] && [ -f "$PD_TEST_ROOT/pid" ]; then
         printf '0x02000003 0 %s phone Sign in – OpenAI\\n' "$(cat "$PD_TEST_ROOT/pid")"
       fi ;;
  -ia) echo raised >> "$PD_TEST_ROOT/raised" ;;
  -ic) echo closed >> "$PD_TEST_ROOT/closed" ;;
  -lx) echo '0x01000001 0 Chrome.Chrome phone Sign in' ;;
esac
''')
        wm.chmod(0o755)
        for name in ('xdotool', 'notify-send', 'zenity', 'xsetroot'):
            f = self.bin / name
            f.write_text('#!/bin/sh\nexit 0\n')
            f.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.root / 'home'),
                        PATH=str(self.bin) + ':' + os.environ['PATH'],
                        POCKETDESK_FREE_MB='2000', PD_TEST_ROOT=str(self.root))
        self.process = None
        self.log = self.root / 'home/.pocketdesk/logs/chatgpt.log'

    def tearDown(self):
        pidfile = self.root / 'pid'
        if pidfile.exists():
            try:
                os.kill(int(pidfile.read_text()), signal.SIGTERM)
            except ProcessLookupError:
                pass
        if self.process:
            try:
                self.process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.tmp.cleanup()

    def command(self, *args):
        return ['bash', str(ASSETS / 'pocketdesk-open.sh'), str(self.wrapper), *args]

    def start(self):
        self.process = subprocess.Popen(self.command(), env=self.env,
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.assertTrue(until(lambda: (self.root / 'pid').exists()))
        self.primary = int((self.root / 'pid').read_text())

    def invoke(self, *args, **extra):
        return subprocess.run(self.command(*args), env=dict(self.env, **extra), timeout=20,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode

    def test_repeated_tap_and_hidden_callback_preserve_primary_and_browser(self):
        self.start()
        self.assertEqual(self.invoke(), 0)
        self.assertEqual((self.root / 'starts').read_text().count('start'), 1)
        secret = 'chatgpt://auth/callback?code=test-secret&state=private'
        self.assertEqual(self.invoke(secret, POCKETDESK_FREE_MB='200'), 0)
        self.assertEqual((self.root / 'callback').read_text().strip(), secret)
        self.assertTrue(until(lambda: 'argument handoff finished' in self.log.read_text()))
        self.assertIn('free memory at launch: 2000 MB', self.log.read_text())
        self.assertNotIn('test-secret', self.log.read_text())
        self.assertNotIn('state=private', self.log.read_text())
        os.kill(self.primary, 0)
        self.assertFalse((self.root / 'closed').exists())
        self.assertEqual((self.root / 'starts').read_text().count('start'), 1)

    def test_dynamic_window_title_and_callback_failure_are_non_destructive(self):
        self.start()
        (self.root / 'visible').touch()
        self.assertTrue(until(lambda: 'window appeared' in self.log.read_text()))
        self.assertEqual(self.invoke(), 0)
        self.assertTrue((self.root / 'raised').exists())
        self.assertEqual(self.invoke('chatgpt://auth/callback?code=bad', PD_TEST_CALLBACK_EXIT='7'), 7)
        os.kill(self.primary, 0)
        self.assertFalse((self.root / 'closed').exists())
        self.assertIn('exit 7 (this does not confirm sign-in)', self.log.read_text())

    def test_chatgpt_graphics_isolation_survives_cold_launch_and_callback(self):
        # Exercise the actual wrapper dispatch, including the second-instance path.
        # The executable is a test double; this checks launch policy, not GPU recovery.
        profile = str(self.root / 'home/existing Codex profile')
        self.env['CODEX_ELECTRON_USER_DATA_PATH'] = profile
        self.start()
        initial = (self.root / 'arguments').read_text().splitlines()
        callback = 'chatgpt://auth/callback?code=graphics-test-only'
        self.assertEqual(self.invoke(callback), 0)
        forwarded = (self.root / 'callback-arguments').read_text().splitlines()
        self.assertEqual(forwarded, initial + [callback])
        for flag in ('--no-sandbox', '--no-zygote', '--use-gl=angle',
                     '--use-angle=swiftshader', '--ignore-gpu-blocklist'):
            self.assertIn(flag, initial)
        for flag in ('--in-process-gpu', '--disable-gpu', '--disable-webgl',
                     '--disable-software-rasterizer', '--disable-gpu-watchdog',
                     '--enable-unsafe-swiftshader'):
            self.assertNotIn(flag, initial)
        self.assertFalse(any('max-old-space-size' in flag for flag in initial))
        self.assertEqual((self.root / 'launch-profile').read_text(), profile)
        self.assertEqual((self.root / 'callback-profile').read_text(), profile)
        self.assertEqual((self.root / 'starts').read_text().count('start'), 1)
        os.kill(self.primary, 0)

    def test_sigkill_during_start_does_not_restart(self):
        self.start()
        os.kill(self.primary, signal.SIGKILL)
        self.assertEqual(self.process.wait(timeout=15), 137)
        self.assertEqual((self.root / 'starts').read_text().count('start'), 1)
        self.assertNotIn('retrying', self.log.read_text())

    def test_low_memory_defers_only_new_heavy_launch(self):
        self.assertEqual(self.invoke(POCKETDESK_FREE_MB='500'), 75)
        self.assertFalse((self.root / 'starts').exists())
        self.assertIn('500 MB available', self.log.read_text())
        self.assertFalse((self.root / 'closed').exists())

    def test_low_memory_browser_launch_is_deferred_but_running_browser_receives_url(self):
        browser = self.app / 'google-chrome'
        self.wrapper.rename(browser)
        self.wrapper = browser
        browser.write_text(browser.read_text().replace('chatgpt://*', 'https://*'))
        self.log = self.root / 'home/.pocketdesk/logs/google-chrome.log'
        self.assertEqual(self.invoke('https://auth.openai.com/', POCKETDESK_FREE_MB='200'), 75)
        self.assertFalse((self.root / 'starts').exists())
        self.start()
        initial = (self.root / 'arguments').read_text().splitlines()
        self.assertIn('--in-process-gpu', initial)
        self.assertIn('--disable-gpu', initial)
        self.assertNotIn('--use-angle=swiftshader', initial)
        url = 'https://auth.openai.com/authorize?state=private&code_challenge=test-only'
        self.assertEqual(self.invoke(url, POCKETDESK_FREE_MB='200'), 0)
        self.assertEqual((self.root / 'callback').read_text().strip(), url)
        self.assertEqual((self.root / 'callback-arguments').read_text().splitlines(),
                         initial + [url])
        os.kill(self.primary, 0)
        self.assertEqual((self.root / 'starts').read_text().count('start'), 1)

    def test_argv_path_is_not_executable_ownership(self):
        decoy = subprocess.Popen(['bash', '-c', 'read -t 20; :', str(self.wrapper)],
                                 stdin=subprocess.PIPE, stdout=subprocess.DEVNULL)
        try:
            found = helper.snapshot(str(self.wrapper), [str(self.app)])
            translated = [helper.wine.signal_pid(p) for p in found]
            self.assertNotIn(decoy.pid, translated)
        finally:
            decoy.terminate()
            decoy.wait()
            decoy.stdin.close()

    def test_env_prefixed_desktop_command_receives_compatibility_flags(self):
        command = ['bash', str(ASSETS / 'pocketdesk-open.sh'), 'env',
                   'PD_LAUNCH_ENV=literal $(do-not-execute)', str(self.wrapper)]
        self.process = subprocess.Popen(command, env=self.env,
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.assertTrue(until(lambda: (self.root / 'pid').exists()))
        self.assertIn('--no-sandbox', (self.root / 'arguments').read_text().splitlines())
        self.assertEqual((self.root / 'launch-env').read_text(), 'literal $(do-not-execute)')

    def test_catalogue_wrapper_without_adjacent_markers_still_gets_flags(self):
        (self.app / 'chrome_100_percent.pak').unlink()
        self.start()
        self.assertIn('--no-sandbox', (self.root / 'arguments').read_text().splitlines())

    def test_shell_leaves_after_window_but_supervisor_records_later_crash(self):
        (self.root / 'visible').touch()
        self.start()
        self.assertEqual(self.process.wait(timeout=15), 0)
        os.kill(self.primary, 0)
        os.kill(self.primary, signal.SIGKILL)
        self.assertTrue(until(lambda: 'process ended' in self.log.read_text()
                             and 'exit 137' in self.log.read_text()))
        self.assertEqual((self.root / 'starts').read_text().count('start'), 1)

    def test_window_poll_keeps_ownership_without_text_filter_processes(self):
        # A real unrelated process is represented by a plausible window title.
        # Repeated polls must not mistake that title for ownership, and must not
        # fork awk/sort/tr merely to parse the handful of window-owner PIDs.
        wm = self.bin / 'wmctrl'
        wm.write_text('''#!/bin/bash
case "$1" in
  -lp)
    echo poll >> "$PD_TEST_ROOT/polls"
    if [ -f "$PD_TEST_ROOT/rows" ]; then
      while IFS= read -r row; do printf '%s\\n' "$row"; done < "$PD_TEST_ROOT/rows"
    fi ;;
esac
''')
        for name in ('awk', 'sort', 'tr'):
            real = shutil.which(name)
            fake = self.bin / name
            fake.write_text('#!/bin/bash\n'
                            'if [ -f "$PD_TEST_ROOT/measure-polls" ]; then '
                            f'echo {name} >> "$PD_TEST_ROOT/filter-children"; fi\n'
                            f'exec {shlex.quote(real)} "$@"\n')
            fake.chmod(0o755)
        python = self.bin / 'python3'
        python.write_text('#!/bin/bash\n'
                          'if [ "${2:-}" = candidates ]; then\n'
                          '  shifted=0\n'
                          '  for arg in "$@"; do\n'
                          '    if [ "$shifted" = 1 ]; then printf "%s " "$arg"; fi\n'
                          '    [ "$arg" != -- ] || shifted=1\n'
                          '  done > "$PD_TEST_ROOT/candidate-pids"\n'
                          'fi\n'
                          f'exec {shlex.quote(sys.executable)} "$@"\n')
        python.chmod(0o755)
        self.start()
        (self.root / 'rows').write_text(f'0x900 0 {os.getpid()} phone ChatGPT\n'
                                      f'0x901 0 {os.getpid()} phone ChatGPT duplicate\n'
                                      '0x902 0 invalid phone Bad PID\n'
                                      '0x903 0 0 phone No owner\n')
        (self.root / 'measure-polls').touch()
        old_polls = (self.root / 'polls').read_text().count('poll')
        self.assertTrue(until(lambda: (self.root / 'polls').read_text().count('poll') >= old_polls + 3))
        self.assertIsNone(self.process.poll())
        self.assertNotIn('window appeared', self.log.read_text())
        self.assertFalse((self.root / 'filter-children').exists())
        self.assertEqual((self.root / 'candidate-pids').read_text().split(), [str(os.getpid())])
        (self.root / 'rows').write_text(f'0x999 0 {self.primary} phone Completely dynamic title\n')
        self.assertEqual(self.process.wait(timeout=15), 0)
        self.assertIn('window appeared', self.log.read_text())

    def test_chrome_payload_avoids_wrappers_permanent_pipe_children(self):
        browser_dir = self.root / 'opt/google/chrome'
        browser_dir.mkdir(parents=True)
        browser = browser_dir / 'google-chrome'
        self.wrapper.rename(browser)
        self.wrapper = browser
        # This represents Chrome's wrapper which would otherwise start two cat
        # processes. A sibling native ELF is the publisher's browser payload.
        browser.write_text('#!/bin/sh\necho used > "$PD_TEST_ROOT/wrapper-used"\nexit 77\n')
        shutil.copy2(shutil.which('true'), browser_dir / 'chrome')
        self.assertEqual(self.invoke(), 0)
        self.assertFalse((self.root / 'wrapper-used').exists())

    def test_supervisor_redacts_auth_output_and_keeps_exit_code(self):
        log = self.root / 'supervisor.log'
        script = self.root / 'output.py'
        script.write_text('print("callback https://auth.openai.com/callback?code=do-not-save&state=private")\nraise SystemExit(7)\n')
        result = subprocess.run(['python3', str(ASSETS / 'pocketdesk-appprocess.py'),
                                 'supervise', str(log), 'Test app', '--', 'python3', str(script)],
                                env=self.env, timeout=8, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(result.returncode, 7)
        self.assertIn('exit 7', log.read_text())
        self.assertNotIn('do-not-save', log.read_text())
        self.assertNotIn('state=private', log.read_text())

    def test_parent_exit_is_reported_when_descendant_keeps_stdout_open(self):
        log = self.root / 'inherited-pipe.log'
        script = self.root / 'inherited-pipe.py'
        descendant_file = self.root / 'descendant.pid'
        script.write_text('''import os, time
from pathlib import Path
if os.fork() == 0:
    Path(os.environ['PD_TEST_ROOT'], 'descendant.pid').write_text(str(os.getpid()))
    time.sleep(30)
    os._exit(0)
data = 'π callback https://auth.openai.com/callback?code=split-secret&state=private'.encode()
for part in (data[:1], data[1:49], data[49:]):
    os.write(1, part)
    time.sleep(.04)
os._exit(7)
''')
        unrelated = subprocess.Popen(['sleep', '30'])
        started = time.monotonic()
        try:
            result = subprocess.run(['python3', str(ASSETS / 'pocketdesk-appprocess.py'),
                                     'supervise', str(log), 'Test app', '--', 'python3', str(script)],
                                    env=self.env, timeout=6, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(result.returncode, 7)
            self.assertLess(time.monotonic() - started, 5)
            text = log.read_text()
            self.assertIn('π callback https://auth.openai.com/callback?[redacted]', text)
            self.assertNotIn('split-secret', text)
            self.assertNotIn('state=private', text)
            self.assertIn('exit 7', text)
            self.assertIn('inherited pipe remained open', text)
            os.kill(int(descendant_file.read_text()), 0)
            self.assertIsNone(unrelated.poll())
        finally:
            if descendant_file.exists():
                try:
                    os.kill(int(descendant_file.read_text()), signal.SIGTERM)
                except ProcessLookupError:
                    pass
            unrelated.terminate()
            unrelated.wait(timeout=3)


if __name__ == '__main__':
    unittest.main(verbosity=2)
