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
import android.os.SystemClock;

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
    static final String ACTION_UNINSTALL_APP = "com.pocketdesk.action.UNINSTALL_APP";
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
    /** Warm enough to start again: a hysteresis gap, or the work stops and starts every minute. */
    private static final float RESUME_TEMPERATURE_C = 43f;
    /** How long a set-up may sit paused for heat before it is stopped for real. */
    private static final long MAX_PAUSE_MS = 45L * 60L * 1000L;
    /**
     * How long a job's wake lock lives before it must be renewed. A timeout, not a lock held for
     * ever: if the process dies between renewals the phone gets its processor back by itself.
     * The monitor renews it every half minute, so it is a lease rather than a deadline -- taken
     * once and never renewed, it expired in the middle of a slow set-up and the phone slept.
     */
    private static final long WAKE_LOCK_MS = 2L * 60L * 60L * 1000L;
    private static final String CHANNEL_ID = "pocketdesk_linux";
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    /** An app install running beside an open desktop, which BUSY (the desktop's own task) is not. */
    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    private static volatile boolean desktopRunning;
    /** The container process of an app install, separate from the desktop's own. */
    private static volatile Process installProcess;
    private static volatile String lastMessage;
    private static volatile String lastDetail;
    private static volatile int lastProgress = -1;
    private static volatile boolean lastError;
    private static volatile Process activeProcess;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** Installs run here while the desktop holds the main executor for as long as it is open. */
    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Future<?> currentTask;
    private volatile Future<?> installTask;
    private volatile Thread workerThread;
    private volatile PowerManager.WakeLock taskWakeLock;
    private volatile PowerManager.WakeLock installWakeLock;
    private long sessionStartedAt;
    /** Set while the monitor is ending a session for a reason it has already announced. */
    private volatile boolean stoppedForReason;
    /** Set when the owner (the Stop button, the notification) asked for the desktop to end. */
    private volatile boolean stopRequested;
    /** Set while a set-up or install is frozen because the phone is too hot. */
    private volatile boolean pausedForHeat;
    private volatile long pausedSince;

    /**
     * The safety stops that apply to a background job -- set-up or an app install -- which is
     * the longest, hottest, most data-hungry thing this app ever does and used to run with no
     * guard at all: no idle or session timer (those belong to a desktop session), but heat, a
     * flat battery and today's mobile-data limit all still stop it, and it can be continued.
     */
    private final Runnable jobMonitor = new Runnable() {
        @Override public void run() {
            if (!BUSY.get() && !INSTALLING.get()) return;   // nothing to guard any more
            SharedPreferences prefs = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
            DeviceProbe probe = DeviceProbe.read(LinuxService.this);
            String reason = null;
            if (probe.batteryPercent >= 0 && probe.batteryPercent <= 3
                    && !DeviceProbe.isCharging(LinuxService.this)) {
                reason = "Battery reached 3%, so the download was stopped. Charge the phone, then "
                        + "start it again — nothing already installed is downloaded twice.";
            } else if (DataBudget.exhausted(LinuxService.this)) {
                reason = "Today's mobile data limit is used up, so the download was stopped. It "
                        + "carries on over Wi-Fi, after midnight, or with a higher limit in Settings.";
            }
            if (reason != null) {
                status("Stopped to protect the phone", reason, -1, false, true);
                recordStop(reason);
                cancelJobs();
                return;
            }
            // Heat is the one condition that comes back on its own, so it PAUSES the work
            // instead of ending it. Killing a set-up mid-apt is what cost the owner their
            // download twice over and left dpkg half-configured; a paused container uses no
            // processor at all, so the phone cools while every byte already fetched is kept.
            boolean hot = prefs.getBoolean(ContainerRuntime.KEY_THERMAL_GUARD, true)
                    && (probe.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL
                        || (probe.batteryTempC > 0 && probe.batteryTempC >= STOP_TEMPERATURE_C));
            if (hot && !pausedForHeat) {
                if (freezeJobs(true)) {
                    pausedForHeat = true;
                    pausedSince = SystemClock.elapsedRealtime();
                    String heat = probe.batteryTempC > 0
                            ? "It is at " + Math.round(probe.batteryTempC) + " °C. " : "";
                    status("Paused while the phone cools",
                            heat + "Nothing is lost and nothing will be downloaded twice — this "
                                    + "carries on by itself once the phone is cooler. Taking it off "
                                    + "the charger cools it fastest.",
                            -1, true, false);
                } else {
                    // An OEM that will not let us signal our own child keeps the old behaviour,
                    // rather than leaving a hot phone working on regardless.
                    String tooHot = "The phone got too hot, so the work was stopped. Let it cool for "
                            + "a few minutes, then start it again — it carries on from where it "
                            + "stopped, and nothing is downloaded twice.";
                    status("Stopped to protect the phone", tooHot, -1, false, true);
                    recordStop(tooHot);
                    cancelJobs();
                    return;
                }
            } else if (pausedForHeat) {
                boolean cool = probe.thermalStatus < PowerManager.THERMAL_STATUS_SEVERE
                        && (probe.batteryTempC <= 0 || probe.batteryTempC <= RESUME_TEMPERATURE_C);
                if (cool) {
                    freezeJobs(false);
                    pausedForHeat = false;
                    status("Carrying on", "The phone has cooled down.", -1, true, false);
                } else if (SystemClock.elapsedRealtime() - pausedSince > MAX_PAUSE_MS) {
                    // Three quarters of an hour without cooling is a phone that cannot finish
                    // this today. Let it go rather than hold a wake lock for ever.
                    freezeJobs(false);
                    pausedForHeat = false;
                    String tooLong = "The phone stayed too hot to carry on. Nothing is lost: tap "
                            + "Continue set-up when it is cool, and it goes on from the step it "
                            + "reached without downloading anything twice.";
                    status("Stopped to protect the phone", tooLong, -1, false, true);
                    recordStop(tooLong);
                    cancelJobs();
                    return;
                }
            }
            renewWakeLocks();
            handler.postDelayed(this, pausedForHeat ? 20_000L : 30_000L);
        }
    };

    /**
     * Freezes or thaws the container of a running set-up or install.
     *
     * SIGSTOP on PRoot is enough to still everything inside it: PRoot traces every syscall its
     * children make, so once the tracer stops, each child blocks at its next one and the whole
     * container uses no processor. SIGCONT starts it again exactly where it was -- no partial
     * download is thrown away, and dpkg is never interrupted between unpacking and configuring.
     *
     * @return true when at least one process was signalled, so the caller knows the pause is real
     */
    private boolean freezeJobs(boolean freeze) {
        int signal = freeze ? 19 : 18;                       // SIGSTOP / SIGCONT
        boolean any = false;
        Process[] processes = {activeProcess, installProcess};
        for (Process process : processes) {
            if (process == null) continue;
            try {
                if (!process.isAlive()) continue;
                android.os.Process.sendSignal((int) process.pid(), signal);
                any = true;
            } catch (Throwable ignored) {
                // An OEM that will not let us signal our own child keeps the old behaviour:
                // the caller sees false and the guard stops the job instead of pausing it.
            }
        }
        return any;
    }

    /** Ends a running set-up or install: the same path the Stop button uses for the desktop. */
    private void cancelJobs() {
        stopRequested = true;
        // A frozen container can act on nothing but SIGKILL, so thaw it before ending it --
        // otherwise the stop leaves a live stopped PRoot and a worker blocked in readLine().
        if (pausedForHeat) {
            freezeJobs(false);
            pausedForHeat = false;
        }
        Thread worker = workerThread;
        if (worker != null) worker.interrupt();
        Future<?> task = currentTask;
        if (task != null) task.cancel(true);
        Future<?> install = installTask;
        if (install != null) install.cancel(true);
        Process active = activeProcess;
        if (active != null) {
            active.destroy();
            if (active.isAlive()) active.destroyForcibly();
        }
        Process installing = installProcess;
        if (installing != null) {
            installing.destroy();
            if (installing.isAlive()) installing.destroyForcibly();
        }
    }

    private final Runnable safetyMonitor = new Runnable() {
        @Override public void run() {
            if (!desktopRunning) return;
            SharedPreferences prefs = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
            prefs.edit().putLong(ContainerRuntime.KEY_HEARTBEAT_AT, System.currentTimeMillis()).apply();
            int minutes = prefs.getInt(ContainerRuntime.KEY_SESSION_MINUTES,
                    ContainerRuntime.SESSION_SMART);
            long elapsed = System.currentTimeMillis() - sessionStartedAt;
            if (minutes == ContainerRuntime.SESSION_SMART) {
                String reason = smartStopReason();
                if (reason != null) {
                    status("The Linux computer stopped by itself", reason, 100, false, false);
                    recordStop(reason);
                    stopEverything(false);
                    return;
                }
            } else if (minutes > 0 && elapsed >= minutes * 60_000L) {
                String reason = "The " + minutes + "-minute timer chosen in Settings ran out. Nothing was lost.";
                status("The Linux computer stopped by itself", reason, 100, false, false);
                recordStop(reason);
                stopEverything(false);
                return;
            }
            if (DataBudget.exhausted(LinuxService.this)) {
                String reason = "Today's mobile data limit is used up, so the Linux computer was "
                        + "stopped to stay within it. Open it again on Wi-Fi, after midnight, or "
                        + "with a higher limit in Settings.";
                status("The Linux computer stopped by itself", reason, 100, false, false);
                recordStop(reason);
                stopEverything(false);
                return;
            }
            DeviceProbe probe = DeviceProbe.read(LinuxService.this);
            if (prefs.getBoolean(ContainerRuntime.KEY_THERMAL_GUARD, true)) {
                // Android throttles hard at SEVERE; the session is only ended at CRITICAL or a
                // genuinely hot battery, so ordinary warm-phone coding is never interrupted.
                // Each limb names its own sensor: quoting the battery temperature for a hot
                // processor told the owner "the phone reached 38°C", which reads like a bug.
                boolean batteryHot = probe.batteryTempC > 0 && probe.batteryTempC >= STOP_TEMPERATURE_C;
                if (batteryHot || probe.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
                    String reason = batteryHot
                            ? String.format(Locale.ROOT,
                                    "The battery reached %.0f°C, so the Linux computer was closed to protect "
                                            + "it. Let the phone cool for a few minutes, then open it again.",
                                    probe.batteryTempC)
                            : "The phone's processor reached its safety limit, so the Linux computer was "
                                    + "closed before the phone throttled itself further. Let it cool for a "
                                    + "few minutes, then open it again.";
                    status("Stopped to cool down", reason, -1, false, true);
                    recordStop(reason);
                    stopEverything(false);
                    return;
                }
                if (probe.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                        || (probe.batteryTempC > 0 && probe.batteryTempC >= WARN_TEMPERATURE_C)) {
                    updateNotification("Phone is warm", "Linux is still running. Take a short break if it gets hotter.", -1);
                }
            }
            // Never behind the heat switch: a flat battery is not a comfort setting, and turning
            // off Overheat protection used to turn this off with it.
            if (probe.batteryPercent >= 0 && probe.batteryPercent <= 3
                    && !DeviceProbe.isCharging(LinuxService.this)) {
                String reason = "Battery reached 3%. Charge the phone, then open the desktop again.";
                status("Stopped at 3% battery", reason, -1, false, true);
                recordStop(reason);
                stopEverything(false);
                return;
            }
            handler.postDelayed(this, 30_000L);
        }
    };

    /**
     * Why Smart mode would end this session now, or null to let it keep running.
     *
     * A fixed timer measures the wrong thing: it cannot tell a session being worked in from one
     * left open by accident. These three can. Each returns a sentence rather than a code, because
     * a desktop that closed itself should be able to say why.
     */
    private String smartStopReason() {
        long idleMinutes = (System.currentTimeMillis() - lastInteractionAt()) / 60_000L;
        if (idleMinutes >= ContainerRuntime.SMART_IDLE_MINUTES) {
            return "Nothing was touched for " + idleMinutes + " minutes, so the session was closed "
                    + "to save battery. Open the desktop again whenever you like.";
        }
        DeviceProbe probe = DeviceProbe.read(this);
        if (probe.batteryPercent >= 0 && probe.batteryPercent < ContainerRuntime.SMART_BATTERY_FLOOR
                && !DeviceProbe.isCharging(this)) {
            return "Battery reached " + probe.batteryPercent + "%. Plug in the phone and open the "
                    + "desktop again.";
        }
        return null;
    }

    /**
     * Why the Linux computer stopped, kept for the home screen. A stop the owner asked for
     * records nothing; a stop Android forced records nothing either, so a stale reason is
     * never shown for a later, different stop (the home screen checks the timestamps).
     */
    private void recordStop(String reason) {
        stoppedForReason = true;
        getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE).edit()
                .putLong(ContainerRuntime.KEY_LAST_STOP_AT, System.currentTimeMillis())
                .putString(ContainerRuntime.KEY_LAST_STOP_REASON, reason)
                .apply();
    }

    /** The last time anything was typed or tapped on the desktop, or when it opened. */
    private long lastInteractionAt() {
        long touched = VncView.lastInteractionAt;
        return touched > 0 ? Math.max(touched, sessionStartedAt) : sessionStartedAt;
    }

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
        reconcileUncleanStop(this);
        // An install while the desktop is open: the desktop keeps its executor and its
        // process, the install gets its own of each, and the new app appears on the running
        // desktop when it is done. Waiting for the desktop to be stopped first was the reason
        // the Apps tab went grey whenever the computer was on.
        if ((ACTION_INSTALL_APP.equals(action) || ACTION_UNINSTALL_APP.equals(action)) && isDesktopRunning()) {
            final boolean removing = ACTION_UNINSTALL_APP.equals(action);
            if (!INSTALLING.compareAndSet(false, true)) {
                status("PocketDesk is busy", "A task is already running; wait for it to finish.", -1, true, false);
                return START_NOT_STICKY;
            }
            // A running desktop holds no wake lock of its own (the screen does, while it is on),
            // so the download takes one, or the package fetch would stall with the screen off.
            acquireInstallWakeLock();
            stopRequested = false;
            pausedForHeat = false;
            pausedSince = 0L;
            handler.removeCallbacks(jobMonitor);
            handler.postDelayed(jobMonitor, 30_000L);
            installTask = installExecutor.submit(() -> {
                try {
                    if (removing) uninstallApp(appId); else installApp(appId);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    status("Cancelled", "The desktop is still running.", -1, false, false);
                } catch (Exception error) {
                    status("Could not complete task", cleanError(error), -1, false, true);
                } finally {
                    INSTALLING.set(false);
                    releaseInstallWakeLock();
                    if (!desktopRunning && !BUSY.get()) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    }
                }
            });
            return START_NOT_STICKY;
        }
        // Deleting or setting up needs the computer closed. Queueing either behind an open
        // desktop session left BUSY true for the whole session: every button went grey, the
        // Apps tab stopped working, and the delete ran hours later when the desktop ended.
        if ((ACTION_REMOVE.equals(action) || ACTION_SETUP.equals(action)) && isDesktopRunning()) {
            status("Stop the Linux computer first",
                    ACTION_REMOVE.equals(action)
                            ? "Deleting needs the computer closed. Tap Stop, then Delete."
                            : "Set-up needs the computer closed. Tap Stop, then try again.",
                    -1, false, true);
            return START_NOT_STICKY;
        }
        if (ACTION_START_DESKTOP.equals(action) && isDesktopRunning()) {
            // Already open: say so instead of queueing a second session behind the first.
            status("The desktop is running", "Tap Back to desktop to return to it.", -1, false, false);
            return START_NOT_STICKY;
        }
        if (ACTION_REMOVE.equals(action) && isInstalling()) {
            status("An app is installing", "Wait for it to finish, then delete the computer.", -1, false, true);
            return START_NOT_STICKY;
        }
        if (!BUSY.compareAndSet(false, true)) {
            status("PocketDesk is busy", desktopRunning ? "Desktop is already running." : "Wait for the current task to finish.", -1, true, false);
            return START_NOT_STICKY;
        }
        acquireTaskWakeLock();
        stopRequested = false;
        if (!ACTION_START_DESKTOP.equals(action)) {
            // A desktop session has its own monitor; a download does not, and needs one.
            pausedForHeat = false;
            pausedSince = 0L;
            handler.removeCallbacks(jobMonitor);
            handler.postDelayed(jobMonitor, 30_000L);
        }
        currentTask = executor.submit(() -> {
            workerThread = Thread.currentThread();
            try {
                if (ACTION_SETUP.equals(action)) setupUbuntu();
                else if (ACTION_START_DESKTOP.equals(action)) startDesktop();
                else if (ACTION_INSTALL_APP.equals(action)) installApp(appId);
                else if (ACTION_UNINSTALL_APP.equals(action)) uninstallApp(appId);
                else if (ACTION_REMOVE.equals(action)) removeLinux();
                else status("Unknown action", "Nothing was changed.", -1, false, true);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                // The monitor or the Stop button already said why it ended; that reason stays,
                // and this must not overwrite it with "Task cancelled" a moment later.
                if (!stoppedForReason && !stopRequested) {
                    status("Task cancelled", "No background task is running.", -1, false, false);
                }
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) {
                    if (!stoppedForReason && !stopRequested) {
                        status("Task cancelled", "No background task is running.", -1, false, false);
                    }
                } else {
                    String message = cleanError(error);
                    status("Could not complete task", message, -1, false, true);
                }
            } finally {
                workerThread = null;
                BUSY.set(false);
                releaseTaskWakeLock();
                // Only the last one out turns off the lights: an install running beside a desktop
                // that just died still needs the service, its notification and its wake lock.
                if (!desktopRunning && !INSTALLING.get()) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(safetyMonitor);
        handler.removeCallbacks(jobMonitor);
        releaseTaskWakeLock();
        releaseInstallWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    static boolean isBusy() { return BUSY.get(); }

    /** True while an app is being installed, beside an open desktop or not. */
    static boolean isInstalling() { return INSTALLING.get() || (BUSY.get() && installingNow); }

    private static volatile boolean installingNow;

    static boolean isDesktopRunning() {
        Process process = activeProcess;
        return desktopRunning && process != null && process.isAlive();
    }

    /**
     * Notices a desktop that ended without the service seeing it end: the alive flag is still
     * set while nothing is running. That is Android ending the whole app while the desktop
     * was open, nearly always to take its memory back for another app; the heartbeat says
     * when. Written down as the last stop, so the home screen can say so instead of nothing.
     */
    static void reconcileUncleanStop(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(ContainerRuntime.KEY_DESKTOP_ALIVE, false)) return;
        if (isDesktopRunning() || BUSY.get()) return;
        long at = prefs.getLong(ContainerRuntime.KEY_HEARTBEAT_AT, 0L);
        if (at == 0L) at = System.currentTimeMillis();
        prefs.edit()
                .putBoolean(ContainerRuntime.KEY_DESKTOP_ALIVE, false)
                .putLong(ContainerRuntime.KEY_LAST_STOP_AT, at)
                .putString(ContainerRuntime.KEY_LAST_STOP_REASON,
                        "Android ended PocketDesk while the desktop was open, which it does to take "
                                + "memory back when the phone runs short. Nothing was lost. Keep one AI "
                                + "app open at a time, and close the browser when you are done with it.")
                .apply();
    }

    /**
     * One set-up, in parts that can be repeated safely.
     *
     * Nothing already finished is done twice: a download continues from the byte it stopped at,
     * an unpacked Ubuntu is kept as it is, and the package steps inside the container each
     * remember that they finished. So a set-up stopped by the owner, a flat battery or Android
     * carries on from where it stopped when it is started again.
     */
    private void setupUbuntu() throws Exception {
        preflight(true, 10);
        SharedPreferences preferences = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
        // Read before stamping: apply() updates the in-memory map straight away, so reading it
        // afterwards always saw "started" and the whole resume path was dead code.
        String previousStage = preferences.getString(ContainerRuntime.KEY_SETUP_STAGE, "");
        boolean finishedBefore = preferences.getBoolean(ContainerRuntime.KEY_DESKTOP_INSTALLED, false);
        // Never write a value that says less than what is already known: overwriting "unpacked"
        // with "started" throws away the only proof the base system is on the phone, and any
        // failure before the next write would leave it thrown away for good -- 550 MB again.
        if (!"unpacked".equals(previousStage)) {
            preferences.edit().putString(ContainerRuntime.KEY_SETUP_STAGE, "started").commit();
        }
        status("Preparing Linux", "Getting the local Linux system ready…", 2, true, false);
        ContainerRuntime.installRuntime(this);

        File root = ContainerRuntime.rootfs(this);
        File archive = ContainerRuntime.downloadFile(this);
        // Only a run that finished unpacking may be continued: the marker and the two binaries
        // have to agree, or a half-written system would be handed to the package steps.
        // The proof lives where the base system lives. A preference is written asynchronously
        // and dies with the process; losing it used to cost the whole rootfs at the deleteTree
        // below, and every package already paid for with it. The preference stays as a fallback
        // so a phone already part-way through this update is not wiped by it.
        File unpackedMark = new File(root, "var/lib/pocketdesk/unpacked");
        boolean unpacked = !finishedBefore
                && (unpackedMark.isFile() || "unpacked".equals(previousStage))
                && new File(root, "usr/bin/apt-get").isFile()
                && new File(root, "usr/bin/dpkg").isFile();
        // A container that once finished set-up is never wiped by starting set-up again: if the
        // proof file (usr/bin/Xtigervnc) is lost to an apt removal or an interrupted upgrade,
        // the computer reads as "not set up", and starting over would delete every app, sign-in
        // and file with it. The staged bootstrap repairs it instead.
        //
        // Those three files cannot be the proof on their own -- they all land early in the base
        // archive, so a half-finished extraction has them while /usr/lib is still missing. The
        // proof is a finished unpacking, or a set-up that once completed.
        boolean established = (unpacked || finishedBefore)
                && new File(root, "etc/os-release").isFile()
                && new File(root, "usr/bin/apt-get").isFile()
                && new File(root, "usr/bin/dpkg").isFile();
        if (established && !unpacked) {
            status("Repairing the computer",
                    "Ubuntu is already on this phone. The missing parts are being installed; "
                            + "nothing of yours is deleted.", 38, true, false);
            // A finished-step mark is a promise, not proof: the package steps return early on
            // the mark alone. Where the proof is gone, the mark goes with it, so the step that
            // installs the display server really runs again.
            if (!new File(root, "usr/bin/Xtigervnc").isFile()) {
                new File(root, "var/lib/pocketdesk/stage/desktop").delete();
                new File(root, "var/lib/pocketdesk/stage/extras").delete();
            }
        } else if (unpacked) {
            // Everything below this point is repeatable, so the 30 MB download and the unpacking
            // are simply skipped: this is what makes "Continue set-up" continue.
            status("Continuing set-up", "Ubuntu is already on the phone; carrying on from where "
                    + "it stopped.", 38, true, false);
        } else {
            String expected = ContainerRuntime.UBUNTU_SHA256;
            if (!archive.isFile() || !expected.equalsIgnoreCase(sha256(archive))) {
                if (archive.exists() && !archive.delete()) throw new IOException("Could not replace old Ubuntu download");
                try {
                    download(ContainerRuntime.UBUNTU_MIRRORS, archive, "Downloading Ubuntu");
                } catch (IOException gone) {
                    // The pinned point release has been pruned from Canonical's directory, which
                    // happens to every APK eventually. Take the newest base image published there
                    // now, with the digest published beside it.
                    String[] current = currentBaseImage();
                    if (current == null) throw gone;
                    status("Downloading Ubuntu", "Using the current Ubuntu 24.04 LTS base image…",
                            -1, true, false);
                    expected = current[1];
                    download(new String[]{current[0]}, archive, "Downloading Ubuntu");
                }
            }
            status("Checking download", "Verifying that the Linux download is safe and complete…", 36, true, false);
            String actual = sha256(archive);
            if (!expected.equalsIgnoreCase(actual)) {
                archive.delete();
                throw new IOException("Ubuntu checksum did not match. The download was removed for safety.");
            }
            if (root.exists()) ContainerRuntime.deleteTree(root);
            status("Installing Linux files", "Saving Linux inside private app storage…", 40, true, false);
            try (FileInputStream input = new FileInputStream(archive)) {
                TarGzExtractor.extract(input, root, (count, name) -> {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (count % 500 == 0) status("Installing Linux files", count + " files prepared", 45, true, false);
                });
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        }
        // Written for every branch, not just a fresh extraction, so a rootfs unpacked by an
        // older version is back-filled the first time it resumes under this one.
        try {
            File markDirectory = new File(root, "var/lib/pocketdesk");
            if (markDirectory.isDirectory() || markDirectory.mkdirs()) {
                try (FileOutputStream mark = new FileOutputStream(unpackedMark)) {
                    mark.write(ContainerRuntime.UBUNTU_SHA256.getBytes("UTF-8"));
                    mark.getFD().sync();
                }
            }
        } catch (IOException couldNotMark) {
            // The preference below still carries the resume, exactly as it did before.
        }
        preferences.edit().putString(ContainerRuntime.KEY_SETUP_STAGE, "unpacked").commit();

        final long toolsStartedAt = System.currentTimeMillis();
        status("Setting up the desktop",
                "The longest part · usually 15\u201340 min in total", -1, true, false);
        final long[] lastLine = {0L};
        // Counting the bytes apt announces is what turns "usually 15-40 min" into something the
        // owner can act on: they can see the download moving, and see it stop moving.
        final long[] fetched = {0L};
        int code = runTracked(ContainerRuntime.bootstrapCommand(), line -> {
            long size = fetchedBytes(line);
            if (size > 0) fetched[0] += size;
            long now = System.currentTimeMillis();
            if (now - lastLine[0] < 900L) return;
            lastLine[0] = now;
            String downloaded = fetched[0] > 0
                    ? " · " + DeviceProbe.formatBytes(fetched[0]) + " downloaded" : "";
            status("Setting up the desktop",
                    phaseFor(line) + downloaded + " · " + elapsedText(toolsStartedAt)
                            + " · usually 15\u201340 min in total",
                    -1, true, false);
        });
        if (code != 0) {
            String reason = ContainerRuntime.setupFailureReason(code);
            throw new IOException(reason != null ? reason
                    : "Set-up stopped while installing packages (code " + code + "). Check the "
                    + "internet connection and free space, then tap Continue set-up — it carries "
                    + "on from where it stopped.");
        }
        File proof = new File(root, "usr/bin/Xtigervnc");
        if (!proof.isFile()) {
            // dpkg can still record the package as installed while its files are gone, and apt
            // then says "already the newest version" and does nothing. Only --reinstall puts it
            // back -- and if even that fails, saying "Linux is ready" would be a lie that leaves
            // the owner tapping Set up for ever.
            status("Repairing the computer", "Putting the desktop's display back…", 92, true, false);
            runTracked("set -eu; export DEBIAN_FRONTEND=noninteractive; apt-get update; "
                    + "apt-get install -y --reinstall --no-install-recommends "
                    + "tigervnc-standalone-server openbox tint2", line -> {});
            if (!proof.isFile()) {
                throw new IOException("The desktop's display server could not be put back. Nothing "
                        + "of yours was deleted — your apps, sign-ins and files are still inside "
                        + "the computer. Try again on a better connection; if it keeps failing, "
                        + "Settings → Storage → Delete the Linux computer and set it up again.");
            }
        }
        ContainerRuntime.writeDesktopScripts(this);
        ContainerRuntime.invalidateSize();
        preferences.edit()
                .putBoolean(ContainerRuntime.KEY_DESKTOP_INSTALLED, true)
                .remove(ContainerRuntime.KEY_SETUP_STAGE)
                .remove(ContainerRuntime.KEY_SHARE_DOWNLOADS)
                .remove(ContainerRuntime.KEY_PROOT_NO_SECCOMP).apply();
        archive.delete();
        status("Linux is ready", "Your local desktop and developer tools are installed.", 100, false, false);
    }

    private void uninstallApp(String appId) throws Exception {
        LinuxApps.App app = LinuxApps.byId(appId);
        if (app == null) throw new IOException("Unknown app.");
        if (!ContainerRuntime.isInstalled(this)) throw new IOException("Nothing to uninstall.");
        if (!app.removable()) throw new IOException(app.name + " is part of the computer and stays.");
        final long startedAt = System.currentTimeMillis();
        status("Uninstalling " + app.name, "Freeing the space it used", -1, true, false);
        installingNow = true;
        int code;
        try {
            code = runInstall(app.uninstallCommand(), line -> {});
        } finally {
            installingNow = false;
        }
        if (code != 0) {
            throw new IOException(app.name + " could not be uninstalled (exit " + code + ").");
        }
        ContainerRuntime.refreshDesktopEntries(this);
        try { runInstall("/usr/local/bin/pocketdesk-menu || true", null); } catch (Exception ignored) {}
        ContainerRuntime.invalidateSize();
        status(app.name + " was uninstalled",
                "Its space is freed. Install it again any time from the Apps tab.", 100, false, false);
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
        // The last few lines the container said, kept for the moment it fails. "It did not
        // install" with no reason is the message that leaves an owner with nowhere to go; the
        // computer usually said exactly what went wrong one line earlier.
        final java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        installingNow = true;
        int code;
        try {
            code = runInstall(app.installCommand(), line -> {
                String trimmed = line == null ? "" : line.trim();
                if (!trimmed.isEmpty() && !isTransferNoise(trimmed)) {
                    tail.addLast(trimmed);
                    while (tail.size() > 6) tail.removeFirst();
                }
                long now = System.currentTimeMillis();
                if (now - lastLine[0] < 900L) return;
                lastLine[0] = now;
                status("Installing " + app.name,
                        phaseFor(line) + " · " + elapsedText(startedAt) + " · usually " + app.typicalTime,
                        -1, true, false);
            });
        } finally {
            installingNow = false;
        }
        if (code != 0) {
            String reason = ContainerRuntime.setupFailureReason(code);
            StringBuilder said = new StringBuilder();
            for (String one : tail) said.append(said.length() == 0 ? "" : "\n").append(one);
            throw new IOException((reason != null ? reason
                    : app.name + " did not finish installing (code " + code + "). Check the "
                    + "internet connection and free space, then tap the row again — what was "
                    + "already downloaded is kept.")
                    + (said.length() == 0 ? ""
                        : "\n\nWhat the computer said last:\n" + said));
        }
        ContainerRuntime.refreshDesktopEntries(this);
        // Refresh the desktop's own menu, panel and icons so the new app is there at once --
        // on a desktop that is open right now as well as at the next start.
        try {
            runInstall("/usr/local/bin/pocketdesk-menu || true", null);
        } catch (Exception ignored) {
            // The menu is rebuilt at the next desktop start anyway.
        }
        ContainerRuntime.invalidateSize();
        status(app.name + " is ready", isDesktopRunning()
                        ? "It is on the desktop now: tap its icon, or open the Apps menu."
                        : "Open the desktop, then tap its icon or open the Apps menu.",
                100, false, false);
    }

    /** A container command for an install: its own process, so the desktop's is untouched. */
    private int runInstall(String command, ContainerRuntime.OutputListener listener)
            throws IOException, InterruptedException {
        Process process = ContainerRuntime.startContainer(this, command);
        installProcess = process;
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
            installProcess = null;
        }
        return process.waitFor();
    }

    private void startDesktop() throws Exception {
        if (!ContainerRuntime.isInstalled(this)) throw new IOException("Set up the Linux computer first.");
        preflight(false, 4);
        ContainerRuntime.installRuntime(this);
        // Refresh the desktop scripts and every installed app's launcher on each start, so a
        // container set up by an older version picks up the current desktop without reinstalling.
        ContainerRuntime.writeDesktopScripts(this);
        status("Opening the desktop", "Starting your Linux computer…", -1, true, false);
        SharedPreferences prefs = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
        prefs.edit().remove(ContainerRuntime.KEY_LAST_STOP_REASON).apply();
        stoppedForReason = false;
        stopRequested = false;
        int[] geometry = DeviceProbe.desktopGeometry(this, ContainerRuntime.GEOMETRY_CAP);
        // The desktop is born the way the phone is held. It used to start landscape whatever
        // the phone was doing, and the viewer then had to ask for a portrait desktop, showing
        // a cropped sideways one in the meantime.
        if (getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            geometry = new int[]{geometry[1], geometry[0]};
        }
        int dpi = prefs.getInt(ContainerRuntime.KEY_UI_SCALE, ContainerRuntime.DEFAULT_UI_SCALE);
        // Downloads live inside the computer, where no other app on the phone can read them.
        // The Shared folder is the way out to the phone's own Files app, and Phone files is the
        // way in; a toggle that quietly moved the folder about was one choice too many.
        String command = ContainerRuntime.startDesktopCommand(geometry[0], geometry[1], dpi, false);
        geometry = ContainerRuntime.safeGeometry(geometry[0], geometry[1]);

        // Off by default now: the seccomp accelerator breaks Chromium/Electron apps (see
        // KEY_FAST_DESKTOP). The owner can turn Faster desktop on to try it; if that start
        // never draws a display, the next attempt drops back to the reliable path.
        boolean accelerated = false;   // never: it breaks every Chromium app (see KEY_FAST_DESKTOP)
        boolean fellBack = false;
        File sessionLog = new File(ContainerRuntime.rootfs(this), "home/coder/.pocketdesk/logs/desktop-session.log");
        File logParent = sessionLog.getParentFile();
        if (logParent != null) logParent.mkdirs();
        sessionLog.delete();
        for (int attempt = 0; ; attempt++) {
            activeProcess = ContainerRuntime.startContainer(this, command, accelerated);
            sessionStartedAt = System.currentTimeMillis();
            recordOutput(activeProcess, sessionLog, attempt);
            // Fifteen seconds was never enough: this phone takes half a minute to put the
            // display up, so the wait expired, the session was killed, and the home screen
            // reported a failure for a desktop that was only slow. Wait as long as the viewer
            // does, and say how it is going.
            boolean ready = false;
            for (int i = 0; i < 600 && activeProcess.isAlive(); i++) {
                if (VncClient.canConnect(new File(ContainerRuntime.rootfs(this),
                                "home/coder/.pocketdesk/vnc.sock").getAbsolutePath())
                        || VncClient.canConnect("127.0.0.1", 5901, 250)) {
                    ready = true;
                    break;
                }
                if (i > 0 && i % 20 == 0) {
                    status("Opening the desktop", "Starting the display… " + (i / 4) + "s", -1, true, false);
                }
                Thread.sleep(250);
            }
            if (ready) break;
            int exit = activeProcess.isAlive() ? -1 : activeProcess.exitValue();
            activeProcess.destroyForcibly();
            activeProcess = null;
            // The first, accelerated try never produced a display -- it died, or it hung until
            // the wait ran out. Either is what a kernel that cannot run PRoot's accelerator
            // looks like, so the second try goes without it. The answer is remembered only if
            // that second try works: a death for some other reason (memory, a broken install)
            // must not condemn every later start to the slow mode.
            if (accelerated && attempt == 0) {
                accelerated = false;
                fellBack = true;
                status("Opening the desktop", "Starting again in compatibility mode…", -1, true, false);
                continue;
            }
            throw new IOException("The display did not start" + (exit >= 0 ? " (exit " + exit + ")" : "")
                    + ". Open the desktop again; if it repeats, run Setup once more.");
        }
        if (fellBack) prefs.edit().putBoolean(ContainerRuntime.KEY_PROOT_NO_SECCOMP, true).apply();
        prefs.edit().putLong(ContainerRuntime.KEY_LAST_OPENED_AT, System.currentTimeMillis())
                .putLong(ContainerRuntime.KEY_HEARTBEAT_AT, System.currentTimeMillis())
                .putBoolean(ContainerRuntime.KEY_DESKTOP_ALIVE, true).apply();
        desktopRunning = true;
        BUSY.set(false);
        releaseTaskWakeLock();
        status("The Linux computer is running",
                "Desktop " + geometry[0] + "×" + geometry[1] + " · tap Open desktop", 100, false, false);
        updateNotification("The Linux computer is running", "Tap to return · phone protection is active", 100);
        handler.removeCallbacks(safetyMonitor);
        handler.postDelayed(safetyMonitor, 30_000L);

        int exitCode = activeProcess.waitFor();
        activeProcess = null;
        desktopRunning = false;
        handler.removeCallbacks(safetyMonitor);
        prefs.edit().putBoolean(ContainerRuntime.KEY_DESKTOP_ALIVE, false).apply();
        // Nobody asked for this stop and no rule announced it: the display server itself ended.
        // On a phone that is nearly always the kernel taking memory back from the largest
        // program it can find, which was the display's own process.
        if (!stopRequested && !stoppedForReason) {
            recordStop("The desktop's display ended by itself (exit " + exitCode + "), which on a "
                    + "phone nearly always means memory ran out. Nothing was lost. Keep one AI app "
                    + "open at a time, and close the browser when you are done with it.");
        }
        status("The Linux computer is stopped", "Everything on it is kept for the next open.", 100, false, false);
    }

    /**
     * The display server narrates as it works; it goes to a file, not the notification. The
     * file is appended to and flushed line by line, so a second attempt's output follows the
     * first attempt's instead of wiping it -- the first attempt's last lines are the diagnosis.
     */
    private void recordOutput(Process process, File sessionLog, int attempt) {
        Thread output = new Thread(() -> {
            if (process == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 java.io.PrintWriter writer = new java.io.PrintWriter(new FileOutputStream(sessionLog, true), true)) {
                writer.println("--- start attempt " + (attempt + 1) + " ---");
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) writer.println(line);
                }
            } catch (IOException ignored) {}
        }, "pocketdesk-linux-output-" + attempt);
        output.setDaemon(true);
        output.start();
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
        // Smart stopping keeps the computer off below its battery floor unless charging. The
        // desktop used to open at 9 % and be stopped thirty seconds later by the same rule,
        // which read as the app breaking; the rule now speaks before the start instead.
        SharedPreferences prefs = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
        if (!download && !DeviceProbe.isCharging(this)
                && prefs.getInt(ContainerRuntime.KEY_SESSION_MINUTES, ContainerRuntime.SESSION_SMART)
                        == ContainerRuntime.SESSION_SMART
                && probe.batteryPercent >= 0 && probe.batteryPercent < ContainerRuntime.SMART_BATTERY_FLOOR) {
            throw new IOException("Battery is at " + probe.batteryPercent + "%. Smart stopping keeps the "
                    + "Linux computer off below " + ContainerRuntime.SMART_BATTERY_FLOOR + "% unless the "
                    + "charger is connected. Plug in, or choose a fixed timer or Never stop in Settings.");
        }
        // The daily limit is a limit on everything: a desktop on mobile data uses data too.
        if (DataBudget.exhausted(this)) {
            int cap = DataBudget.capMb(getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE));
            throw new IOException("Today's mobile data limit (" + DeviceProbe.formatBytes(cap * 1_000_000L)
                    + ") is used up. Connect to Wi-Fi, raise the limit in Settings, or wait "
                    + "for midnight when it resets.");
        }
        if (download) {
            if (!DeviceProbe.hasInternet(this)) throw new IOException("Connect to the internet first.");
            boolean wifiOnly = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE)
                    .getBoolean(ContainerRuntime.KEY_WIFI_ONLY, false);
            if (wifiOnly && !DeviceProbe.isWifi(this)) {
                throw new IOException("Wi-Fi-only download is enabled. Connect to Wi-Fi, or turn it off in Settings.");
            }
        }
    }

    /**
     * Downloads with resume and mirror failover. A dropped mobile-data connection continues
     * from the byte it stopped at instead of starting the whole archive again.
     */
    /**
     * The newest arm64 base image Canonical publishes for this release, and its digest.
     *
     * Read from the release directory's own SHA256SUMS, over HTTPS to Canonical's own host --
     * the same trust as the pinned URL itself. Returns {url, sha256}, or null if the listing
     * cannot be read or holds no arm64 base image, in which case the caller reports the original
     * failure rather than inventing one.
     */
    private String[] currentBaseImage() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    ContainerRuntime.UBUNTU_RELEASE_DIRECTORY + "SHA256SUMS").openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(40_000);
            connection.setRequestProperty("User-Agent", "PocketDesk");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            String best = null, digest = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // "<64 hex>  *ubuntu-base-24.04.4-base-arm64.tar.gz", the sha256sum format.
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length != 2 || parts[0].length() != 64) continue;
                    String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
                    if (!name.startsWith("ubuntu-base-") || !name.endsWith("-base-arm64.tar.gz")) continue;
                    // Plain text order is enough: the names differ only in the point number.
                    if (best == null || name.compareTo(best) > 0) {
                        best = name;
                        digest = parts[0];
                    }
                }
            }
            if (best == null) return null;
            return new String[]{ContainerRuntime.UBUNTU_RELEASE_DIRECTORY + best, digest};
        } catch (Exception unreachable) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

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
        if (isDesktopRunning()) throw new IOException("Stop the Linux computer before deleting it.");
        status("Deleting Linux", "Deleting the Ubuntu system…", -1, true, false);
        ContainerRuntime.deleteTree(ContainerRuntime.rootfs(this));
        File archive = ContainerRuntime.downloadFile(this);
        if (archive.exists()) archive.delete();
        File part = new File(archive.getAbsolutePath() + ".part");
        if (part.exists()) part.delete();
        getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE).edit()
                .putBoolean(ContainerRuntime.KEY_DESKTOP_INSTALLED, false)
                // Nothing is part way any more: the next set-up starts from the beginning.
                .remove(ContainerRuntime.KEY_SETUP_STAGE)
                .remove(ContainerRuntime.KEY_PROOT_NO_SECCOMP)
                .apply();
        ContainerRuntime.invalidateSize();
        status("Linux deleted", "The storage it was using is free again.", 100, false, false);
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
        stopRequested = true;
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
        Process install = installProcess;
        if (install != null) {
            install.destroy();
            if (install.isAlive()) install.destroyForcibly();
        }
        activeProcess = null;
        installProcess = null;
        desktopRunning = false;
        BUSY.set(false);
        getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE).edit()
                .putBoolean(ContainerRuntime.KEY_DESKTOP_ALIVE, false).apply();
        releaseTaskWakeLock();
        if (userRequested) status("The Linux computer is stopped", "Everything on it is kept for the next open.", 100, false, false);
        // An install running beside the desktop keeps the service and its own lock alive.
        if (!INSTALLING.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    static String lastMessage() { return lastMessage; }
    static String lastDetail() { return lastDetail; }
    static int lastProgress() { return lastProgress; }
    static boolean lastWasError() { return lastError; }

    private void status(String message, String detail, int progress, boolean busy, boolean error) {
        // Every report is a chance to attribute the bytes moved since the last one to the
        // network they moved on; the daily limit counts mobile data only.
        DataBudget.usedToday(this);
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

    /**
     * One lock per job, never one shared between them.
     *
     * A single field was acquired by an install and released by the desktop task finishing --
     * so a long download continued with the screen off and no lock, and Android suspended it
     * half way. Both fields are volatile: they are written from the main thread and from two
     * worker threads.
     */
    private PowerManager.WakeLock newWakeLock(String tag) {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return null;
        PowerManager.WakeLock lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        lock.setReferenceCounted(false);
        lock.acquire(WAKE_LOCK_MS);
        return lock;
    }

    private synchronized void acquireTaskWakeLock() {
        if (taskWakeLock != null && taskWakeLock.isHeld()) return;
        taskWakeLock = newWakeLock("PocketDeskLinux:task");
    }

    private synchronized void releaseTaskWakeLock() {
        PowerManager.WakeLock lock = taskWakeLock;
        taskWakeLock = null;
        if (lock != null && lock.isHeld()) lock.release();
    }

    private synchronized void acquireInstallWakeLock() {
        if (installWakeLock != null && installWakeLock.isHeld()) return;
        installWakeLock = newWakeLock("PocketDeskLinux:install");
    }

    private synchronized void releaseInstallWakeLock() {
        PowerManager.WakeLock lock = installWakeLock;
        installWakeLock = null;
        if (lock != null && lock.isHeld()) lock.release();
    }

    /**
     * Extends both job locks so a long set-up or app install keeps the processor.
     *
     * The lock was taken once with a two-hour timeout and never renewed, so a 550 MB set-up on
     * mobile data that ran past two hours lost the processor with the screen off, apt's fetch
     * died at its own timeout, and the job stopped at a late step -- the second half of the
     * owner's "it stops again near the end". setReferenceCounted(false) makes a repeat acquire
     * restart the timeout on the same lock rather than take a second one.
     *
     * Called only from the monitor, and only past its BUSY/INSTALLING check: a job that has just
     * finished clears its flag before releasing its lock, so renewing any earlier would bring a
     * finished job's lock back to life.
     */
    private synchronized void renewWakeLocks() {
        try {
            if (taskWakeLock != null) taskWakeLock.acquire(WAKE_LOCK_MS);
            if (installWakeLock != null) installWakeLock.acquire(WAKE_LOCK_MS);
        } catch (Throwable ignored) {
            // An OEM power manager that will not renew our own lock must not take the job down
            // with it: the work carries on, and stops early at worst, exactly as it does today.
        }
    }

    /** Human phase for a raw apt/dpkg/curl output line. */
    private static String phaseFor(String line) {
        String value = line == null ? "" : line.trim();
        // The container's own step lines, so the phone can say what is happening rather than
        // echoing package names at someone who did not ask for them.
        if (value.startsWith("PocketDesk:")) {
            // A failure line wins over the words it happens to contain: "Google Chrome did not
            // install this time" used to match the Chrome branch and be shown as progress.
            if (value.contains("did not install")) {
                return value.contains("Chrome") ? "Google Chrome did not install"
                        : "Some everyday tools did not install";
            }
            if (value.contains("already done")) return "Skipping a part that is already installed";
            if (value.contains("did not finish")) return "Connection hiccup · trying that part again";
            if (value.contains("Chrome")) return "Installing Google Chrome";
        }
        if (value.startsWith("Get:") || value.contains("Fetched")) return "Downloading packages";
        if (value.startsWith("Unpacking") || value.startsWith("Preparing to unpack")) return "Unpacking files";
        if (value.startsWith("Setting up") || value.startsWith("Processing triggers")) return "Finishing set-up";
        if (value.startsWith("Reading") || value.startsWith("Building") || value.startsWith("Selecting")) return "Getting packages ready";
        if (value.contains("% ") || value.startsWith("#")) return "Downloading";
        if (value.startsWith("Get:") || value.startsWith("Hit:")) return "Downloading packages";
        return "Working";
    }

    /**
     * The size apt prints at the end of a "Get:" line, in bytes, or -1 when there is none.
     *
     * apt writes it as "[2,927 kB]" or "[1,024 B]" or "[15.8 MB]", with the owner's own
     * thousands separator. Adding these up is the only way the phone can say how much of the
     * download has actually arrived: apt itself reports a total only when it has finished.
     */
    static long fetchedBytes(String line) {
        if (line == null || !line.startsWith("Get:")) return -1L;
        int open = line.lastIndexOf('[');
        int close = line.lastIndexOf(']');
        if (open < 0 || close < open) return -1L;
        String inside = line.substring(open + 1, close).replace(",", "").trim();
        int space = inside.lastIndexOf(' ');
        if (space <= 0) return -1L;
        String number = inside.substring(0, space).trim();
        String unit = inside.substring(space + 1).trim();
        double value;
        try {
            value = Double.parseDouble(number);
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
        if (value < 0) return -1L;
        switch (unit) {
            case "B": return (long) value;
            case "kB": return (long) (value * 1000L);
            case "MB": return (long) (value * 1000_000L);
            case "GB": return (long) (value * 1000_000_000L);
            default: return -1L;
        }
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
