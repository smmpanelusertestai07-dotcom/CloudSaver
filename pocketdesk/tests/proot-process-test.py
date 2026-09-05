#!/usr/bin/env python3
"""Exercise Android's PRoot stop policy against real host process trees.

Android's signal API is substituted. Some build hosts expose an outer PID
namespace in /proc; the test Process adapter translates only that boundary so
Android's Process.toString PID and sendSignal refer to the same real subprocess.
Process.pid deliberately throws, as that API is unavailable on Android 13.
Production discovery,
identity checks, grace periods and asynchronous stop run unchanged. This is not
a PRoot-on-Android or real-device compatibility test.
"""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest


PROJECT = Path(__file__).resolve().parents[1]
PRODUCTION = PROJECT / 'app/src/com/pocketdesk/ProotProcess.java'

SIGNAL_STUB = r'''
package android.os;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class Process {
    public static int myPid() { return (int) java.lang.ProcessHandle.current().pid(); }
    public static int myUid() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/self/status")))
                if (line.startsWith("Uid:")) return Integer.parseInt(line.trim().split("\\s+")[2]);
        } catch (Exception e) { throw new AssertionError(e); }
        throw new AssertionError("No host UID");
    }
    public static void sendSignal(int pid, int signal) {
        try {
            Path dir = Paths.get(System.getProperty("pd.test.dir"));
            Files.write(dir.resolve("signals"), (pid + " " + signal + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            int namespacePid = -1;
            for (String line : Files.readAllLines(dir.resolve("pids")))
                if (line.startsWith(pid + " ")) namespacePid = Integer.parseInt(line.split(" ")[2]);
            if (namespacePid < 0) {
                Files.write(dir.resolve("unsafe-signal"), (pid + " " + signal).getBytes(StandardCharsets.UTF_8));
                throw new AssertionError("Signal targeted a process outside this test's owned tree: " + pid);
            }
            new ProcessBuilder("/bin/kill", "-" + signal, Integer.toString(namespacePid))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
'''

