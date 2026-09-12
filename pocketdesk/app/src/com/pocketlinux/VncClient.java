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
        /** A block already on screen, moved: the whole of a scroll, in six bytes. */
        void onCopyRect(int sourceX, int sourceY, int x, int y, int width, int height);
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
    /**
     * Volatile because it is published by the network thread and then read by the UI thread on
     * every tap and key: enqueue and sendPointer both test it for null before queueing. It was
     * the one cross-thread field in this class that was not.
     */
    private volatile DataOutputStream output;
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
     * How many bytes one pixel takes on the wire: 4 for full colour, 2 for 16-bit 5-6-5. Fixed
     * at the handshake, because it is what the server was told and every decoder below reads it.
     */
    private int wirePixelBytes = 4;
    /** Set by the viewer before connecting, when its own framebuffer only keeps 16-bit colour. */
    private volatile boolean lowColour;

    /**
     * ZRLE's zlib stream and the window its tiles are parsed out of.
     *
     * One stream serves the whole connection, which is how the encoding is specified, so it is
     * created once, never reset between rectangles, and rectangles have to be decoded in the
     * order they arrive. The window is deliberately small: a full screen of unpacked tiles would
     * be megabytes, and nothing here needs more than the bytes it is reading right now.
     */
    private java.util.zip.Inflater inflater;
    private byte[] zrleCompressed;
    private byte[] zrleWindow;
    private int zrleFill;
    private int zrlePos;
    private int[] tilePixels;
    private int[] tilePalette;

    /**
     * The shortest gap between asking for one frame and asking for the next. Without it a
     * desktop playing an animation kept the reader, the unpacking loop and the screen working
     * flat out at whatever rate the server could manage, and the picture was no smoother for it.
     */
    private volatile int frameIntervalMillis = 16;
    private long nextRequestAtNanos;

    /** The viewer passes the phone's own frame interval, so frames nobody can see are not built. */
    void setFrameInterval(int millis) {
        if (millis >= 4 && millis <= 60) frameIntervalMillis = millis;
    }

    /** Called before connecting: true when the viewer stores RGB_565 and full colour is waste. */
    void setLowColour(boolean low) { lowColour = low; }

    /**
     * The display server's private socket inside this app's storage. This is the only way in.
     *
     * Android does not keep loopback apart between apps, so a TCP port here could be opened by
     * any other app on the phone that holds the internet permission -- and this session has no
     * password. A unix socket in app-private storage cannot be opened by anyone else at all.
     *
     * There is no port behind it any more. The desktop script starts the display with
     * -rfbport -1 and fails with a reason when the socket will not come up, because a desktop
     * on 127.0.0.1 with no password is a desktop every other app on the phone can watch and
     * type into. Do not put the port back as a convenience for an old container: no desktop is
     * better than one anybody on the phone can drive.
     */
    private final String socketPath;
    private volatile android.net.LocalSocket localSocket;

    /**
     * Host and port name a loopback address the shipped app never reaches.
     *
     * With a socket path set, connectAndRun gives up rather than dialling them, so the only
     * caller that gets there is one built without a path: the RFB tests, which drive this class
     * over a plain ServerSocket because that is the only way to exercise the handshake and the
     * decoders without a container. The pair cannot simply be deleted here -- DesktopActivity
     * still passes it in and LinuxService still probes the same address.
     */
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
            if (rawIn == null && socketPath != null && !portOffered(socketPath, "vnc.port")) {
                // Nothing writes vnc.port any more, so a real session always stops here when its
                // private socket is not up: reporting that beats reaching for a loopback port
                // every other app on this phone can reach as well.
                throw new IOException("The desktop's private display socket is not ready");
            }
            if (rawIn == null) {
                // Only a client built without a socket path arrives here, which is the RFB
                // tests. A real session has its private socket or has already given up above.
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
            // The zlib stream holds memory the Java heap does not account for. This is the
            // thread that was decoding with it, and it has stopped, so nothing can be inside
            // inflate() while this runs.
            if (inflater != null) { inflater.end(); inflater = null; }
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

    /**
     * The format every pixel then arrives in.
     *
     * A phone whose framebuffer only keeps 16-bit colour was still being sent 32 bits a pixel
     * and throwing half of each one away. It asks for 16-bit 5-6-5 when that is what it keeps:
     * half the bytes over the socket and half the bytes the unpacking loop has to walk.
     */
    private void setPixelFormat() throws IOException {
        wirePixelBytes = lowColour ? 2 : 4;
        synchronized (writeLock) {
            output.writeByte(0);
            output.write(new byte[3]);
            if (wirePixelBytes == 2) {
                output.writeByte(16);      // bits per pixel
                output.writeByte(16);      // depth
                output.writeByte(0);       // little endian, which is how the loops below read it
                output.writeByte(1);       // true colour
                output.writeShort(31);
                output.writeShort(63);
                output.writeShort(31);
                output.writeByte(11);
                output.writeByte(5);
                output.writeByte(0);
            } else {
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
            }
            output.write(new byte[3]);
            output.flush();
        }
    }

    private void setEncodings() throws IOException {
        synchronized (writeLock) {
            output.writeByte(2);
            output.writeByte(0);
            output.writeShort(7);
            // Most wanted first. ZRLE is zlib over 64x64 tiles that are themselves reduced to a
            // small palette or to runs of one colour, and a desktop is mostly flat colour, so
            // most of a change costs a small fraction of the pixels Raw was sending. Raw comes
            // after it and is still offered: the server drops back to it when compressing a
            // rectangle would not pay, and it is the one encoding every server can always send.
            output.writeInt(16);       // ZRLE
            // CopyRect: "this block is already on your screen, at these other coordinates."
            // Scrolling a page, dragging a window and switching a tab are all mostly this, and
            // without it every one of them re-sent every pixel over a local socket, through the
            // pixel loop and up to the GPU. It is six bytes instead of a megabyte.
            output.writeInt(1);        // CopyRect
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
                readRaw(x, y, w, h);
            } else if (encoding == 16) {
                readZrle(x, y, w, h);
            } else if (encoding == 1) {
                int sourceX = input.readUnsignedShort();
                int sourceY = input.readUnsignedShort();
                if (w <= 0 || h <= 0) throw new IOException("Invalid copy rectangle");
                listener.onCopyRect(sourceX, sourceY, x, y, w, h);
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
        // The order here is deliberate and is load-bearing, so it is written down: the frame is
        // handed over FIRST, and only then is the next one asked for. Asking first would overlap
        // the blit with the server's next render, which sounds free -- but the viewer pauses
        // updates from inside this callback when the phone leaves the desktop screen, and a
        // request that has already gone out cannot be taken back. One frame of overlap is not
        // worth a Linux desktop that keeps rendering in someone's pocket.
        listener.onUpdateComplete();
        synchronized (writeLock) {
            readingFramebuffer = false;
            updatePending = false;
        }
        // Never ask for the next frame sooner than the phone can show one. A desktop with an
        // animation on it used to hand back a frame and ask for another in the same breath, so
        // the reader, the unpacking loop and the screen all ran flat out for frames nobody ever
        // saw. Waiting here is what paces the whole chain; input rides its own thread and is
        // not held up by it.
        long waitNanos = nextRequestAtNanos - System.nanoTime();
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }
        nextRequestAtNanos = System.nanoTime() + frameIntervalMillis * 1_000_000L;
        requestUpdate(true);
    }

    private void readRaw(int x, int y, int w, int h) throws IOException {
        if (w <= 0 || h <= 0 || (long) w * h > 5_000_000L) throw new IOException("Invalid desktop rectangle");
        int perPixel = wirePixelBytes;
        int stripCapacity = Math.min(h, STRIP_ROWS);
        if (stripPixels == null || stripPixels.length < w * stripCapacity) {
            stripPixels = new int[w * stripCapacity];
        }
        // One read per strip rather than one per row: a full-screen update was 120
        // separate readFully calls into the same buffer, each with its own bounds check
        // and its own trip through the socket's own buffering.
        if (rowBytes == null || rowBytes.length < w * perPixel * stripCapacity) {
            rowBytes = new byte[w * perPixel * stripCapacity];
        }
        for (int py = 0; py < h; ) {
            int rows = Math.min(stripCapacity, h - py);
            int index = 0;
            input.readFully(rowBytes, 0, w * perPixel * rows);
            int end = w * rows;
            if (perPixel == 4) {
                for (int base = 0; index < end; base += 4) {
                    int blue = rowBytes[base] & 0xff;
                    int green = rowBytes[base + 1] & 0xff;
                    int red = rowBytes[base + 2] & 0xff;
                    stripPixels[index++] = 0xff000000 | (red << 16) | (green << 8) | blue;
                }
            } else {
                for (int base = 0; index < end; base += 2) {
                    stripPixels[index++] =
                            widen565((rowBytes[base] & 0xff) | ((rowBytes[base + 1] & 0xff) << 8));
                }
            }
            // The listener copies the strip into its bitmap before returning, so the
            // same array can be refilled for the next strip.
            listener.onRectangle(x, y + py, w, rows, stripPixels);
            py += rows;
        }
    }

    /**
     * A 16-bit 5-6-5 pixel opened out to full colour.
     *
     * The top bits are repeated into the bottom ones. Shifting alone would have made the
     * brightest red 0xf8 rather than 0xff, so white came out slightly grey and every light
     * colour was a shade off.
     */
    private static int widen565(int value) {
        int red = (value >> 11) & 0x1f;
        int green = (value >> 5) & 0x3f;
        int blue = value & 0x1f;
        return 0xff000000
                | (((red << 3) | (red >> 2)) << 16)
                | (((green << 2) | (green >> 4)) << 8)
                | ((blue << 3) | (blue >> 2));
    }

    /**
     * ZRLE: the whole rectangle arrives as one zlib block that unpacks into 64x64 tiles.
     *
     * Each tile says how it was stored -- plain pixels, one solid colour, a small palette packed
     * down to a few bits a pixel, or runs of a repeated colour -- and a desktop is mostly flat
     * colour, so most tiles come to a handful of bytes. Tiles are handed over one at a time, so
     * nothing here ever holds more than 64x64 pixels of unpacked picture.
     */
    private void readZrle(int x, int y, int w, int h) throws IOException {
        if (w <= 0 || h <= 0 || (long) w * h > 5_000_000L) throw new IOException("Invalid desktop rectangle");
        int length = input.readInt();
        if (length < 0 || length > 32 * 1024 * 1024) throw new IOException("Invalid compressed rectangle");
        if (zrleCompressed == null || zrleCompressed.length < length) {
            zrleCompressed = new byte[Math.max(length, 64 * 1024)];
        }
        input.readFully(zrleCompressed, 0, length);
        if (inflater == null) inflater = new java.util.zip.Inflater();
        inflater.setInput(zrleCompressed, 0, length);
        zrleFill = 0;
        zrlePos = 0;
        if (tilePixels == null) {
            tilePixels = new int[64 * 64];
            tilePalette = new int[128];
        }
        for (int tileY = 0; tileY < h; tileY += 64) {
            int tileHeight = Math.min(64, h - tileY);
            for (int tileX = 0; tileX < w; tileX += 64) {
                int tileWidth = Math.min(64, w - tileX);
                readZrleTile(tileWidth, tileHeight);
                listener.onRectangle(x + tileX, y + tileY, tileWidth, tileHeight, tilePixels);
            }
        }
    }

    private void readZrleTile(int w, int h) throws IOException {
        int count = w * h;
        int subencoding = zrleByte();
        boolean runs = (subencoding & 128) != 0;
        int paletteSize = subencoding & 127;
        if (!runs && paletteSize == 0) {
            for (int i = 0; i < count; i++) tilePixels[i] = readTilePixel();
            return;
        }
        if (!runs && paletteSize == 1) {
            java.util.Arrays.fill(tilePixels, 0, count, readTilePixel());
            return;
        }
        // 17 to 127 without runs, and 129, are defined as unused. A tile claiming one of them
        // means this reader and the server have lost each other, and going on would paint noise.
        if ((!runs && paletteSize > 16) || (runs && paletteSize == 1)) {
            throw new IOException("Unsupported desktop tile " + subencoding);
        }
        for (int i = 0; i < paletteSize; i++) tilePalette[i] = readTilePixel();
        if (!runs) {
            // A palette of 2 takes one bit a pixel, 3 or 4 take two, 5 to 16 take four, most
            // significant bits first -- and every ROW starts again on a fresh byte.
            int bits = paletteSize == 2 ? 1 : paletteSize <= 4 ? 2 : 4;
            int mask = (1 << bits) - 1;
            for (int row = 0; row < h; row++) {
                int packed = 0;
                int shift = -1;
                for (int column = 0; column < w; column++) {
                    if (shift < 0) {
                        packed = zrleByte();
                        shift = 8 - bits;
                    }
                    tilePixels[row * w + column] = tilePalette[(packed >> shift) & mask];
                    shift -= bits;
                }
            }
            return;
        }
        int filled = 0;
        while (filled < count) {
            int colour;
            int run;
            if (paletteSize == 0) {
                colour = readTilePixel();
                run = readRunLength();
            } else {
                int index = zrleByte();
                run = 1;
                if ((index & 128) != 0) {
                    index &= 127;
                    run = readRunLength();
                }
                if (index >= paletteSize) throw new IOException("Invalid desktop tile colour");
                colour = tilePalette[index];
            }
            if (run > count - filled) throw new IOException("Invalid desktop tile run");
            java.util.Arrays.fill(tilePixels, filled, filled + run, colour);
            filled += run;
        }
    }

    /** A run length: one more than the sum of the bytes, every 255 saying another byte follows. */
    private int readRunLength() throws IOException {
        int length = 1;
        int part;
        do {
            part = zrleByte();
            length += part;
            if (length > 64 * 64) throw new IOException("Invalid desktop tile run");
        } while (part == 255);
        return length;
    }

    /**
     * One pixel inside a tile. At 32 bits a pixel the fourth byte carries nothing, so ZRLE
     * leaves it out and sends three; at 16 bits there is nothing spare and it sends two.
     */
    private int readTilePixel() throws IOException {
        if (wirePixelBytes == 4) {
            int blue = zrleByte();
            int green = zrleByte();
            int red = zrleByte();
            return 0xff000000 | (red << 16) | (green << 8) | blue;
        }
        int low = zrleByte();
        int high = zrleByte();
        return widen565(low | (high << 8));
    }

    private int zrleByte() throws IOException {
        if (zrlePos >= zrleFill) {
            if (zrleWindow == null) zrleWindow = new byte[16 * 1024];
            zrlePos = 0;
            try {
                zrleFill = inflater.inflate(zrleWindow);
            } catch (java.util.zip.DataFormatException broken) {
                throw new IOException("The desktop's compressed picture could not be read");
            }
            if (zrleFill <= 0) throw new IOException("Truncated compressed rectangle");
        }
        return zrleWindow[zrlePos++] & 0xff;
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
        // The cursor's pixels come in the same format as everything else, so they are two bytes
        // each once 16-bit colour has been asked for.
        int perPixel = wirePixelBytes;
        byte[] pixels = new byte[count * perPixel];
        input.readFully(pixels);
        int maskRow = (w + 7) / 8;
        byte[] mask = new byte[maskRow * h];
        input.readFully(mask);
        int[] argb = new int[count];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean opaque = (mask[y * maskRow + x / 8] & (0x80 >> (x % 8))) != 0;
                int base = (y * w + x) * perPixel;
                int colour;
                if (perPixel == 4) {
                    int blue = pixels[base] & 0xff;
                    int green = pixels[base + 1] & 0xff;
                    int red = pixels[base + 2] & 0xff;
                    colour = 0xff000000 | (red << 16) | (green << 8) | blue;
                } else {
                    colour = widen565((pixels[base] & 0xff) | ((pixels[base + 1] & 0xff) << 8));
                }
                argb[y * w + x] = opaque ? colour : 0;
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

    /**
     * True when the desktop wrote the named marker to say it had to fall back to a loopback port.
     *
     * pocketdesk-desktop deletes every marker at each start and writes one only in a fallback
     * branch, so a missing file means the private socket is the only way in, and a port another
     * app on the phone could have opened first is never tried. Only the sound still has such a
     * branch; the display has none, so vnc.port is never written and this is always false for it.
     */
    static boolean portOffered(String socketPath, String markerName) {
        java.io.File socket = new java.io.File(socketPath);
        java.io.File parent = socket.getParentFile();
        return parent != null && new java.io.File(parent, markerName).exists();
    }

    /** True once the desktop's private socket answers: the same question, asked of the socket. */
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
