package com.pocketdesk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class VncClient {
    interface Listener {
        void onConnected(int width, int height, String name);
        void onResize(int width, int height);
        void onRectangle(int x, int y, int width, int height, int[] pixels);
        void onClipboard(String text);
        void onDisconnected(String reason);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile int width;
    private volatile int height;
    /** Set once the server advertises ExtendedDesktopSize, which is what allows live resizing. */
    private volatile boolean resizable;
    private volatile int screenId;
    /**
     * Input events are written by this thread, never by the caller. Android forbids network
     * writes on the main thread (NetworkOnMainThreadException), and every tap used to do exactly
     * that -- which is what kept ending the app the moment the desktop was touched.
     */
    private Thread sender;
    private final LinkedBlockingQueue<WriteTask> outbox = new LinkedBlockingQueue<>(512);

    private interface WriteTask { void write() throws IOException; }
    /** Reused across every update. A fresh multi-megabyte array per frame caused real
     *  OutOfMemoryError crashes on a 4 GB phone while apt was working in the background. */
    private int[] stripPixels;
    private byte[] rowBytes;
    private static final int STRIP_ROWS = 120;

    VncClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    void connectAndRun() throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(host, port), 3000);
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 256 * 1024));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
        handshake();
        sender = new Thread(this::drainOutbox, "pocketdesk-vnc-sender");
        sender.setDaemon(true);
        sender.start();
        requestUpdate(false);
        try {
            readMessages();
        } finally {
            close();
        }
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
            output.writeShort(4);
            output.writeInt(0);        // Raw
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
                width = w;
                height = h;
                listener.onResize(w, h);
                requestUpdate(false);
            } else if (encoding == -308) {
                readExtendedDesktopSize(x, y, w, h);
            } else if (encoding == -224) {
                break;
            } else {
                throw new IOException("Unsupported desktop encoding " + encoding);
            }
        }
        requestUpdate(true);
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
        listener.onClipboard(new String(value, StandardCharsets.UTF_8));
    }

    void requestUpdate(boolean incremental) throws IOException {
        if (closed.get() || output == null) return;
        synchronized (writeLock) {
            output.writeByte(3);
            output.writeByte(incremental ? 1 : 0);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(width);
            output.writeShort(height);
            output.flush();
        }
    }

    private void drainOutbox() {
        try {
            while (!closed.get()) {
                WriteTask task = outbox.poll(500, TimeUnit.MILLISECONDS);
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

    /** Full queue means a flood of pointer moves; dropping one is harmless, blocking the UI is not. */
    private void enqueue(WriteTask task) {
        if (closed.get() || output == null) return;
        outbox.offer(task);
    }

    void sendPointer(int x, int y, int buttonMask) {
        final int pointerX = clamp(x, 0, Math.max(0, width - 1));
        final int pointerY = clamp(y, 0, Math.max(0, height - 1));
        enqueue(() -> {
            synchronized (writeLock) {
                output.writeByte(5);
                output.writeByte(buttonMask & 0xff);
                output.writeShort(pointerX);
                output.writeShort(pointerY);
                output.flush();
            }
        });
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
        int keysym = codePoint <= 0xff ? codePoint : 0x01000000 | codePoint;
        sendKey(keysym, true);
        sendKey(keysym, false);
    }

    void sendClipboard(String text) {
        if (text == null) return;
        final byte[] value = text.getBytes(StandardCharsets.UTF_8);
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
        if (!closed.compareAndSet(false, true)) return;
        Thread activeSender = sender;
        if (activeSender != null) activeSender.interrupt();
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    static boolean canConnect(String host, int port, int timeoutMs) {
        try (Socket test = new Socket()) {
            test.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
