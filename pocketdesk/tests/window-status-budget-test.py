#!/usr/bin/env python3
"""Behavior tests for fitting and phone counters without shell helper bursts."""
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import types
import unittest
from unittest import mock

PROJECT = Path(__file__).resolve().parents[1]


def embedded(name, marker):
    path = PROJECT / 'app/assets' / name
    source = path.read_text().split("<<'" + marker + "'\n", 1)[1].rsplit('\n' + marker, 1)[0]
    module = types.ModuleType(name)
    exec(compile(source, str(path), 'exec'), module.__dict__)
    return module


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value)


class WindowGuardTests(unittest.TestCase):
    def setUp(self):
        self.guard = embedded('pocketdesk-window-guard.sh', 'POCKETDESK_GUARD_PY')

    def test_decorated_geometry_and_workarea_offsets(self):
        fit = self.guard.fit_geometry
        self.assertEqual(fit((0, 60, 720, 1100), (-20, 10, 900, 1200), (4, 4, 28, 4)),
                         (0, 60, 712, 1068))
        self.assertIsNone(fit((0, 60, 720, 1100), (104, 108, 300, 200), (4, 4, 28, 4)))
        self.assertEqual(fit((100, 40, 600, 400), (670, 380, 200, 100), (4, 4, 28, 4)),
                         (492, 308, 200, 100))
        self.assertEqual(fit((0, 0, 40, 40), (-1, -1, 100, 100), (0, 0, 0, 0)),
                         (0, 0, 64, 64))

    def test_combined_properties_preserve_window_skip_rules(self):
        windows = '\n'.join('0x%08x 0 -20 10 900 1200 phone Window' % i for i in range(1, 9))
        properties = {
            2: '_NET_WM_WINDOW_TYPE_DESKTOP',
            3: '_NET_WM_STATE_MAXIMIZED_VERT, _NET_WM_STATE_MAXIMIZED_HORZ',
            4: '_NET_WM_STATE_HIDDEN',
            5: '_NET_WM_STATE_FULLSCREEN',
            6: '_NET_WM_WINDOW_TYPE_NOTIFICATION',
            7: '_NET_WM_STATE_MAXIMIZED_VERT',  # A partially maximised window can need fitting.
        }
        calls, moves = [], []
        def query(args):
            calls.append(args)
            if args == ['wmctrl', '-lG']:
                return windows + '\nnot a valid window\n'
            if args[:2] == ['xprop', '-id']:
                self.assertEqual(args[3:], list(self.guard.PROPERTIES))
                return (properties.get(int(args[2], 16), '_NET_WM_WINDOW_TYPE_DIALOG')
                        + '\n_NET_FRAME_EXTENTS(CARDINAL) = 4, 4, 28, 4\n')
            moves.append(args)
            return ''
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(self.guard, 'STATE_DIR', Path(directory)), \
                    mock.patch.object(self.guard, 'query', query):
                self.guard.clamp_all((0, 60, 720, 1100))
        self.assertEqual([call[3] for call in moves], ['0x00000001', '0x00000007', '0x00000008'])
        self.assertTrue(all(call[-1] == '0,0,60,712,1068' for call in moves))
        self.assertEqual(len(calls), 12)  # One list, eight batched properties, three changes.

    def test_workarea_override_and_old_container_fallback(self):
        with mock.patch.dict(os.environ, {'POCKETDESK_WORKAREA': '10 20 700 900'}), \
                mock.patch.object(self.guard, 'query') as query:
            self.assertEqual(self.guard.work_area(), (10, 20, 700, 900))
            query.assert_not_called()
        with mock.patch.dict(os.environ, {'POCKETDESK_WORKAREA': ''}), \
                mock.patch.object(self.guard, 'query', side_effect=['', '  dimensions: 720x1280 pixels']):
            self.assertEqual(self.guard.work_area(), (0, 0, 720, 1280))

    def test_focus_reordering_and_unchanged_workarea_preserve_user_layout(self):
        with mock.patch.dict(os.environ, {'POCKETDESK_WORKAREA': ''}):
            changes = self.guard.WindowChanges()
        with mock.patch.object(self.guard, 'clamp_all') as fit:
            self.assertTrue(changes.receive('_NET_WORKAREA(CARDINAL) = 0, 0, 720, 1100'))
            self.assertTrue(changes.receive('_NET_CLIENT_LIST(WINDOW): window id # 0x1, 0x2'))
            changes.apply()
            fit.assert_called_once_with((0, 0, 720, 1100))
            fit.reset_mock()
            for line in ('_NET_CLIENT_LIST_STACKING(WINDOW): window id # 0x2, 0x1',
                         '_NET_CLIENT_LIST(WINDOW): window id # 0x2, 0x1',
                         '_NET_WORKAREA(CARDINAL) = 0, 0, 720, 1100'):
                self.assertFalse(changes.receive(line))
            changes.apply()
            fit.assert_not_called()

    def test_new_dialog_does_not_resize_existing_window_and_removed_dialog_is_dropped(self):
        with mock.patch.dict(os.environ, {'POCKETDESK_WORKAREA': '0 0 720 1100'}):
            changes = self.guard.WindowChanges()
        with mock.patch.object(self.guard, 'clamp_all') as fit:
            changes.receive('_NET_CLIENT_LIST(WINDOW): window id # 0x1')
            changes.apply()
            fit.reset_mock()
            changes.receive('_NET_CLIENT_LIST(WINDOW): window id # 0x1, 0x2, 0x3')
            changes.receive('_NET_CLIENT_LIST(WINDOW): window id # 0x1, 0x3')
            changes.apply()
            fit.assert_called_once_with((0, 0, 720, 1100), {3})

    def test_targeted_fit_only_queries_new_window(self):
        calls = []
        def query(args):
            calls.append(args)
            if args == ['wmctrl', '-lG']:
                return ('0x00000001 0 -20 10 900 1200 phone User layout\n'
                        '0x00000003 0 -20 10 900 1200 phone New dialog\n')
            if args[:2] == ['xprop', '-id']:
                return '_NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_DIALOG'
            return ''
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(self.guard, 'STATE_DIR', Path(directory)), \
                mock.patch.object(self.guard, 'query', query):
            self.guard.clamp_all((0, 0, 720, 1100), {3})
        self.assertEqual(len(calls), 3)  # List, one property query, one move.
        self.assertEqual(calls[1][2], '0x00000003')
        self.assertEqual(calls[2][3], '0x00000003')

    def test_real_watcher_coalesces_events_and_stops_its_only_child(self):
        # Real processes exercise exec, pipe buffering, event coalescing and cleanup.
        # Stub the X tools because this host has no X server; no X11 success claim.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bindir, state = root / 'bin', root / 'state'
            bindir.mkdir()
            tool = '''#!/usr/bin/python3
import json, os, signal, sys, time
from pathlib import Path
root=Path(os.environ['GUARD_FIXTURE'])
with (root/'calls').open('a') as f: f.write(json.dumps([Path(sys.argv[0]).name]+sys.argv[1:])+'\\n')
if Path(sys.argv[0]).name == 'wmctrl':
    if sys.argv[1:] == ['-lG']: print('0x00000001 0 0 0 700 900 phone Dialog')
elif '-spy' in sys.argv:
    def stop(sig, frame):
        (root/'monitor-stopped').write_text('yes')
        sys.exit(0)
    signal.signal(signal.SIGTERM, stop)
    (root/'monitor-parent').write_text(str(os.getppid()))
    for i in range(10):
        print('_NET_WORKAREA(CARDINAL) = 0, 60, 720, 1100', flush=True)
        print('_NET_CLIENT_LIST(WINDOW): window id # 0x00000001', flush=True)
    while True: time.sleep(1)
elif '-root' in sys.argv:
    print('_NET_WORKAREA(CARDINAL) = 0, 60, 720, 1100')
else:
    print('_NET_WM_WINDOW_TYPE(ATOM) = _NET_WM_WINDOW_TYPE_DIALOG')
    print('_NET_WM_STATE(ATOM) =')
    print('_NET_FRAME_EXTENTS(CARDINAL) = 4, 4, 28, 4')
'''
            for name in ('xprop', 'wmctrl'):
                (bindir / name).write_text(tool)
                (bindir / name).chmod(0o755)
            env = dict(os.environ, PATH=str(bindir) + ':' + os.environ['PATH'],
                       GUARD_FIXTURE=str(root), POCKETDESK_STATE_DIR=str(state))
            env.pop('POCKETDESK_WORKAREA', None)
            process = subprocess.Popen(['bash', str(PROJECT / 'app/assets/pocketdesk-window-guard.sh'),
                                        'watch'], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 6
                calls = []
                while time.monotonic() < deadline:
                    if (root / 'calls').exists():
                        calls = [json.loads(line) for line in (root / 'calls').read_text().splitlines()]
                    if sum(call == ['wmctrl', '-lG'] for call in calls) >= 2:
                        break
                    time.sleep(0.05)
                self.assertEqual(sum(call == ['wmctrl', '-lG'] for call in calls), 2)
                # All 20 events arrived as one burst; only one post-initial fitting pass.
                time.sleep(0.35)
                calls = [json.loads(line) for line in (root / 'calls').read_text().splitlines()]
                self.assertEqual(sum(call == ['wmctrl', '-lG'] for call in calls), 2)
                self.assertEqual(sum('-spy' in call for call in calls), 1)
                self.assertEqual((root / 'monitor-parent').read_text().strip(),
                                 (state / 'window-guard.pid').read_text().strip())
                process.terminate()
                _, stderr = process.communicate(timeout=5)
                self.assertEqual(process.returncode, 0, stderr.decode())
                self.assertTrue((root / 'monitor-stopped').exists())
                self.assertFalse((state / 'window-guard.pid').exists())
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate(timeout=3)


class PhoneStatusTests(unittest.TestCase):
    def setUp(self):
        self.status = embedded('pocketdesk-status.sh', 'POCKETDESK_STATUS_PY')

    def test_phone_counters_and_two_line_tooltip_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            battery = root / 'sys/class/power_supply/main'
            for name, value in {'type': 'Battery', 'capacity': '84', 'status': 'Charging',
                                'temp': '370'}.items():
                write(battery / name, value)
            write(root / 'sys/class/net/wlan0/operstate', 'up')
            write(root / 'sys/class/net/rmnet_data0/operstate', 'up')
            write(root / 'meminfo', 'MemTotal: 3900000 kB\nMemAvailable: 1200000 kB\n')
            fs = types.SimpleNamespace(f_frsize=4096, f_bavail=16000000)
            with mock.patch.object(self.status.os, 'statvfs', return_value=fs) as stat:
                values = self.status.collect(str(root / 'sys'), str(root / 'meminfo'))
                stat.assert_called_once_with('/')
            output, tooltip = io.StringIO(), io.StringIO()
            self.status.render(values, output, tooltip)
            self.assertEqual(output.getvalue(), '84% · 65.5G\n37°C · 1.2G\n')
            self.assertTrue(tooltip.getvalue().startswith('\033[2JThis phone, right now\n'))
            self.assertIn('Battery: 84%, charging', tooltip.getvalue())
            self.assertIn('Network: Wi-Fi', tooltip.getvalue())
            self.assertIn('Tap for storage.', tooltip.getvalue())
            self.assertTrue(all(len(line) <= 16 for line in output.getvalue().splitlines()))

    def test_missing_invalid_counters_and_zero_free_storage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            battery = root / 'sys/class/power_supply/battery'
            for name, value in {'capacity': 'unknown', 'status': 'Discharging', 'temp': 'broken'}.items():
                write(battery / name, value)
            write(root / 'sys/class/net/ccmni0/operstate', 'up')
            with mock.patch.object(self.status.os, 'statvfs', side_effect=OSError):
                values = self.status.collect(str(root / 'sys'), str(root / 'missing'))
            out, tip = io.StringIO(), io.StringIO()
            self.status.render(values, out, tip)
            self.assertEqual(out.getvalue(), 'PocketLinux\n')
            self.assertNotIn('unknown', tip.getvalue())
            self.assertNotIn('broken', tip.getvalue())
            self.assertIn('Network: Mobile data', tip.getvalue())
            with mock.patch.object(self.status.os, 'statvfs', return_value=types.SimpleNamespace(
                    f_frsize=4096, f_bavail=0)):
                self.assertEqual(self.status.collect(str(root / 'sys'), str(root / 'missing'))['free_gb'], '0.0')

    def test_temperature_units_and_real_entrypoint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            battery = root / 'class/power_supply/battery'
            battery.mkdir(parents=True)
            with mock.patch.object(self.status.os, 'statvfs', side_effect=OSError):
                for raw, expected in [('37', '37'), ('375', '37'), ('37000', '37'),
                                      ('900', ''), ('-50', '')]:
                    write(battery / 'temp', raw)
                    self.assertEqual(self.status.collect(str(root), str(root / 'missing'))['temp'], expected)
        result = subprocess.run(['bash', str(PROJECT / 'app/assets/pocketdesk-status.sh')],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0)
        self.assertLessEqual(len(result.stdout.splitlines()), 2)
        self.assertIn('Tap for storage.', result.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
