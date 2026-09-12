package com.pocketlinux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Real subprocess pipes: these tests do not mock EOF or process lifetime. */
public final class ProcessOutputTest {
    public static void main(String[] args) throws Exception {
        preservesOutput();
        inheritedPipeDoesNotOwnTaskLifetime();
        cancellationStopsCallbacks();
        callbackErrorPropagates();
        enormousUtf8LineIsBoundedWithoutCorruption();
        System.out.println("PASS ProcessOutput (5 real-process cases)");
    }

    private static Process python(String program) throws IOException {
        return new ProcessBuilder("python3", "-c", program).redirectErrorStream(true).start();
    }

    private static void preservesOutput() throws Exception {
        Process process = python("import os,time\n"
                + "os.write(1,b'first\\r'); time.sleep(.05); os.write(1,b'\\n\\n')\n"
                + "word='नमस्ते 🌍'.encode(); os.write(1,word[:2]); time.sleep(.05); os.write(1,word[2:])\n"
                + "os.write(1,b'\\nlast without newline')\n");
        List<String> lines = new ArrayList<>();
        Thread caller = Thread.currentThread();
        int code = ProcessOutput.consume(process, line -> {
            check(Thread.currentThread() == caller, "callbacks moved off the owning worker");
            lines.add(line);
        });
        check(code == 0, "normal process exit changed");
        check(lines.equals(Arrays.asList("first", "", "नमस्ते 🌍", "last without newline")),
                "UTF-8, CRLF or final partial line changed: " + lines);
    }

    private static void inheritedPipeDoesNotOwnTaskLifetime() throws Exception {
        Path childFile = Files.createTempFile("pd-output-child-", ".pid");
        Process parent = new ProcessBuilder("bash", "-c",
                "sleep 8 & printf '%s' \"$!\" > \"$1\"; printf 'before exit\\n'; sleep 0.15; exit 7",
                "process-output-test", childFile.toString()).redirectErrorStream(true).start();
        try {
            List<String> lines = new ArrayList<>();
            long started = System.nanoTime();
            int code = ProcessOutput.consume(parent, lines::add);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            check(code == 7, "parent's nonzero exit was replaced by pipe state");
            check(elapsedMillis < 2500, "inherited stdout stalled completion: " + elapsedMillis + " ms");
            check(lines.equals(Arrays.asList("before exit")), "final parent output was lost");
        } finally {
            String pid = new String(Files.readAllBytes(childFile), StandardCharsets.UTF_8).trim();
            if (pid.matches("[0-9]+")) new ProcessBuilder("kill", "-KILL", pid).start().waitFor();
            if (parent.isAlive()) parent.destroyForcibly();
            Files.deleteIfExists(childFile);
        }
    }

    private static void cancellationStopsCallbacks() throws Exception {
        Process process = python("import os,time\nos.write(1,b'one\\ntwo\\nthree\\n'); time.sleep(8)\n");
        List<String> lines = new ArrayList<>();
        try {
            try {
                ProcessOutput.consume(process, line -> {
                    lines.add(line);
                    Thread.currentThread().interrupt();
                });
                throw new AssertionError("cancellation was swallowed");
            } catch (InterruptedException expected) {
                check(lines.equals(Arrays.asList("one")), "callbacks continued after cancellation");
            }
        } finally {
            Thread.interrupted();
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor();
        }
    }

    private static void callbackErrorPropagates() throws Exception {
        Process process = python("print('report line',flush=True)");
        IOException failure = new IOException("fixture report cannot be written");
        try {
            try {
                ProcessOutput.consume(process, line -> { throw failure; });
                throw new AssertionError("report error was swallowed");
            } catch (IOException actual) {
                check(actual == failure, "report error was replaced");
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor();
        }
    }

    private static void enormousUtf8LineIsBoundedWithoutCorruption() throws Exception {
        Process process = python("import sys\nsys.stdout.write('🙂'*100000)");
        List<String> chunks = new ArrayList<>();
        check(ProcessOutput.consume(process, chunks::add) == 0, "large-output process failed");
        StringBuilder joined = new StringBuilder();
        for (String chunk : chunks) {
            check(chunk.getBytes(StandardCharsets.UTF_8).length <= 256 * 1024,
                    "an unbounded line reached the callback");
            joined.append(chunk);
        }
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 100000; i++) expected.append("🙂");
        check(joined.toString().equals(expected.toString()), "large UTF-8 line was corrupted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
