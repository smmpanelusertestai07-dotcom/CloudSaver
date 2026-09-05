#!/usr/bin/env python3
"""Exercise the shipped single-process panel/download watcher with real inotify events."""
import os
from pathlib import Path
import select
import signal
import shlex
import subprocess
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest import mock

PROJECT = Path(__file__).resolve().parents[1]
SOURCE = (PROJECT / 'app/assets/pocketdesk-desktop.sh').read_text()
CODE = SOURCE.split("<<'DESKTOPWATCH'\n", 1)[1].split('\nDESKTOPWATCH\n', 1)[0]
WATCH = types.ModuleType('desktop_watch_under_test')
exec(compile(CODE, 'pocketdesk-desktop.sh:DESKTOPWATCH', 'exec'), WATCH.__dict__)


class DesktopWatchTest(unittest.TestCase):
    def setUp(self):
        environment = mock.patch.dict(os.environ, {
            'POCKETDESK_CHILDWATCH_HELPER': str(PROJECT / 'app/assets/pocketdesk-childwatch.py')})
        environment.start()
        self.addCleanup(environment.stop)

    def read_events(self, descriptor, directory):
        ready, _, _ = select.select([descriptor], [], [], 2)
        self.assertTrue(ready, 'real inotify should report the completed write or rename')
        return list(WATCH.completed_downloads(os.read(descriptor, 65536), directory))

    def test_only_completed_exe_is_offered_not_another_unfinished_file(self):
        with tempfile.TemporaryDirectory(prefix='pd-watch-') as folder:
            directory = Path(folder)
            descriptor = WATCH.watch_directory(directory)
            try:
                unfinished = (directory / 'still-downloading.exe').open('wb')
                try:
                    unfinished.write(b'not finished')
                    unfinished.flush()
                    complete = directory / 'पूर्ण $HOME; sample.exe'
                    complete.write_bytes(b'download complete')
                    (directory / 'readme.txt').write_text('unrelated')
                    self.assertEqual(self.read_events(descriptor, directory), [complete])
                finally:
                    unfinished.close()
                self.assertEqual(self.read_events(descriptor, directory),
                                 [directory / 'still-downloading.exe'])
            finally:
                os.close(descriptor)

    def test_browser_atomic_rename_to_exe_is_detected(self):
        with tempfile.TemporaryDirectory(prefix='pd-watch-') as folder:
            directory = Path(folder)
            descriptor = WATCH.watch_directory(directory)
            try:
                temporary = directory / 'setup.EXE.part'
                temporary.write_bytes(b'complete')
                self.assertEqual(self.read_events(descriptor, directory), [])
                final = directory / 'setup.EXE'
                temporary.rename(final)
                self.assertEqual(self.read_events(descriptor, directory), [final])
            finally:
                os.close(descriptor)

    def test_duplicate_events_open_the_installer_once_with_a_literal_filename(self):
        with tempfile.TemporaryDirectory(prefix='pd-offer-') as folder:
            path = Path(folder) / 'app;$(echo incorrect).deb'
            path.write_bytes(b'test fixture')
            children = []
            with mock.patch.object(WATCH, 'notify') as notify, \
                    mock.patch.object(WATCH.subprocess, 'Popen') as launch:
                WATCH.offer_download(path, children)
                WATCH.offer_download(path, children)
            self.assertEqual(launch.call_count, 1)
            self.assertEqual(launch.call_args.args[0],
                             ['/usr/local/bin/pocketdesk-install', str(path)])
            self.assertNotIn('shell', launch.call_args.kwargs)
            self.assertEqual(len(children), 1)
            self.assertEqual(notify.call_count, 1)

    def test_any_other_finished_file_is_placed_where_the_owner_chose(self):
        with tempfile.TemporaryDirectory(prefix='pd-offer-') as folder:
            path = Path(folder) / 'report.pdf'
            path.write_bytes(b'test fixture')
            with mock.patch.object(WATCH, 'notify'), \
                    mock.patch.object(WATCH.subprocess, 'Popen') as launch:
                WATCH.offer_download(path, [])
            self.assertEqual(launch.call_args.args[0],
                             ['/usr/local/bin/pocketdesk-save', str(path)])

    def test_a_half_finished_download_is_left_alone(self):
        # Every browser writes these while the transfer is still running. Acting on one both
        # acts on half a file and burns the once-only marker for the finished file.
        for name in ('big.iso.crdownload', 'big.iso.part', 'big.iso.tmp', '.hidden'):
            with tempfile.TemporaryDirectory(prefix='pd-offer-') as folder:
                path = Path(folder) / name
                path.write_bytes(b'partial')
                with mock.patch.object(WATCH.subprocess, 'Popen') as launch:
                    WATCH.offer_download(path, [])
                launch.assert_not_called()
                self.assertFalse(Path(str(path) + '.pocketdesk-seen').exists(), name)

    def test_a_windows_program_is_never_offered_an_installer(self):
        # The Windows layer is gone on purpose. A downloaded .exe is an ordinary file now: it is
        # placed like any other, and PocketLinux's installer explains why it cannot run.
        with tempfile.TemporaryDirectory(prefix='pd-offer-') as folder:
            path = Path(folder) / 'setup.exe'
            path.write_bytes(b'test fixture')
            with mock.patch.object(WATCH, 'notify'), \
                    mock.patch.object(WATCH.subprocess, 'Popen') as launch:
                WATCH.offer_download(path, [])
            self.assertEqual(launch.call_args.args[0][0], '/usr/local/bin/pocketdesk-save')

    def test_failed_panel_gets_one_fallback_without_deleting_rejected_config(self):
        with tempfile.TemporaryDirectory(prefix='pd-panel-') as folder:
            home = Path(folder)
            config = home / '.config/tint2/tint2rc'
            config.parent.mkdir(parents=True)
            config.write_text('invalid panel fixture')
            failed = mock.Mock()
            failed.poll.return_value = 1
            with mock.patch.object(WATCH, 'start_panel', side_effect=[failed, None]) as panel, \
                    mock.patch.object(WATCH, 'watch_directory', side_effect=OSError('unavailable')):
                WATCH.main(home, home / 'Downloads', panel_delay=0)
            self.assertEqual(panel.call_count, 2)
            self.assertFalse(config.exists())
            self.assertEqual(config.with_name('tint2rc.rejected').read_text(), 'invalid panel fixture')

    def test_healthy_panel_keeps_its_settings_and_is_not_restarted(self):
        with tempfile.TemporaryDirectory(prefix='pd-panel-') as folder:
            home = Path(folder)
            config = home / '.config/tint2/tint2rc'
            config.parent.mkdir(parents=True)
            config.write_text('working panel fixture')
            healthy = mock.Mock()
            healthy.poll.return_value = None
            with mock.patch.object(WATCH, 'start_panel', return_value=healthy) as panel, \
                    mock.patch.object(WATCH, 'watch_directory', side_effect=OSError('unavailable')), \
                    mock.patch.object(WATCH.select, 'select', side_effect=InterruptedError('test stop')):
                with self.assertRaises(InterruptedError):
                    WATCH.main(home, home / 'Downloads', panel_delay=0)
            self.assertEqual(panel.call_count, 1)
            self.assertEqual(config.read_text(), 'working panel fixture')
            self.assertFalse(config.with_name('tint2rc.rejected').exists())

    def test_inherited_display_exit_is_propagated_without_idle_polling(self):
        for child_code, expected in [('import time; time.sleep(.3)', 0),
                                     ('import os,signal,time; time.sleep(.3); '
                                      'os.kill(os.getpid(),signal.SIGKILL)', 137)]:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as folder:
                home = Path(folder)
                child = subprocess.Popen([sys.executable, '-c', child_code])
                original_handler = signal.getsignal(signal.SIGCHLD)
                real_select = select.select
                try:
                    with mock.patch.object(WATCH, 'start_panel', return_value=None), \
                            mock.patch.object(WATCH.select, 'select', wraps=real_select) as waits:
                        before = time.monotonic()
                        status = WATCH.main(home, home / 'Downloads', panel_delay=0,
                                            display_pid=child.pid)
                        child.returncode = -9 if status == 137 else status  # main reaped it.
                        self.assertEqual(status, expected)
                        self.assertLess(time.monotonic() - before, 2)
                        self.assertLessEqual(waits.call_count, 2)
                        self.assertIsNone(waits.call_args.args[3])  # event-driven idle wait
                    self.assertEqual(signal.getsignal(signal.SIGCHLD), original_handler)
                finally:
                    if child.returncode is None:
                        child.kill()
                        child.wait()

    def test_slow_download_offer_cannot_delay_display_exit(self):
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder)
            release, entered = threading.Event(), threading.Event()
            descriptor, writer = os.pipe()
            os.write(writer, b'completed download event')
            def offer(path, children):
                entered.set()
                release.wait(5)
            child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(.4)'])
            try:
                with mock.patch.object(WATCH, 'start_panel', return_value=None), \
                        mock.patch.object(WATCH, 'watch_directory', return_value=descriptor), \
                        mock.patch.object(WATCH, 'completed_downloads', return_value=[home / 'app.exe']), \
                        mock.patch.object(WATCH, 'offer_download', side_effect=offer):
                    before = time.monotonic()
                    status = WATCH.main(home, home / 'Downloads', panel_delay=0,
                                        display_pid=child.pid)
                    child.returncode = status
                    self.assertEqual(status, 0)
                    self.assertTrue(entered.is_set())
                    self.assertLess(time.monotonic() - before, 2)
            finally:
                release.set()
                os.close(writer)
                if child.returncode is None:
                    child.kill()
                    child.wait()

    def test_child_wakeup_fallback_and_exact_pid_status(self):
        helper = WATCH.load_childwatch()
        with mock.patch.object(helper.os, 'pipe2', side_effect=OSError('unavailable')):
            with helper.ChildWakeup() as wake:
                self.assertIsNone(wake.reader)
                self.assertEqual(wake.bounded_wait(None), 1)
                self.assertEqual(wake.bounded_wait(.1), .1)
        with mock.patch.object(helper.os, 'waitpid', side_effect=ChildProcessError):
            self.assertEqual(helper.inherited_child_status(123), 1)

    def test_real_shell_exec_preserves_display_child_ownership(self):
        function = ('start_desktop_watch() {' + SOURCE.split('start_desktop_watch() {', 1)[1]
                    .split('\nDESKTOPWATCH\n}', 1)[0] + '\nDESKTOPWATCH\n}')
        for code, expected in [('import time; time.sleep(.3)', 0),
                               ('import os,signal,time; time.sleep(.3); '
                                'os.kill(os.getpid(),signal.SIGKILL)', 137)]:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as folder:
                home = Path(folder)
                bindir = home / 'bin'
                bindir.mkdir()
                (bindir / 'tint2').write_text('#!/bin/sh\nexit 0\n')
                (bindir / 'tint2').chmod(0o755)
                env = dict(os.environ, HOME=str(home), POCKETDESK_DOWNLOAD_DIR=str(home / 'Downloads'),
                           PATH=str(bindir) + ':' + os.environ['PATH'])
                command = (function + '\n' + shlex.quote(sys.executable) + ' -c ' + shlex.quote(code)
                           + ' &\nVNC_PID=$!\nsleep .05 &\nDESKTOP_CHILDREN=("$!")\n'
                             'start_desktop_watch\n')
                result = subprocess.run(['bash', '-c', command], env=env, capture_output=True,
                                        text=True, timeout=4)
                self.assertEqual(result.returncode, expected, result.stderr)


if __name__ == '__main__':
    unittest.main()
