package com.pocketlinux;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Reads a process pipe without waiting forever for an inherited writer to close it. */
final class ProcessOutput {
    interface LineListener { void line(String line) throws IOException; }

    private static final long DRAIN_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int MAX_LINE_BYTES = 256 * 1024;

    private ProcessOutput() {}

    /**
     * The direct process determines task completion, not EOF on its stdout: a surviving child
     * can retain the write end after a PRoot tracer exits. Only read bytes the process pipe
     * reports as available, then allow a short final drain. Callbacks stay on the caller's
     * worker thread and no extra reader thread is left waiting in readLine after cancellation.
     * The caller owns stopping a still-live process when this method throws.
     */
    static int consume(Process process, LineListener listener) throws IOException, InterruptedException {
        Lines lines = new Lines(listener);
        byte[] buffer = new byte[8192];
        long drainUntil = 0L;
        try (InputStream input = process.getInputStream()) {
            while (true) {
                checkInterrupted();
                boolean alive = process.isAlive();
                if (!alive && drainUntil == 0L) drainUntil = System.nanoTime() + DRAIN_NANOS;
                if (!alive && System.nanoTime() >= drainUntil) break;

                int available = input.available();
                if (available > 0) {
                    // Process pipes have a real available-byte count. Reading no more than
                    // that count avoids a blocking read when an orphan still owns stdout.
                    int count = input.read(buffer, 0, Math.min(buffer.length, available));
                    if (count < 0) break;
                    lines.accept(buffer, count);
                    continue;
                }
                if (alive) process.waitFor(40, TimeUnit.MILLISECONDS);
                else Thread.sleep(10);
            }
            lines.finish();
        }
        checkInterrupted();
        return process.waitFor();
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Process output cancelled");
    }

    private static final class Lines {
        private final LineListener listener;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private boolean afterCarriageReturn;

        Lines(LineListener listener) { this.listener = listener; }

        void accept(byte[] bytes, int count) throws IOException, InterruptedException {
            for (int i = 0; i < count; i++) {
                int value = bytes[i] & 0xff;
                if (value == '\r') {
                    emit();
                    afterCarriageReturn = true;
                } else if (value == '\n') {
                    if (!afterCarriageReturn) emit();
                    afterCarriageReturn = false;
                } else {
                    afterCarriageReturn = false;
                    pending.write(value);
                    if (pending.size() >= MAX_LINE_BYTES) emitChunk();
                }
            }
        }

        private void deliver(String line) throws IOException, InterruptedException {
            checkInterrupted();
            if (listener != null) listener.line(line);
        }

        private void emit() throws IOException, InterruptedException {
            String line = new String(pending.toByteArray(), StandardCharsets.UTF_8);
            pending.reset();
            deliver(line);
        }

        private void emitChunk() throws IOException, InterruptedException {
            // A damaged app can print an enormous line. Bound its memory without splitting a
            // UTF-8 character: retain the final few bytes for the next chunk or newline.
            byte[] bytes = pending.toByteArray();
            int end = bytes.length - 4;
            while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
            pending.reset();
            pending.write(bytes, end, bytes.length - end);
            deliver(new String(bytes, 0, end, StandardCharsets.UTF_8));
        }

        void finish() throws IOException, InterruptedException {
            if (pending.size() > 0) emit();
        }
    }
}
