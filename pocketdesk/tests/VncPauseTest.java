package com.pocketlinux;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real RFB peer: hidden viewers stop requesting pixels without losing their session. */
public final class VncPauseTest {
    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            VncClient[] holder = new VncClient[1];
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger resized = new AtomicInteger();
            AtomicInteger painted = new AtomicInteger();
            holder[0] = new VncClient("127.0.0.1", server.getLocalPort(), new VncClient.Listener() {
                public void onConnected(int w, int h, String name) { holder[0].typeCodePoint('A'); }
                public void onResize(int w, int h) { resized.incrementAndGet(); }
                public void onRectangle(int x, int y, int w, int h, int[] p) {
                    check(x == 0 && y == 0 && w == 1 && h == 1 && p[0] == 0xffff0000,
                            "pixel data lost across pause/resize");
                    painted.incrementAndGet();
                }
                public void onUpdateComplete() {
                    holder[0].setUpdatesPaused(true);
                    holder[0].typeCodePoint('B');
                }
                public void onCursor(int x, int y, int w, int h, int[] p) {}
                public void onClipboard(String text) {}
                public void onDisconnected(String reason) {}
            });
            holder[0].setUpdatesPaused(true); // Activity stops before handshake completes.
            Thread client = new Thread(() -> {
                try { holder[0].connectAndRun(3000); }
                catch (IOException expectedClose) {}
                catch (Throwable error) { failure.set(error); }
            });
            client.start();
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                DataInputStream in = new DataInputStream(peer.getInputStream());
                DataOutputStream out = new DataOutputStream(peer.getOutputStream());
                out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                in.readFully(new byte[12]);
                out.write(new byte[]{1, 1}); out.flush(); in.readByte();
                out.writeInt(0); out.flush(); in.readByte();
                out.writeShort(320); out.writeShort(240);
                out.write(new byte[16]); out.writeInt(0); out.flush();
                in.readFully(new byte[20]); // pixel format
                check(in.readUnsignedByte() == 2, "encodings missing");
                in.readByte(); int count = in.readUnsignedShort();
                in.readFully(new byte[count * 4]);
                keyPair(in, 'A');
                quiet(peer, in);
                holder[0].setUpdatesPaused(false);
                refresh(in, false, 320, 240);
                StringBuilder paste = new StringBuilder();
                for (int i = 0; i < 6000; i++) paste.append('p');
                paste.append("\n\ud83d\ude80");
                holder[0].replaceText(3, 2, paste.toString());
                holder[0].sendPointer(12, 23, 0);
                for (int i = 0; i < 3; i++) keyPair(in, 0xff08);
                for (int i = 0; i < 2; i++) keyPair(in, 0xffff);
                for (int i = 0; i < 6000; i++) keyPair(in, 'p');
                keyPair(in, 0xff0d);
                keyPair(in, 0x0101f680);
                check(in.readUnsignedByte() == 5 && in.readUnsignedByte() == 0, "input after long paste lost");
                check(in.readUnsignedShort() == 12 && in.readUnsignedShort() == 23, "post-paste pointer");
                frameHeader(out, 1);
                rawPixel(out); out.flush(); // allowed in-flight frame is retained while hidden
                keyPair(in, 'B');
                quiet(peer, in); // paused from callback; no automatic incremental request
                holder[0].setUpdatesPaused(false);
                refresh(in, true, 320, 240);

                // A quiet server can retain this incremental request indefinitely. Switching
                // Android apps repeatedly must not create a stream of full-frame requests.
                for (int i = 0; i < 20; i++) {
                    holder[0].setUpdatesPaused(true);
                    holder[0].setUpdatesPaused(false);
                }
                holder[0].typeCodePoint('Q'); // sender barrier after all resume tasks
                keyPair(in, 'Q');
                quiet(peer, in);
                frameHeader(out, 0); out.flush();
                keyPair(in, 'B');
                quiet(peer, in);
                holder[0].setUpdatesPaused(false);
                refresh(in, true, 320, 240); // an empty update still completes one request

                frameHeader(out, 1);
                rectangle(out, 0, 0, -239); out.flush(); // empty cursor-only update
                keyPair(in, 'B');
                quiet(peer, in);
                holder[0].setUpdatesPaused(false);
                refresh(in, true, 320, 240);

                // Both resize encodings may occur among pixels. No request may escape with
                // an intermediate size, nor may full + incremental credits be doubled.
                frameHeader(out, 3);
                rectangle(out, 640, 480, -223);
                rawPixel(out);
                rectangle(out, 800, 600, -308);
                out.writeByte(1); out.write(new byte[3]);
                out.writeInt(7); out.writeShort(0); out.writeShort(0);
                out.writeShort(800); out.writeShort(600); out.writeInt(0); out.flush();
                keyPair(in, 'B');
                quiet(peer, in);
                holder[0].setUpdatesPaused(false);
                refresh(in, false, 800, 600);
                quiet(peer, in);
                frameHeader(out, 1); rawPixel(out); out.flush();
                keyPair(in, 'B');
                quiet(peer, in);
                holder[0].setUpdatesPaused(false);
                refresh(in, true, 800, 600);
                check(resized.get() == 2 && painted.get() == 3, "resize/frame callback count");
            } finally {
                holder[0].close(); client.join(4000);
            }
            check(!client.isAlive(), "client did not stop");
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
        System.out.println("PASS VncPauseTest (initial full frame, incremental resume, one pending request, cursor/empty/resize updates, 6,000-character Unicode replacement)");
    }
    private static void keyPair(DataInputStream in, int key) throws IOException {
        for (int down = 1; down >= 0; down--) {
            check(in.readUnsignedByte() == 4, "unexpected pixels while paused");
            check(in.readUnsignedByte() == down, "key pair order");
            in.readShort(); check(in.readInt() == key, "key identity");
        }
    }
    private static void refresh(DataInputStream in, boolean incremental, int width, int height) throws IOException {
        check(in.readUnsignedByte() == 3 && in.readUnsignedByte() == (incremental ? 1 : 0),
                "wrong refresh kind");
        check(in.readInt() == 0, "refresh origin");
        check(in.readUnsignedShort() == width && in.readUnsignedShort() == height, "refresh bounds");
    }
    private static void frameHeader(DataOutputStream out, int rectangles) throws IOException {
        out.writeByte(0); out.writeByte(0); out.writeShort(rectangles);
    }
    private static void rectangle(DataOutputStream out, int width, int height, int encoding) throws IOException {
        out.writeShort(0); out.writeShort(0); out.writeShort(width); out.writeShort(height);
        out.writeInt(encoding);
    }
    private static void rawPixel(DataOutputStream out) throws IOException {
        rectangle(out, 1, 1, 0);
        out.write(new byte[]{0, 0, (byte)255, 0});
    }
    private static void quiet(Socket peer, DataInputStream in) throws IOException {
        peer.setSoTimeout(180);
        try { in.readUnsignedByte(); throw new AssertionError("hidden viewer requested another frame"); }
        catch (SocketTimeoutException expected) {}
        finally { peer.setSoTimeout(3000); }
    }
    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }
}
