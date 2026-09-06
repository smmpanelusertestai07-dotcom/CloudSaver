package com.pocketlinux;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class VncConnectionTest {
    public static void main(String[] args) throws Exception {
        check(true);
        check(false);
        System.out.println("PASS VncConnection (stalled handshake bounded; healthy idle connection retained)");
    }

    private static void check(boolean stall) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1)) {
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            Thread peer = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    if (!stall) {
                        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                        in.readFully(new byte[12]);
                        out.writeByte(1); out.writeByte(1); out.flush(); in.readByte();
                        out.writeInt(0); out.flush(); in.readByte();
                        out.writeShort(2); out.writeShort(1); out.write(new byte[16]); out.writeInt(0); out.flush();
                        // Longer than the handshake timeout: normal desktop idleness is valid.
                        Thread.sleep(500);
                        out.writeByte(0); out.writeByte(0); out.writeShort(0); out.flush();
                    }
                    while (in.read() != -1) {}
                } catch (Throwable error) { serverError.set(error); }
            });
            peer.start();
            AtomicInteger updates = new AtomicInteger();
            VncClient[] holder = new VncClient[1];
            holder[0] = new VncClient("127.0.0.1", server.getLocalPort(), new VncClient.Listener() {
                public void onConnected(int w, int h, String name) {}
                public void onResize(int w, int h) {}
                public void onRectangle(int x, int y, int w, int h, int[] pixels) {}
                public void onUpdateComplete() { updates.incrementAndGet(); holder[0].close(); }
                public void onCursor(int x, int y, int w, int h, int[] pixels) {}
                public void onClipboard(String text) {}
                public void onDisconnected(String reason) {}
            });
            boolean timedOut = false;
            try { holder[0].connectAndRun(200); }
            catch (SocketTimeoutException expected) { timedOut = true; }
            finally { holder[0].close(); }
            peer.join(4000);
            if (peer.isAlive()) throw new AssertionError("peer did not finish");
            if (serverError.get() != null) throw new AssertionError(serverError.get());
            if (timedOut != stall) throw new AssertionError("wrong handshake/idle timeout behavior");
            if (holder[0].hasConnected() == stall) throw new AssertionError("incorrect readiness signal");
            if (updates.get() != (stall ? 0 : 1)) throw new AssertionError("idle session lost its later update");
        }
    }
}
