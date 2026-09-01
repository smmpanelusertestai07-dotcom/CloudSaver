package com.pocketdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LinuxService extends Service {
    static final String ACTION_SETUP = "com.pocketdesk.action.SETUP";
    static final String ACTION_START_DESKTOP = "com.pocketdesk.action.START_DESKTOP";
    static final String ACTION_INSTALL_APP = "com.pocketdesk.action.INSTALL_APP";
    static final String EXTRA_APP_ID = "app_id";
    static final String ACTION_STOP = "com.pocketdesk.action.STOP";
    static final String ACTION_REMOVE = "com.pocketdesk.action.REMOVE";
    static final String ACTION_STATUS = "com.pocketdesk.action.STATUS";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_DETAIL = "detail";
    static final String EXTRA_PROGRESS = "progress";
    static final String EXTRA_BUSY = "busy";
    static final String EXTRA_ERROR = "error";

    private static final int NOTIFICATION_ID = 2307;
    /** Battery temperatures in °C: warn first, only stop when it is genuinely unsafe. */
    private static final float WARN_TEMPERATURE_C = 45f;
    private static final float STOP_TEMPERATURE_C = 49f;
    private static final String CHANNEL_ID = "pocketdesk_linux";
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static volatile boolean desktopRunning;
    private static volatile String lastMessage;
    private static volatile String lastDetail;
    private static volatile int lastProgress = -1;
    private static volatile boolean lastError;
    private static volatile Process activeProcess;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Future<?> currentTask;
    private volatile Thread workerThread;
    private PowerManager.WakeLock wakeLock;
    private long sessionStartedAt;

    private final Runnable safetyMonitor = new Runnable() {
        @Override public void run() {
            if (!desktopRunning) return;
            SharedPreferences prefs = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
            int minutes = prefs.getInt(ContainerRuntime.KEY_SESSION_MINUTES, 240);
            long elapsed = System.currentTimeMillis() - sessionStartedAt;
            if (minutes > 0 && elapsed >= minutes * 60_000L) {
                status("Session timer reached", "Linux was stopped after " + minutes + " minutes.", 100, false, false);
                stopEverything(false);
                return;
            }
            if (prefs.getBoolean(ContainerRuntime.KEY_THERMAL_GUARD, true)) {
                DeviceProbe probe = DeviceProbe.read(LinuxService.this);
                // Android throttles hard at SEVERE; the session is only ended at CRITICAL or a
                // genuinely hot battery, so ordinary warm-phone coding is never interrupted.
                if (probe.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL
                        || probe.batteryTempC >= STOP_TEMPERATURE_C) {
                    status("Stopped to cool down",
                            String.format(Locale.ROOT, "The phone reached %.0f°C. Linux was closed to protect the battery.",
                                    probe.batteryTempC),
                            -1, false, true);
                    stopEverything(false);
                    return;
                }
                if (probe.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                        || probe.batteryTempC >= WARN_TEMPERATURE_C) {
                    updateNotification("Phone is warm", "Linux is still running. Take a short break if it gets hotter.", -1);
                }
                if (probe.batteryPercent >= 0 && probe.batteryPercent <= 3
                        && !DeviceProbe.isCharging(LinuxService.this)) {
                    status("Stopped at 3% battery", "Charge the phone, then open the desktop again.", -1, false, true);
                    stopEverything(false);
                    return;
                }
            }
            handler.postDelayed(this, 30_000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        final String appId = intent == null ? null : intent.getStringExtra(EXTRA_APP_ID);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification("PocketDesk", "Preparing local Linux…", -1),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification("PocketDesk", "Preparing local Linux…", -1));
        }
        if (ACTION_STOP.equals(action)) {
            stopEverything(true);
            return START_NOT_STICKY;
        }
        if (action == null) return START_NOT_STICKY;
        if (!BUSY.compareAndSet(false, true)) {
            status("PocketDesk is busy", desktopRunning ? "Desktop is already running." : "Wait for the current task to finish.", -1, true, false);
            return START_NOT_STICKY;
        }
        acquireWakeLock();
        currentTask = executor.submit(() -> {
            workerThread = Thread.currentThread();
            try {
                if (ACTION_SETUP.equals(action)) setupUbuntu();
                else if (ACTION_START_DESKTOP.equals(action)) startDesktop();
                else if (ACTION_INSTALL_APP.equals(action)) installApp(appId);
                else if (ACTION_REMOVE.equals(action)) removeLinux();
                else status("Unknown action", "Nothing was changed.", -1, false, true);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                status("Task cancelled", "No background task is running.", -1, false, false);
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) {
                    status("Task cancelled", "No background task is running.", -1, false, false);
                } else {
                    String message = cleanError(error);
                    status("Could not complete task", message, -1, false, true);
                }
            } finally {
                workerThread = null;
                BUSY.set(false);
                if (!desktopRunning) {
                    releaseWakeLock();
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(safetyMonitor);
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    static boolean isBusy() { return BUSY.get(); }

    static boolean isDesktopRunning() {
        Process process = activeProcess;
        return desktopRunning && process != null && process.isAlive();
    }

    private void setupUbuntu() throws Exception {
        preflight(true, 10);
        status("Preparing Linux", "Getting the local Linux system ready…", 2, true, false);
        ContainerRuntime.installRuntime(this);

        File archive = ContainerRuntime.downloadFile(this);
        if (!archive.isFile() || !ContainerRuntime.UBUNTU_SHA256.equalsIgnoreCase(sha256(archive))) {
            if (archive.exists() && !archive.delete()) throw new IOException("Could not replace old Ubuntu download");
            download(ContainerRuntime.UBUNTU_MIRRORS, archive, "Downloading Ubuntu");
        }
        status("Checking download", "Verifying that the Linux download is safe and complete…", 36, true, false);
        String actual = sha256(archive);
        if (!ContainerRuntime.UBUNTU_SHA256.equalsIgnoreCase(actual)) {
            archive.delete();
            throw new IOException("Ubuntu checksum did not match. The download was removed for safety.");
        }

        File root = ContainerRuntime.rootfs(this);
        if (root.exists()) ContainerRuntime.deleteTree(root);
        status("Installing Linux files", "Saving Linux inside private app storage…", 40, true, false);
        try (FileInputStream input = new FileInputStream(archive)) {
            TarGzExtractor.extract(input, root, (count, name) -> {
                if (Thread.currentThread().isInterrupted()) return;
                if (count % 500 == 0) status("Installing Linux files", count + " files prepared", 45, true, false);
            });
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

        final long toolsStartedAt = System.currentTimeMillis();
        status("Installing desktop tools",
                "The longest step · usually takes 10\u201325 min on mobile data", -1, true, false);
        final long[] lastLine = {0L};
        int code = runTracked(ContainerRuntime.bootstrapCommand(), line -> {
            long now = System.currentTimeMillis();
            if (now - lastLine[0] < 900L) return;
            lastLine[0] = now;
            status("Installing desktop tools",
                    phaseFor(line) + " · " + elapsedText(toolsStartedAt) + " · usually 10\u201325 min"
                            + (isTransferNoise(line) ? "" : "\n" + shortText(line)),
                    -1, true, false);
        });
        if (code != 0) throw new IOException("Ubuntu package setup exited with code " + code + ". Check Wi-Fi and free storage, then retry.");
        ContainerRuntime.writeDesktopScripts(this);
        getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE).edit()
                .putBoolean(ContainerRuntime.KEY_DESKTOP_INSTALLED, true).apply();
        archive.delete();
        status("Linux is ready", "Your local desktop and coding tools are installed.", 100, false, false);
    }

    private void installApp(String appId) throws Exception {
        LinuxApps.App app = LinuxApps.byId(appId);
        if (app == null) throw new IOException("Unknown app.");
        if (!ContainerRuntime.isInstalled(this)) throw new IOException("Install Linux first.");
        preflight(true, 10);
        long free = DeviceProbe.read(this).freeStorage;
        if (free < app.needsBytes) {
            throw new IOException(app.name + " needs " + DeviceProbe.formatBytes(app.needsBytes)
                    + " free. You have " + DeviceProbe.formatBytes(free) + ".");
        }
        final long startedAt = System.currentTimeMillis();
        status("Installing " + app.name,
                "Fetching the newest build · usually takes " + app.typicalTime, -1, true, false);
        final long[] lastLine = {0L};
        int code = runTracked(app.installCommand(), line -> {
            long now = System.currentTimeMillis();
            if (now - lastLine[0] < 900L) return;
            lastLine[0] = now;
            status("Installing " + app.name,
                    phaseFor(line) + " · " + elapsedText(startedAt) + " · usually " + app.typicalTime
                            + (isTransferNoise(line) ? "" : "\n" + shortText(line)),
                    -1, true, false);
        });
        if (code != 0) {
            throw new IOException(app.name + " did not install (exit " + code
                    + "). Check the connection and free space, then try again.");
        }
        ContainerRuntime.writeAppShortcut(this, app);
        // Refresh the desktop's own menu so the new app is there without a restart.
        try {
            runTracked("/usr/local/bin/pocketdesk-menu || true", null);
        } catch (Exception ignored) {
            // The menu is rebuilt at the next desktop start anyway.
        }
        status(app.name + " is ready",
                "Open the desktop, then tap its icon or right-click the background for the menu.",
                100, false, false);
    }

    private void startDesktop() throws Exception {
        if (!ContainerRuntime.isInstalled(this)) throw new IOException("Install Linux first.");
        preflight(false, 4);
        ContainerRuntime.installRuntime(this);
        // Refresh the desktop scripts and every installed app's launcher on each start, so a
        // container set up by an older version picks up the current desktop without reinstalling.
        ContainerRuntime.writeDesktopScripts(this);
        for (LinuxApps.App app : LinuxApps.CATALOG) {
            if (ContainerRuntime.isAppInstalled(this, app)) ContainerRuntime.writeAppShortcut(this, app);
        }
        status("Opening desktop", "Starting your local Linux screen…", -1, true, false);
        int[] geometry = DeviceProbe.desktopGeometry(this, ContainerRuntime.GEOMETRY_CAP);
        int dpi = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE)
                .getInt(ContainerRuntime.KEY_UI_SCALE, ContainerRuntime.DEFAULT_UI_SCALE);
        activeProcess = ContainerRuntime.startContainer(this,
                ContainerRuntime.startDesktopCommand(geometry[0], geometry[1], dpi));
        sessionStartedAt = System.currentTimeMillis();

        Thread output = new Thread(() -> {
            Process process = activeProcess;
            if (process == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) updateNotification("Linux desktop", shortText(line), -1);
                }
            } catch (IOException ignored) {}
        }, "pocketdesk-linux-output");
        output.setDaemon(true);
        output.start();

        boolean ready = false;
        for (int i = 0; i < 60 && activeProcess.isAlive(); i++) {
            if (VncClient.canConnect("127.0.0.1", 5901, 250)) { ready = true; break; }
            Thread.sleep(250);
        }
        if (!ready) {
            int exit = activeProcess.isAlive() ? -1 : activeProcess.exitValue();
            activeProcess.destroyForcibly();
            activeProcess = null;
            throw new IOException("Desktop display did not start" + (exit >= 0 ? " (exit " + exit + ")" : "") + ". Try setup again if the issue repeats.");
        }
        desktopRunning = true;
        BUSY.set(false);
        releaseWakeLock();
        status("Desktop is running",
                "Local display · " + geometry[0] + "×" + geometry[1] + " · tap Open desktop", 100, false, false);
        updateNotification("Desktop is running", "Tap to return · phone protection is active", 100);
        handler.removeCallbacks(safetyMonitor);
        handler.postDelayed(safetyMonitor, 30_000L);

        int exitCode = activeProcess.waitFor();
        activeProcess = null;
        desktopRunning = false;
        handler.removeCallbacks(safetyMonitor);
        status("Desktop stopped", "The Linux session ended safely.", 100, false, false);
    }

    private int runTracked(String command, ContainerRuntime.OutputListener listener)
            throws IOException, InterruptedException {
        activeProcess = ContainerRuntime.startContainer(this, command);
        Process process = activeProcess;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (listener != null && !line.trim().isEmpty()) listener.line(line);
                if (Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new InterruptedException();
                }
            }
        } finally {
            if (Thread.currentThread().isInterrupted() && process.isAlive()) process.destroyForcibly();
        }
        int code = process.waitFor();
        activeProcess = null;
        return code;
    }

    private void preflight(boolean download, int minimumBattery) throws IOException {
        DeviceProbe probe = DeviceProbe.read(this);
        if (!DeviceProbe.isArm64()) throw new IOException("This build needs an ARM64 phone.");
        if (!DeviceProbe.isCharging(this)
                && probe.batteryPercent >= 0 && probe.batteryPercent < minimumBattery) {
            throw new IOException("Battery is at " + probe.batteryPercent + "%. Charge to "
                    + minimumBattery + "% or plug in the charger.");
        }
        if (probe.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL
                || probe.batteryTempC >= STOP_TEMPERATURE_C) {
            throw new IOException("The phone is too hot right now. Let it cool for a few minutes.");
        }
        if (download) {
            if (!DeviceProbe.hasInternet(this)) throw new IOException("Connect to the internet first.");
            boolean wifiOnly = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE)
                    .getBoolean(ContainerRuntime.KEY_WIFI_ONLY, false);
            if (wifiOnly && !DeviceProbe.isWifi(this)) {
                throw new IOException("Wi-Fi-only download is enabled. Connect to Wi-Fi or change Phone care settings.");
            }
        }
    }

    /**
     * Downloads with resume and mirror failover. A dropped mobile-data connection continues
     * from the byte it stopped at instead of starting the whole archive again.
     */
    private void download(String[] sources, File destination, String title) throws Exception {
        File part = new File(destination.getAbsolutePath() + ".part");
        Exception last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String source = sources[attempt % sources.length];
            try {
                fetch(source, part, title);
                if (destination.exists() && !destination.delete()) {
                    throw new IOException("Could not replace the previous download");
                }
                if (!part.renameTo(destination)) throw new IOException("Could not save the download");
                return;
            } catch (InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception error) {
                last = error;
                if (!DeviceProbe.hasInternet(this)) {
                    throw new IOException("The internet connection dropped. Reconnect and tap Install again.");
                }
                status(title, "Connection problem — retrying (" + (attempt + 1) + " of 6)…", -1, true, false);
                Thread.sleep(1500L * (attempt + 1));
            }
        }
        throw new IOException("Download failed after 6 attempts: "
                + (last == null ? "unknown error" : last.getMessage()));
    }

    private void fetch(String source, File part, String title) throws Exception {
        long alreadyHave = part.isFile() ? part.length() : 0L;
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(40_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent",
                "PocketDesk/" + MainActivity.VERSION + " (Android " + Build.VERSION.RELEASE + ")");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (alreadyHave > 0) connection.setRequestProperty("Range", "bytes=" + alreadyHave + "-");
        connection.connect();

        int response = connection.getResponseCode();
        boolean resumed = response == HttpURLConnection.HTTP_PARTIAL;
        if (!resumed && response != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Download server returned HTTP " + response);
        }
        if (!resumed) alreadyHave = 0L;                 // server ignored the range: start over

        long remaining = connection.getContentLengthLong();
        final long total = remaining > 0 ? alreadyHave + remaining : -1L;
        long received = alreadyHave;
        long windowBytes = 0L;
        long windowStart = System.currentTimeMillis();
        long rate = 0L;
        long lastUpdate = 0L;

        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream(), 256 * 1024);
             FileOutputStream output = new FileOutputStream(part, alreadyHave > 0)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                output.write(buffer, 0, read);
                received += read;
                windowBytes += read;
                long now = System.currentTimeMillis();
                long windowMillis = now - windowStart;
                if (windowMillis >= 2_000L) {
                    rate = windowBytes * 1000L / windowMillis;
                    windowBytes = 0L;
                    windowStart = now;
                }
                if (now - lastUpdate > 600L) {
                    lastUpdate = now;
                    status(title, describeTransfer(received, total, rate),
                            total > 0 ? 4 + (int) Math.min(31L, received * 31L / total) : -1,
                            true, false);
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
        if (total > 0 && received < total) {
            throw new IOException("The download stopped early at " + DeviceProbe.formatBytes(received));
        }
    }

    private static String describeTransfer(long received, long total, long rate) {
        StringBuilder detail = new StringBuilder(DeviceProbe.formatBytes(received));
        if (total > 0) detail.append(" of ").append(DeviceProbe.formatBytes(total));
        String speed = DeviceProbe.formatRate(rate);
        if (!speed.isEmpty()) detail.append("  ·  ").append(speed);
        if (total > 0 && rate > 0) {
            String eta = DeviceProbe.formatEta((total - received) / rate);
            if (!eta.isEmpty()) detail.append("  ·  ").append(eta);
        }
        return detail.toString();
    }

    private void removeLinux() throws Exception {
        if (isDesktopRunning()) throw new IOException("Stop the desktop before removing Linux.");
        status("Removing Linux", "Deleting the Ubuntu system…", -1, true, false);
        ContainerRuntime.deleteTree(ContainerRuntime.rootfs(this));
        File archive = ContainerRuntime.downloadFile(this);
        if (archive.exists()) archive.delete();
        File part = new File(archive.getAbsolutePath() + ".part");
        if (part.exists()) part.delete();
        getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE).edit()
                .putBoolean(ContainerRuntime.KEY_DESKTOP_INSTALLED, false)
                .apply();
        status("Linux removed", "The storage it was using is free again.", 100, false, false);
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private void stopEverything(boolean userRequested) {
        handler.removeCallbacks(safetyMonitor);
        Future<?> task = currentTask;
        if (task != null) task.cancel(true);
        Thread worker = workerThread;
        if (worker != null) worker.interrupt();
        Process process = activeProcess;
        if (process != null) {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
        activeProcess = null;
        desktopRunning = false;
        BUSY.set(false);
        releaseWakeLock();
        if (userRequested) status("Linux stopped", "PocketDesk ended the local processes safely.", 100, false, false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    static String lastMessage() { return lastMessage; }
    static String lastDetail() { return lastDetail; }
    static int lastProgress() { return lastProgress; }
    static boolean lastWasError() { return lastError; }

    private void status(String message, String detail, int progress, boolean busy, boolean error) {
        lastMessage = message;
        lastDetail = detail;
        lastProgress = progress;
        lastError = error;
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_DETAIL, detail);
        intent.putExtra(EXTRA_PROGRESS, progress);
        intent.putExtra(EXTRA_BUSY, busy);
        intent.putExtra(EXTRA_ERROR, error);
        sendBroadcast(intent);
        updateNotification(message, detail, progress);
    }

    private void updateNotification(String title, String detail, int progress) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(title, detail, progress));
    }

    private Notification notification(String title, String detail, int progress) {
        Intent open = desktopRunning
                ? new Intent(this, DesktopActivity.class)
                : new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, LinuxService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail == null ? "" : detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail == null ? "" : detail))
                .setContentIntent(content)
                .setOngoing(BUSY.get() || desktopRunning)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        if (progress >= 0 && progress < 100) builder.setProgress(100, progress, false);
        else if (progress < 0 && BUSY.get()) builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return;
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketDeskLinux:setup");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(2 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    /** Human phase for a raw apt/dpkg/curl output line. */
    private static String phaseFor(String line) {
        String value = line == null ? "" : line.trim();
        if (value.startsWith("Get:") || value.contains("Fetched")) return "Downloading packages";
        if (value.startsWith("Unpacking") || value.startsWith("Preparing to unpack")) return "Unpacking files";
        if (value.startsWith("Setting up") || value.startsWith("Processing triggers")) return "Finishing set-up";
        if (value.startsWith("Reading") || value.startsWith("Building") || value.startsWith("Selecting")) return "Preparing";
        if (value.contains("% ") || value.startsWith("#")) return "Downloading";
        if (value.startsWith("Get:") || value.startsWith("Hit:")) return "Downloading packages";
        return "Working";
    }

    private static String elapsedText(long startedAt) {
        long minutes = (System.currentTimeMillis() - startedAt) / 60_000L;
        if (minutes < 1) return "under a minute so far";
        return minutes + " min so far";
    }

    /** True for a curl/wget progress line, which is a wall of numbers rather than a status. */
    private static boolean isTransferNoise(String value) {
        if (value == null) return true;
        String line = value.trim();
        if (line.isEmpty()) return true;
        if (line.startsWith("%") || line.startsWith("Dload") || line.startsWith("Current")) return true;
        int digits = 0;
        int letters = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) digits++;
            else if (Character.isLetter(c)) letters++;
        }
        return digits > 8 && digits > letters * 2;
    }

    private static String shortText(String value) {
        if (value == null || value.trim().isEmpty()) return "Working…";
        String oneLine = value.trim().replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 150 ? oneLine.substring(0, 147) + "…" : oneLine;
    }

    private static String cleanError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return shortText(message);
    }
}
