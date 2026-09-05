#!/usr/bin/env python3
"""Exercise paired-phone policy behavior with an Android settings/adb simulator."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'app/assets/pocketdesk-process-policy.py'
spec = importlib.util.spec_from_file_location('policy', SOURCE)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
BOOT = '12345678-1234-1234-1234-123456789abc'
OTHER_BOOT = 'abcdef12-1234-1234-1234-123456789abc'


class Phone:
    def __init__(self, boot=BOOT, sdk='33', setting=None, prop=''):
        self.boot, self.sdk, self.setting, self.prop = boot, sdk, setting, prop
        self.calls = []
        self.online = True
        self.reject_write = False
        self.lose_write_response = False
        self.write_took_effect = True
        self.on_write = None

    def shell(self, *args):
        self.calls.append(args)
        if not self.online:
            raise policy.PolicyError('connection_failed', 'offline')
        if args == ('cat', '/proc/sys/kernel/random/boot_id'):
            return self.boot
        if args == ('getprop', 'ro.build.version.sdk'):
            return self.sdk
        if args == ('getprop', policy.PROPERTY):
            return self.prop
        if args == ('settings', 'get', 'global', policy.KEY):
            return 'null' if self.setting is None else self.setting
        if args[:3] == ('settings', 'put', 'global') or args[:3] == ('settings', 'delete', 'global'):
            if self.on_write:
                self.on_write()
            if self.reject_write:
                raise policy.PolicyError('command_failed', 'permission denied')
            if self.write_took_effect:
                self.setting = args[4] if args[1] == 'put' else None
            if self.lose_write_response:
                raise policy.PolicyError('timeout', 'reply lost')
            return ''
        raise AssertionError(args)

    def writes(self):
        return [call for call in self.calls if len(call) > 1 and call[:2] in (
            ('settings', 'put'), ('settings', 'delete'))]


class ProcessPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.state = self.root / 'adb'
        self.state.mkdir()
        (self.state / 'device').write_text('127.0.0.1:12345')
        self.boot = self.root / 'boot'
        self.boot.write_text(BOOT)
        self.phone = Phone()
        self.targets = {'127.0.0.1:12345': self.phone}
        self.discovered = []

    def tearDown(self):
        self.temp.cleanup()

    def run_action(self, action):
        return policy.execute(action, self.state, self.boot, self.targets.__getitem__,
                              lambda deadline: self.discovered)

    def test_status_reads_without_mutation_and_default_enabled(self):
        result = self.run_action('status')
        self.assertTrue(result['ok'])
        self.assertTrue(result['effective_enabled'])
        self.assertTrue(result['same_device'])
        self.assertEqual([], self.phone.writes())
        self.assertFalse((self.state / 'process-policy.json').exists())

    def test_property_override_is_detected_and_global_wins(self):
        self.phone.prop = 'false'
        self.assertFalse(self.run_action('status')['effective_enabled'])
        self.phone.setting = 'TRUE'
        self.assertTrue(self.run_action('status')['effective_enabled'])

    def test_apply_persists_baseline_before_write_then_restore_deletes_unset(self):
        def proof():
            snapshot = json.loads((self.state / 'process-policy.json').read_text())
            self.assertIsNone(snapshot['previous'])
        self.phone.on_write = proof
        result = self.run_action('apply')
        self.assertTrue(result['ok'])
        self.assertTrue(result['changed'])
        self.assertFalse(result['effective_enabled'])
        self.assertEqual('false', self.phone.setting)
        self.phone.on_write = None
        result = self.run_action('restore')
        self.assertTrue(result['ok'])
        self.assertIsNone(self.phone.setting)
        self.assertFalse(result['restore_available'])

    def test_restore_exact_preexisting_global_override(self):
        self.phone.setting = 'TRUE'
        self.phone.prop = 'false'
        self.assertTrue(self.run_action('apply')['ok'])
        self.assertTrue(self.run_action('restore')['ok'])
        self.assertEqual('TRUE', self.phone.setting)
        self.assertFalse((self.state / 'process-policy.json').exists())

    def test_repeated_apply_keeps_first_baseline(self):
        self.phone.setting = 'true'
        self.assertTrue(self.run_action('apply')['ok'])
        self.assertEqual('already_applied', self.run_action('apply')['code'])
        snapshot = json.loads((self.state / 'process-policy.json').read_text())
        self.assertEqual('true', snapshot['previous'])
        self.assertEqual(1, len(self.phone.writes()))

    def test_existing_disabled_policy_is_not_claimed_or_owned(self):
        self.phone.prop = 'false'
        result = self.run_action('apply')
        self.assertEqual('already_applied', result['code'])
        self.assertFalse(result['changed'])
        self.assertFalse(result['restore_available'])
        self.assertEqual([], self.phone.writes())

    def test_selected_other_phone_refuses_even_if_this_phone_is_discoverable(self):
        self.phone.boot = OTHER_BOOT
        this_phone = Phone()
        self.targets['127.0.0.1:54321'] = this_phone
        self.discovered = ['127.0.0.1:54321']
        result = self.run_action('apply')
        self.assertEqual('different_device', result['code'])
        self.assertEqual([], self.phone.writes())
        self.assertEqual([], this_phone.calls)

    def test_missing_local_identity_fails_before_any_adb_query(self):
        self.boot.unlink()
        self.assertEqual('identity_unavailable', self.run_action('apply')['code'])
        self.assertEqual([], self.phone.calls)

    def test_wrong_or_unavailable_remote_identity_fails_without_write(self):
        self.phone.boot = 'permission denied'
        self.assertEqual('different_device', self.run_action('apply')['code'])
        self.assertEqual([], self.phone.writes())

    def test_android12_does_not_get_android13_policy_change(self):
        self.phone.sdk = '31'
        self.assertEqual('unsupported_android', self.run_action('apply')['code'])
        self.assertEqual([], self.phone.writes())

    def test_discovery_finds_this_phone_and_never_writes_others(self):
        (self.state / 'device').unlink()
        other = Phone(boot=OTHER_BOOT)
        self.targets['192.168.1.9:5555'] = other
        self.discovered = ['192.168.1.9:5555', '127.0.0.1:12345']
        result = self.run_action('apply')
        self.assertTrue(result['ok'])
        self.assertTrue(result['rediscovered'])
        self.assertEqual([], other.writes())
        self.assertFalse((self.state / 'device').exists())

    def test_expired_saved_port_uses_read_only_discovery(self):
        self.phone.online = False
        live = Phone()
        self.targets['127.0.0.1:54321'] = live
        self.discovered = ['127.0.0.1:54321']
        self.assertTrue(self.run_action('apply')['ok'])
        self.assertEqual('false', live.setting)
        self.assertEqual([], self.phone.writes())

    def test_two_matching_connections_require_explicit_selection(self):
        (self.state / 'device').unlink()
        duplicate = Phone()
        self.targets['127.0.0.1:54321'] = duplicate
        self.discovered = ['127.0.0.1:12345', '127.0.0.1:54321']
        self.assertEqual('ambiguous_device', self.run_action('apply')['code'])
        self.assertEqual([], self.phone.writes())
        self.assertEqual([], duplicate.writes())

    def test_no_active_connection_does_not_treat_pairing_as_protection(self):
        self.phone.online = False
        result = self.run_action('apply')
        self.assertEqual('not_connected', result['code'])
        self.assertFalse(result['ok'])
        self.assertIsNone(result['effective_enabled'])

    def test_lost_apply_response_keeps_recovery_snapshot(self):
        self.phone.lose_write_response = True
        result = self.run_action('apply')
        self.assertFalse(result['ok'])
        self.assertTrue(result['restore_available'])
        self.assertEqual('false', self.phone.setting)
        self.phone.lose_write_response = False
        self.assertTrue(self.run_action('restore')['ok'])
        self.assertIsNone(self.phone.setting)

    def test_rejected_apply_does_not_claim_protected(self):
        self.phone.reject_write = True
        result = self.run_action('apply')
        self.assertEqual('command_failed', result['code'])
        self.assertFalse(result['ok'])
        self.assertTrue(result['effective_enabled'])
        self.assertTrue(result['restore_available'])

    def test_readback_mismatch_is_failure_and_retains_restore(self):
        self.phone.write_took_effect = False
        result = self.run_action('apply')
        self.assertEqual('verification_failed', result['code'])
        self.assertFalse(result['ok'])
        self.assertTrue(result['restore_available'])

    def test_external_choice_is_never_overwritten_on_restore_or_apply(self):
        self.assertTrue(self.run_action('apply')['ok'])
        self.phone.setting = 'TRUE'
        calls = len(self.phone.writes())
        self.assertEqual('restore_conflict', self.run_action('restore')['code'])
        self.assertEqual('restore_conflict', self.run_action('apply')['code'])
        self.assertEqual(calls, len(self.phone.writes()))
        self.assertEqual('TRUE', self.phone.setting)

    def test_lost_restore_response_can_be_retried_without_another_write(self):
        self.assertTrue(self.run_action('apply')['ok'])
        self.phone.lose_write_response = True
        self.assertFalse(self.run_action('restore')['ok'])
        self.assertIsNone(self.phone.setting)
        count = len(self.phone.writes())
        self.phone.lose_write_response = False
        self.assertEqual('already_restored', self.run_action('restore')['code'])
        self.assertEqual(count, len(self.phone.writes()))

    def test_reboot_allows_restore_after_new_same_phone_proof(self):
        self.assertTrue(self.run_action('apply')['ok'])
        self.boot.write_text(OTHER_BOOT)
        self.phone.boot = OTHER_BOOT
        self.assertTrue(self.run_action('restore')['ok'])

    def test_corrupt_restore_data_refuses_mutation(self):
        (self.state / 'process-policy.json').write_text('{"version":99}')
        self.assertEqual('invalid_state', self.run_action('apply')['code'])
        self.assertEqual([], self.phone.writes())

    def test_failure_to_save_restore_happens_before_android_write(self):
        with patch.object(policy, 'atomic_save', side_effect=OSError('disk full')):
            self.assertEqual('storage_error', self.run_action('apply')['code'])
        self.assertEqual([], self.phone.writes())

    def test_simultaneous_action_refuses_instead_of_replacing_baseline(self):
        with (self.state / 'process-policy.lock').open('a') as lock:
            policy.fcntl.flock(lock.fileno(), policy.fcntl.LOCK_EX | policy.fcntl.LOCK_NB)
            self.assertEqual('busy', self.run_action('apply')['code'])
        self.assertEqual([], self.phone.writes())

    def test_external_choice_between_snapshot_and_write_is_not_overwritten(self):
        original_save = policy.atomic_save
        def changed_while_saving(path, state):
            original_save(path, state)
            self.phone.setting = 'TRUE'
        with patch.object(policy, 'atomic_save', side_effect=changed_while_saving):
            self.assertEqual('restore_conflict', self.run_action('apply')['code'])
        self.assertEqual([], self.phone.writes())

    def test_bad_saved_connection_bytes_return_json_error_contract(self):
        (self.state / 'device').write_bytes(b'\xff')
        result = self.run_action('status')
        self.assertEqual('invalid_state', result['code'])
        self.assertFalse(result['ok'])

    def test_adb_uses_explicit_serial_and_finite_timeouts(self):
        with patch.object(policy.Adb, '_capture', return_value=(0, b'false\r\n', b'')) as run:
            adb = policy.Adb('127.0.0.1:12345')
            start = time.monotonic()
            self.assertEqual('false', adb.shell('settings', 'get', 'global', policy.KEY))
            args, kwargs = run.call_args
            self.assertEqual(['adb', '-s', '127.0.0.1:12345', 'shell'], args[0][:4])
            self.assertLessEqual(args[1] - start, 5.1)
            self.assertEqual({}, kwargs)

    def test_adb_preserves_setting_spaces_and_rejects_them_without_normalizing(self):
        with patch.object(policy.Adb, '_capture', return_value=(0, b' true \r\n', b'')):
            value = policy.Adb('127.0.0.1:12345').shell('settings', 'get', 'global', policy.KEY)
        self.assertEqual(' true ', value)
        with self.assertRaises(policy.PolicyError):
            policy.global_value(value)

    def test_offline_connection_is_distinct_from_denied_identity(self):
        for stderr, code in ((b"adb: error: device '127.0.0.1:12345' not found", 'connection_failed'),
                             (b'cat: /proc/sys/kernel/random/boot_id: Permission denied', 'command_failed')):
            with patch.object(policy.Adb, '_capture', return_value=(1, b'', stderr)):
                with self.assertRaises(policy.PolicyError) as found:
                    policy.Adb('127.0.0.1:12345').shell('cat', '/proc/sys/kernel/random/boot_id')
                self.assertEqual(code, found.exception.code)

    def test_actual_subprocess_output_flood_is_capped_and_stopped(self):
        source = "import os\nwhile True:\n os.write(1,b'a'*4096)\n os.write(2,b'b'*4096)\n"
        started = time.monotonic()
        with self.assertRaises(policy.PolicyError) as found:
            policy.Adb('', executable=sys.executable).run(['-c', source])
        self.assertEqual('invalid_reply', found.exception.code)
        self.assertLess(time.monotonic() - started, 2)

    def test_actual_subprocess_silent_wait_obeys_total_budget(self):
        started = time.monotonic()
        with self.assertRaises(policy.PolicyError) as found:
            policy.Adb('', executable=sys.executable, budget=0.1).run(['-c', 'import time; time.sleep(20)'])
        self.assertEqual('timeout', found.exception.code)
        self.assertLess(time.monotonic() - started, 1)

    def test_devices_enumeration_filters_offline_and_caps_eight(self):
        with patch.object(policy.Adb, 'run', return_value='List of devices attached\na device product:test\nb offline\nc unauthorized\n'):
            self.assertEqual(['a'], policy.list_devices(123))
        with patch.object(policy.Adb, 'run', return_value='\n'.join(f'a{i} device' for i in range(9))):
            with self.assertRaises(policy.PolicyError) as found:
                policy.list_devices(123)
            self.assertEqual('too_many_devices', found.exception.code)


if __name__ == '__main__':
    unittest.main()