HARNESS = r'''
package com.pocketdesk;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.io.*;
public final class ProotProcessHarness {
    private static final class VisibleProcess extends Process {
        final Process actual;
        final long visiblePid;
        final boolean malformed;
        VisibleProcess(Process actual, long visiblePid, boolean malformed) {
            this.actual = actual; this.visiblePid = visiblePid; this.malformed = malformed;
        }
        public long pid() { throw new NoSuchMethodError("Android 13 has no public Process.pid()"); }
        public String toString() {
            return "Process[pid=" + visiblePid + ", hasExited=" + !actual.isAlive() + "]"
                    + (malformed ? " unexpected suffix" : "");
        }
        public OutputStream getOutputStream() { return actual.getOutputStream(); }
        public InputStream getInputStream() { return actual.getInputStream(); }
        public InputStream getErrorStream() { return actual.getErrorStream(); }
        public int waitFor() throws InterruptedException { return actual.waitFor(); }
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return actual.waitFor(timeout, unit);
        }
        public int exitValue() { return actual.exitValue(); }
        public void destroy() { actual.destroy(); }
        public boolean isAlive() { return actual.isAlive(); }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[2]);
        System.setProperty("pd.test.dir", dir.toString());
        Process target = new ProcessBuilder(args[0], args[1], dir.toString(), args[3], "root")
                .redirectOutput(dir.resolve("worker-output").toFile())
                .redirectErrorStream(true).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(dir.resolve("ready"))) {
            if (!target.isAlive() || System.nanoTime() > deadline)
                throw new AssertionError("Fixture did not become ready");
            Thread.sleep(10);
        }
        long visiblePid = -1;
        for (String line : Files.readAllLines(dir.resolve("pids"))) {
            String[] fields = line.split(" ");
            if (fields[1].equals("root")) visiblePid = Long.parseLong(fields[0]);
        }
        if (visiblePid < 0) throw new AssertionError("Missing root identity");
        target = new VisibleProcess(target, visiblePid, args[4].equals("malformed"));
        if (args[4].equals("reused-pid")) {
            Process reaped = new ProcessBuilder("/bin/true").start();
            reaped.waitFor();
            // A stale Process description now names a different, still-live same-UID child.
            Process stale = new VisibleProcess(reaped, visiblePid, false);
            ProotProcess.track(stale);
            ProotProcess.requestStop(stale);
            ProotProcess.awaitPendingStops();
            if (!target.isAlive() || Files.exists(dir.resolve("signals")))
                throw new AssertionError("Initial registration claimed a recycled process ID");
        }
        if (args[4].equals("malformed")) {
            ProotProcess.requestStop(target);
            boolean rejected = false;
            try { ProotProcess.awaitPendingStops(); }
            catch (IOException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Unknown live process was omitted from new-start barrier");
            if (!target.isAlive()) throw new AssertionError("Unknown process was signalled");
            if (Files.exists(dir.resolve("signals"))) throw new AssertionError("Malformed identity received a signal");
            Files.write(dir.resolve("malformed-rejected"), new byte[0]);
            // Cleanup belongs to this fixture, after proving production failed closed.
            target.destroy();
            if (!target.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture cleanup failed");
            return;
        }
        if (args[4].equals("stopped")) {
            android.os.Process.sendSignal((int) visiblePid, 19);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!new String(Files.readAllBytes(Paths.get("/proc/" + visiblePid + "/status")),
                              StandardCharsets.UTF_8).contains("T (stopped)")) {
                if (System.nanoTime() > deadline) throw new AssertionError("Fixture did not stop");
                Thread.sleep(10);
            }
        }
        boolean forced = args[4].startsWith("forced-");
        Process otherSession = null;
        if (forced) {
            ProotProcess.track(target);
            if (args[4].equals("forced-prune-history")) {
                // A long build can create thousands of helpers. Seed proven-nonexistent
                // historical identities, while withholding the live /proc inventory to
                // model temporarily inaccessible metadata for the real owned descendants.
                java.lang.reflect.Field trackedField = ProotProcess.class.getDeclaredField("TRACKED");
                trackedField.setAccessible(true);
                Object session = ((java.util.Map<?, ?>) trackedField.get(null)).get(target);
                java.lang.reflect.Field membersField = session.getClass().getDeclaredField("members");
                membersField.setAccessible(true);
                @SuppressWarnings("unchecked") java.util.Map<Integer, Object> members =
                        (java.util.Map<Integer, Object>) membersField.get(session);
                Class<?> identityClass = Class.forName("com.pocketdesk.ProotProcess$Identity");
                java.lang.reflect.Constructor<?> identity = identityClass.getDeclaredConstructor(
                        int.class, int.class, int.class, int.class, long.class, char.class);
                identity.setAccessible(true);
                java.lang.reflect.Method collect = ProotProcess.class.getDeclaredMethod(
                        "collectMembers", session.getClass(), java.util.Map.class);
                collect.setAccessible(true);
                synchronized (session) {
                    int baseline = members.size();
                    if (baseline != 3) throw new AssertionError("Fixture tree was not captured");
                    for (int wave = 0; wave < 12; wave++) {
                        for (int item = 0; item < 200; item++) {
                            int gonePid = Integer.MAX_VALUE - wave * 200 - item;
                            if (!Files.notExists(Paths.get("/proc/" + gonePid)))
                                throw new AssertionError("Fixture identity is not proven absent");
                            members.put(gonePid, identity.newInstance(gonePid, (int) visiblePid, 0,
                                    android.os.Process.myUid(), 1L, 'S'));
                        }
                        collect.invoke(null, session, java.util.Collections.emptyMap());
                        if (members.size() != baseline)
                            throw new AssertionError("History grew or uncertain living identities were discarded: "
                                    + members.size());
                    }
                }
            }
            if (args[4].equals("forced-other-session")) {
                Path otherPid = dir.resolve("other-pid");
                Process actualOther = new ProcessBuilder(args[0], "-c",
                        "import os,sys,time;open(sys.argv[1],'w').write(os.readlink('/proc/self'));time.sleep(120)",
                        otherPid.toString()).start();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!Files.exists(otherPid) || Files.size(otherPid) == 0) {
                    if (System.nanoTime() > deadline) throw new AssertionError("Other session did not start");
                    Thread.sleep(10);
                }
                otherSession = new VisibleProcess(actualOther,
                        Long.parseLong(new String(Files.readAllBytes(otherPid), StandardCharsets.UTF_8)), false);
                ProotProcess.track(otherSession);
            }
            if (args[4].equals("forced-late-child")) {
                Files.write(dir.resolve("spawn-late"), new byte[0]);
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (Files.readAllLines(dir.resolve("pids")).size() < 4) {
                    if (System.nanoTime() > deadline) throw new AssertionError("Late child not spawned");
                    Thread.sleep(10);
                }
                // No explicit refresh here: the production timer must remember the late child.
                Thread.sleep(2400);
                if (!ProotProcess.trackingSummary().contains("recorded=4"))
                    throw new AssertionError("Periodic tracker missed late child: " + ProotProcess.trackingSummary());
            }
            android.os.Process.sendSignal((int) visiblePid, 9);
            if (!target.waitFor(3, TimeUnit.SECONDS)) throw new AssertionError("External kill did not end root");
            if (args[4].equals("forced-timer")) {
                // Neither stop API nor new-start barrier runs before this check.
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                while (!ProotProcess.trackingSummary().contains("Tracked sessions: 0")) {
                    if (System.nanoTime() > deadline) throw new AssertionError("Sampler did not clean dead root");
                    Thread.sleep(20);
                }
            }
        }
        if (args[4].equals("interrupted")) Thread.currentThread().interrupt();
        long start = System.nanoTime();
        if (args[4].equals("forced-barrier")) ProotProcess.awaitPendingStops();
        else if (args[4].equals("async")) ProotProcess.requestStop(target);
        else ProotProcess.stopAndWait(target);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Files.write(dir.resolve("returned-ms"), Long.toString(elapsed).getBytes(StandardCharsets.UTF_8));
        if (args[4].equals("interrupted")) {
            if (!Thread.currentThread().isInterrupted()) throw new AssertionError("Stop swallowed interrupt flag");
            Thread.interrupted();
        }
        if (args[4].equals("async")) {
            ProotProcess.awaitPendingStops();
            if (target.isAlive()) throw new AssertionError("New-start barrier returned before old root stopped");
        }
        if (!target.waitFor(8, TimeUnit.SECONDS)) throw new AssertionError("Stop left root alive");
        if (otherSession != null) {
            if (!otherSession.isAlive()) throw new AssertionError("Cleanup affected another tracked session");
            otherSession.destroy(); // Fixture cleanup after proving production preserved it.
            if (!otherSession.waitFor(3, TimeUnit.SECONDS)) throw new AssertionError("Other fixture did not exit");
            ProotProcess.stopAndWait(otherSession);
        }
        // The public API must safely accept a previously reaped Process repeatedly.
        int beforeRepeat = Files.readAllLines(dir.resolve("signals")).size();
        ProotProcess.stopAndWait(target);
        ProotProcess.requestStop(target);
        Thread.sleep(100);
        if (beforeRepeat != Files.readAllLines(dir.resolve("signals")).size())
            throw new AssertionError("Already-reaped target received another signal");
        Files.write(dir.resolve("exit-code"), Integer.toString(target.exitValue()).getBytes(StandardCharsets.UTF_8));
    }
}
'''

