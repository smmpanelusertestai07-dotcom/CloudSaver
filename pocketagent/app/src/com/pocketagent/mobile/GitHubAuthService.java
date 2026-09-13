package com.pocketagent.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** User-started GitHub CLI authentication; survives the handoff to the official browser. */
public final class GitHubAuthService extends Service {
    static final String EVENT = "com.pocketagent.github.STATUS", EXTRA_ACTION = "github_action", EXTRA_USER = "github_user";
    private static final int NOTICE = 4820;
    private static volatile String latest = "{}";
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Object STATE_LOCK = new Object();
    private long generation;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean active = new AtomicBoolean();
    private volatile boolean cancelled, destroyed;
    private volatile Process process;
    private PowerManager.WakeLock wake;
    static JSONObject snapshot() { try { return new JSONObject(latest); } catch (Exception ignored) { return new JSONObject(); } }
    @Override public void onCreate() {
        super.onCreate();
        synchronized (STATE_LOCK) { generation = GENERATION.incrementAndGet(); }
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("github_connection", "GitHub connection", NotificationManager.IMPORTANCE_LOW));
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAgent:GitHub");
        wake.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getStringExtra(EXTRA_ACTION);
        if ("cancel".equals(action)) {
            cancelled = true; Process old = process; if (old != null) ProotProcess.requestStop(old);
            io.shutdownNow(); update(AgentProtocol.object("status", "GitHub operation cancelled. Projects were kept.", "busy", false, "code", "", "url", ""));
            stopSelf(); return START_NOT_STICKY;
        }
        if (io.isShutdown()) { stopSelf(); return START_NOT_STICKY; }
        if (!active.compareAndSet(false, true)) { broadcast(); return START_NOT_STICKY; }
        final String operation = action == null ? "status" : action, user = intent.getStringExtra(EXTRA_USER);
        cancelled = false;
        update(AgentProtocol.object("busy", true, "error", false, "connected", false, "gitReady", false, "code", "", "url", "", "status", "Starting GitHub…"));
        wake.acquire(20 * 60 * 1000L);
        io.execute(() -> {
            boolean acquired = false;
            try {
                if (!operation.matches("status|install|login|logout")) throw new IllegalArgumentException("Choose a supported GitHub action.");
                if (!ContainerRuntime.isWorkspaceInstalled(this)) throw new IllegalStateException("Finish Ubuntu workspace setup first.");
                if (user != null && !user.isEmpty() && !user.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) throw new IllegalArgumentException("Enter the GitHub username to sign out.");
                WorkspaceTools.beginProjectOperation(this); acquired = true;
                installBridge();
                if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String command = "exec /usr/bin/python3 -u /usr/local/lib/pocketagent/github-auth.py " + operation
                        + ("logout".equals(operation) && user != null ? " " + WorkspaceTools.quote(user) : "");
                process = ContainerRuntime.startContainer(this, command);
                int result = ProcessOutput.consume(process, this::receive);
                if (!cancelled && result != 0 && !snapshot().optBoolean("error"))
                    update(AgentProtocol.object("error", true, "status", "GitHub could not complete this operation. Check the network and retry."));
            } catch (Exception failure) {
                if (!cancelled) update(AgentProtocol.object("error", true, "status", failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                        ? failure.getMessage() : "GitHub setup or connection stopped. Check your workspace, network and storage, then retry."));
            } finally {
                Process old = process; process = null; if (old != null) ProotProcess.stopAndWait(old);
                if (acquired) WorkspaceTools.endProjectOperation();
                active.set(false); releaseWake();
                update(AgentProtocol.object("busy", false, "code", "", "url", ""));
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }
        });
        return START_NOT_STICKY;
    }
    private void receive(String line) {
        if (cancelled || destroyed) return;
        try {
            JSONObject record = new JSONObject(line), accepted = new JSONObject();
            String kind = record.optString("kind");
            if (!kind.matches("progress|state|device|error")) return;
            for (String key : new String[]{"installed", "connected", "gitReady"}) if (record.opt(key) instanceof Boolean) accepted.put(key, record.optBoolean(key));
            if (record.optString("account").matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}") || record.has("account") && record.optString("account").isEmpty()) accepted.put("account", record.optString("account"));
            if (record.has("version")) accepted.put("version", AgentProtocol.clean(record.optString("version"), 150));
            accepted.put("status", AgentProtocol.clean(record.optString("status"), 700));
            accepted.put("error", "error".equals(kind));
            if ("error".equals(kind)) accepted.put("connected", false).put("gitReady", false);
            if ("device".equals(kind) && record.optString("code").matches("[A-Z0-9]{4}-[A-Z0-9]{4}")
                    && "https://github.com/login/device".equals(record.optString("url"))) {
                accepted.put("code", record.optString("code")).put("url", record.optString("url"));
            }
            update(accepted);
        } catch (Exception ignored) { /* Never expose arbitrary CLI diagnostics, tokens or config content. */ }
    }
    private void installBridge() throws Exception {
        File directory = WorkspaceTools.safeChild(ContainerRuntime.rootfs(this), "usr/local/lib/pocketagent");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot prepare GitHub connection.");
        File destination = WorkspaceTools.safeChild(directory, "github-auth.py");
        try (InputStream input = getAssets().open("pocketagent-github-auth.py"); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192]; int size; while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size); output.getFD().sync();
        }
    }
    private void update(JSONObject values) {
        synchronized (STATE_LOCK) {
            // A cancelled instance can finish after Android has created a retry service.
            if (generation != GENERATION.get()) return;
            JSONObject state = snapshot();
            for (java.util.Iterator<String> keys = values.keys(); keys.hasNext();) { String key = keys.next(); try { state.put(key, values.opt(key)); } catch (Exception ignored) {} }
            latest = state.toString();
        }
        broadcast();
    }
    private void broadcast() { sendBroadcast(new Intent(EVENT).setPackage(getPackageName())); }
    private void foreground() {
        Intent open = new Intent(this, ProjectToolsActivity.class);
        PendingIntent launch = PendingIntent.getActivity(this, NOTICE, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, NOTICE + 1, new Intent(this, GitHubAuthService.class).putExtra(EXTRA_ACTION, "cancel"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "github_connection").setSmallIcon(R.drawable.ic_stat_pocketagent)
                .setContentTitle("PocketAgent · GitHub").setContentText("GitHub setup or official account connection is running")
                .setContentIntent(launch).setOngoing(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); else startForeground(NOTICE, notification);
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private synchronized void releaseWake() { if (wake != null && wake.isHeld()) wake.release(); }
    @Override public void onDestroy() {
        destroyed = true; Process old = process; if (old != null) ProotProcess.requestStop(old);
        io.shutdownNow(); releaseWake();
        update(AgentProtocol.object("busy", false, "code", "", "url", "")); super.onDestroy();
    }
}
