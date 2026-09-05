package com.pocketdesk;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessPolicyOutputTest {
    public static void main(String[] args) throws Exception {
        normalOutput();
        exactCapAllowed();
        overflowRejected();
        hangingCommandTimesOut();
        inheritedPipeDoesNotBlock();
        System.out.println("PASS ProcessPolicyOutput (JSON response, output bound, deadline, inherited pipe)");
    }

    private static Process start(String command) throws IOException {
        return new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
    }

    private static long deadline(long ms) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms); }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static void normalOutput() throws Exception {
        Process command = start("printf 'diagnostic\\n{\"ok\":true,\"message\":\"ready\"}\\n'; exit 7");
        try {
            ProcessPolicyOutput.Output output = ProcessPolicyOutput.read(command, deadline(2000));
            check(output.text.contains("{\"ok\":true"), "JSON output preserved");
            check(output.exit == 7, "nonzero exit preserved for verification");
        } finally { command.destroyForcibly(); }
    }

    private static void exactCapAllowed() throws Exception {
        Process command = start("head -c 16384 /dev/zero");
        try {
            ProcessPolicyOutput.Output output = ProcessPolicyOutput.read(command, deadline(2000));
            check(output.text.length() == 16384, "exact byte cap accepted");
        } finally { command.destroyForcibly(); }
    }

    private static void overflowRejected() throws Exception {
        Process command = start("head -c 16385 /dev/zero");
        try {
            try {
                ProcessPolicyOutput.read(command, deadline(2000));
                throw new AssertionError("oversized command response accepted");
            } catch (IOException expected) {
                check(expected.getMessage().contains("16 KiB"), "bounded output error is identified");
            }
        } finally { command.destroyForcibly(); }
    }

    private static void hangingCommandTimesOut() throws Exception {
        Process command = start("exec sleep 5");
        Process unrelated = start("exec sleep 5");
        long started = System.nanoTime();
        try {
            try {
                ProcessPolicyOutput.read(command, deadline(120));
                throw new AssertionError("hung command did not time out");
            } catch (TimeoutException expected) {}
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "deadline is bounded");
            check(command.isAlive(), "reader does not assume process-tree ownership");
            check(unrelated.isAlive(), "unrelated live work untouched");
        } finally {
            command.destroyForcibly();
            unrelated.destroyForcibly();
        }
    }

    private static void inheritedPipeDoesNotBlock() throws Exception {
        Process command = start("sleep 1 >&1 & printf '{\"ok\":true}'; exit 0");
        long started = System.nanoTime();
        try {
            ProcessPolicyOutput.Output output = ProcessPolicyOutput.read(command, deadline(2000));
            check(output.text.equals("{\"ok\":true}"), "inherited pipe did not discard final JSON");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 800, "orphan stdout not awaited");
        } finally { command.destroyForcibly(); }
    }
}