WORKER = r'''
import os, signal, subprocess, sys, time
from pathlib import Path
directory, mode, role = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
with (directory / 'pids').open('a') as out:
    out.write('%s %s %d\n' % (os.readlink('/proc/self'), role, os.getpid()))
    out.flush()
child = None
if role in ('root', 'branch'):
    child = subprocess.Popen([sys.executable, __file__, str(directory), mode,
                              'branch' if role == 'root' else 'leaf'],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
def finish(signum, frame):
    if role == 'root':
        (directory / 'graceful-start').touch()
    if child is not None:
        child.terminate()
        child.wait(timeout=2)
    if role == 'root':
        (directory / 'graceful-complete').touch()
    sys.exit(0)
if mode == 'graceful':
    signal.signal(signal.SIGQUIT, finish)
    signal.signal(signal.SIGTERM, finish)
    # The host JDK can pass a blocked SIGQUIT mask to children. This fixture is
    # the cooperative tracer; stubborn mode deliberately keeps inherited masks.
    signal.pthread_sigmask(signal.SIG_UNBLOCK, {signal.SIGQUIT, signal.SIGTERM})
else:
    signal.signal(signal.SIGQUIT, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
if role == 'root':
    deadline = time.monotonic() + 4
    while len((directory / 'pids').read_text().splitlines()) != 3:
        if time.monotonic() > deadline: raise RuntimeError('children did not start')
        time.sleep(.01)
    # Give descendants time to install signal handlers before declaring readiness.
    time.sleep(.1)
    (directory / 'ready').touch()
while True:
    if role == 'root' and (directory / 'spawn-late').exists():
        (directory / 'spawn-late').unlink()
        subprocess.Popen([sys.executable, __file__, str(directory), mode, 'late-leaf'],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(.05)
'''


