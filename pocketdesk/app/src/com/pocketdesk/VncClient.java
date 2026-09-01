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
            output.writeShort(3);
            output.writeInt(0);
            output.writeInt(-223);
            output.writeInt(-224);
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
                int[] pixels = new int[w * h];
                byte[] row = new byte[w * 4];
                int index = 0;
                for (int py = 0; py < h; py++) {
                    input.readFully(row);
                    for (int px = 0; px < w; px++) {
                        int base = px * 4;
                        int blue = row[base] & 0xff;
                        int green = row[base + 1] & 0xff;
                        int red = row[base + 2] & 0xff;
                        pixels[index++] = 0xff000000 | (red << 16) | (green << 8) | blue;
                    }
                }
                listener.onRectangle(x, y, w, h, pixels);
            } else if (encoding == -223) {
                width = w;
                height = h;
                listener.onResize(w, h);
            } else if (encoding == -224) {
                break;
            } else {
                throw new IOException("Unsupported desktop encoding " + encoding);
            }
        }
        requestUpdate(true);
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

    void sendPointer(int x, int y, int buttonMask) {
        if (closed.get() || output == null) return;
        try {
            synchronized (writeLock) {
                output.writeByte(5);
                output.writeByte(buttonMask & 0xff);
                output.writeShort(clamp(x, 0, Math.max(0, width - 1)));
                output.writeShort(clamp(y, 0, Math.max(0, height - 1)));
                output.flush();
            }
        } catch (IOException error) { close(); }
    }

    void sendKey(int keysym, boolean down) {
        if (closed.get() || output == null) return;
        try {
            synchronized (writeLock) {
                output.writeByte(4);
                output.writeByte(down ? 1 : 0);
                output.writeShort(0);
                output.writeInt(keysym);
                output.flush();
            }
        } catch (IOException error) { close(); }
    }

    void typeCodePoint(int codePoint) {
        int keysym = codePoint <= 0xff ? codePoint : 0x01000000 | codePoint;
        sendKey(keysym, true);
        sendKey(keysym, false);
    }

    void sendClipboard(String text) {
        if (closed.get() || output == null || text == null) return;
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        try {
            synchronized (writeLock) {
                output.writeByte(6);
                output.write(new byte[3]);
                output.writeInt(value.length);
                output.write(value);
                output.flush();
            }
        } catch (IOException error) { close(); }
    }

    int getWidth() { return width; }
    int getHeight() { return height; }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
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
