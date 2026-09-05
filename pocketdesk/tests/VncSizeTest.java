package com.pocketlinux;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Reject dangerous screen dimensions over the real RFB boundary, before any UI allocation. */
public final class VncSizeTest {
    public static void main(String[] args) throws Exception {
        VncClient closed = new VncClient("127.0.0.1", 1, (VncClient.Listener) null);
        closed.close();
        try { closed.connectAndRun(); throw new AssertionError("closed client reconnected"); }
        catch (IOException error) {
            if (!error.getMessage().contains("was closed")) throw error;
        }
        check(0, 720, 0);
        check(65535, 65535, 0);
        check(1280, 720, -223);
        check(720, 1280, -308);
        System.out.println("PASS VncSizeTest (ServerInit, DesktopSize, ExtendedDesktopSize bounds)");
    }

    private static void check(int width, int height, int resizeEncoding) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1)) {
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                    in.readFully(new byte[12]);
                    out.writeByte(1); out.writeByte(1); out.flush(); in.readByte();
                    out.writeInt(0); out.flush(); in.readByte();
                    out.writeShort(width); out.writeShort(height);
                    out.write(new byte[16]); out.writeInt(0); out.flush();
                    if (resizeEncoding != 0) {
                        out.writeByte(0); out.writeByte(0); out.writeShort(1);
                        out.writeShort(0); out.writeShort(0);
                        out.writeShort(65535); out.writeShort(65535);
                        out.writeInt(resizeEncoding);
                        if (resizeEncoding == -308) out.writeInt(0); // zero screens and padding
                        out.flush();
                    }
                    while (in.read() != -1) { /* wait for the client's bounded failure */ }
                } catch (Throwable error) { serverError.set(error); }
            }, "rfb-size-test");
            peer.start();
            AtomicInteger connected = new AtomicInteger();
            AtomicInteger resized = new AtomicInteger();
            VncClient client = new VncClient("127.0.0.1", server.getLocalPort(), new VncClient.Listener() {
                public void onConnected(int w, int h, String name) { connected.incrementAndGet(); }
                public void onResize(int w, int h) { resized.incrementAndGet(); }
                public void onRectangle(int x, int y, int w, int h, int[] pixels) {}
                public void onUpdateComplete() {}
                public void onCursor(int x, int y, int w, int h, int[] pixels) {}
                public void onClipboard(String text) {}
                public void onDisconnected(String reason) {}
            });
            String message = "";
            try { client.connectAndRun(); }
            catch (IOException error) { message = error.getMessage(); }
            finally { client.close(); }
            peer.join(6000);
            if (peer.isAlive()) throw new AssertionError("server did not finish");
            if (!message.contains("screen size exceeds")) throw new AssertionError(message);
            if (connected.get() != (resizeEncoding == 0 ? 0 : 1)) throw new AssertionError("unsafe ServerInit reached UI");
            if (resized.get() != 0) throw new AssertionError("unsafe resize reached UI");
            if (serverError.get() != null) throw new AssertionError(serverError.get());
        }
    }
}
