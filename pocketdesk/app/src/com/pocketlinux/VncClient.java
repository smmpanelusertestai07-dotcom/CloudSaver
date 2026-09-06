package com.pocketlinux;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class VncClient {
    interface Listener {
        void onConnected(int width, int height, String name);
        void onResize(int width, int height);
        void onRectangle(int x, int y, int width, int height, int[] pixels);
        /** Every rectangle of one framebuffer update has been delivered: put it on screen now. */
        void onUpdateComplete();
        /**
         * The pointer's shape, from the Cursor pseudo-encoding: width*height ARGB pixels, fully
         * transparent where the cursor's mask is clear, with the hotspot at (hotX, hotY). A
         * zero-sized cursor means "draw nothing". With this negotiated the server stops painting
         * its own arrow into the picture, and the viewer draws whatever suits the pointer mode.
         */
        void onCursor(int hotX, int hotY, int width, int height, int[] argb);
        void onClipboard(String text);
        void onDisconnected(String reason);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile int width;
    private volatile int height;
    private volatile boolean handshakeCompleted;
    private long connectedAtNanos;
    /** Set once the server advertises ExtendedDesktopSize, which is what allows live resizing. */
    private volatile boolean resizable;
    private volatile int screenId;
    /**
     * Input events are written by this thread, never by the caller. Android forbids network
     * writes on the main thread (NetworkOnMainThreadException), and every tap used to do exactly
     * that -- which is what kept ending the app the moment the desktop was touched.
     */
    private Thread sender;
    private final VncOutbox<WriteTask> outbox = new VncOutbox<>(512);
    private volatile boolean updatesPaused;
    // Guarded by writeLock. RFB requests are credits, not polls: an unchanged desktop may
    // leave one incremental request pending indefinitely. App switching must not add more.
    private boolean updatePending;
    private boolean readingFramebuffer;
    private boolean fullUpdateNeeded = true;

    private interface WriteTask { void write() throws IOException; }
    /** Reused across every update. A fresh multi-megabyte array per frame caused real
     *  OutOfMemoryError crashes on a 4 GB phone while apt was working in the background. */
    private int[] stripPixels;
    private byte[] rowBytes;
    private static final int STRIP_ROWS = 120;

    /**
     * The display server's private socket inside this app's storage, when there is one.
     *
     * Android does not keep loopback apart between apps, so a TCP port here could be opened by
     * any other app on the phone that holds the internet permission -- and this session has no
     * password. A unix socket in app-private storage cannot be opened by anyone else at all.
     * The port stays as a fallback for a container whose Xtigervnc is too old for it.
     */
    private final String socketPath;
    private volatile android.net.LocalSocket localSocket;

    VncClient(String host, int port, Listener listener) {
        this(host, port, null, listener);
    }

    VncClient(String host, int port, String socketPath, Listener listener) {
        this.host = host;
        this.port = port;
        this.socketPath = socketPath;
        this.listener = listener;
    }

    void connectAndRun() throws IOException {
        connectAndRun(20_000);
    }

    /** A server that accepts but never sends its greeting must not trap the viewer forever. */
    void connectAndRun(int handshakeTimeoutMs) throws IOException {
        if (handshakeTimeoutMs <= 0) throw new IllegalArgumentException("Positive handshake timeout required");
        try {
            if (closed.get()) throw new IOException("Viewer connection was closed");
            java.io.InputStream rawIn = null;
            java.io.OutputStream rawOut = null;
            if (socketPath != null && new java.io.File(socketPath).exists()) {
                android.net.LocalSocket local = new android.net.LocalSocket();
                localSocket = local;
                try {
                    local.connect(new android.net.LocalSocketAddress(socketPath,
                            android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                    local.setSoTimeout(handshakeTimeoutMs);
                    localSocket = local;
                    rawIn = local.getInputStream();
                    rawOut = local.getOutputStream();
                } catch (IOException notThere) {
                    // A socket file left behind by a session that died answers nobody. Fall through
                    // to the port rather than retrying this for ever.
                    try { local.close(); } catch (IOException ignored) {}
                    localSocket = null;
                }
            }
            if (rawIn == null) {
                if (closed.get()) throw new IOException("Viewer connection was closed");
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.connect(new InetSocketAddress(host, port), 3000);
                socket.setSoTimeout(handshakeTimeoutMs);
                rawIn = socket.getInputStream();
                rawOut = socket.getOutputStream();
            }
            input = new DataInputStream(new BufferedInputStream(rawIn, 256 * 1024));
            output = new DataOutputStream(new BufferedOutputStream(rawOut, 64 * 1024));
            if (closed.get()) throw new IOException("Viewer connection was closed");
            handshake();
            connectedAtNanos = System.nanoTime();
            handshakeCompleted = true;
            // A quiet desktop can legitimately send no updates for hours. Only the initial
            // handshake has a read timeout; never disconnect a healthy idle session.
            if (socket != null) socket.setSoTimeout(0);
            if (localSocket != null) localSocket.setSoTimeout(0);
            sender = new Thread(this::drainOutbox, "pocketdesk-vnc-sender");
            sender.setDaemon(true);
            sender.start();
            // fullUpdateNeeded starts true. A resume queued during the handshake may have
            // requested it already; do not queue a second full refresh in that race.
            requestUpdate(true);
            readMessages();
        } finally {
            close();
        }
    }

    private static void validateDesktopSize(int width, int height) throws IOException {
        // Check before posting a UI-thread bitmap allocation. Rectangle limits alone do not
        // protect ServerInit or resize events, which can arrive without any pixel payload.
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (long) width * height > 5_000_000L) {
            throw new IOException("Desktop screen size exceeds this viewer's memory limit");
        }
    }

    boolean hasConnected() { return handshakeCompleted; }
    long connectedMillis() {
        return handshakeCompleted ? (System.nanoTime() - connectedAtNanos) / 1_000_000L : 0L;
    }

    private void handshake() throws IOException {
        byte[] versionBytes = new byte[12];
        input.readFully(versionBytes);
        String serverVersion = new String(versionBytes, StandardCharsets.US_ASCII);
        if (!serverVersion.startsWith("RFB 003.")) throw new IOException("Unsupported RFB server");
        output.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();

        int count = input.readUnsignedByte();
        if (count == 0) throw new IOException(readFailureReason());
        boolean none = false;
        for (int i = 0; i < count; i++) if (input.readUnsignedByte() == 1) none = true;
        if (!none) throw new IOException("Local VNC server did not offer private no-auth mode");
        output.writeByte(1);
        output.flush();
        int securityResult = input.readInt();
        if (securityResult != 0) throw new IOException(readFailureReason());

        output.writeByte(1);
        output.flush();
        width = input.readUnsignedShort();
        height = input.readUnsignedShort();
        validateDesktopSize(width, height);
        byte[] originalPixelFormat = new byte[16];
        input.readFully(originalPixelFormat);
        int nameLength = input.readInt();
        if (nameLength < 0 || nameLength > 1024 * 1024) throw new IOException("Invalid server name");
        byte[] nameBytes = new byte[nameLength];
        input.readFully(nameBytes);
        String name = new String(nameBytes, StandardCharsets.UTF_8);
        setPixelFormat();
        setEncodings();
        listener.onConnected(width, height, name);
    }

    private String readFailureReason() throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 64 * 1024) return "VNC security negotiation failed";
        byte[] reason = new byte[length];
        input.readFully(reason);
        return new String(reason, StandardCharsets.UTF_8);
    }

    private void setPixelFormat() throws IOException {
        synchronized (writeLock) {
            output.writeByte(0);
            output.write(new byte[3]);
            output.writeByte(32);
            output.writeByte(24);
            output.writeByte(0);
            output.writeByte(1);
            output.writeShort(255);
            output.writeShort(255);
            output.writeShort(255);
            output.writeByte(16);
            output.writeByte(8);
            output.writeByte(0);
            output.write(new byte[3]);
            output.flush();
        }
    }

    private void setEncodings() throws IOException {
        synchronized (writeLock) {
            output.writeByte(2);
            output.writeByte(0);
            output.writeShort(5);
            output.writeInt(0);        // Raw
            output.writeInt(-239);     // Cursor: the pointer's shape comes to us, not into the picture
            output.writeInt(-223);     // DesktopSize
            output.writeInt(-224);     // LastRect
            output.writeInt(-308);     // ExtendedDesktopSize, needed to resize the desktop
            output.flush();
        }
    }

    private void readMessages() throws IOException {
        while (!closed.get()) {
            int type;
            try { type = input.readUnsignedByte(); }
            catch (EOFException end) { throw new IOException("Desktop connection closed"); }
            switch (type) {
                case 0: readFramebufferUpdate(); break;
                case 1: readColorMap(); break;
                case 2: break;
                case 3: readClipboard(); break;
                default: throw new IOException("Unsupported RFB message " + type);
            }
        }
    }

    private void readFramebufferUpdate() throws IOException {
        synchronized (writeLock) { readingFramebuffer = true; }
        input.readUnsignedByte();
        int rectangles = input.readUnsignedShort();
        for (int rectangle = 0; rectangle < rectangles; rectangle++) {
            int x = input.readUnsignedShort();
            int y = input.readUnsignedShort();
            int w = input.readUnsignedShort();
            int h = input.readUnsignedShort();
            int encoding = input.readInt();
            if (encoding == 0) {
                if (w <= 0 || h <= 0 || (long) w * h > 5_000_000L) throw new IOException("Invalid desktop rectangle");
                int stripCapacity = Math.min(h, STRIP_ROWS);
                if (stripPixels == null || stripPixels.length < w * stripCapacity) {
                    stripPixels = new int[w * stripCapacity];
                }
                if (rowBytes == null || rowBytes.length < w * 4) rowBytes = new byte[w * 4];
                for (int py = 0; py < h; ) {
                    int rows = Math.min(stripCapacity, h - py);
                    int index = 0;
                    for (int r = 0; r < rows; r++) {
                        input.readFully(rowBytes, 0, w * 4);
                        for (int px = 0; px < w; px++) {
                            int base = px * 4;
                            int blue = rowBytes[base] & 0xff;
                            int green = rowBytes[base + 1] & 0xff;
                            int red = rowBytes[base + 2] & 0xff;
                            stripPixels[index++] = 0xff000000 | (red << 16) | (green << 8) | blue;
                        }
                    }
                    // The listener copies the strip into its bitmap before returning, so the
                    // same array can be refilled for the next strip.
                    listener.onRectangle(x, y + py, w, rows, stripPixels);
                    py += rows;
                }
            } else if (encoding == -223) {
                validateDesktopSize(w, h);
                width = w;
                height = h;
                listener.onResize(w, h);
                requestUpdate(false);
            } else if (encoding == -308) {
                readExtendedDesktopSize(x, y, w, h);
            } else if (encoding == -239) {
                readCursor(x, y, w, h);
            } else if (encoding == -224) {
                break;
            } else {
                throw new IOException("Unsupported desktop encoding " + encoding);
            }
        }
        listener.onUpdateComplete();
        synchronized (writeLock) {
            readingFramebuffer = false;
            updatePending = false;
        }
        requestUpdate(true);
    }

    /**
     * A Cursor pseudo-rectangle: the hotspot rides in x and y, then width*height pixels in our
     * own pixel format, then a one-bit-per-pixel mask, rows padded to whole bytes, most
     * significant bit first, a set bit meaning the pixel is part of the cursor.
     */
    private void readCursor(int hotX, int hotY, int w, int h) throws IOException {
        if (w < 0 || h < 0 || w > 256 || h > 256) throw new IOException("Invalid cursor size");
        int count = w * h;
        if (count == 0) {
            listener.onCursor(0, 0, 0, 0, null);
            return;
        }
        byte[] pixels = new byte[count * 4];
        input.readFully(pixels);
        int maskRow = (w + 7) / 8;
        byte[] mask = new byte[maskRow * h];
        input.readFully(mask);
        int[] argb = new int[count];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean opaque = (mask[y * maskRow + x / 8] & (0x80 >> (x % 8))) != 0;
                int base = (y * w + x) * 4;
                int blue = pixels[base] & 0xff;
                int green = pixels[base + 1] & 0xff;
                int red = pixels[base + 2] & 0xff;
                argb[y * w + x] = opaque ? 0xff000000 | (red << 16) | (green << 8) | blue : 0;
            }
        }
        listener.onCursor(hotX, hotY, w, h, argb);
    }

    /**
     * The server's answer about the desktop size. Its "x" and "y" carry the reason and status of
     * the change rather than a position, which is how the RFB extension is specified.
     */
    private void readExtendedDesktopSize(int reason, int status, int newWidth, int newHeight)
            throws IOException {
        int screens = input.readUnsignedByte();
        readExactly(3);
        int firstId = 0;
        for (int i = 0; i < screens; i++) {
            int id = input.readInt();
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readInt();
            if (i == 0) firstId = id;
        }
        resizable = true;
        if (screens > 0) screenId = firstId;
        if (reason == 1 && status != 0) return;          // our request was refused
        if (newWidth <= 0 || newHeight <= 0) return;
        validateDesktopSize(newWidth, newHeight);
        if (newWidth == width && newHeight == height) return;
        width = newWidth;
        height = newHeight;
        listener.onResize(newWidth, newHeight);
        requestUpdate(false);
    }

    private void readExactly(int count) throws IOException {
        byte[] discard = new byte[count];
        input.readFully(discard);
    }

    boolean isResizable() { return resizable; }

    /** Asks the desktop to become this size, so it can match the phone after a rotation. */
    void requestDesktopSize(int newWidth, int newHeight) {
        if (!resizable || newWidth <= 0 || newHeight <= 0) return;
        final int requestedWidth = newWidth;
        final int requestedHeight = newHeight;
        enqueue(() -> {
            synchronized (writeLock) {
                output.writeByte(251);
                output.writeByte(0);
                output.writeShort(requestedWidth);
                output.writeShort(requestedHeight);
                output.writeByte(1);
                output.writeByte(0);
                output.writeInt(screenId);
                output.writeShort(0);
                output.writeShort(0);
                output.writeShort(requestedWidth);
                output.writeShort(requestedHeight);
                output.writeInt(0);
                output.flush();
            }
        });
    }

    private void readColorMap() throws IOException {
        input.readUnsignedByte();
        input.readUnsignedShort();
        int colors = input.readUnsignedShort();
        long bytes = colors * 6L;
        while (bytes > 0) {
            int skipped = input.skipBytes((int) Math.min(bytes, 8192));
            if (skipped <= 0) throw new EOFException("Truncated color map");
            bytes -= skipped;
        }
    }

    private void readClipboard() throws IOException {
        input.skipBytes(3);
        int length = input.readInt();
        if (length < 0 || length > 4 * 1024 * 1024) throw new IOException("Invalid clipboard size");
        byte[] value = new byte[length];
        input.readFully(value);
        listener.onClipboard(new String(value, StandardCharsets.ISO_8859_1));
    }

    void requestUpdate(boolean incremental) throws IOException {
        synchronized (writeLock) {
            // A resize can arrive while hidden or among several rectangles. Remember that
            // its newly allocated buffers need a full frame, but wait for this update to end
            // so the request uses the final dimensions and cannot race the remaining pixels.
            if (!incremental) fullUpdateNeeded = true;
            if (closed.get() || output == null || !handshakeCompleted || updatesPaused
                    || updatePending || readingFramebuffer) return;
            output.writeByte(3);
            output.writeByte(fullUpdateNeeded ? 0 : 1);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(width);
            output.writeShort(height);
            output.flush();
            updatePending = true;
            fullUpdateNeeded = false;
        }
    }

    /** Pause pixel requests while the viewer is hidden; the Linux session stays running.
     * One requested frame may finish. Its pixels are retained, so resume only needs changes
     * accumulated by the server; the first frame and resize still request all pixels. */
    void setUpdatesPaused(boolean paused) {
        boolean wasPaused = updatesPaused;
        updatesPaused = paused;
        if (wasPaused && !paused) enqueue(() -> requestUpdate(true));
    }

    private void drainOutbox() {
        try {
            while (!closed.get()) {
                WriteTask task = outbox.poll(500);
                if (task == null) continue;
                try {
                    task.write();
                } catch (IOException error) {
                    close();
                    return;
                }
            }
        } catch (InterruptedException ended) {
            Thread.currentThread().interrupt();
        }
    }

    /** Never silently lose a key or button release. A stalled connection with 512 discrete
     * commands closes instead of retaining unbounded input or blocking Android's UI. */
    private void enqueue(WriteTask task) {
        if (closed.get() || output == null) return;
        if (!outbox.offer(task)) close();
    }

    void sendPointer(int x, int y, int buttonMask) {
        final int pointerX = clamp(x, 0, Math.max(0, width - 1));
        final int pointerY = clamp(y, 0, Math.max(0, height - 1));
        if (closed.get() || output == null) return;
        WriteTask task = () -> {
            synchronized (writeLock) {
                output.writeByte(5);
                output.writeByte(buttonMask & 0xff);
                output.writeShort(pointerX);
                output.writeShort(pointerY);
                output.flush();
            }
        };
        if (!outbox.offerPointer(task, buttonMask)) close();
    }

    void sendKey(int keysym, boolean down) {
        enqueue(() -> {
            synchronized (writeLock) {
                output.writeByte(4);
                output.writeByte(down ? 1 : 0);
                output.writeShort(0);
                output.writeInt(keysym);
                output.flush();
            }
        });
    }

    void typeCodePoint(int codePoint) {
        final int keysym = codePoint <= 0xff ? codePoint : 0x01000000 | codePoint;
        enqueue(() -> {
            synchronized (writeLock) {
                writeKeyPair(keysym);
                output.flush();
            }
        });
    }

    private void writeKeyPair(int keysym) throws IOException {
        for (int down = 1; down >= 0; down--) {
            output.writeByte(4);
            output.writeByte(down);
            output.writeShort(0);
            output.writeInt(keysym);
        }
    }

    /** One IME replacement is one queued command, even for a long pasted prompt. Flush
     * bounded chunks, allowing framebuffer requests and close between them. */
    void replaceText(int backspaces, int deletes, String text) {
        final int before = Math.max(0, backspaces);
        final int after = Math.max(0, deletes);
        final String value = text == null ? "" : text;
        if (before == 0 && after == 0 && value.isEmpty()) return;
        enqueue(() -> {
            int remainingBefore = before, remainingAfter = after, offset = 0;
            while (!closed.get() && (remainingBefore > 0 || remainingAfter > 0 || offset < value.length())) {
                synchronized (writeLock) {
                    for (int sent = 0; sent < 128; sent++) {
                        int keysym;
                        if (remainingBefore > 0) { remainingBefore--; keysym = 0xff08; }
                        else if (remainingAfter > 0) { remainingAfter--; keysym = 0xffff; }
                        else if (offset < value.length()) {
                            int point = value.codePointAt(offset);
                            offset += Character.charCount(point);
                            keysym = point == '\n' ? 0xff0d : point <= 0xff ? point : 0x01000000 | point;
                        } else break;
                        writeKeyPair(keysym);
                    }
                    output.flush();
                }
            }
        });
    }

    void sendClipboard(String text) {
        if (text == null) return;
        final byte[] value = text.getBytes(StandardCharsets.ISO_8859_1);
        enqueue(() -> {
            synchronized (writeLock) {
                output.writeByte(6);
                output.write(new byte[3]);
                output.writeInt(value.length);
                output.write(value);
                output.flush();
            }
        });
    }

    int getWidth() { return width; }
    int getHeight() { return height; }

    void close() {
        // A close can race connection setup. Always close a socket published after an earlier
        // close call; returning merely because the flag is set leaks that late socket.
        closed.set(true);
        outbox.clear();
        Thread activeSender = sender;
        if (activeSender != null) activeSender.interrupt();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        try { if (localSocket != null) localSocket.close(); } catch (IOException ignored) {}
    }

    static boolean canConnect(String host, int port, int timeoutMs) {
        try (Socket test = new Socket()) {
            test.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    /** True once the desktop's private socket answers -- the same question, for the new path. */
    static boolean canConnect(String socketPath) {
        if (socketPath == null || !new java.io.File(socketPath).exists()) return false;
        android.net.LocalSocket test = new android.net.LocalSocket();
        try {
            test.connect(new android.net.LocalSocketAddress(socketPath,
                    android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (IOException ignored) {
            return false;
        } finally {
            try { test.close(); } catch (IOException ignored) {}
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
