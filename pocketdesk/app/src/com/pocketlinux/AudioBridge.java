package com.pocketlinux;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The Linux computer's sound, played through the phone's speaker.
 *
 * A container has no sound card. So inside Linux, PulseAudio plays everything into a virtual
 * output called Phone and streams what reaches it as plain 16-bit stereo PCM on a local port
 * (module-simple-protocol-tcp, started by pocketdesk-desktop). This thread reads that stream
 * while the desktop screen is open and hands it to an AudioTrack -- the same path every music
 * app uses, so the phone's own volume keys and media volume apply.
 *
 * Silence is the normal state, and a virtual output is silent as a stream of zeros, not an
 * absence of data. Those are read and dropped: the speaker path is opened only while there is
 * something to hear, and closed again a moment after it goes quiet, so an idle desktop costs
 * no battery here.
 */
final class AudioBridge {
    static final int PORT = 4712;
    private static final int RATE = 44100;
    private static final int CHUNK = 4096;                 // 23 ms of stereo 16-bit at 44.1 kHz
    private static final long QUIET_AFTER_MS = 1500L;

    private volatile boolean running;
    private Thread thread;
    /** The live connection, closed by stop(): a blocking read cannot be interrupted any other way. */
    private volatile Socket socket;
    private volatile android.net.LocalSocket localSocket;
    /** The desktop's private audio socket inside this app's storage, or null to use the port. */
    private volatile String socketPath;

    void setSocketPath(String path) { socketPath = path; }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "pocketdesk-audio");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        Thread active = thread;
        thread = null;
        if (active != null) active.interrupt();
        Socket open = socket;
        socket = null;
        if (open != null) {
            try { open.close(); } catch (IOException ignored) {}
        }
        android.net.LocalSocket openLocal = localSocket;
        localSocket = null;
        if (openLocal != null) {
            try { openLocal.close(); } catch (IOException ignored) {}
        }
    }

    private void run() {
        while (running) {
            // The private socket first: on Android any other app could open a loopback port,
            // and desktop sound is the owner's conversations read aloud. The port is only used
            // by a container whose PulseAudio has no unix module.
            boolean playedLocally = false;
            if (socketPath != null && new java.io.File(socketPath).exists()) {
                android.net.LocalSocket local = new android.net.LocalSocket();
                try {
                    localSocket = local;
                    if (!running) return;
                    local.connect(new android.net.LocalSocketAddress(socketPath,
                            android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                    local.setReceiveBufferSize(64 * 1024);
                    playedLocally = true;
                    play(new DataInputStream(local.getInputStream()));
                } catch (Exception ignored) {
                    // Not up yet, or a socket file left behind by a session that died: the port
                    // is tried in the same turn rather than leaving the owner with no sound.
                } finally {
                    localSocket = null;
                    try { local.close(); } catch (IOException ignored) {}
                }
            }
            if (!playedLocally) {
                try (Socket connection = new Socket()) {
                    socket = connection;
                    if (!running) return;
                    connection.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
                    connection.setReceiveBufferSize(64 * 1024);
                    play(new DataInputStream(connection.getInputStream()));
                } catch (Exception ignored) {
                    // The desktop is still starting, or has no PulseAudio yet: try again shortly.
                } finally {
                    socket = null;
                }
            }
            if (!running) return;
            try { Thread.sleep(3000L); } catch (InterruptedException ended) { return; }
        }
    }

    private void play(DataInputStream input) throws IOException {
        int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(minimum * 2, CHUNK * 4);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        byte[] chunk = new byte[CHUNK];
        boolean playing = false;
        long quietSince = -1L;
        try {
            while (running) {
                input.readFully(chunk);
                long now = System.currentTimeMillis();
                if (isSilent(chunk)) {
                    if (quietSince < 0) quietSince = now;
                    if (playing && now - quietSince > QUIET_AFTER_MS) {
                        track.pause();
                        track.flush();
                        playing = false;
                    }
                    if (!playing) continue;
                } else {
                    quietSince = -1L;
                    if (!playing) {
                        track.play();
                        playing = true;
                    }
                }
                track.write(chunk, 0, chunk.length);
            }
        } finally {
            try { track.stop(); } catch (IllegalStateException ignored) {}
            track.release();
        }
    }

    /** True when every sample is (near) zero: the virtual output ticking over with nothing playing. */
    private static boolean isSilent(byte[] samples) {
        for (int i = 0; i + 1 < samples.length; i += 2) {
            int value = (short) ((samples[i] & 0xff) | (samples[i + 1] << 8));
            if (value > 8 || value < -8) return false;
        }
        return true;
    }
}
