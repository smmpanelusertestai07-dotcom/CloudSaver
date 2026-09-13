package com.pocketagent.mobile;

import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real multi-MiB JSON, framing recovery and lifecycle/backpressure regressions. */
public final class AgentFrameReaderTest {
    private static int assertions;
    private static File directory;
    public static void main(String[] args) throws Exception {
        directory = Files.createTempDirectory("pocketagent-frame-test-").toFile();
        try {
            largeImageAndFollowingPacket();
            newlineAndUtf8Boundaries();
            boundedOversizedDrain();
            invalidUtf8AndNextFrame();
            pathologicalNesting();
            cancellationAndStorageFailure();
            handoffBackpressureAndShutdown();
            check(emptyDirectory(), "All temporary frames must be removed");
            System.out.println("PASS AgentFrameReaderTest (" + assertions + " assertions)");
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }

    private static void largeImageAndFollowingPacket() throws Exception {
        byte[] image = new byte[3 * 1024 * 1024 + 13];
        for (int i = 0; i < image.length; i++) image[i] = (byte) (i * 31 + 7);
        String encoded = Base64.getEncoder().encodeToString(image);
        String json = "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"thread-original\","
                + "\"turnId\":\"turn-original\",\"item\":{\"type\":\"mcpToolCall\",\"id\":\"item-1\","
                + "\"result\":{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + encoded + "\"}]}}}}";
        byte[] input = (json + "\n{\"id\":71,\"result\":{\"next\":true}}\n").getBytes(StandardCharsets.UTF_8);
        check(json.length() > 2 * 1024 * 1024, "Fixture must cross former fatal 2 MiB bound");
        try (AgentFrameReader reader = new AgentFrameReader(new ByteArrayInputStream(input), directory)) {
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                check(!frame.oversized, "A normal multi-MiB image is supported");
                check(frame.spooled(), "Encoded image bytes must be disk backed");
                check(directory.list().length == 1, "Only this temporary file exists");
                JSONObject packet = new JSONObject(frame.readUtf8());
                JSONObject params = packet.getJSONObject("params");
                check(params.getString("threadId").equals("thread-original"), "Thread guard input is intact");
                check(params.getString("turnId").equals("turn-original"), "Turn guard input is intact");
                String actual = params.getJSONObject("item").getJSONObject("result")
                        .getJSONArray("content").getJSONObject(0).getString("data");
                check(actual.equals(encoded), "Full image payload survives framing");
                check(Base64.getDecoder().decode(actual).length == image.length, "Image decoded size is unchanged");
            }
            check(emptyDirectory(), "Decoded frame is removed immediately");
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                JSONObject next = new JSONObject(frame.readUtf8());
                check(next.getInt("id") == 71 && next.getJSONObject("result").getBoolean("next"), "RPC after large image survives");
                check(!frame.spooled(), "Small metadata stays in bounded memory");
            }
            check(reader.next(() -> true) == null, "Clean EOF");
        }
    }

    private static void newlineAndUtf8Boundaries() throws Exception {
        String text = "{\"id\":\"request-नमस्ते-🙂\",\"result\":{\"text\":\"café\\nline\"}}";
        byte[] bytes = ("\n" + text + "\r\n" + text).getBytes(StandardCharsets.UTF_8);
        InputStream tinyChunks = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 1));
            }
        };
        try (AgentFrameReader reader = new AgentFrameReader(tinyChunks, directory, 4096, 17)) {
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) { check(frame.readUtf8().isEmpty(), "Blank frame"); }
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                check(frame.readUtf8().equals(text + "\r"), "CRLF and split UTF-8 bytes preserve text");
                check(new JSONObject(frame.readUtf8()).getString("id").equals("request-नमस्ते-🙂"), "Unicode request ID remains exact");
            }
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                check(frame.readUtf8().equals(text), "Final frame without newline is delivered");
            }
            check(reader.next(() -> true) == null, "EOF without newline does not repeat frame");
        }
    }

    private static void boundedOversizedDrain() throws Exception {
        byte[] exact = new byte[1024]; java.util.Arrays.fill(exact, (byte) 'a');
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(exact); data.write('\n'); data.write(exact); data.write('b'); data.write('\n'); data.write("{\"id\":9}\n".getBytes(StandardCharsets.UTF_8));
        try (AgentFrameReader reader = new AgentFrameReader(new ByteArrayInputStream(data.toByteArray()), directory, 1024, 16)) {
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                check(!frame.oversized && frame.length == 1024, "Exact maximum is accepted");
                check(frame.readUtf8().length() == 1024, "No truncation at limit");
            }
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                check(frame.oversized, "Maximum plus one is gracefully skipped");
                check(!frame.spooled() && emptyDirectory(), "Rejected bytes do not remain on disk");
                boolean refused = false; try { frame.readUtf8(); } catch (IOException expected) { refused = true; }
                check(refused, "Oversized frame cannot become a partial valid JSON packet");
            }
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                check(new JSONObject(frame.readUtf8()).getInt("id") == 9, "The next complete frame still parses");
            }
        }
        // 64 MiB streamed from a tiny generator: no fixture allocation can mask reader retention.
        InputStream enormous = new InputStream() {
            int remaining = 64 * 1024 * 1024; boolean newline;
            @Override public int read() {
                if (remaining-- > 0) return 'x';
                if (!newline) { newline = true; return '\n'; }
                return -1;
            }
            @Override public int read(byte[] out, int offset, int length) {
                if (remaining <= 0) { int c = read(); if (c < 0) return -1; out[offset] = (byte) c; return 1; }
                int n = Math.min(length, remaining); java.util.Arrays.fill(out, offset, offset + n, (byte) 'x'); remaining -= n; return n;
            }
        };
        try (AgentFrameReader reader = new AgentFrameReader(enormous, directory, 1024, 16)) {
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                check(frame.oversized && frame.length <= 1024 && emptyDirectory(), "Runaway output is drained with bounded storage");
            }
            check(reader.next(() -> true) == null, "Runaway frame drain reaches EOF");
        }
    }

    private static void invalidUtf8AndNextFrame() throws Exception {
        byte[] corrupt = {'{', '"', 'i', 'd', '"', ':', '"', (byte) 0xc3, '(', '"', '}', '\n', '{', '}', '\n'};
        try (AgentFrameReader reader = new AgentFrameReader(new ByteArrayInputStream(corrupt), directory)) {
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                boolean refused = false;
                try { frame.readUtf8(); } catch (CharacterCodingException expected) { refused = true; }
                check(refused, "Malformed UTF-8 is never silently replaced");
            }
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) { check(frame.readUtf8().equals("{}"), "UTF-8 error does not consume next packet"); }
        }
    }

    private static void cancellationAndStorageFailure() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        InputStream cancelled = new InputStream() {
            int reads;
            @Override public int read() { return 'x'; }
            @Override public int read(byte[] out, int offset, int length) {
                java.util.Arrays.fill(out, offset, offset + length, (byte) 'x');
                if (++reads == 3) active.set(false);
                return length;
            }
        };
        try (AgentFrameReader reader = new AgentFrameReader(cancelled, directory, 65536, 16)) {
            check(reader.next(active::get) == null, "Cancelled partial frame is not delivered");
            check(emptyDirectory(), "Cancellation deletes spooled partial frame");
        }
        InputStream failed = new InputStream() {
            int reads;
            @Override public int read() throws IOException { throw new IOException("fixture"); }
            @Override public int read(byte[] out, int offset, int length) throws IOException {
                if (++reads == 2) throw new IOException("fixture");
                java.util.Arrays.fill(out, offset, offset + length, (byte) 'x'); return length;
            }
        };
        try (AgentFrameReader reader = new AgentFrameReader(failed, directory, 65536, 16)) {
            boolean failedCleanly = false;
            try { reader.next(() -> true); } catch (IOException expected) { failedCleanly = true; }
            check(failedCleanly && emptyDirectory(), "Read failure also cleans private partial data");
        }
    }

    private static void pathologicalNesting() throws Exception {
        String deep = new String(new char[AgentFrameReader.MAX_NESTING + 1]).replace('\0', '[');
        String quoted = "{\"text\":\"escaped \\\" " + deep + "\"}";
        try (AgentFrameReader reader = new AgentFrameReader(new ByteArrayInputStream((deep + "\n" + quoted + "\n{}\n").getBytes(StandardCharsets.UTF_8)), directory)) {
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                boolean rejected = false; try { frame.readUtf8(); } catch (IOException expected) { rejected = true; }
                check(rejected, "Pathological JSON cannot overflow the Android parser stack");
            }
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                check(new JSONObject(frame.readUtf8()).getString("text").endsWith(deep), "Braces inside quoted text are not structural depth");
            }
            try (AgentFrameReader.Frame frame = reader.next(() -> true)) {
                check(frame.readUtf8().equals("{}"), "Malformed structure cannot lose the following frame");
            }
        }
    }

    private static void handoffBackpressureAndShutdown() throws Exception {
        ExecutorService reading = Executors.newSingleThreadExecutor();
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        CountDownLatch queuedOne = new CountDownLatch(1);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger received = new AtomicInteger();
        byte[] bytes = ("{\"value\":\"" + new String(new char[5000]).replace('\0', 'a') + "\"}\n{}\n").getBytes(StandardCharsets.UTF_8);
        try (AgentFrameReader reader = new AgentFrameReader(new ByteArrayInputStream(bytes), directory, 8192, 16)) {
            Future<Boolean> blocked = reading.submit(() -> {
                AgentFrameReader.Frame first = reader.next(active::get);
                boolean alive = AgentFrameReader.deliver(first, task -> { synchronized (queued) { queued.add(task); } queuedOne.countDown(); }, active::get, frame -> received.incrementAndGet());
                if (alive) { try (AgentFrameReader.Frame second = reader.next(active::get)) { received.incrementAndGet(); } }
                return alive;
            });
            check(queuedOne.await(3, TimeUnit.SECONDS), "Worker handoff is queued");
            check(!blocked.isDone() && received.get() == 0 && directory.list().length == 1, "Reader cannot race ahead or accumulate large frames");
            active.set(false);
            check(!blocked.get(3, TimeUnit.SECONDS), "Closing process cancels queued handoff");
            check(emptyDirectory(), "Cancelled queued frame is deleted even if executor never runs it");
            synchronized (queued) { queued.remove().run(); }
            check(received.get() == 0, "Late callback cannot deliver an old process frame");
        } finally { reading.shutdownNow(); }

        try (AgentFrameReader reader = new AgentFrameReader(new ByteArrayInputStream(bytes), directory, 8192, 16)) {
            boolean delivered = AgentFrameReader.deliver(reader.next(() -> true), task -> { throw new RejectedExecutionException(); }, () -> true, frame -> received.incrementAndGet());
            check(!delivered && emptyDirectory(), "Rejected executor cleans its frame");
        }
        try (AgentFrameReader reader = new AgentFrameReader(new ByteArrayInputStream("{}\n{}".getBytes(StandardCharsets.UTF_8)), directory)) {
            check(AgentFrameReader.deliver(reader.next(() -> true), Runnable::run, () -> true, frame -> received.incrementAndGet()), "Immediate worker delivery succeeds");
            check(received.get() == 1, "Valid packet is delivered exactly once");
            try (AgentFrameReader.Frame next = reader.next(() -> true)) { check(next.readUtf8().equals("{}"), "Next frame follows successful handoff"); }
        }
    }

    private static boolean emptyDirectory() { String[] files = directory.list(); return files != null && files.length == 0; }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
