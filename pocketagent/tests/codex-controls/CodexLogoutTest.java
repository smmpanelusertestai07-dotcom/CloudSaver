package com.pocketagent.mobile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

/** Process fixtures only: no accounts or credential files are accessed. */
public final class CodexLogoutTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        check("exec /usr/local/bin/pocketagent-codex logout".equals(CodexControls.logoutCommand()), "fixed official logout command has no user argument interpolation");
        FakeProcess success = new FakeProcess(0, true, false);
        CodexLogout.run(success, p -> ((FakeProcess)p).stopped = true, 200);
        check(success.stopped, "successful logout process cleaned up");
        check(success.stdinClosed, "official logout receives no interactive input");
        check(success.waitMillis == 200, "logout wait is bounded");
        FakeProcess failed = new FakeProcess(1, true, false);
        expectFailure(failed, "nonzero official exit does not claim signed out");
        check(failed.stopped, "failed logout process cleaned up");
        FakeProcess timeout = new FakeProcess(0, false, false);
        expectFailure(timeout, "timeout does not claim signed out");
        check(timeout.stopped, "timed out logout process cleaned up");
        FakeProcess interrupted = new FakeProcess(0, true, true);
        try { CodexLogout.run(interrupted, p -> ((FakeProcess)p).stopped = true, 200); throw new AssertionError("interruption must propagate"); }
        catch (InterruptedException expected) { assertions++; }
        check(interrupted.stopped, "interrupted logout process cleaned up");
        try { CodexLogout.run(new FakeProcess(0, true, false), p -> {}, 30001); throw new AssertionError("unbounded timeout"); }
        catch (IllegalArgumentException expected) { assertions++; }
        // Real local subprocess fills both pipes beyond their buffer capacity. Discarding output
        // must prevent deadlock without retaining an authentication response in diagnostics.
        Process noisy = new ProcessBuilder("python", "-c", "import sys;sys.stdout.write('x'*200000);sys.stderr.write('y'*200000)").start();
        CodexLogout.run(noisy, Process::destroy, 5000);
        check(!noisy.isAlive(), "both large output streams drained without blocking successful logout");
        Process slow = new ProcessBuilder("python", "-c", "import time;time.sleep(10)").start();
        long started = System.nanoTime();
        try { CodexLogout.run(slow, p -> p.destroyForcibly(), 100); throw new AssertionError("slow process should time out"); }
        catch (IOException expected) { assertions++; }
        check(slow.waitFor(2, TimeUnit.SECONDS), "real timed-out process stopped");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started) < 3000, "real timeout remains bounded");
        System.out.println("PASS CodexLogoutTest (" + assertions + " assertions)");
    }
    private static void expectFailure(FakeProcess process, String message) throws Exception {
        try { CodexLogout.run(process, p -> ((FakeProcess)p).stopped = true, 200); throw new AssertionError(message); }
        catch (IOException expected) {
            check(!expected.getMessage().contains("private-response"), "CLI output never leaks into errors"); assertions++;
        }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static final class FakeProcess extends Process {
        final int code; final boolean completed, interrupted;
        boolean stopped, stdinClosed; long waitMillis;
        final InputStream stdout = new ByteArrayInputStream("private-response".getBytes(StandardCharsets.UTF_8));
        final InputStream stderr = new ByteArrayInputStream("private-response".getBytes(StandardCharsets.UTF_8));
        FakeProcess(int code, boolean completed, boolean interrupted) { this.code=code;this.completed=completed;this.interrupted=interrupted; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream() { @Override public void close() { stdinClosed=true; } }; }
        @Override public int waitFor() { return code; }
        @Override public boolean waitFor(long duration, TimeUnit unit) throws InterruptedException { waitMillis=unit.toMillis(duration);if(interrupted)throw new InterruptedException();return completed; }
        @Override public int exitValue() { return code; }
        @Override public void destroy() { stopped=true; }
    }
}
