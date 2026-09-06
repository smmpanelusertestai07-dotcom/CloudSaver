package com.pocketlinux;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Sends the phone's microphone to PulseAudio's private FIFO while the owner enables it.
 * Each recording owns its recorder and cancellation state. A stopped or replaced recording
 * cannot keep the microphone open or overwrite the state of a newer recording.
 */
final class MicBridge {
    /** Must match module-pipe-source's 16 kHz, mono, signed 16-bit little-endian format. */
    static final int RATE = 16_000;
    private static final long WRITE_TIMEOUT_MS = 2000L;

    private final Context context;
    private volatile Session active;
    private volatile String lastProblem;

    private static final class Session {
        volatile boolean cancelled;
        Thread worker;
        private AudioRecord recorder;

        synchronized boolean begin(AudioRecord candidate) {
            if (cancelled) {
                candidate.release();
                return false;
            }
            recorder = candidate;
            candidate.startRecording();
            return true;
        }

        synchronized void closeRecorder() {
            AudioRecord owned = recorder;
            recorder = null;
            if (owned == null) return;
            try { owned.stop(); } catch (Throwable ignored) {}
            try { owned.release(); } catch (Throwable ignored) {}
        }

        void cancel() {
            cancelled = true;
            Thread thread = worker;
            if (thread != null) thread.interrupt();
            // Release here, rather than waiting for a pipe reader or worker cleanup. begin()
            // shares this lock, so cancellation cannot race a late startRecording().
            closeRecorder();
        }
    }

    MicBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    static File pipe(Context context) {
        return new File(ContainerRuntime.rootfs(context), "home/coder/.pocketdesk/mic.pipe");
    }

    static boolean available(Context context) {
        try { return OsConstants.S_ISFIFO(Os.lstat(pipe(context).getAbsolutePath()).st_mode); }
        catch (ErrnoException unavailable) { return false; }
    }

    boolean isRunning() { return active != null; }
    String problem() { return lastProblem; }

    synchronized void start() {
        if (active != null) return;
        lastProblem = null;
        if (!available(context)) {
            lastProblem = "The desktop has not made a microphone yet. Start the desktop, then try again.";
            return;
        }
        Session session = new Session();
        active = session;
        session.worker = new Thread(() -> pump(session, pipe(context)), "pocketdesk-mic");
        session.worker.setDaemon(true);
        session.worker.start();
    }

    synchronized void stop() {
        Session session = active;
        active = null;
        if (session != null) session.cancel();
    }

    private synchronized void failed(Session session, String message) {
        if (active == session && !session.cancelled) lastProblem = message;
    }

    private boolean live(Session session) {
        return !session.cancelled && !Thread.currentThread().isInterrupted();
    }

    private void pump(Session session, File target) {
        FileDescriptor pipe = null;
        AudioRecord recorder = null;
        boolean ownedBySession = false;
        try {
            // Never start recording while waiting for a vanished PulseAudio reader. O_NONBLOCK
            // makes a FIFO without a reader fail immediately; no regular file is created.
            pipe = Os.open(target.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK
                    | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            if (!OsConstants.S_ISFIFO(Os.fstat(pipe).st_mode)) {
                throw new IOException("The desktop microphone endpoint is not a pipe.");
            }
            if (!live(session)) return;
            int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("The phone does not support this microphone format.");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum * 2, RATE));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("This phone would not open its microphone for the desktop.");
            }
            // begin() owns the candidate even when startRecording throws or cancellation wins.
            ownedBySession = true;
            if (!session.begin(recorder)) return;
            byte[] buffer = new byte[2048];
            while (live(session)) {
                int read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
                if (read < 0) {
                    throw new IOException("The phone's microphone stopped (audio error " + read + ").");
                }
                if (read == 0) { Thread.sleep(20L); continue; }
                writeChunk(session, pipe, buffer, read);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (SecurityException noPermission) {
            failed(session, "Microphone permission was not granted.");
        } catch (ErrnoException unavailable) {
            failed(session, "The desktop microphone is not listening. Reopen the desktop and try again.");
        } catch (IOException broken) {
            failed(session, broken.getMessage());
        } catch (Throwable unexpected) {
            failed(session, "The microphone stopped: " + unexpected.getClass().getSimpleName());
        } finally {
            session.closeRecorder();
            if (recorder != null && !ownedBySession) {
                try { recorder.release(); } catch (Throwable ignored) {}
            }
            if (pipe != null) {
                try { Os.close(pipe); } catch (ErrnoException ignored) {}
            }
            synchronized (this) {
                if (active == session) active = null;
            }
        }
    }

    private void writeChunk(Session session, FileDescriptor pipe, byte[] bytes, int length)
            throws ErrnoException, IOException, InterruptedException {
        int offset = 0;
        long deadline = SystemClock.elapsedRealtime() + WRITE_TIMEOUT_MS;
        while (offset < length && live(session)) {
            try {
                int written = Os.write(pipe, bytes, offset, length - offset);
                if (written > 0) { offset += written; continue; }
            } catch (ErrnoException blocked) {
                if (blocked.errno != OsConstants.EAGAIN && blocked.errno != OsConstants.EINTR) throw blocked;
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new IOException("The desktop stopped reading the microphone. Turn it on again to retry.");
            }
            Thread.sleep(10L);
        }
    }
}
