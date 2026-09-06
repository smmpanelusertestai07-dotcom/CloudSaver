#!/usr/bin/env python3
"""Check root-only D-Bus preparation decisions without touching the host bus."""
import contextlib
import errno
import io
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import types
import unittest
from unittest import mock

PROJECT = Path(__file__).resolve().parents[1]
DESKTOP = PROJECT / 'app/assets/pocketdesk-desktop.sh'
SOURCE = DESKTOP.read_text()
CODE = SOURCE.split("<<'SYSTEMBUS'\n", 1)[1].split('\nSYSTEMBUS\n', 1)[0]
BUS = types.ModuleType('system_bus_under_test')
exec(compile(CODE, 'pocketdesk-desktop.sh:SYSTEMBUS', 'exec'), BUS.__dict__)


class SystemBusTest(unittest.TestCase):
    def prepare(self, state, results):
        with mock.patch.object(BUS.os, 'makedirs'), \
                mock.patch.object(BUS, 'restore_system_config', return_value=True), \
                mock.patch.object(BUS, 'prepare_bus_identity', return_value=True), \
                mock.patch.object(BUS, 'listener_state', return_value=state), \
                mock.patch.object(BUS.subprocess, 'run', side_effect=results) as run, \
                contextlib.redirect_stdout(io.StringIO()):
            code = BUS.prepare_system_bus()
        return code, run

    def test_socket_absence_requires_enoent_or_connection_refused(self):
        cases = [(None, 'connected'), (OSError(errno.ENOENT, 'absent'), 'absent'),
                 (OSError(errno.ECONNREFUSED, 'stale'), 'absent'),
                 (OSError(errno.EACCES, 'denied'), 'unknown'),
                 (TimeoutError('not proof of absence'), 'unknown')]
        for error, expected in cases:
            with self.subTest(expected=expected, error=error):
                connection = mock.MagicMock()
                connection.__enter__.return_value.connect.side_effect = error
                with mock.patch.object(BUS.socket, 'socket', return_value=connection), \
                        contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(BUS.listener_state('/fixture/socket'), expected)

    def test_unknown_listener_never_starts_or_replaces_a_daemon(self):
        code, run = self.prepare('unknown', [])
        self.assertEqual(code, 1)
        run.assert_not_called()

    def test_existing_listener_is_preserved_even_when_dbus_reply_fails(self):
        code, run = self.prepare('connected', [subprocess.CompletedProcess([], 1, 'access denied')])
        self.assertEqual(code, 1)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0][0], 'dbus-send')
        self.assertIn('org.freedesktop.DBus.ListNames', run.call_args.args[0])

    def test_absent_bus_has_one_bounded_start_then_real_readiness_check(self):
        code, run = self.prepare('absent', [subprocess.CompletedProcess([], 0, ''),
                                           subprocess.CompletedProcess([], 0, 'method return')])
        self.assertEqual(code, 0)
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args_list[0].args[0],
                         ['dbus-daemon', '--system', '--fork', '--nopidfile', '--nosyslog'])
        self.assertEqual(run.call_args_list[0].kwargs['timeout'], 8)
        self.assertNotEqual(run.call_args_list[0].kwargs.get('stdout'), subprocess.PIPE)
        self.assertEqual(run.call_args_list[1].kwargs['timeout'], 4)
        self.assertEqual(run.call_args_list[1].args[0][0], 'dbus-send')

    def test_forked_daemon_timeout_does_not_trigger_duplicate_start(self):
        code, run = self.prepare('absent', [subprocess.TimeoutExpired('dbus-daemon', 8),
                                           subprocess.CompletedProcess([], 0, 'method return')])
        self.assertEqual(code, 0)
        self.assertEqual([call.args[0][0] for call in run.call_args_list], ['dbus-daemon', 'dbus-send'])

    def test_missing_configuration_is_restored_offline_with_distro_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'dbus-1/system.conf'
            source = PROJECT / 'app/assets/dbus-system.conf'
            self.assertTrue(BUS.restore_system_config(str(source), str(target)))
            self.assertEqual(target.read_bytes(), source.read_bytes())
            self.assertEqual(target.stat().st_mode & 0o777, 0o644)
            target.write_text('owner-custom-policy')
            self.assertTrue(BUS.restore_system_config('/missing', str(target)))
            self.assertEqual(target.read_text(), 'owner-custom-policy')

    def test_invalid_bundle_cannot_install_a_bus_policy(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'system.conf'
            source = Path(folder) / 'broken.conf'
            source.write_text('<busconfig><allow user="*"/></busconfig>')
            self.assertFalse(BUS.restore_system_config(str(source), str(target)))
            self.assertFalse(target.exists())

    def test_identity_failure_cannot_start_an_unconfigured_daemon(self):
        with mock.patch.object(BUS.os, 'makedirs'), \
                mock.patch.object(BUS, 'listener_state', return_value='absent'), \
                mock.patch.object(BUS, 'restore_system_config', return_value=True), \
                mock.patch.object(BUS, 'prepare_bus_identity', return_value=False), \
                mock.patch.object(BUS.subprocess, 'run') as run:
            self.assertEqual(BUS.prepare_system_bus(), 1)
            run.assert_not_called()

    def test_root_mode_never_runs_desktop_user_setup(self):
        with tempfile.TemporaryDirectory(prefix='pd-bus-mode-') as folder:
            root = Path(folder)
            binary = root / 'python3'
            binary.write_text('#!/bin/sh\nprintf "system-bus-helper-only\\n"\nexit 23\n')
            binary.chmod(0o755)
            result = subprocess.run(['bash', str(DESKTOP), '--prepare-system-bus'],
                                    env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH']),
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=3)
            self.assertEqual(result.returncode, 23)
            self.assertEqual(result.stdout, 'system-bus-helper-only\n')
            self.assertEqual(result.stderr, '')
            self.assertNotIn('PD_DESKTOP_PHASE', result.stdout)


if __name__ == '__main__':
    unittest.main()
