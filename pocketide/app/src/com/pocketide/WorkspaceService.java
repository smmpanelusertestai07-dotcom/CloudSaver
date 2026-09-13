package com.pocketide;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The one process that owns the workspace: it runs set-up, it runs the editor, and it is the
 * only thing that may stop either.
 *
 * It has to be a foreground service with a visible notification, and that is not a formality.
 * PRoot is started with --kill-on-exit, so when this process goes, everything inside Linux goes
 * with it -- the editor, the extension host, a build half way through. Android reclaims a
 * backgrounded app within seconds; a foreground service with a Stop button is the platform's
 * own answer for work a person started and can see.
 *
 * The Stop button is not decoration either. Something that keeps a phone's CPU awake needs an
 * obvious way to end it that does not involve hunting through Settings.
 */
public final class WorkspaceService extends Service {
    static final String ACTION_SETUP = "com.pocketide.SETUP";
    static final String ACTION_START = "com.pocketide.START";
    static final String ACTION_STOP = "com.pocketide.STOP";

    /** What the service tells the screens, as they happen. */
    static final String EVENT = "com.pocketide.EVENT";
    static final String EXTRA_LINE = "line";
    static final String EXTRA_STAGE = "stage";
    static final String EXTRA_STATE = "state";        // setting-up | ready | failed | stopped
    static final String EXTRA_PERCENT = "percent";
    static final String EXTRA_ELAPSED = "elapsed";
    static final String EXTRA_URL = "url";

    private static final int NOTIFICATION = 4201;

    private static volatile boolean busy;
    private static volatile boolean editorRunning;
    private static volatile String editorUrl = "";
    private static volatile long runningSince;

    /**
     * The last few hundred lines the workspace printed, kept so the Activity screen can show
     * what happened rather than only what is happening.
     *
     * A ring buffer rather than a file: these lines are diagnostic, not a record, and a
     * development server that has been running all afternoon would otherwise write a log
     * nobody asked for onto a phone that is short of space. LOG_LINES is what fits a screen
     * scrolled back a few times.
     *
     * Guarded by its own lock because the service writes from its worker thread and the screen
     * reads from the thread that draws.
     */
    private static final int LOG_LINES = 400;
    private static final java.util.ArrayDeque<String> LOG = new java.util.ArrayDeque<>();

    private Thread worker;
    private volatile Process editor;
    private PowerManager.WakeLock wakeLock;
    private long startedAt;

    static boolean busy() { return busy; }
    static boolean editorRunning() { return editorRunning; }
    static String editorUrl() { return editorUrl; }

    /** When the editor started answering, or 0 if it is not running. */
    static long runningSince() { return editorRunning ? runningSince : 0L; }

    /** A copy of the recent output, oldest first. A copy, so the caller cannot see it change. */
    static java.util.List<String> recentLog() {
        synchronized (LOG) {
            return new java.util.ArrayList<>(LOG);
        }
    }

    static void clearLog() {
        synchronized (LOG) {
            LOG.clear();
        }
    }

    private static void record(String line) {
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;
        synchronized (LOG) {
            LOG.addLast(trimmed);
            while (LOG.size() > LOG_LINES) LOG.removeFirst();
        }
    }

    static void setUp(Context context) { send(context, ACTION_SETUP); }
    static void startEditor(Context context) { send(context, ACTION_START); }
    static void stop(Context context) { send(context, ACTION_STOP); }

