#!/usr/bin/env python3
"""Run the shipping desktop wake-lease methods with Android power-service substitution.

This verifies lease lifetime and task isolation; it is not an OEM background-kill test.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
SOURCE = (PROJECT / 'app/src/com/pocketlinux/LinuxService.java').read_text()


def method(signature):
    start = SOURCE.index(signature)
    left = SOURCE.index('{', start)
    level = 1
    end = left + 1
    while level:
        if SOURCE[end] == '{':
            level += 1
        elif SOURCE[end] == '}':
            level -= 1
        end += 1
    return SOURCE[start:end]


METHODS = '\n'.join(method(signature) for signature in (
    'private PowerManager.WakeLock newWakeLock(String tag)',
    'private PowerManager.WakeLock newWakeLock(String tag, long timeoutMillis)',
    'private boolean renewDesktopWakeLock(long generation)',
    'private synchronized void releaseDesktopWakeLock()',
    'private synchronized void acquireTaskWakeLock()',
    'private synchronized void releaseTaskWakeLock()',
    'private synchronized void acquireInstallWakeLock()',
    'private synchronized void releaseInstallWakeLock()',
))

HARNESS = r'''
package com.pocketlinux;
public final class DesktopWakeHarness {
    static final class PowerManager {
        static final int PARTIAL_WAKE_LOCK = 1;
        static long now;
        boolean refuse;
        int created;
        final class WakeLock {
            long expires;
            boolean referenced = true;
            int released;
            void setReferenceCounted(boolean value) { referenced = value; }
            void acquire(long timeout) {
                if (refuse) throw new SecurityException("Vendor refused wake lock");
                expires = now + timeout;
            }
            boolean isHeld() { return expires > now; }
            void release() { expires = 0; released++; }
        }
        WakeLock newWakeLock(int kind, String tag) {
            if (kind != PARTIAL_WAKE_LOCK) throw new AssertionError("Screen wake requested");
            created++;
            return new WakeLock();
        }
    }
    private static final long WAKE_LOCK_MS = 7_200_000L;
    private static final long DESKTOP_WAKE_LOCK_MS = 120_000L;
    private static final String POWER_SERVICE = "power";
    private final PowerManager power = new PowerManager();
    private static final TaskGeneration PRIMARY_TASK = new TaskGeneration();
    private boolean rootAlive = true, stopRequested, serviceDestroyed;
    private PowerManager.WakeLock desktopWakeLock, taskWakeLock, installWakeLock;
    private Object getSystemService(String name) { return power; }
    private boolean isDesktopRunning() { return rootAlive; }
    private static void check(boolean test, String message) {
        if (!test) throw new AssertionError(message);
    }
    __METHODS__
    public static void main(String[] args) {
        DesktopWakeHarness service = new DesktopWakeHarness();
        long session = PRIMARY_TASK.next();
        check(service.renewDesktopWakeLock(session), "Live desktop not renewed");
        PowerManager.WakeLock desktop = service.desktopWakeLock;
        check(desktop.isHeld() && !desktop.referenced, "Lease must be held and non-reference-counted");
        switch (args[0]) {
            case "renewal":
                PowerManager.now = 90_000L;
                check(service.renewDesktopWakeLock(session), "Background work lost lease");
                check(service.desktopWakeLock == desktop && service.power.created == 1,
                        "Renewal allocated another lock");
                PowerManager.now = 120_001L;
                check(desktop.isHeld(), "Renewed lease expired at original deadline");
                PowerManager.now = 210_001L;
                check(!desktop.isHeld(), "Lease remained held when monitor ceased");
                break;
            case "parallel-task":
                service.acquireTaskWakeLock();
                service.acquireInstallWakeLock();
                service.releaseTaskWakeLock();
                service.releaseInstallWakeLock();
                check(desktop.isHeld(), "Finishing setup/install released desktop lease");
                service.releaseDesktopWakeLock();
                service.releaseDesktopWakeLock();
                check(!desktop.isHeld() && desktop.released == 1, "Desktop release not idempotent");
                break;
            case "stale-session":
                PRIMARY_TASK.next();
                service.releaseDesktopWakeLock();
                check(!service.renewDesktopWakeLock(session), "Old monitor revived lease after Stop/Open");
                check(service.desktopWakeLock == null, "Stale callback allocated a lease");
                break;
            case "dead-root":
                service.rootAlive = false;
                PowerManager.now = 90_000L;
                check(!service.renewDesktopWakeLock(session), "Dead root renewed desktop lease");
                PowerManager.now = 120_001L;
                check(!desktop.isHeld(), "Dead session lease outlived timeout");
                break;
            case "destroyed":
                service.serviceDestroyed = true;
                service.releaseDesktopWakeLock();
                check(!service.renewDesktopWakeLock(session), "Destroyed service revived a lease");
                check(service.desktopWakeLock == null, "Destroyed service allocated a lease");
                break;
            case "refused":
                service.power.refuse = true;
                check(service.renewDesktopWakeLock(session), "A refused renewal stopped the desktop");
                PowerManager.now = 120_001L;
                check(!desktop.isHeld(), "Refused renewal held an unlimited lock");
                break;
            default: throw new AssertionError("Unknown test");
        }
    }
}
'''.replace('__METHODS__', METHODS)


class DesktopWakeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.TemporaryDirectory(prefix='pd-desktop-wake-')
        cls.path = Path(cls.folder.name)
        source = cls.path / 'DesktopWakeHarness.java'
        source.write_text(HARNESS)
        compiler = [shutil.which('javac')] if shutil.which('javac') else [
            shutil.which('java'), '-m', 'jdk.compiler/com.sun.tools.javac.Main']
        result = subprocess.run(compiler + ['-d', str(cls.path), str(source),
            str(PROJECT / 'app/src/com/pocketlinux/TaskGeneration.java')],
            capture_output=True, text=True, timeout=25)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.folder.cleanup()

    def run_case(self, name):
        result = subprocess.run(['java', '-cp', str(self.path),
            'com.pocketlinux.DesktopWakeHarness', name], capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_active_renewal_then_monitor_loss_has_bounded_lease(self):
        self.run_case('renewal')

    def test_parallel_setup_or_install_cannot_release_desktop_lease(self):
        self.run_case('parallel-task')

    def test_stale_monitor_cannot_reacquire_after_stop_or_new_session(self):
        self.run_case('stale-session')

    def test_dead_root_cannot_extend_lease(self):
        self.run_case('dead-root')

    def test_destroyed_service_cannot_reacquire_from_a_late_worker(self):
        self.run_case('destroyed')

    def test_vendor_refusal_does_not_end_live_desktop(self):
        self.run_case('refused')


if __name__ == '__main__':
    unittest.main()
