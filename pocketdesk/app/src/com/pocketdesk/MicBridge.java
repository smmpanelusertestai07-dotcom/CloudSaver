package com.pocketdesk;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The phone's microphone, offered to the Linux computer as an ordinary recording device.
 *
 * The desktop session makes a named pipe inside this app's private storage and asks PulseAudio
 * to read it as a source called "Phone microphone". This class is the other end: it records from
 * the phone with AudioRecord and writes the same raw PCM into that pipe. Everything inside the
 * computer -- a voice reply, a meeting page in the browser, an AI app's dictation -- then finds a
 * working microphone with no idea that it is on a phone.
 *
 * Three rules it keeps, because a microphone is the one thing an owner must never have to guess
 * about:
 *
 *  - It is OFF until the owner turns it on, every session. Nothing is remembered as "always on".
 *  - It records only while the desktop screen is in front. Leaving the desktop stops it.
 *  - With nothing recording, the pipe has no writer, and PulseAudio reads a microphone that is
 *    simply silent -- not one that is listening and discarding.
 *
 * 16 kHz mono is what the pipe is declared as and what speech wants; it is also a sixth of the
 * bytes of the sound going the other way, which matters on a phone that is already tracing every
 * syscall of the container reading them.
 */
final class MicBridge {
    /** Must match the rate and channels module-pipe-source is loaded with. */
    static final int RATE = 16_000;

    private final Context context;
    private volatile Thread worker;
    private volatile boolean running;
    private volatile String lastProblem;

    MicBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Where the desktop session's pipe lives, inside the container's own home folder. */
    static File pipe(Context context) {
        return new File(ContainerRuntime.rootfs(context), "home/coder/.pocketdesk/mic.pipe");
    }

    /** True when the desktop has made the pipe, so there is a microphone to feed at all. */
    static boolean available(Context context) {
        return Trees.exists(pipe(context));
    }

    boolean isRunning() {
        return running;
    }

    /** What went wrong last time, for the screen to show, or null. */
    String problem() {
        return lastProblem;
    }

    synchronized void start() {
        if (running) return;
        lastProblem = null;
        File target = pipe(context);
        if (!Trees.exists(target)) {
            lastProblem = "The desktop has not made a microphone yet. Start the desktop, then try again.";
            return;
        }
        running = true;
        worker = new Thread(() -> pump(target), "pocketdesk-mic");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void stop() {
        running = false;
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
    }

    private void pump(File target) {
        AudioRecord recorder = null;
        FileOutputStream out = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) minimum = RATE;              // a phone that will not say; ask for a second
            int size = Math.max(minimum * 2, RATE);
            // VOICE_RECOGNITION rather than MIC: Android applies the noise suppression and gain
            // meant for speech, which is what every use of this is, and skips the effects meant
            // for recording music.
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                lastProblem = "This phone would not open its microphone for the desktop.";
                return;
            }
            recorder.startRecording();
            // Opening the pipe for writing blocks until PulseAudio opens the other end, which it
            // already has: the module is loaded when the session starts.
            out = new FileOutputStream(target);
            byte[] buffer = new byte[2048];
            while (running && !Thread.currentThread().isInterrupted()) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) break;
                    continue;
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (SecurityException noPermission) {
            lastProblem = "Microphone permission was not granted.";
        } catch (IOException broken) {
            // The desktop stopped, so the reader is gone. Not a fault: the microphone goes with it.
            if (running) lastProblem = "The desktop stopped listening to the microphone.";
        } catch (Throwable unexpected) {
            lastProblem = "The microphone stopped: " + unexpected.getClass().getSimpleName();
        } finally {
            running = false;
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
                } catch (Throwable ignored) {
                    // A phone that will not stop cleanly must not take the desktop down with it.
                }
                recorder.release();
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Closing a pipe whose reader has gone is expected.
                }
            }
        }
    }
}
