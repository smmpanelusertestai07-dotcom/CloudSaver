package com.pocketagent.mobile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Newline-delimited official engine transport. Large JSON stays in a temporary private file
 * until the serialized protocol worker is ready; it never enters an Android Intent or history.
 * A hard bound protects a phone from runaway output. Reaching it drains only that frame.
 */
final class AgentFrameReader implements Closeable {
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    static final int MEMORY_BYTES = 256 * 1024;
    static final int MAX_NESTING = 128;
    private final InputStream input;
    private final File directory;
    private final int limit, memoryLimit;
    private final byte[] buffer = new byte[8192];
    private int position, available;

    AgentFrameReader(InputStream input, File directory) {
        this(input, directory, MAX_FRAME_BYTES, MEMORY_BYTES);
    }

    AgentFrameReader(InputStream input, File directory, int limit, int memoryLimit) {
        if (input == null || directory == null || limit < 1 || memoryLimit < 1 || memoryLimit > limit)
            throw new IllegalArgumentException("Invalid frame reader configuration");
        this.input = input; this.directory = directory; this.limit = limit; this.memoryLimit = memoryLimit;
    }

    /** Returns null on EOF/cancellation. Caller closes every returned frame. */
    Frame next(BooleanSupplier active) throws IOException {
        if (available < 0) return null;
        ByteArrayOutputStream memory = new ByteArrayOutputStream(Math.min(4096, memoryLimit));
        File file = null;
        FileOutputStream output = null;
        int length = 0;
        boolean oversized = false, transferred = false;
        try {
            while (active.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                if (position == available) {
                    available = input.read(buffer); position = 0;
                    if (available < 0) {
                        if (length == 0 && !oversized) return null;
                        if (output != null) { output.close(); output = null; }
                        Frame frame = new Frame(file, file == null && !oversized ? memory.toByteArray() : null, length, oversized);
                        transferred = true; return frame;
                    }
                    if (available == 0) continue;
                }
                int end = position;
                while (end < available && buffer[end] != '\n') end++;
                int count = end - position;
                if (!oversized && count > limit - length) {
                    oversized = true;
                    if (output != null) { output.close(); output = null; }
                    if (file != null) { file.delete(); file = null; }
                    memory = null;
                }
                if (!oversized && count > 0) {
                    if (output == null && length + count > memoryLimit) {
                        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
                            throw new IOException("Cannot prepare private agent output storage.");
                        file = File.createTempFile("frame-", ".json", directory);
                        output = new FileOutputStream(file);
                        memory.writeTo(output); memory = null;
                    }
                    if (output == null) memory.write(buffer, position, count);
                    else output.write(buffer, position, count);
                    length += count;
                }
                position = end;
                if (end < available) {
                    position++;
                    if (output != null) { output.close(); output = null; }
                    Frame frame = new Frame(file, file == null && !oversized ? memory.toByteArray() : null, length, oversized);
                    transferred = true; return frame;
                }
            }
            return null;
        } finally {
            if (output != null) try { output.close(); } catch (IOException ignored) {}
            if (!transferred && file != null) file.delete();
        }
    }

    /**
     * Backpressure: a reader cannot enqueue the next frame until this one is consumed.
     * Cancellation/rejected shutdown cleans up even when an executor drops queued work.
     */
    static boolean deliver(Frame frame, Executor executor, BooleanSupplier active, Consumer<Frame> handler) {
        CountDownLatch completed = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                try { if (active.getAsBoolean()) handler.accept(frame); }
                finally { frame.close(); completed.countDown(); }
            });
        } catch (RejectedExecutionException stopped) { frame.close(); return false; }
        try {
            while (!completed.await(100, TimeUnit.MILLISECONDS)) {
                if (!active.getAsBoolean()) { frame.close(); return false; }
            }
            return active.getAsBoolean();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt(); frame.close(); return false;
        }
    }

    @Override public void close() throws IOException { input.close(); }

    static final class Frame implements Closeable {
        final int length;
        final boolean oversized;
        private File file;
        private byte[] bytes;
        private boolean closed;

        private Frame(File file, byte[] bytes, int length, boolean oversized) {
            this.file = file; this.bytes = bytes; this.length = length; this.oversized = oversized;
        }

        synchronized boolean spooled() { return file != null; }

        /** No replacement characters: corrupt UTF-8 must never silently alter an RPC ID/path. */
        synchronized String readUtf8() throws IOException {
            if (closed || oversized) throw new IOException("Agent output is unavailable.");
            InputStream source = file == null ? new ByteArrayInputStream(bytes) : new FileInputStream(file);
            try (InputStreamReader reader = new InputStreamReader(source, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
                StringBuilder text = new StringBuilder(length);
                char[] block = new char[8192]; int count;
                boolean quoted = false, escaped = false; int depth = 0;
                while ((count = reader.read(block)) != -1) {
                    // Android's JSON parser is recursive. Reject pathological nesting before
                    // parsing, while ignoring braces inside strings (including escaped quotes).
                    for (int i = 0; i < count; i++) {
                        char value = block[i];
                        if (quoted) {
                            if (escaped) escaped = false;
                            else if (value == '\\') escaped = true;
                            else if (value == '"') quoted = false;
                        } else if (value == '"') quoted = true;
                        else if (value == '{' || value == '[') {
                            if (++depth > MAX_NESTING) throw new IOException("Agent output is too deeply nested.");
                        } else if (value == '}' || value == ']') depth = Math.max(0, depth - 1);
                    }
                    text.append(block, 0, count);
                }
                return text.toString();
            }
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true; bytes = null;
            if (file != null) { file.delete(); file = null; }
        }
    }
}
