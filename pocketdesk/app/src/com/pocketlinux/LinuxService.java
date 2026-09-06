package com.pocketlinux;

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
    static final String ACTION_SETUP = "com.pocketlinux.action.SETUP";
    static final String ACTION_START_DESKTOP = "com.pocketlinux.action.START_DESKTOP";
    static final String ACTION_INSTALL_APP = "com.pocketlinux.action.INSTALL_APP";
    static final String ACTION_UNINSTALL_APP = "com.pocketlinux.action.UNINSTALL_APP";
    static final String EXTRA_APP_ID = "app_id";
    static final String ACTION_STOP = "com.pocketlinux.action.STOP";
    static final String ACTION_REMOVE = "com.pocketlinux.action.REMOVE";
    static final String ACTION_STATUS = "com.pocketlinux.action.STATUS";
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
    // A live desktop is user-started foreground work too. Its independent CPU lease must
    // survive finishing setup or a parallel install, but expire promptly if renewal stops.
    private static final long DESKTOP_WAKE_LOCK_MS = 120_000L;
    private static final String CHANNEL_ID = "pocketdesk_linux";
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    /** An app install running beside an open desktop, which BUSY (the desktop's own task) is not. */
    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    private static volatile boolean desktopRunning;
    private static volatile boolean desktopStarting;
    private static final TaskGeneration PRIMARY_TASK = new TaskGeneration();
    private final ThreadLocal<Long> workerGeneration = new ThreadLocal<>();
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
    private volatile PowerManager.WakeLock desktopWakeLock;
    private volatile boolean serviceDestroyed;
    private long sessionStartedAt;
    private volatile long sessionGeneration;
    /** A bounded, fixed-label phase reported by our startup script, never an app URL. */
    private volatile String desktopStartupPhase = "Starting the Linux process";
    /** Set while the monitor is ending a session for a reason it has already announced. */
    private volatile boolean stoppedForReason;
    /** Set when the owner (the Stop button, the notification) asked for the desktop to end. */
    private volatile boolean stopRequested;
    /** Set while a set-up or install is frozen because the phone is too hot. */
    private volatile boolean pausedForHeat;
    private volatile long pausedSince;
    private volatile long timedJobStartedAt;
    private volatile String timedJobTitle;
    private volatile String timedJobPhase;
    private volatile String timedJobTypical;
    /** Mirrors the auto-installed compatibility layer into the same report as the app package. */

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
            if (!pausedForHeat && timedJobTitle != null && timedJobStartedAt > 0L) {
                status(timedJobTitle,
                        (timedJobPhase == null ? "Working" : timedJobPhase) + " · "
                                + elapsedText(timedJobStartedAt) + " · usually "
                                + (timedJobTypical == null ? "depends on the connection" : timedJobTypical),
                        -1, true, false);
            }
            renewWakeLocks();
            handler.postDelayed(this, pausedForHeat ? 20_000L
                    : timedJobTitle != null ? 10_000L : 30_000L);
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
                int pid = ProotProcess.pidOf(process);
                if (pid <= 0) continue;
                android.os.Process.sendSignal(pid, signal);
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
        RuntimeDiagnostics.sample(this, "Cancel setup/install requested", ProotProcess.trackingSummary());
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
            ProotProcess.requestStop(active);
        }
        Process installing = installProcess;
        if (installing != null) {
            ProotProcess.requestStop(installing);
        }
    }

    private final Runnable safetyMonitor = new Runnable() {
        @Override public void run() {
            if (!renewDesktopWakeLock(sessionGeneration)) return;
            RuntimeDiagnostics.sample(LinuxService.this, "Desktop runtime sample", ProotProcess.trackingSummary());
            SharedPreferences prefs = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
            prefs.edit().putLong(ContainerRuntime.KEY_HEARTBEAT_AT, System.currentTimeMillis()).apply();
            // The job monitor owns heat, battery and data guards during an install. A desktop
            // idle/session timer must not call stopEverything() and kill that background job.
            if (installingNow || INSTALLING.get() || timedJobTitle != null) {
                handler.postDelayed(this, 30_000L);
                return;
            }
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
            return "Nothing was touched and the computer was doing nothing for " + idleMinutes
                    + " minutes, so the session was closed to save battery. Open the desktop "
                    + "again whenever you like.";
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
        synchronized (PRIMARY_TASK) {
            Long generation = workerGeneration.get();
            if (generation != null && !PRIMARY_TASK.isCurrent(generation)) return;
            stoppedForReason = true;
            getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE).edit()
                    .putLong(ContainerRuntime.KEY_LAST_STOP_AT, System.currentTimeMillis())
                    .putString(ContainerRuntime.KEY_LAST_STOP_REASON, reason)
                    .apply();
        }
    }


    /**
     * The last time anything was typed or tapped on the desktop, the computer inside it did some
     * real work, or the session opened.
     *
     * "Nothing was touched" used to mean exactly that: a finger on the glass. So a session left
     * to build a project, download a dependency or let an AI agent work -- with the phone in a
     * pocket, which is precisely when that is worth doing -- was closed for being idle while it
     * was at full stretch. Work counts as use now, because it is.
     */
    private long lastInteractionAt() {
        long touched = VncView.lastInteractionAt;
        long used = Math.max(touched > 0 ? touched : 0L, busySince());
        return Math.max(used, sessionStartedAt);
    }

    /**
     * Ticks of processor time above which the container counts as working rather than sitting.
     *
     * The monitor samples every 30 seconds. An idle desktop still spends a little -- a clock
     * redrawing, a panel repainting -- so this is set well above that: 300 ticks is three
     * seconds of processor time in thirty, about a tenth of one core.
     */
    private static final long BUSY_TICKS = 300;
    private long lastCpuTicks = -1;
    private long lastBusyAt;

    private long busySince() {
        Process running = activeProcess;
        long ticks = running == null ? -1 : ProotProcess.cpuTicks(running);
        if (ticks < 0) {
            lastCpuTicks = -1;
            return lastBusyAt;
        }
        if (lastCpuTicks >= 0 && ticks - lastCpuTicks >= BUSY_TICKS) {
            lastBusyAt = System.currentTimeMillis();
        }
        lastCpuTicks = ticks;
        return lastBusyAt;
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        linuxDns = new LinuxDns(this);
        linuxDns.start();
    }

    private LinuxDns linuxDns;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        final String appId = intent == null ? null : intent.getStringExtra(EXTRA_APP_ID);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification("PocketLinux", "Preparing local Linux…", -1),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification("PocketLinux", "Preparing local Linux…", -1));
        }
        if (ACTION_STOP.equals(action)) {
            stopEverything(true);
            return START_NOT_STICKY;
        }
        if (action == null) return START_NOT_STICKY;
        reconcileUncleanStop(this);
        stoppedForReason = false;
        // An install while the desktop is open: the desktop keeps its executor and its
        // process, the install gets its own of each, and the new app appears on the running
        // desktop when it is done. Waiting for the desktop to be stopped first was the reason
        // the Apps tab went grey whenever the computer was on.
        boolean appAction = ACTION_INSTALL_APP.equals(action) || ACTION_UNINSTALL_APP.equals(action);
        if (appAction && isDesktopRunning()) {
            final boolean removing = ACTION_UNINSTALL_APP.equals(action);
            if (!INSTALLING.compareAndSet(false, true)) {
                status("PocketLinux is busy", "A task is already running; wait for it to finish.", -1, true, false);
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
                    if (removing) uninstallApp(appId);
                    else installApp(appId);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    status("Cancelled", "The desktop is still running.", -1, false, false);
                } catch (Exception error) {
                    status("Could not complete task", cleanError(error), -1, false, true);
                    // The dialog goes when it is dismissed; the reason should not go with it.
                    Crash.note(LinuxService.this,
                            (removing ? "Removing " : "Installing ") + appId + " failed",
                            fullError(error));
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
        if (!appAction && !ACTION_SETUP.equals(action) && !ACTION_REMOVE.equals(action)
                && !ACTION_START_DESKTOP.equals(action)) {
            status("Unknown action", "Nothing was changed.", -1, false, true);
            return START_NOT_STICKY;
        }
        final long generation;
        synchronized (PRIMARY_TASK) {
            // Startup may have completed after the earlier running-desktop branch. Do not
            // supersede that live session with a task queued behind its waitFor worker.
            if (isDesktopRunning()) {
                status("The desktop is running", appAction
                        ? "The desktop just became ready. Tap the app again to start this task."
                        : "Return to the desktop, or stop it before starting this task.", -1, false, false);
                return START_NOT_STICKY;
            }
            if (!BUSY.compareAndSet(false, true)) {
                status("PocketLinux is busy", desktopRunning ? "Desktop is already running." : "Wait for the current task to finish.", -1, true, false);
                return START_NOT_STICKY;
            }
            generation = PRIMARY_TASK.next();
            // A dead desktop can still be unwinding on its worker. A newly accepted task
            // owns the state now; do not let its install process inherit desktopRunning=true.
            if (!isDesktopRunning()) {
                desktopRunning = false;
                Process previous = activeProcess;
                if (previous != null && !previous.isAlive()) activeProcess = null;
                getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(ContainerRuntime.KEY_DESKTOP_ALIVE, false).apply();
            }
            desktopStarting = ACTION_START_DESKTOP.equals(action);
            if (ACTION_START_DESKTOP.equals(action)) {
                sessionGeneration = generation;
            }
            acquireTaskWakeLock();
            stopRequested = false;
        }
        if (!ACTION_START_DESKTOP.equals(action)) {
            // A desktop session has its own monitor; a download does not, and needs one.
            pausedForHeat = false;
            pausedSince = 0L;
            handler.removeCallbacks(jobMonitor);
            handler.postDelayed(jobMonitor, 30_000L);
        }
        currentTask = executor.submit(() -> {
            if (!PRIMARY_TASK.isCurrent(generation)) return;
            workerGeneration.set(generation);
            workerThread = Thread.currentThread();
            try {
                if (ACTION_SETUP.equals(action)) setupUbuntu();
                else if (ACTION_START_DESKTOP.equals(action)) startDesktop(generation);
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
                    PRIMARY_TASK.runIfCurrent(generation, () -> {
                        status("Could not complete task", cleanError(error), -1, false, true);
                        Crash.note(LinuxService.this, "A task failed", fullError(error));
                    });
                }
            } finally {
                if (workerThread == Thread.currentThread()) workerThread = null;
                PRIMARY_TASK.runIfCurrent(generation, () -> {
                    if (ACTION_START_DESKTOP.equals(action)) desktopStarting = false;
                    BUSY.set(false);
                    releaseTaskWakeLock();
                    // A new Open can already be queued after Stop. Only this task's generation
                    // may clear busy/starting, release its lock or end the foreground service.
                    if (!desktopRunning && !INSTALLING.get()) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    }
                });
                workerGeneration.remove();
            }
        });
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        serviceDestroyed = true;
        if (linuxDns != null) linuxDns.stop();
        handler.removeCallbacks(safetyMonitor);
        handler.removeCallbacks(jobMonitor);
        releaseTaskWakeLock();
        releaseInstallWakeLock();
        releaseDesktopWakeLock();
        // Each Stop/Open can create another Service instance. A single-thread executor keeps
        // its idle worker alive until shutdown, even after all of that instance's work ended.
        // Let any in-flight cleanup finish without retaining an idle worker for every reopen.
        executor.shutdown();
        installExecutor.shutdown();
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

    static boolean isDesktopStarting() { return desktopStarting; }

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
                        "Android ended PocketLinux while the desktop was open, which it does to take "
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
        // An APK update can add a helper used by a new catalogue row. Put the current helpers in
        // the existing computer before running that row, even when the desktop has not been opened
        // since the Android app was updated.
        ContainerRuntime.refreshDesktopEntries(this);
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
        installingNow = true;
        int code;
        try {
            code = runInstall(app.installCommand(), line -> {
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
            // The container's own last words are added by fullError, from the shared record, so
            // they reach the report whichever of the two handlers catches this.
            throw new IOException(reason != null ? reason
                    : app.name + " did not finish installing (code " + code + "). Check the "
                    + "internet connection and free space, then tap the row again — what was "
                    + "already downloaded is kept.");
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



    /** POSIX single-quote escaping for values placed into the root container command. */
    private static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\"'\"'")) + "'";
    }

    /** A container command for an install: its own process, so the desktop's is untouched. */
    private int runInstall(String command, ContainerRuntime.OutputListener listener)
            throws IOException, InterruptedException {
        forgetLines();
        Process process = ContainerRuntime.startContainer(this, command);
        installProcess = process;
        try {
            return ProcessOutput.consume(process, line -> {
                // Kept whether or not anyone is listening: the report needs these exactly when
                // the listener has stopped caring, because the job is about to fail.
                rememberLine(line);
                if (listener != null && !line.trim().isEmpty()) listener.line(line);
            });
        } finally {
            ProotProcess.stopAndWait(process);
            if (installProcess == process) installProcess = null;
        }
    }


    private void startDesktop(long generation) throws Exception {
        requireCurrentPrimary(generation);
        if (!ContainerRuntime.isInstalled(this)) throw new IOException("Set up the Linux computer first.");
        preflight(false, 4);
        ContainerRuntime.installRuntime(this);
        // Refresh the desktop scripts and every installed app's launcher on each start, so a
        // container set up by an older version picks up the current desktop without reinstalling.
        ContainerRuntime.writeDesktopScripts(this);
        status("Opening the desktop", "Starting your Linux computer…", -1, true, false);
        SharedPreferences prefs = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
        synchronized (PRIMARY_TASK) {
            requireCurrentPrimary(generation);
            prefs.edit().remove(ContainerRuntime.KEY_LAST_STOP_REASON).apply();
            stoppedForReason = false;
            stopRequested = false;
        }
        int[] geometry = DeviceProbe.desktopGeometry(this, ContainerRuntime.GEOMETRY_CAP);
        // The desktop is born the way the OWNER asked for, and only Auto-rotate asks the phone
        // how it is being held. Reading the phone alone was why Screen rotation -> Portrait
        // changed the phone window and left the computer inside it landscape.
        if (ScreenRotation.portraitDesktop(
                prefs.getString(ContainerRuntime.KEY_ORIENTATION, ScreenRotation.AUTO),
                getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_PORTRAIT)) {
            geometry = new int[]{geometry[1], geometry[0]};   // desktopGeometry is long side first
        }
        // 0, and an absent value, both mean "work it out from this phone's screen".
        int dpi = prefs.getInt(ContainerRuntime.KEY_UI_SCALE, 0);
        if (dpi <= 0) dpi = ContainerRuntime.defaultUiScale(this);
        String downloadTarget = ContainerRuntime.normaliseDownloadTarget(
                prefs.getString(ContainerRuntime.KEY_DOWNLOAD_TARGET, ContainerRuntime.DOWNLOAD_ASK));
        // A revoked All files permission must never make Chrome save into an unmounted placeholder.
        // Fall back to asking, which in turn uses the private computer folder as its safe default.
        if (ContainerRuntime.DOWNLOAD_PHONE.equals(downloadTarget) && !PhoneFiles.allowed(this)) {
            downloadTarget = ContainerRuntime.DOWNLOAD_ASK;
        }
        String command = ContainerRuntime.startDesktopCommand(
                geometry[0], geometry[1], dpi, downloadTarget, desktopTheme(prefs));
        geometry = ContainerRuntime.safeGeometry(geometry[0], geometry[1]);

        // Off by default now: the seccomp accelerator breaks Chromium/Electron apps (see
        // KEY_FAST_DESKTOP). The owner can turn Faster desktop on to try it; if that start
        // never draws a display, the next attempt drops back to the reliable path.
        boolean accelerated = false;   // never: it breaks every Chromium app (see KEY_FAST_DESKTOP)
        boolean fellBack = false;
        File sessionLog = new File(ContainerRuntime.rootfs(this), "home/coder/.pocketdesk/logs/desktop-session.log");
        File logParent = sessionLog.getParentFile();
        if (logParent != null) logParent.mkdirs();
        if (sessionLog.isFile()) {
            File previousLog = new File(logParent, "desktop-session.previous.log");
            previousLog.delete();
            if (!sessionLog.renameTo(previousLog)) sessionLog.delete();
        }
        desktopStartupPhase = "Starting the Linux process";
        Process desktopProcess = null;
        for (int attempt = 0; ; attempt++) {
            desktopProcess = ContainerRuntime.startContainer(this, command, accelerated);
            try {
                synchronized (PRIMARY_TASK) {
                    requireCurrentPrimary(generation);
                    activeProcess = desktopProcess;
                }
            } catch (InterruptedException superseded) {
                ProotProcess.stopAndWait(desktopProcess);
                throw superseded;
            }
            sessionStartedAt = System.currentTimeMillis();
            recordOutput(desktopProcess, sessionLog, attempt);
            // Fifteen seconds was never enough: this phone takes half a minute to put the
            // display up, so the wait expired, the session was killed, and the home screen
            // reported a failure for a desktop that was only slow. Wait as long as the viewer
            // does, and say how it is going.
            boolean ready = false;
            long startupAt = SystemClock.elapsedRealtime();
            long nextStatusAt = startupAt;
            long nextSampleAt = startupAt;
            while (desktopProcess.isAlive()
                    && SystemClock.elapsedRealtime() - startupAt < 150_000L) {
                if (!PRIMARY_TASK.isCurrent(generation) || stopRequested || Thread.currentThread().isInterrupted()) {
                    ProotProcess.stopAndWait(desktopProcess);
                    throw new InterruptedException("Desktop start cancelled");
                }
                if (VncClient.canConnect(new File(ContainerRuntime.rootfs(this),
                                "home/coder/.pocketdesk/vnc.sock").getAbsolutePath())
                        || VncClient.canConnect("127.0.0.1", 5901, 250)) {
                    ready = true;
                    break;
                }
                long now = SystemClock.elapsedRealtime();
                if (now >= nextSampleAt) {
                    RuntimeDiagnostics.sample(this, "Desktop startup sample", ProotProcess.trackingSummary());
                    nextSampleAt = now + 10_000L;
                }
                if (now >= nextStatusAt) {
                    status("Opening the desktop", desktopStartupPhase + "… "
                            + ((now - startupAt) / 1000L) + "s", -1, true, false);
                    nextStatusAt = now + 5_000L;
                }
                Thread.sleep(250);
            }
            if (ready) break;
            int exit = desktopProcess.isAlive() ? -1 : desktopProcess.exitValue();
            String failedPhase = desktopStartupPhase;
            RuntimeDiagnostics.snap(this, "Desktop start failed: exit=" + exit
                    + ", stage=" + failedPhase);
            ProotProcess.stopAndWait(desktopProcess);
            if (activeProcess == desktopProcess) activeProcess = null;
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
            throw new IOException((exit < 0 ? "Desktop startup reached its 150-second limit"
                    : "The display did not start (exit " + exit + ")")
                    + ". Last stage: " + failedPhase
                    + ". The desktop session report keeps the startup output; installed apps and files are kept.");
        }
        try {
            synchronized (PRIMARY_TASK) {
                requireCurrentPrimary(generation);
                if (fellBack) prefs.edit().putBoolean(ContainerRuntime.KEY_PROOT_NO_SECCOMP, true).apply();
                prefs.edit().putLong(ContainerRuntime.KEY_LAST_OPENED_AT, System.currentTimeMillis())
                        .putLong(ContainerRuntime.KEY_HEARTBEAT_AT, System.currentTimeMillis())
                        .putBoolean(ContainerRuntime.KEY_DESKTOP_ALIVE, true).apply();
                desktopRunning = true;
                desktopStarting = false;
                renewDesktopWakeLock(generation);
                RuntimeDiagnostics.snap(this, "Desktop display is ready");
                BUSY.set(false);
                releaseTaskWakeLock();
                status("The Linux computer is running",
                        "Desktop " + geometry[0] + "×" + geometry[1] + " · tap Open desktop", 100, false, false);
                updateNotification("The Linux computer is running", "Tap to return · phone protection is active", 100);
                handler.removeCallbacks(safetyMonitor);
                handler.postDelayed(safetyMonitor, 30_000L);
            }
        } catch (InterruptedException superseded) {
            ProotProcess.stopAndWait(desktopProcess);
            throw superseded;
        }

        int exitCode;
        try {
            // Stop can clear the shared field while this worker is waking from a connection
            // probe. Keep ownership of the actual session instead of dereferencing that field.
            exitCode = desktopProcess.waitFor();
            if (PRIMARY_TASK.isCurrent(generation) && !stopRequested && !stoppedForReason) {
                RuntimeDiagnostics.sample(this, "Desktop root exited before cleanup: exit=" + exitCode,
                        ProotProcess.trackingSummary());
            }
        } finally {
            // A killed tracer can leave live descendants behind with PPid=1/TracerPid=0.
            // The Android-side tracker recorded those identities while the session was live.
            ProotProcess.stopAndWait(desktopProcess);
            synchronized (PRIMARY_TASK) {
                if (PRIMARY_TASK.isCurrent(generation) && activeProcess == desktopProcess) {
                    activeProcess = null;
                    desktopRunning = false;
                    releaseDesktopWakeLock();
                    handler.removeCallbacks(safetyMonitor);
                    prefs.edit().putBoolean(ContainerRuntime.KEY_DESKTOP_ALIVE, false).apply();
                }
            }
        }
        if (!PRIMARY_TASK.isCurrent(generation)) return;
        // Nobody asked for this stop and no rule announced it: the display server itself ended.
        if (!stopRequested && !stoppedForReason) {
            RuntimeDiagnostics.snap(this, "Desktop process ended unexpectedly: exit=" + exitCode);
            if (bringItBack(exitCode)) return;
            recordStop("The desktop display ended unexpectedly (exit " + exitCode + "). "
                    + (exitCode == 137 ? "Its process received SIGKILL, which on this phone means Android "
                            + "ended it rather than the computer failing. " : "")
                    + "Saved files are kept; unsaved work may need recovery. "
                    + "It was reopened automatically twice already, so this time it is left stopped: "
                    + "close other apps, then open the desktop again.");
        }
        status("The Linux computer is stopped", "Everything on it is kept for the next open.", 100, false, false);
    }

    /** How many times a session that ended by itself is quietly reopened before we stop trying. */
    private static final int AUTOMATIC_REOPENS = 2;
    /** A session that lasted this long earns a fresh pair of retries; a flapping one does not. */
    private static final long HEALTHY_SESSION_MS = 5 * 60 * 1000L;
    private volatile int automaticReopens;
    private volatile long lastAutomaticReopenAt;
    /**
     * Until when an automatic reopen is in flight, so the viewer keeps waiting instead of
     * reporting a stopped computer during the gap between one session ending and the next
     * starting. A timestamp rather than a flag: a reopen that never arrives expires by itself.
     */
    private static volatile long reopeningUntil;

    /** True while a session that ended by itself is being brought back. */
    static boolean isReopening() { return SystemClock.elapsedRealtime() < reopeningUntil; }

    /**
     * Reopens a desktop that ended by itself, instead of leaving the owner on the home screen.
     *
     * A session ending is nearly always Android's doing rather than the computer's: on this phone
     * an app may hold 32 forked processes, and every Linux process is one of them, so a busy
     * moment ends the lot with SIGKILL. The computer keeps itself well under that ceiling now,
     * but "well under" is not "never", and the owner should not be the retry mechanism.
     *
     * Bounded, because an unconditional restart of a genuinely broken computer is a loop that
     * heats the phone: twice, and then it stays stopped and says so. A session that ran healthily
     * for five minutes before dying earns the pair back, because that is a phone having a bad
     * moment rather than a computer that cannot start.
     */
    private boolean bringItBack(int exitCode) {
        long now = System.currentTimeMillis();
        if (now - lastAutomaticReopenAt > HEALTHY_SESSION_MS) automaticReopens = 0;
        if (automaticReopens >= AUTOMATIC_REOPENS) return false;
        if (!ContainerRuntime.isInstalled(this)) return false;
        automaticReopens++;
        lastAutomaticReopenAt = now;
        reopeningUntil = SystemClock.elapsedRealtime() + 30_000L;
        status("Reopening the desktop",
                "It stopped by itself" + (exitCode == 137 ? " because Android ended it" : "")
                        + ". Everything on it was kept; opening it again\u2026", -1, true, false);
        // Through the ordinary action, so one path starts a session and one generation owns it.
        try {
            android.content.Intent again = new android.content.Intent(this, LinuxService.class)
                    .setAction(ACTION_START_DESKTOP);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(again); else startService(again);
            return true;
        } catch (Throwable refused) {
            // Android would not let a background start through. Fall back to saying what happened.
            reopeningUntil = 0L;
            automaticReopens = AUTOMATIC_REOPENS;
            return false;
        }
    }

    /**
     * The display server narrates as it works; it goes to a file, not the notification. The
     * file is appended to and flushed line by line, so a second attempt's output follows the
     * first attempt's instead of wiping it -- the first attempt's last lines are the diagnosis.
     */
    private void recordOutput(Process process, File sessionLog, int attempt) {
        Thread output = new Thread(() -> {
            if (process == null) return;
            try (java.io.PrintWriter writer = new java.io.PrintWriter(new FileOutputStream(sessionLog, true), true)) {
                writer.println("--- start attempt " + (attempt + 1) + " ---");
                writer.println("PocketLinux " + MainActivity.VERSION + " | "
                        + new java.util.Date() + " | current desktop attempt");
                ProcessOutput.consume(process, line -> {
                    if (line.startsWith("PD_DESKTOP_PHASE: ")) {
                        String phase = line.substring("PD_DESKTOP_PHASE: ".length()).trim();
                        synchronized (PRIMARY_TASK) {
                            if (activeProcess == process && phase.length() > 0 && phase.length() <= 100
                                    && phase.matches("[A-Za-z ]+")) desktopStartupPhase = phase;
                        }
                    }
                    if (!line.trim().isEmpty()) writer.println(line);
                });
            } catch (IOException ignored) {}
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "pocketdesk-linux-output-" + attempt);
        output.setDaemon(true);
        output.start();
    }

    private int runTracked(String command, ContainerRuntime.OutputListener listener)
            throws IOException, InterruptedException {
        forgetLines();
        Process process = ContainerRuntime.startContainer(this, command);
        try {
            synchronized (PRIMARY_TASK) {
                Long generation = workerGeneration.get();
                if (generation != null) requireCurrentPrimary(generation);
                activeProcess = process;
            }
            return ProcessOutput.consume(process, line -> {
                // Kept whether or not anyone is listening: the report needs these exactly when
                // the listener has stopped caring, because the job is about to fail.
                rememberLine(line);
                if (listener != null && !line.trim().isEmpty()) listener.line(line);
            });
        } finally {
            ProotProcess.stopAndWait(process);
            if (activeProcess == process) activeProcess = null;
        }
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
            connection.setRequestProperty("User-Agent", "PocketLinux");
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
                "PocketLinux/" + MainActivity.VERSION + " (Android " + Build.VERSION.RELEASE + ")");
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
        RuntimeDiagnostics.sample(this, userRequested ? "User requested desktop stop" : "Safety policy requested desktop stop",
                ProotProcess.trackingSummary());
        synchronized (PRIMARY_TASK) {
            PRIMARY_TASK.next(); // Invalidate the old worker before it can finish on another thread.
            stopRequested = true;
            handler.removeCallbacks(safetyMonitor);
            Future<?> task = currentTask;
            if (task != null) task.cancel(true);
            Thread worker = workerThread;
            if (worker != null) worker.interrupt();
            Process process = activeProcess;
            if (process != null) {
                ProotProcess.requestStop(process);
            }
            Process install = installProcess;
            if (install != null) {
                ProotProcess.requestStop(install);
            }
            activeProcess = null;
            installProcess = null;
            desktopRunning = false;
            desktopStarting = false;
            BUSY.set(false);
            getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(ContainerRuntime.KEY_DESKTOP_ALIVE, false).apply();
            releaseTaskWakeLock();
            releaseDesktopWakeLock();
            if (userRequested) status("The Linux computer is stopped", "Everything on it is kept for the next open.", 100, false, false);
            // An install running beside the desktop keeps the service and its own lock alive.
            if (!INSTALLING.get()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }


    private static void requireCurrentPrimary(long generation) throws InterruptedException {
        if (!PRIMARY_TASK.isCurrent(generation) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Task was stopped or superseded");
        }
    }

    static String lastMessage() { return lastMessage; }
    static String lastDetail() { return lastDetail; }
    static int lastProgress() { return lastProgress; }
    static boolean lastWasError() { return lastError; }

    /**
     * Light or dark for the computer inside, resolved here from the same setting the app uses.
     *
     * "System" has to be resolved on this side: the container has no idea what the phone's night
     * mode is doing, and asking it to guess is how a light phone ended up holding a dark computer.
     */
    private String desktopTheme(SharedPreferences prefs) {
        String mode = prefs.getString(ContainerRuntime.KEY_THEME, "system");
        if (ContainerRuntime.THEME_LIGHT.equals(mode)) return ContainerRuntime.THEME_LIGHT;
        if (ContainerRuntime.THEME_DARK.equals(mode)) return ContainerRuntime.THEME_DARK;
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? ContainerRuntime.THEME_DARK : ContainerRuntime.THEME_LIGHT;
    }

    private void status(String message, String detail, int progress, boolean busy, boolean error) {
        synchronized (PRIMARY_TASK) {
            Long generation = workerGeneration.get();
            if (generation != null && !PRIMARY_TASK.isCurrent(generation)) return;
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
        return newWakeLock(tag, WAKE_LOCK_MS);
    }

    private PowerManager.WakeLock newWakeLock(String tag, long timeoutMillis) {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return null;
        PowerManager.WakeLock lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        lock.setReferenceCounted(false);
        lock.acquire(timeoutMillis);
        return lock;
    }

    /**
     * Keep background desktop work from suspending with the screen off. This is a CPU wake
     * lease, not a memory reservation or exemption from Android's child-process policy.
     * Generation and root liveness are checked under the same lock used by Stop/Open, so a
     * delayed monitor from an old session cannot revive its lease after Stop released it.
     */
    private boolean renewDesktopWakeLock(long generation) {
        synchronized (PRIMARY_TASK) {
            if (serviceDestroyed || !PRIMARY_TASK.isCurrent(generation)
                    || !isDesktopRunning() || stopRequested) return false;
            synchronized (this) {
                if (serviceDestroyed) return false;
                try {
                    if (desktopWakeLock == null) {
                        desktopWakeLock = newWakeLock("PocketLinuxLinux:desktop", DESKTOP_WAKE_LOCK_MS);
                    } else {
                        desktopWakeLock.acquire(DESKTOP_WAKE_LOCK_MS);
                    }
                } catch (RuntimeException denied) {
                    // A refused wake lock must not stop an otherwise live desktop.
                }
            }
            return true;
        }
    }

    private synchronized void releaseDesktopWakeLock() {
        PowerManager.WakeLock lock = desktopWakeLock;
        desktopWakeLock = null;
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (RuntimeException ignored) { /* The bounded lease still expires. */ }
    }

    private synchronized void acquireTaskWakeLock() {
        if (taskWakeLock != null && taskWakeLock.isHeld()) return;
        taskWakeLock = newWakeLock("PocketLinuxLinux:task");
    }

    private synchronized void releaseTaskWakeLock() {
        PowerManager.WakeLock lock = taskWakeLock;
        taskWakeLock = null;
        if (lock != null && lock.isHeld()) lock.release();
    }

    private synchronized void acquireInstallWakeLock() {
        if (installWakeLock != null && installWakeLock.isHeld()) return;
        installWakeLock = newWakeLock("PocketLinuxLinux:install");
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
        if (value.startsWith("PD_PHASE:")) {
            String phase = value.substring("PD_PHASE:".length()).trim();
            return phase.isEmpty() ? "Working" : phase;
        }
        if (value.startsWith("PD_ERROR:")) {
            String problem = value.substring("PD_ERROR:".length()).trim();
            return problem.isEmpty() ? "Install stopped" : problem;
        }
        // The container's own step lines, so the phone can say what is happening rather than
        // echoing package names at someone who did not ask for them.
        if (value.startsWith("PocketLinux:")) {
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
        long totalSeconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
        if (totalSeconds < 60L) return totalSeconds + " sec so far";
        return (totalSeconds / 60L) + "m " + (totalSeconds % 60L) + "s so far";
    }

    /** curl --progress-bar's final numeric token, or -1 for an ordinary setup line. */
    static int transferPercent(String line) {
        if (line == null) return -1;
        int percent = line.lastIndexOf('%');
        if (percent < 1) return -1;
        int start = percent - 1;
        while (start >= 0) {
            char value = line.charAt(start);
            if ((value >= '0' && value <= '9') || value == '.') start--;
            else break;
        }
        String number = line.substring(start + 1, percent);
        if (number.isEmpty()) return -1;
        try {
            int value = (int) Double.parseDouble(number);
            return value >= 0 && value <= 100 ? value : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
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

    /**
     * The failure in full, for the report, where shortText's 150 characters are not a kindness.
     *
     * The notification has to be one short line; the report has to be the whole thing, or the
     * owner copies out a message that ends in an ellipsis and nobody can help them from it.
     */
    private static String fullError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getName();
        String tail = containerTail();
        return message + (tail.isEmpty() ? "" : "\n\nWhat the computer said last:\n" + tail);
    }

    /**
     * The last lines the container printed, whichever job printed them.
     *
     * Shared rather than per-job on purpose: a failure can be caught in either of two places,
     * and the owner should not get the explanation from one and a bare sentence from the other.
     * Bounded in both directions, so a long install cannot grow it without end and one very long
     * line cannot fill the report on its own.
     */
    private static final java.util.ArrayDeque<String> CONTAINER_TAIL = new java.util.ArrayDeque<>();

    static void rememberLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || isTransferNoise(trimmed)) return;
        synchronized (CONTAINER_TAIL) {
            CONTAINER_TAIL.addLast(trimmed.length() > 300
                    ? trimmed.substring(0, 300) + "\u2026" : trimmed);
            while (CONTAINER_TAIL.size() > 12) CONTAINER_TAIL.removeFirst();
        }
    }

    static void forgetLines() {
        synchronized (CONTAINER_TAIL) {
            CONTAINER_TAIL.clear();
        }
    }

    private static String containerTail() {
        StringBuilder text = new StringBuilder();
        synchronized (CONTAINER_TAIL) {
            for (String one : CONTAINER_TAIL) {
                text.append(text.length() == 0 ? "" : "\n").append(one);
            }
        }
        return text.toString();
    }
}
