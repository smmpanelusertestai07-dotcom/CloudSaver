#!/usr/bin/env python3
"""Explicit, reversible Android child-process policy action for this paired phone.

Normal startup and pairing never invoke apply. Android's UI explains that this setting
is device-wide and asks the owner before calling it. The Linux and adb-shell boot IDs
must match: selecting a different paired phone can never change that phone's settings.
No root, maximum-process override, DeviceConfig sync change, or background daemon.

AOSP Android 13 references:
  core/java/android/util/FeatureFlagUtils.java (Global > persisted property > default)
  services/core/java/com/android/server/am/PhantomProcessList.java (count trimming)
"""
import fcntl
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import tempfile
import time

KEY = 'settings_enable_monitor_phantom_procs'
PROPERTY = 'persist.sys.fflag.override.' + KEY
STATE = Path('/home/coder/.pocketdesk/adb')
UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
SERIAL = re.compile(r'[A-Za-z0-9][A-Za-z0-9._:\[\]-]{0,199}')
VALUE = re.compile(r'[A-Za-z0-9_.-]{1,128}')


class PolicyError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class Adb:
    def __init__(self, serial, executable='adb', budget=24):
        self.serial = serial
        self.executable = executable
        self.deadline = time.monotonic() + budget

    def shell(self, *args):
        return self.run(['-s', self.serial, 'shell', *args])

    def run(self, args):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise PolicyError('timeout', 'The phone did not answer in time. Reconnect Wireless debugging and retry.')
        try:
            code, stdout, stderr = self._capture([self.executable, *args],
                                                 time.monotonic() + min(5, remaining))
        except FileNotFoundError:
            raise PolicyError('adb_missing', 'Install Mobile app development, then pair and connect this phone.')
        except subprocess.TimeoutExpired:
            raise PolicyError('timeout', 'The phone did not answer in time. Reconnect Wireless debugging and retry.')
        except OSError:
            raise PolicyError('connection_failed', 'Wireless debugging could not be reached. Connect this phone again.')
        if code != 0:
            detail = stderr.decode('utf-8', 'replace').lower()
            if (re.search(r'\bdevice\b.*\b(offline|not found)\b', detail)
                    or any(note in detail for note in ('no devices/emulators', 'failed to connect',
                                                      'cannot connect', 'error: closed'))):
                raise PolicyError('connection_failed', 'The saved Wireless debugging connection is no longer available.')
            raise PolicyError('command_failed', 'Android refused the request or the paired phone disconnected. No success is assumed.')
        if len(stdout) > 8192:
            raise PolicyError('invalid_reply', 'The phone returned an unexpected response. Its settings were not trusted.')
        # Remove ADB's line framing only. Normalizing a Global value's spaces
        # would change Boolean.parseBoolean semantics and its restore baseline.
        return stdout.decode('utf-8', 'replace').rstrip('\r\n')

    @staticmethod
    def _capture(command, deadline):
        """Drain both pipes with a hard combined cap, including a noisy/broken adb."""
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        output = [bytearray(), bytearray()]
        total = 0
        try:
            with selectors.DefaultSelector() as selector:
                for index, stream in enumerate((process.stdout, process.stderr)):
                    os.set_blocking(stream.fileno(), False)
                    selector.register(stream, selectors.EVENT_READ, index)
                while selector.get_map():
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise subprocess.TimeoutExpired(command, 5)
                    for key, _ in selector.select(remaining):
                        chunk = os.read(key.fd, 4096)
                        if not chunk:
                            selector.unregister(key.fileobj)
                            continue
                        total += len(chunk)
                        if total > 16384:
                            raise PolicyError('invalid_reply', 'The phone returned too much output. The action was stopped; no success is assumed.')
                        output[key.data].extend(chunk)
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(command, 5)
                return process.wait(timeout=remaining), bytes(output[0]), bytes(output[1])
        finally:
            if process.poll() is None:
                process.kill()
            process.stdout.close()
            process.stderr.close()
            # Reap the short adb client without waiting indefinitely for its teardown.
            try:
                process.wait(timeout=0.5)
            except subprocess.TimeoutExpired:
                pass


