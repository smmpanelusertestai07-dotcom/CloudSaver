package com.pocketdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded output for the one short, user-requested Android policy command. */
final class ProcessPolicyOutput {
    static final int MAX_BYTES = 16 * 1024;

    static final class Output {
        final String text;
        final int exit;
        Output(String text, int exit) { this.text = text; this.exit = exit; }
    }

    private ProcessPolicyOutput() {}

    /** Caller owns cleanup of exactly this process, including on timeout or output overflow. */
    static Output read(Process process, long deadlineNanos)
            throws IOException, InterruptedException, TimeoutException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        long drainUntil = 0;
        try (InputStream input = process.getInputStream()) {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long now = System.nanoTime();
                if (now >= deadlineNanos) throw new TimeoutException("Android process check timed out");
                boolean alive = process.isAlive();
                if (!alive && drainUntil == 0) drainUntil = now + TimeUnit.MILLISECONDS.toNanos(250);
                if (!alive && now >= drainUntil) break;
                int available = input.available();
                if (available > 0) {
                    // Read one extra byte to detect the cap without allocating a growing line.
                    int capacity = Math.min(buffer.length, MAX_BYTES - bytes.size() + 1);
                    int count = input.read(buffer, 0, Math.min(available, capacity));
                    if (count < 0) break;
                    if (bytes.size() + count > MAX_BYTES)
                        throw new IOException("Android process check output exceeded 16 KiB");
                    bytes.write(buffer, 0, count);
                } else if (alive) {
                    process.waitFor(40, TimeUnit.MILLISECONDS);
                } else {
                    Thread.sleep(10);
                }
            }
        }
        return new Output(new String(bytes.toByteArray(), StandardCharsets.UTF_8), process.waitFor());
    }
}
