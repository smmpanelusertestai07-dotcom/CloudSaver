package com.pocketagent.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Owns a user-started local preview even when its Activity is not visible. */
public final class PreviewService extends Service {
    static final String ACTION_START = "com.pocketagent.mobile.preview.START";
    static final String ACTION_STOP = "com.pocketagent.mobile.preview.STOP";
    static final String ACTION_STATUS = "com.pocketagent.mobile.preview.STATUS";
    static final String EVENT = "com.pocketagent.mobile.preview.EVENT";
    static final String EXTRA_PROJECT = "project";
    static final String EXTRA_NPM = "npm";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_RUNNING = "running";
    static final String EXTRA_STARTING = "starting";
    private static final String CHANNEL = "pocketagent_preview";
    private static final int NOTICE = 2416;
    private static final long MAX_SESSION_MS = 2 * 60 * 60 * 1000L;
    private static JSONObject lastState = new JSONObject();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> startTask;
    private PowerManager.WakeLock wakeLock;
    private volatile Process ownedProcess;
    private volatile boolean destroyed;
    private boolean starting, running, npmMode;
    private int port, generation, latestStartId;
    private String token = "", projectName = "";
    private long startedAt;

    static synchronized JSONObject snapshot() {
        try { return new JSONObject(lastState.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Project preview", NotificationManager.IMPORTANCE_LOW));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAgent:preview");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession("Preview stopped.", false);
            return START_NOT_STICKY;
        }
        if (ACTION_STATUS.equals(action)) {
            broadcastSnapshot();
            if (!running && !starting) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) { stopSelf(startId); return START_NOT_STICKY; }
        // Satisfy Android's foreground deadline before any file or runtime operation.
        try {
            Notification notice = notification("Starting project preview…");
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTICE, notice);
        } catch (RuntimeException blocked) {
            publish("Android could not start the preview service: " + blocked.getMessage(), true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running || starting) {
            String requested = intent.getStringExtra(EXTRA_PROJECT);
            if (projectName == null || !projectName.equals(requested)
                    || npmMode != intent.getBooleanExtra(EXTRA_NPM, false)) {
                publish("A preview is already active for " + projectName
                        + ". Stop it before starting another project or preview mode.", true);
                return START_NOT_STICKY;
            }
            publish(running ? "Preview is already running." : "Preview is starting…", false);
            return START_NOT_STICKY;
        }
        projectName = intent.getStringExtra(EXTRA_PROJECT);
        final boolean npm = intent.getBooleanExtra(EXTRA_NPM, false);
        npmMode = npm;
        starting = true;
        port = 0;
        token = "";
        startedAt = SystemClock.elapsedRealtime();
        final int request = ++generation;
        renewWakeLock();
        publish("Starting " + (npm ? "npm" : "protected static") + " preview…", false);
        main.removeCallbacks(monitor);
        main.postDelayed(monitor, 15000);
        startTask = worker.submit(() -> {
            try {
                if (projectName == null || !projectName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                    throw new IOException("Choose a valid project first.");
                }
                File project = WorkspaceTools.safeChild(ContainerRuntime.workspaceRoot(this), projectName);
                int readyPort = WorkspaceTools.startPreview(this, project, npm, null);
                Process process = WorkspaceTools.previewProcessHandle(readyPort);
                ownedProcess = process;
                if (destroyed || Thread.currentThread().isInterrupted()) {
                    WorkspaceTools.stopPreview(process);
                    return;
                }
                String readyToken = WorkspaceTools.previewToken();
                main.post(() -> {
                    if (destroyed || request != generation) {
                        stopOwnedAsync(process);
                        return;
                    }
                    port = readyPort;
                    token = readyToken;
                    starting = false;
                    running = true;
                    publish(npm ? "Project dev preview is running on this phone."
                            : "Protected static preview is running on this phone.", false);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!destroyed && request == generation) {
                        stopSession("Preview could not start. " + safeMessage(error.getMessage()), true);
                    }
                });
            }
        });
        return START_NOT_STICKY;
    }

    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (destroyed || (!starting && !running)) return;
            if (SystemClock.elapsedRealtime() - startedAt >= MAX_SESSION_MS) {
                stopSession("Preview stopped after two hours. Tap Preview to start a new session.", false);
                return;
            }
            if (running && (ownedProcess == null || !ownedProcess.isAlive())) {
                stopSession("The preview server exited. Check the project and start preview again.", true);
                return;
            }
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                boolean plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                if (temperature >= 490) {
                    stopSession("Preview stopped because the phone is too hot. Let it cool before retrying.", false);
                    return;
                }
                if (!plugged && level >= 0 && scale > 0 && level * 100 / scale < 5) {
                    stopSession("Preview stopped because battery is below 5%.", false);
                    return;
                }
            }
            renewWakeLock();
            main.postDelayed(this, 30000);
        }
    };

    private void renewWakeLock() {
        if (wakeLock != null) wakeLock.acquire(120000);
    }

    private void stopSession(String message, boolean error) {
        ++generation;
        starting = false;
        running = false;
        port = 0;
        token = "";
        main.removeCallbacks(monitor);
        if (startTask != null) startTask.cancel(true);
        Process process = ownedProcess;
        ownedProcess = null;
        stopOwnedAsync(process);
        releaseWakeLock();
        publish(message, error);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(latestStartId);
    }

    private void stopOwnedAsync(Process process) {
        if (process == null) return;
        Thread cleanup = new Thread(() -> WorkspaceTools.stopPreview(process), "PocketAgent-preview-stop");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void publish(String message, boolean error) {
        try {
            JSONObject state = new JSONObject().put(EXTRA_PROJECT, projectName == null ? "" : projectName)
                    .put(EXTRA_NPM, npmMode)
                    .put(EXTRA_PORT, port).put(EXTRA_TOKEN, token).put(EXTRA_RUNNING, running)
                    .put(EXTRA_STARTING, starting).put(EXTRA_ERROR, error).put(EXTRA_MESSAGE, safeMessage(message));
            synchronized (PreviewService.class) { lastState = state; }
        } catch (Exception ignored) { }
        broadcastSnapshot();
        if (starting || running) getSystemService(NotificationManager.class).notify(NOTICE, notification(message));
    }

    private void broadcastSnapshot() {
        JSONObject state = snapshot();
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName())
                .putExtra(EXTRA_PROJECT, state.optString(EXTRA_PROJECT))
                .putExtra(EXTRA_PORT, state.optInt(EXTRA_PORT))
                .putExtra(EXTRA_TOKEN, state.optString(EXTRA_TOKEN))
                .putExtra(EXTRA_RUNNING, state.optBoolean(EXTRA_RUNNING))
                .putExtra(EXTRA_STARTING, state.optBoolean(EXTRA_STARTING))
                .putExtra(EXTRA_ERROR, state.optBoolean(EXTRA_ERROR))
                .putExtra(EXTRA_MESSAGE, state.optString(EXTRA_MESSAGE)));
    }

    private Notification notification(String message) {
        Intent open = running && port > 0
                ? new Intent(this, PreviewActivity.class).putExtra(PreviewActivity.EXTRA_PORT, port)
                    .putExtra(PreviewActivity.EXTRA_TOKEN, token)
                : new Intent(this, DeskActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, NOTICE, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, NOTICE + 1,
                new Intent(this, PreviewService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_pocketagent)
                .setContentTitle("PocketAgent · " + (projectName == null || projectName.isEmpty() ? "Project preview" : projectName))
                .setContentText(safeMessage(message)).setContentIntent(content).setOngoing(true)
                .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop preview", stop).build()).build();
    }

    private static String safeMessage(String message) {
        if (message == null || message.trim().isEmpty()) return "No further details were provided.";
        return message.length() > 6000 ? message.substring(message.length() - 6000) : message;
    }

    @Override public void onDestroy() {
        destroyed = true;
        ++generation;
        main.removeCallbacks(monitor);
        if (startTask != null) startTask.cancel(true);
        Process process = ownedProcess;
        ownedProcess = null;
        stopOwnedAsync(process);
        worker.shutdownNow();
        releaseWakeLock();
        boolean wasActive = starting || running;
        starting = false;
        running = false;
        port = 0;
        token = "";
        if (wasActive) publish("Preview service stopped.", false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
