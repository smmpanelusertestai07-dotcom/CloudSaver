package com.pocketagent.mobile;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Runs only the official logout command. Authentication output is discarded, never logged. */
final class CodexLogout {
    interface Stopper { void stop(Process process); }
    private CodexLogout() {}

    static void run(Process process, Stopper stopper, long timeoutMillis) throws IOException, InterruptedException {
        if (process == null || stopper == null || timeoutMillis < 1 || timeoutMillis > 30000)
            throw new IllegalArgumentException("Invalid Codex logout process.");
        Thread output = drain(process.getInputStream()), error = drain(process.getErrorStream());
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS))
                throw new IOException("Codex did not finish signing out in time. Try again.");
            if (process.exitValue() != 0)
                throw new IOException("Codex did not confirm sign-out. Try again.");
        } finally {
            stopper.stop(process);
            close(process.getInputStream()); close(process.getErrorStream());
            output.interrupt(); error.interrupt();
        }
    }

    private static Thread drain(InputStream input) {
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[2048];
            try { while (input.read(buffer) != -1) { /* No credentials or arbitrary CLI output retained. */ } }
            catch (IOException ignored) { }
        }, "PocketAgent-codex-logout-output");
        reader.setDaemon(true); reader.start(); return reader;
    }

    private static void close(InputStream input) { try { input.close(); } catch (IOException ignored) { } }
}
