package android.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Test stub for the platform's unix-socket class. The protocol suite drives VncClient over a
 * TCP loopback socket, so nothing here has to connect: it exists to let VncClient compile
 * outside an Android SDK, and it fails as a real unconnected socket would.
 */
public class LocalSocket implements java.io.Closeable {
    public LocalSocket() {}

    public void connect(LocalSocketAddress address) throws IOException {
        throw new IOException("LocalSocket is not available in tests");
    }

    public void setReceiveBufferSize(int size) throws IOException {}

    public InputStream getInputStream() throws IOException { return new ByteArrayInputStream(new byte[0]); }

    public OutputStream getOutputStream() throws IOException { return new ByteArrayOutputStream(); }

    @Override public void close() throws IOException {}
}
