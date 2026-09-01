package com.pocketdesk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                byte[] setEncodings = new byte[16];
                input.readFully(setEncodings);
                require(setEncodings[0] == 2, "SetEncodings missing");
                byte[] request = new byte[10];
                input.readFully(request);
                require(request[0] == 3 && request[1] == 0, "full framebuffer request missing");

                output.writeByte(0);
                output.writeByte(0);
                output.writeShort(1);
                output.writeShort(0);
                output.writeShort(0);
                output.writeShort(2);
                output.writeShort(1);
                output.writeInt(0);
                output.write(new byte[]{0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0});
                output.flush();
                Thread.sleep(200);
            } catch (Throwable error) {
                serverError.set(error);
            }
        }, "fake-rfb-server");
        fakeServer.start();

        CountDownLatch frame = new CountDownLatch(1);
        AtomicReference<Throwable> clientError = new AtomicReference<>();
        AtomicReference<int[]> received = new AtomicReference<>();
        VncClient[] holder = new VncClient[1];
        holder[0] = new VncClient("127.0.0.1", server.getLocalPort(), new VncClient.Listener() {
            @Override public void onConnected(int width, int height, String name) {
                if (width != 2 || height != 1 || !"PocketDesk test".equals(name)) {
                    clientError.set(new AssertionError("wrong ServerInit"));
                }
            }
            @Override public void onResize(int width, int height) {}
            @Override public void onRectangle(int x, int y, int width, int height, int[] pixels) {
                received.set(pixels);
                frame.countDown();
                holder[0].close();
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
        client.join(2000);
        fakeServer.join(2000);
        server.close();
        if (serverError.get() != null) throw new AssertionError("server failed", serverError.get());
        if (clientError.get() != null) throw new AssertionError("client failed", clientError.get());
        int[] pixels = received.get();
        require(pixels != null && pixels.length == 2, "wrong pixel count");
        require(pixels[0] == 0xffff0000, "first pixel must be red");
        require(pixels[1] == 0xff00ff00, "second pixel must be green");
        System.out.println("PASS VncClientProtocolTest");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