def list_devices(deadline):
    adb = Adb('')
    adb.deadline = deadline
    targets = []
    for line in adb.run(['devices', '-l']).splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] == 'device' and SERIAL.fullmatch(fields[0]):
            targets.append(fields[0])
    targets = list(dict.fromkeys(targets))
    if len(targets) > 8:
        raise PolicyError('too_many_devices', 'Too many phones are connected. Select this phone in Phone app testing → Connect.')
    return targets


def same_phone(serial, local_boot, adb_factory, devices_list, deadline):
    def client(target):
        adb = adb_factory(target)
        adb.deadline = deadline
        return adb

    if serial:
        adb = client(serial)
        try:
            remote = adb.shell('cat', '/proc/sys/kernel/random/boot_id').lower()
        except PolicyError as error:
            # A saved port can expire when Wireless debugging restarts. Reading the
            # existing adb device list does not pair, connect, or alter any device.
            if error.code != 'connection_failed':
                raise
        else:
            if UUID.fullmatch(remote) and remote == local_boot:
                return adb, False
            # An explicit, answering other phone is never silently replaced by another target.
            raise PolicyError('different_device', 'The selected connection is not verified as this phone. Connect this phone; no other device was changed.')
    matched = []
    for target in devices_list(deadline):
        adb = client(target)
        try:
            remote = adb.shell('cat', '/proc/sys/kernel/random/boot_id').lower()
        except PolicyError as error:
            if error.code == 'timeout':
                raise
            continue
        if UUID.fullmatch(remote) and remote == local_boot:
            matched.append(adb)
    if len(matched) > 1:
        raise PolicyError('ambiguous_device', 'More than one connection points to this phone. Select one in Phone app testing → Connect.')
    if not matched:
        raise PolicyError('not_connected', 'Pairing alone is not a connection. Turn on Wireless debugging and use Phone app testing → Connect for this phone.')
    return matched[0], True


def read_small(path, limit=4096):
    with Path(path).open('r', encoding='utf-8') as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        raise PolicyError('invalid_state', 'Saved phone settings are too large to read safely.')
    return value.strip()


def global_value(raw):
    # Android's `settings get` represents an absent row as the literal null.
    if raw == 'null':
        return None
    if not VALUE.fullmatch(raw):
        raise PolicyError('invalid_reply', 'The phone returned an unrecognized child-process setting.')
    return raw


def effective(global_setting, property_setting):
    # Match Boolean.parseBoolean used by AOSP FeatureFlagUtils, including uppercase TRUE.
    source = global_setting if global_setting is not None else property_setting
    return True if source is None else source.lower() == 'true'