def alive(pid):
    try:
        stat = Path('/proc/%d/stat' % pid).read_text()
        return stat[stat.rfind(')') + 2:].split()[0] not in ('Z', 'X')
    except (FileNotFoundError, ProcessLookupError):
        return False


class ProotProcessBehavior(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compiler_dir = tempfile.TemporaryDirectory(prefix='pocketdesk-stop-tests-')
        cls.classes = Path(cls.compiler_dir.name)
        (cls.classes / 'Process.java').write_text(SIGNAL_STUB)
        (cls.classes / 'ProotProcessHarness.java').write_text(HARNESS)
        cls.worker = cls.classes / 'worker.py'
        cls.worker.write_text(WORKER)
        compiler = [shutil.which('javac')] if shutil.which('javac') else [
            shutil.which('java'), '-m', 'jdk.compiler/com.sun.tools.javac.Main']
        result = subprocess.run(compiler + ['-encoding', 'UTF-8', '-d', str(cls.classes),
                                str(cls.classes / 'Process.java'), str(PRODUCTION),
                                str(cls.classes / 'ProotProcessHarness.java')],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=30)
        if result.returncode:
            cls.compiler_dir.cleanup()
            raise AssertionError('Stop helper host compile failed:\n' + result.stdout)

    @classmethod
    def tearDownClass(cls):
        cls.compiler_dir.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='pocketdesk-stop-tree-')
        self.root = Path(self.temp.name)
        # Same UID and same interpreter as the worker, but unrelated to its tree.
        self.unrelated = subprocess.Popen([os.sys.executable, '-c', 'import time; time.sleep(120)'])

    def tearDown(self):
        self.unrelated.kill()
        self.unrelated.wait(timeout=3)
        pids = self.root / 'pids'
        if pids.exists():
            for line in reversed(pids.read_text().splitlines()):
                pid, _, namespace_pid = line.split()
                pid, namespace_pid = int(pid), int(namespace_pid)
                if alive(pid):
                    try:
                        os.kill(namespace_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
        self.temp.cleanup()

    def run_stop(self, mode, action='sync'):
        run = subprocess.run(['java', '-cp', str(self.classes), 'com.pocketdesk.ProotProcessHarness',
                              os.sys.executable, str(self.worker), str(self.root), mode, action],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=16)
        self.assertFalse((self.root / 'unsafe-signal').exists(), 'Attempted to signal an unrelated process')
        self.assertEqual(run.returncode, 0, run.stdout)
        self.assertIsNone(self.unrelated.poll(), 'Unrelated same-UID process was terminated')
        pids = {role: int(pid) for pid, role, _ in (line.split() for line in (self.root / 'pids').read_text().splitlines())}
        deadline = time.monotonic() + 2
        while any(alive(pid) for pid in pids.values()) and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertFalse({role: pid for role, pid in pids.items() if alive(pid)}, 'Owned descendants survived stop')
        if action == 'malformed':
            self.assertTrue((self.root / 'malformed-rejected').exists())
            self.assertFalse((self.root / 'signals').exists())
            return pids, [], 0
        signals_file = self.root / 'signals'
        signals = [tuple(map(int, line.split())) for line in signals_file.read_text().splitlines()]
        return pids, signals, int((self.root / 'returned-ms').read_text())

    def test_graceful_stop_allows_root_to_reap_its_children(self):
        pids, signals, _ = self.run_stop('graceful')
        self.assertTrue((self.root / 'graceful-complete').exists())
        self.assertIn((pids['root'], signal.SIGQUIT), signals)
        self.assertFalse(any(sig == signal.SIGKILL for _, sig in signals), signals)

    def test_stopped_parent_is_resumed_before_graceful_shutdown(self):
        pids, signals, _ = self.run_stop('graceful', 'stopped')
        self.assertTrue((self.root / 'graceful-complete').exists())
        self.assertLess(signals.index((pids['root'], signal.SIGCONT)),
                        signals.index((pids['root'], signal.SIGQUIT)))

    def test_uncooperative_parent_fallback_cleans_only_owned_tree(self):
        pids, signals, elapsed = self.run_stop('stubborn')
        self.assertIn((pids['root'], signal.SIGQUIT), signals)
        self.assertEqual({pid for pid, sig in signals if sig == signal.SIGKILL}, set(pids.values()))
        self.assertGreaterEqual(elapsed, 1500, 'Fallback skipped the promised graceful shutdown interval')
        self.assertLess(elapsed, 7500, 'Blocking stop exceeded its bounded deadline')

    def test_request_stop_returns_without_waiting_for_grace_period(self):
        _, _, elapsed = self.run_stop('stubborn', 'async')
        self.assertLess(elapsed, 500, 'requestStop blocked the caller during teardown')

    def test_interruption_preserves_flag_and_does_not_abandon_owned_processes(self):
        self.run_stop('stubborn', 'interrupted')

    def test_malformed_android_identity_blocks_new_start_without_sending_signals(self):
        self.run_stop('graceful', 'malformed')

    def test_tracked_children_are_cleaned_after_external_root_sigkill(self):
        pids, signals, _ = self.run_stop('stubborn', 'forced-stop')
        self.assertEqual({pid for pid, sig in signals if sig == signal.SIGKILL}, set(pids.values()))

    def test_exited_history_is_bounded_without_losing_uncertain_live_children(self):
        self.run_stop('stubborn', 'forced-prune-history')

    def test_new_start_barrier_cleans_dead_tracer_without_waiting_for_sampler(self):
        self.run_stop('stubborn', 'forced-barrier')

    def test_sampler_detects_root_death_and_cleans_without_explicit_stop(self):
        self.run_stop('stubborn', 'forced-timer')

    def test_periodic_snapshot_retains_children_born_after_start(self):
        self.run_stop('stubborn', 'forced-late-child')

    def test_new_registration_rejects_a_pid_reused_after_original_exit(self):
        self.run_stop('graceful', 'reused-pid')

    def test_dead_root_cleanup_preserves_another_tracked_session(self):
        self.run_stop('stubborn', 'forced-other-session')


if __name__ == '__main__':
    unittest.main()
