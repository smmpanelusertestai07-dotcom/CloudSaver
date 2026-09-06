#!/usr/bin/env python3
"""Exercise real child output and log failures without a publisher app or login."""
import importlib.util
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
import unittest
from unittest import mock

ASSETS = Path(__file__).resolve().parents[1] / 'app/assets'
spec = importlib.util.spec_from_file_location('applog', ASSETS / 'pocketdesk-appprocess.py')
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class AppLogging(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.log = self.root / 'chatgpt.log'
        self.failure = self.root / 'chatgpt.log.failure'

    def tearDown(self):
        self.tmp.cleanup()

    def run_child(self, code, log=None):
        return helper.supervise(log or self.log, 'Test app', [sys.executable, '-c', code])

    def test_verbose_child_rotates_and_finishes(self):
        self.assertEqual(self.run_child("import sys; sys.stdout.write(('x'*2000+'\\n')*2500)"), 0)
        self.assertIn('exit 0', self.log.read_text())
        older = self.root / 'chatgpt.log.older'
        self.assertTrue(older.exists())
        for file in (self.log, older):
            self.assertLessEqual(file.stat().st_size, helper.AppLog.LIMIT)
            self.assertEqual(stat.S_IMODE(file.stat().st_mode), 0o600)
        self.assertFalse(self.failure.exists())

    def test_log_storage_failure_does_not_stop_child(self):
        self.log.write_text('a file, so it cannot be the parent log directory')
        sentinel = self.root / 'finished'
        code = ("from pathlib import Path; import sys; "
                "sys.stdout.write(('still-running\\n')*300000); sys.stdout.flush(); "
                f"Path({str(sentinel)!r}).write_text('done')")
        self.assertEqual(self.run_child(code, self.log / 'unwritable.log'), 0)
        self.assertEqual(sentinel.read_text(), 'done')

    def test_failure_survives_success_and_normal_termination(self):
        self.assertEqual(self.run_child("import sys; print('auth: chatgpt://auth?code=secret&state=private'); sys.exit(7)"), 7)
        saved = self.failure.read_bytes()
        self.assertIn(b'exit 7', saved)
        self.assertNotIn(b'code=secret', saved)
        self.assertNotIn(b'state=private', saved)
        self.assertEqual(stat.S_IMODE(self.failure.stat().st_mode), 0o600)
        self.assertEqual(self.run_child("print('reopened successfully')"), 0)
        self.assertEqual(self.failure.read_bytes(), saved)
        self.assertEqual(self.run_child("import os, signal; os.kill(os.getpid(), signal.SIGTERM)"), 143)
        self.assertEqual(self.failure.read_bytes(), saved)

    def test_non_newline_renderer_failure_survives_clean_main_exit(self):
        self.assertEqual(self.run_child("import sys; sys.stdout.write('render-process-gone reason=oom')"), 0)
        saved = self.failure.read_text()
        self.assertIn('possible child/runtime failure', saved)
        self.assertIn('render-process-gone reason=oom', saved)
        self.assertIn('PD_APP_RESOURCES:', saved)

    def test_unicode_failure_is_bounded_in_bytes(self):
        log = helper.AppLog(self.log)
        try:
            for _ in range(100):
                log.append('\U0001f600' * 300)
            log.failure('FATAL: ' + '\U0001f600' * 20000, 'PD_APP_RESOURCES: sample')
        finally:
            log.close()
        saved = self.failure.read_bytes()
        self.assertLessEqual(len(saved), 32 * 1024)
        self.assertTrue(saved.decode('utf-8').endswith('PD_APP_RESOURCES: sample\n'))

    def test_small_lines_are_batched_and_close_flushes(self):
        log = helper.AppLog(self.log)
        for _ in range(100):
            log.append('sample')
        self.assertFalse(self.log.exists())
        log.close()
        self.assertEqual(self.log.read_text().count('sample\n'), 100)

    def test_retained_tokens_and_headers_are_redacted(self):
        output = ('FATAL: Authorization: Bearer header-secret\n'
                  'authorization=Bearer field-secret\n'
                  '{"access_token":"json-secret", "state":"connected"}\n')
        self.assertEqual(self.run_child(f'import sys; print({output!r}); sys.exit(8)'), 8)
        for text in (self.log.read_text(), self.failure.read_text()):
            for secret in ('header-secret', 'field-secret', 'json-secret'):
                self.assertNotIn(secret, text)
            self.assertIn('"state":"connected"', text)

    def test_native_signal_is_distinguished_from_numeric_exit(self):
        code = ('import os, signal, resource; '
                'resource.setrlimit(resource.RLIMIT_CORE, (0, 0)); '
                'os.kill(os.getpid(), signal.SIGSEGV)')
        self.assertEqual(self.run_child(code), 139)
        actual = self.failure.read_text()
        self.assertIn('signal=SIGSEGV (11)', actual)
        self.assertIn('failing component is not identified', actual)
        self.assertEqual(self.run_child('import sys; sys.exit(139)'), 139)
        numeric = self.failure.read_text()
        self.assertIn('returnCode=139; no signal reported', numeric)
        self.assertNotIn('signal=SIGSEGV', numeric)

    def test_sigkill_does_not_claim_out_of_memory(self):
        self.assertEqual(self.run_child('import os, signal; os.kill(os.getpid(), signal.SIGKILL)'), 137)
        saved = self.failure.read_text()
        self.assertIn('signal=SIGKILL (9)', saved)
        self.assertIn('does not identify who sent', saved)
        self.assertNotIn('out of memory', saved)

    def test_silent_app_sleeps_until_real_deadlines_and_exit(self):
        selector_factory = helper.selectors.DefaultSelector
        waits = []
        def observed_selector():
            selector = selector_factory()
            real_select = selector.select
            def select(timeout=None):
                waits.append(timeout)
                return real_select(timeout)
            selector.select = select
            return selector
        with mock.patch.object(helper.selectors, 'DefaultSelector', side_effect=observed_selector):
            self.assertEqual(self.run_child('import time; time.sleep(2.3)'), 0)
        self.assertLessEqual(len(waits), 5)  # old 250ms polling woke at least ten times
        self.assertTrue(any(wait is not None and wait > 10 for wait in waits))

    def test_child_exit_with_inherited_pipe_still_has_bounded_drain(self):
        code = ('import subprocess,sys; subprocess.Popen([sys.executable,"-c",'
                '"import time; time.sleep(2)"])')
        before = time.monotonic()
        self.assertEqual(self.run_child(code), 0)
        self.assertLess(time.monotonic() - before, 1.8)
        self.assertIn('inherited pipe remained open', self.log.read_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