def atomic_save(path, state):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.process-policy-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            json.dump(state, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        # The previous value must survive a killed PRoot session before any Android write.
        descriptor = os.open(path.parent, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def load_snapshot(path):
    try:
        state = json.loads(read_small(path))
    except FileNotFoundError:
        return None
    except (ValueError, OSError):
        raise PolicyError('invalid_state', 'Saved restore data could not be read. No phone setting was changed.')
    if (not isinstance(state, dict) or state.get('version') != 1 or state.get('key') != KEY
            or 'previous' not in state
            or (state['previous'] is not None and
                (not isinstance(state['previous'], str) or not VALUE.fullmatch(state['previous'])))):
        raise PolicyError('invalid_state', 'Saved restore data is not valid. No phone setting was changed.')
    return state


def execute(action, directory=STATE, boot_path=Path('/proc/sys/kernel/random/boot_id'),
            adb_factory=Adb, devices_list=list_devices):
    result = dict(ok=False, action=action, code='not_checked', message='', supported=False,
                  paired=False, same_device=False, global_value=None, property_value=None,
                  effective_enabled=None, restore_available=False, changed=False,
                  rediscovered=False)
    snapshot_path = directory / 'process-policy.json'
    lock = None
    try:
        if action not in ('status', 'apply', 'restore'):
            raise PolicyError('usage', 'Use status, apply, or restore.')
        try:
            serial = read_small(directory / 'device', 200)
        except FileNotFoundError:
            serial = ''
        if serial and not SERIAL.fullmatch(serial):
            raise PolicyError('not_connected', 'Connect this phone in Phone app testing before changing this setting.')
        result['paired'] = bool(serial)
        try:
            local_boot = read_small(boot_path, 64).lower()
        except OSError:
            raise PolicyError('identity_unavailable', 'This phone could not be identified. No paired-device setting was changed.')
        if not UUID.fullmatch(local_boot):
            raise PolicyError('identity_unavailable', 'This phone could not be identified. No paired-device setting was changed.')
        adb, discovered = same_phone(serial, local_boot, adb_factory, devices_list,
                                     time.monotonic() + 24)
        result.update(same_device=True, paired=True, rediscovered=discovered)
        sdk = adb.shell('getprop', 'ro.build.version.sdk')
        if not sdk.isdecimal() or int(sdk) < 33:
            raise PolicyError('unsupported_android', 'This protection action requires Android 13 or later.')
        result['supported'] = True
        # Serialize status and mutation so two UI actions cannot overwrite the restore baseline.
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        lock = (directory / 'process-policy.lock').open('a')
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise PolicyError('busy', 'Another phone-protection check is running. Try again when it finishes.')
        snapshot = load_snapshot(snapshot_path)
        result['restore_available'] = snapshot is not None

        def refresh():
            current = global_value(adb.shell('settings', 'get', 'global', KEY))
            prop = adb.shell('getprop', PROPERTY)
            if prop and not VALUE.fullmatch(prop):
                raise PolicyError('invalid_reply', 'The phone returned an unrecognized child-process property.')
            result.update(global_value=current, property_value=prop or None,
                          effective_enabled=effective(current, prop or None))
            return current

        current = refresh()
        if action == 'status':
            result.update(ok=True, code='status', message=(
                'Android child-process restrictions are enabled. Pairing does not switch them off.'
                if result['effective_enabled'] else
                'Android child-process restrictions are switched off by the current setting.'))
        elif action == 'apply':
            if result['effective_enabled'] is False:
                result.update(ok=True, code='already_applied', message='Child-process restrictions are already switched off. No setting was changed.')
            else:
                if snapshot is not None:
                    raise PolicyError('restore_conflict', 'The setting changed since PocketLinux saved its restore value. No newer phone choice was overwritten.')
                snapshot = dict(version=1, key=KEY, previous=current, boot_id=local_boot,
                                saved_at=int(time.time()))
                atomic_save(snapshot_path, snapshot)
                result['restore_available'] = True
                # Detect an external settings change while saving the baseline.
                if global_value(adb.shell('settings', 'get', 'global', KEY)) != current:
                    raise PolicyError('restore_conflict', 'The phone setting changed during this action. No newer choice was overwritten.')
                adb.shell('settings', 'put', 'global', KEY, 'false')
                if refresh() != 'false':
                    raise PolicyError('verification_failed', 'Android did not retain the requested setting. Protection is not confirmed; the restore value is kept.')
                result.update(ok=True, code='applied', changed=True,
                              message='Child-process restrictions are switched off for this phone. This device-wide setting can be restored here.')
        else:
            if snapshot is None:
                raise PolicyError('no_restore', 'PocketLinux has no saved previous value to restore. Nothing was changed.')
            previous = snapshot['previous']
            if current == previous:
                # A prior restore can take effect before its adb response is lost. The
                # desired value is already present; clear our record without rewriting it.
                snapshot_path.unlink()
                result.update(ok=True, code='already_restored', restore_available=False,
                              message='The previous phone setting is already restored. Nothing was changed.')
                return result
            if current != 'false':
                raise PolicyError('restore_conflict', 'The phone setting changed after PocketLinux applied protection. That newer choice was kept.')
            if previous is None:
                adb.shell('settings', 'delete', 'global', KEY)
            else:
                adb.shell('settings', 'put', 'global', KEY, previous)
            if refresh() != previous:
                raise PolicyError('verification_failed', 'The previous setting could not be confirmed. Restore data is kept so you can retry.')
            snapshot_path.unlink()
            result.update(ok=True, code='restored', changed=True, restore_available=False,
                          message='The phone’s previous child-process setting has been restored.')
    except PolicyError as error:
        result.update(code=error.code, message=str(error))
    except UnicodeError:
        result.update(code='invalid_state', message='Saved phone connection data is not valid text. No successful change is claimed.')
    except OSError:
        result.update(code='storage_error', message='The restore record could not be safely stored or read. No successful change is claimed.')
    finally:
        if lock is not None:
            lock.close()
    return result


if __name__ == '__main__':
    response = execute(sys.argv[1] if len(sys.argv) == 2 else '')
    print(json.dumps(response, ensure_ascii=True, separators=(',', ':')))
    raise SystemExit(0 if response['ok'] else 1)
