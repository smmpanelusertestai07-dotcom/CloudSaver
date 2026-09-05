#!/usr/bin/env python3
"""Exercise the shipped single-process panel/download watcher with real inotify events."""
import importlib.util
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

# The helper the supervisor loads at runtime, loaded the same way here: the subreaper and the
# process count it uses to stay under Android's ceiling live in it.
_SPEC = importlib.util.spec_from_file_location(
    'childwatch_under_test', PROJECT / 'app/assets/pocketdesk-childwatch.py')
CHILDWATCH = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(CHILDWATCH)


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

    def test_every_finished_file_is_seen_and_a_running_download_is_not(self):
        # A download is only finished when the browser closes it. A part file is half a file, and
        # acting on one both acts on nothing useful and burns the once-only marker for the real one.
        with tempfile.TemporaryDirectory(prefix='pd-watch-') as folder:
            directory = Path(folder)
            descriptor = WATCH.watch_directory(directory)
            try:
                unfinished = (directory / 'still-downloading.deb').open('wb')
                try:
                    unfinished.write(b'not finished')
                    unfinished.flush()
                    complete = directory / 'पूर्ण $HOME; sample.deb'
                    complete.write_bytes(b'download complete')
                    report = directory / 'readme.txt'
                    report.write_text('an ordinary file is placed too, not ignored')
                    (directory / 'big.iso.crdownload').write_bytes(b'browser part file')
                    self.assertEqual(self.read_events(descriptor, directory), [complete, report])
                finally:
                    unfinished.close()
                self.assertEqual(self.read_events(descriptor, directory),
                                 [directory / 'still-downloading.deb'])
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

    # ---- Android's real ceiling ---------------------------------------------------------
    #
    # Under PRoot every Linux process is one of this app's Android child processes, and Android 12
    # and later kill every one of them once there are more than 32. That arrives as "the desktop
    # display ended unexpectedly (exit 137)" with memory still free -- which is exactly what a real
    # report showed: 36 processes at peak, 1.2 GB available, lowMemory false.

    def test_a_finished_process_nobody_owns_is_cleared_but_an_owned_one_is_left(self):
        # A zombie still holds an Android process slot. On the real report five of the seven
        # surviving processes were zombies of an app that had already exited: nothing in a
        # container waits for a reparented orphan, because there is no init to do it.
        owned = os.fork()
        if owned == 0:
            os._exit(7)
        orphan = os.fork()
        if orphan == 0:
            os._exit(0)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if CHILDWATCH.reap_unowned({owned}) or self._is_gone(orphan):
                break
            time.sleep(0.05)
        # The owned child's status is still there to be collected by its owner.
        self.assertEqual(os.waitpid(owned, 0)[1] >> 8, 7)

    def _is_gone(self, pid):
        try:
            os.kill(pid, 0)
        except OSError:
            return True
        return False

    def test_a_crowd_closes_one_program_instead_of_letting_android_kill_the_computer(self):
        watch = mock.Mock()
        watch.process_count.return_value = WATCH.CROWDED_AT + 4
        killed = []
        with mock.patch.object(WATCH, 'busiest_closable', return_value=('chrome', [111, 112])), \
                mock.patch.object(WATCH.os, 'kill', side_effect=lambda p, s: killed.append((p, s))), \
                mock.patch.object(WATCH, 'notify') as notify:
            # A launch spikes the count for a moment; one crowded reading must not close anything.
            first = WATCH.keep_under_ceiling(watch, None, now=100.0)
            self.assertEqual(first, 100.0)
            self.assertEqual(killed, [])
            # Still crowded, but not for long enough yet.
            self.assertEqual(WATCH.keep_under_ceiling(watch, first, now=101.0), first)
            self.assertEqual(killed, [])
            # A crowd that persists is acted on, before Android acts on everything.
            self.assertIsNone(WATCH.keep_under_ceiling(watch, first,
                                                       now=100.0 + WATCH.CROWDED_FOR_SECONDS))
            self.assertEqual([pid for pid, _ in killed], [111, 112])
            self.assertEqual({sig for _, sig in killed}, {signal.SIGTERM})
            self.assertIn('32', notify.call_args.args[1])

    def test_room_to_spare_closes_nothing_and_forgets_the_crowd(self):
        watch = mock.Mock()
        watch.process_count.return_value = WATCH.CROWDED_AT - 1
        with mock.patch.object(WATCH, 'busiest_closable') as pick:
            self.assertIsNone(WATCH.keep_under_ceiling(watch, 100.0, now=200.0))
        pick.assert_not_called()

    def test_the_ceiling_stays_below_androids_own_limit(self):
        # Being killed at 33 is, to the owner, the same as being killed at 40. The headroom is
        # what lets an app being opened add several processes between two checks.
        self.assertLess(WATCH.CROWDED_AT, CHILDWATCH.PROCESS_CEILING)
        self.assertGreaterEqual(CHILDWATCH.PROCESS_CEILING - WATCH.CROWDED_AT, 4)

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
