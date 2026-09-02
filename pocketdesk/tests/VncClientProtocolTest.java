package com.pocketdesk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class VncClientProtocolTest {
    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(0, 1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        Thread fakeServer = new Thread(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                byte[] version = new byte[12];
                input.readFully(version);
                output.writeByte(1);
                output.writeByte(1);
                output.flush();
                require(input.readUnsignedByte() == 1, "client must choose None security");
                output.writeInt(0);
                output.flush();
                require(input.readUnsignedByte() == 1, "client must share session");
                output.writeShort(2);
                output.writeShort(1);
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
                byte[] name = "PocketDesk test".getBytes(StandardCharsets.UTF_8);
                output.writeInt(name.length);
                output.write(name);
                output.flush();

                byte[] setPixelFormat = new byte[20];
                input.readFully(setPixelFormat);
                require(setPixelFormat[0] == 0, "SetPixelFormat missing");
                // Read the declared count rather than a fixed length, so adding an encoding
                // cannot silently desynchronise this fake server again.
                byte[] encodingsHeader = new byte[4];
                input.readFully(encodingsHeader);
                require(encodingsHeader[0] == 2, "SetEncodings missing");
                int encodingCount = ((encodingsHeader[2] & 0xff) << 8) | (encodingsHeader[3] & 0xff);
                require(encodingCount > 0 && encodingCount < 64, "unreasonable encoding count");
                int[] encodings = new int[encodingCount];
                boolean rawOffered = false;
                boolean resizeOffered = false;
                boolean cursorOffered = false;
                for (int i = 0; i < encodingCount; i++) {
                    encodings[i] = input.readInt();
                    if (encodings[i] == 0) rawOffered = true;
                    if (encodings[i] == -308) resizeOffered = true;
                    if (encodings[i] == -239) cursorOffered = true;
                }
                require(rawOffered, "Raw encoding must be offered");
                require(resizeOffered, "ExtendedDesktopSize must be offered so the desktop can resize");
                // With this the server stops painting its arrow into the picture and sends the
                // pointer's shape instead, so the viewer can show a hand in Finger mode.
                require(cursorOffered, "the Cursor pseudo-encoding must be offered");
                byte[] request = new byte[10];
                input.readFully(request);
                require(request[0] == 3 && request[1] == 0, "full framebuffer request missing");

                // One update with two rectangles: the picture, then the pointer's shape.
                output.writeByte(0);
                output.writeByte(0);
                output.writeShort(2);
                output.writeShort(0);
                output.writeShort(0);
                output.writeShort(2);
                output.writeShort(1);
                output.writeInt(0);
                output.write(new byte[]{0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0});
                // Cursor: hotspot (1,0), 2x1, a red pixel that is part of the cursor and a green
                // one that the mask leaves out.
                output.writeShort(1);
                output.writeShort(0);
                output.writeShort(2);
                output.writeShort(1);
                output.writeInt(-239);
                output.write(new byte[]{0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0});
                output.write(new byte[]{(byte) 0x80});
                output.flush();

                // Two messages follow in either order: the reader thread's next update request
                // (type 3, 10 bytes) and the queued pointer press (type 5, 6 bytes). Input events
                // ride a sender thread now -- writing them on Android's main thread was the
                // NetworkOnMainThreadException that ended the app on every tap.
                boolean sawPointer = false;
                boolean sawRequest = false;
                for (int message = 0; message < 2; message++) {
                    int type = input.readUnsignedByte();
                    if (type == 3) {
                        byte[] rest = new byte[9];
                        input.readFully(rest);
                        sawRequest = true;
                    } else if (type == 5) {
                        int buttons = input.readUnsignedByte();
                        int pointerX = input.readUnsignedShort();
                        int pointerY = input.readUnsignedShort();
                        require(buttons == 1, "left button expected");
                        require(pointerX == 1 && pointerY == 0, "pointer must land on the second pixel");
                        sawPointer = true;
                    } else {
                        throw new AssertionError("unexpected client message " + type);
                    }
                }
                require(sawPointer, "the queued pointer press never reached the server");
                require(sawRequest, "the follow-up framebuffer request never reached the server");
            } catch (Throwable error) {
                serverError.set(error);
            }
        }, "fake-rfb-server");
        fakeServer.start();

        CountDownLatch frame = new CountDownLatch(1);
        AtomicReference<Throwable> clientError = new AtomicReference<>();
        AtomicReference<int[]> received = new AtomicReference<>();
        AtomicReference<int[]> cursor = new AtomicReference<>();
        AtomicInteger cursorHotspot = new AtomicInteger(-1);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger completedBeforeCursor = new AtomicInteger(-1);
        VncClient[] holder = new VncClient[1];
        holder[0] = new VncClient("127.0.0.1", server.getLocalPort(), new VncClient.Listener() {
            @Override public void onConnected(int width, int height, String name) {
                if (width != 2 || height != 1 || !"PocketDesk test".equals(name)) {
                    clientError.set(new AssertionError("wrong ServerInit"));
                }
            }
            @Override public void onResize(int width, int height) {}
            @Override public void onRectangle(int x, int y, int width, int height, int[] pixels) {
                received.set(pixels.clone());   // the buffer is reused once this returns
            }
            @Override public void onUpdateComplete() {
                completed.incrementAndGet();
                frame.countDown();
            }
            @Override public void onCursor(int hotX, int hotY, int width, int height, int[] argb) {
                completedBeforeCursor.set(completed.get());
                cursorHotspot.set(hotX * 100 + hotY);
                cursor.set(argb == null ? null : argb.clone());
            }
            @Override public void onClipboard(String text) {}
            @Override public void onDisconnected(String reason) {}
        });
        Thread client = new Thread(() -> {
            try { holder[0].connectAndRun(); }
            catch (Throwable error) {
                if (frame.getCount() != 0) clientError.set(error);
            }
        }, "rfb-client-test");
        client.start();

        require(frame.await(5, TimeUnit.SECONDS), "frame timed out");
        holder[0].sendPointer(1, 0, 1);          // from this thread, like a tap on the UI thread
        fakeServer.join(4000);
        holder[0].close();
        client.join(2000);
        server.close();
        if (serverError.get() != null) throw new AssertionError("server failed", serverError.get());
        if (clientError.get() != null) throw new AssertionError("client failed", clientError.get());
        int[] pixels = received.get();
        require(pixels != null && pixels.length == 2, "wrong pixel count");
        require(pixels[0] == 0xffff0000, "first pixel must be red");
        require(pixels[1] == 0xff00ff00, "second pixel must be green");
        int[] shape = cursor.get();
        require(shape != null && shape.length == 2, "the cursor shape must arrive with the update");
        require(shape[0] == 0xffff0000, "a masked-in cursor pixel keeps its colour and is opaque");
        require(shape[1] == 0, "a masked-out cursor pixel is fully transparent");
        require(cursorHotspot.get() == 100, "the hotspot rides in the rectangle's x and y");
        require(completedBeforeCursor.get() == 0, "the cursor is delivered before the update is called complete");
        require(completed.get() == 1, "one update, one completion: " + completed.get());
        System.out.println("PASS VncClientProtocolTest (raw, cursor shape, update completion)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