    private static void send(Context context, String action) {
        Intent intent = new Intent(context, WorkspaceService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable refused) {
            // Android can refuse to start a foreground service from the background. The screens
            // handle this by showing the permission rows; silently doing nothing is the one
            // thing that must not happen, so it is reported as a failure.
            Intent failure = new Intent(EVENT)
                    .setPackage(context.getPackageName())
                    .putExtra(EXTRA_STATE, "failed")
                    .putExtra(EXTRA_LINE, "Android refused to start the workspace in the "
                            + "background. Open the app and try again, or allow background "
                            + "activity from Settings.");
            context.sendBroadcast(failure);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything("stopped", "Workspace stopped.");
            return START_NOT_STICKY;
        }
        // The notification must be posted in this call or Android kills the service. It is
        // posted before any work begins, for exactly that reason.
        startForeground(NOTIFICATION, notification(
                ACTION_SETUP.equals(action) ? "Setting up…" : "Starting the editor…"));
        if (busy) return START_NOT_STICKY;
        busy = true;
        startedAt = System.currentTimeMillis();
        hold();
        boolean setup = ACTION_SETUP.equals(action);
        worker = new Thread(setup ? this::runSetup : this::runEditor, "workspace");
        worker.start();
        // Not sticky: if Android kills this, restarting it without the owner asking would
        // silently spend their battery and their data.
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ set-up

    private void runSetup() {
        try {
            Prefs.of(this).edit().putLong(Prefs.SETUP_STARTED_AT, startedAt).apply();
            Workspace.install(this, (stage, message, percentWithin) -> {
                int percent = stage.percentBefore();
                if (percentWithin >= 0) {
                    percent += Math.round((stage.percentAfter() - stage.percentBefore())
                            * percentWithin / 100f);
                }
                announce(stage.name(), message, "setting-up", percent);
                note(stage.title + " · " + Stage.clock(elapsed()));
            });
            Prefs.of(this).edit().putLong(Prefs.SETUP_ELAPSED_MS, elapsed()).apply();
            announce(Stage.READY.name(), "Set-up finished in " + Stage.clock(elapsed()) + ".",
                    "ready", 100);
            stopEverything(null, null);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    // ------------------------------------------------------------------ the editor

    private void runEditor() {
        try {
            Workspace.writeScripts(this);
            String layout = Prefs.of(this).getString(Prefs.EDITOR_LAYOUT, "phone");
            int zoomTenths = Prefs.of(this).getInt(Prefs.EDITOR_ZOOM, 15);
            String command = "PIDE_LAYOUT=" + layout
                    + " PIDE_ZOOM=" + (zoomTenths / 10) + "." + (zoomTenths % 10)
                    + " bash /opt/pocketide/pocketide-editor.sh start";
            editor = Workspace.start(this, command);
            announce(null, "Starting the editor…", "setting-up", -1);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    editor.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String clean = Workspace.clean(line);
                    if (clean.startsWith("PIDE-READY ")) {
                        editorUrl = clean.substring("PIDE-READY ".length()).trim();
                        editorRunning = true;
                        note("Editor running");
                        runningSince = System.currentTimeMillis();
                        Intent ready = new Intent(EVENT).setPackage(getPackageName())
                                .putExtra(EXTRA_STATE, "ready")
                                .putExtra(EXTRA_URL, editorUrl)
                                .putExtra(EXTRA_LINE, "The editor is running.");
                        sendBroadcast(ready);
                        continue;
                    }
                    announce(null, clean, "setting-up", -1);
                }
            }
            int code = editor.waitFor();
            // Code 143 is SIGTERM, which is what the Stop button sends. That is not a failure.
            if (code != 0 && code != 143 && busy) {
                throw new IOException("The editor stopped with code " + code + ".");
            }
            stopEverything("stopped", "The editor stopped.");
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private void fail(Throwable failure) {
        String raw = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        Prefs.of(this).edit()
                .putString(Prefs.LAST_FAILURE, raw)
                .putLong(Prefs.LAST_FAILURE_AT, System.currentTimeMillis())
                .apply();
        record("Failed: " + raw);
        Intent event = new Intent(EVENT).setPackage(getPackageName())
                .putExtra(EXTRA_STATE, "failed")
                .putExtra(EXTRA_LINE, raw);
        sendBroadcast(event);
        stopEverything(null, null);
    }

    private void announce(String stage, String message, String state, int percent) {
        if (message == null || message.trim().isEmpty()) return;
        record(message);
        Intent event = new Intent(EVENT).setPackage(getPackageName())
                .putExtra(EXTRA_LINE, message)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_PERCENT, percent)
                .putExtra(EXTRA_ELAPSED, elapsed());
        if (stage != null) event.putExtra(EXTRA_STAGE, stage);
        sendBroadcast(event);
    }

    private long elapsed() { return System.currentTimeMillis() - startedAt; }

    private void stopEverything(String state, String message) {
        busy = false;
        editorRunning = false;
        editorUrl = "";
        runningSince = 0L;
        Process running = editor;
        editor = null;
        if (running != null) running.destroy();
        if (worker != null) worker.interrupt();
        release();
        if (state != null) {
            Intent event = new Intent(EVENT).setPackage(getPackageName())
                    .putExtra(EXTRA_STATE, state);
            if (message != null) event.putExtra(EXTRA_LINE, message);
            sendBroadcast(event);
        }
        stopForeground(true);
        stopSelf();
    }

    /**
     * Keeps the CPU awake while work is happening.
     *
     * Without it Android sleeps the processor between screen-off moments and a twenty-minute
     * download becomes an hour, or stops entirely. The lock is partial -- the screen is free to
     * turn off -- and it is released the moment the work ends, in a finally-shaped path so a
     * failure cannot leak it.
     */
    private void hold() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) return;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketide:workspace");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(4L * 60 * 60 * 1000);
        } catch (Throwable refused) {
            // A phone that refuses the lock still works; it is just slower with the screen off.
        }
    }

    private void release() {
        PowerManager.WakeLock lock = wakeLock;
        wakeLock = null;
        if (lock != null && lock.isHeld()) {
            try { lock.release(); } catch (Throwable alreadyGone) { /* nothing to undo */ }
        }
    }

    private void note(String text) {
        try {
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION, notification(text));
        } catch (Throwable notAllowed) {
            // Notifications may be denied. The work continues; only the update is lost.
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, WorkspaceService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, App.CHANNEL_WORKSPACE)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("PocketIDE")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_pocketide)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        Icon_stop(), "Stop", stopIntent).build())
                .build();
    }

    private android.graphics.drawable.Icon Icon_stop() {
        return android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop);
    }

    @Override public void onDestroy() {
        stopEverything(null, null);
        super.onDestroy();
    }
}
