package com.pocketide;

import android.app.Notification;
import android.app.NotificationManager;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

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
    /** The daily update, under this service's wake lock and notification. See Updates. */
    static final String ACTION_UPDATE = "com.pocketide.UPDATE";
    /** The phone as a test device: a pairing code from the notification, or a connect. */
    static final String ACTION_PHONE_PAIR = "com.pocketide.PHONE_PAIR";
    static final String ACTION_PHONE_CONNECT = "com.pocketide.PHONE_CONNECT";
    static final String EXTRA_CODE = "code";

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
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private Thread worker;
    private volatile Process editor;
    /** adb's server, held here when the editor's own start could not have started one. */
    private volatile Process adbServer;
    /** The workspace's door to the phone, open exactly as long as the editor is. See PhoneBroker. */
    private volatile PhoneBroker broker;
    /** True while the job under way is the daily update, whose PRoot no field here holds. */
    private volatile boolean updating;
    /** An editor asked for during the update: it opens the moment the update is done. */
    private volatile boolean startAfterUpdate;
    /**
     * Held while either side reads and writes the two flags above. Without it a start that
     * arrived in the moment between the update finishing and its flag being read was neither
     * queued nor refused -- lost, and the screen waited for an editor nobody was starting.
     */
    private final Object updateLock = new Object();
    private PowerManager.WakeLock wakeLock;
    private long startedAt;
    /** What the notification last said, so a repeated START does not talk over it. */
    private volatile String lastNote;

    /** True while every process in the workspace is stopped because the phone is too hot. */
    private static volatile boolean pausedForHeat;
    private PowerManager.OnThermalStatusChangedListener thermal;

    static boolean pausedForHeat() { return pausedForHeat; }

    static boolean busy() { return busy; }
    static boolean editorRunning() { return editorRunning; }
    static String editorUrl() { return editorUrl; }

    /** When the editor started answering, or 0 if it is not running. */
    static long runningSince() { return editorRunning ? runningSince : 0L; }

    /** A copy of the recent output, oldest first. A copy, so the caller cannot see it change. */
    static List<String> recentLog() {
        synchronized (LOG) {
            return new ArrayList<>(LOG);
        }
    }

    static void record(String line) {
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
    static void update(Context context) { send(context, ACTION_UPDATE); }

    /** The pairing code from the notification's reply box. Acted on only while the editor runs. */
    static void pairPhone(Context context, String code) {
        sendPhone(context, ACTION_PHONE_PAIR, code);
    }

    static void connectPhone(Context context) { sendPhone(context, ACTION_PHONE_CONNECT, null); }

    private static void sendPhone(Context context, String action, String code) {
        Intent intent = new Intent(context, WorkspaceService.class).setAction(action);
        if (code != null) intent.putExtra(EXTRA_CODE, code);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable refused) {
            Phone.tell(context, "Could not reach Linux",
                    "Open the editor, then try again from Settings → Test on this phone.");
        }
    }

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
                    .putExtra(EXTRA_LINE, "Android refused to start Linux in the "
                            + "background. Open the app and try again, or allow background "
                            + "activity from Settings.");
            context.sendBroadcast(failure);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // Delivered by startForegroundService(), and a service started that way has to
            // call startForeground() before it goes away, or Android reports the app as
            // having crashed -- even when going away was the whole point. One more frame of
            // the notification, then everything comes down.
            try {
                startForeground(NOTIFICATION, notification("Stopping…"));
            } catch (Throwable refused) {
                // Not allowed into the foreground just now. Stopping needs no permission.
            }
            stopEverything("stopped", "Linux stopped.");
            return START_NOT_STICKY;
        }
        // The notification must be posted in this call or Android kills the service. It is
        // posted before any work begins, for exactly that reason. When work is already under
        // way the text it was showing is kept: a second START while the editor was up used to
        // flip it back to "Starting the editor…" for something already running.
        boolean phone = ACTION_PHONE_PAIR.equals(action) || ACTION_PHONE_CONNECT.equals(action);
        String text = busy && lastNote != null ? lastNote
                : ACTION_SETUP.equals(action) ? "Setting up…"
                : ACTION_UPDATE.equals(action) ? "Checking for updates…"
                : phone ? "Linux is not running" : "Starting the editor…";
        try {
            startForeground(NOTIFICATION, notification(text));
        } catch (Throwable refused) {
            // Android 12 and later refuse a foreground service started from the background, and
            // the refusal is an exception that kills the whole app rather than the request.
            // Reported instead, which is what every other refusal in this file already does.
            Intent failure = new Intent(EVENT).setPackage(getPackageName())
                    .putExtra(EXTRA_STATE, "failed")
                    .putExtra(EXTRA_LINE, "Android would not let Linux start from the "
                            + "background just now. Open the app and start it from there.");
            sendBroadcast(failure);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PHONE_PAIR.equals(action) || ACTION_PHONE_CONNECT.equals(action)) {
            // Only while the editor runs: the adb server that would hold the connection starts
            // and stops with it, and pairing into a server that will be gone by the time the
            // terminal asks is worse than saying so.
            final boolean pair = ACTION_PHONE_PAIR.equals(action);
            final String code = intent.getStringExtra(EXTRA_CODE);
            if (!editorRunning) {
                Phone.tell(this, "The editor is not running", "Open the editor, then "
                        + "Settings → Test on this phone → "
                        + (pair ? "Pair for the first time" : "Connect now")
                        + ". The connection lives as long as the editor does.");
                if (!busy) {
                    stopForeground(true);
                    stopSelf();
                }
                return START_NOT_STICKY;
            }
            new Thread(() -> {
                if (pair) Phone.pair(this, code);
                else Phone.connect(this, true);
            }, "phone").start();
            return START_NOT_STICKY;
        }
        if (busy) {
            // A start that arrives while the daily update is running used to be dropped
            // without a word: the screen waited for an editor nobody was starting. It is
            // queued instead -- apt is not interrupted half way -- and the screen is told.
            boolean queued = false;
            synchronized (updateLock) {
                if (updating && ACTION_START.equals(action)) {
                    startAfterUpdate = true;
                    queued = true;
                }
            }
            if (queued) {
                announce(null, "Finishing the update first; the editor opens right after.",
                        "setting-up", -1);
            }
            return START_NOT_STICKY;
        }
        busy = true;
        synchronized (updateLock) {
            updating = ACTION_UPDATE.equals(action);
            startAfterUpdate = false;
        }
        startedAt = System.currentTimeMillis();
        // Said once, where the owner will look: a normal power-saving mode only slows a job;
        // a super or ultra mode ends every app not on its short list, this one included, and
        // the Help says which is which.
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power != null && power.isPowerSaveMode()) {
                record("Power saving is on. A normal power-saving mode only slows this job; "
                        + "a super or ultra mode ends every app not on its list, this one "
                        + "included.");
            }
        } catch (Throwable unreadable) {
            // Not knowing is not worth failing over.
        }
        // Remembered on disk, so that if Android kills the process the next start can tell
        // whether Linux was running at the time. See Exits.
        Prefs.of(this).edit().putBoolean(Prefs.LINUX_WAS_RUNNING, true).apply();
        hold();
        watchHeat();
        boolean setup = ACTION_SETUP.equals(action);
        boolean update = ACTION_UPDATE.equals(action);
        worker = new Thread(update ? this::runUpdate : setup ? this::runSetup : this::runEditor,
                "Linux");
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

    // ------------------------------------------------------------------ the daily update

    /**
     * The quiet daily update, under this service rather than on a bare thread from a screen.
     *
     * It used to run from Home's onResume on a thread of its own: an owner who put the phone
     * down mid-apt left dpkg to be frozen or killed with nothing on screen to say so, and the
     * next run began with a repair. Here it has the wake lock, the thermal pause and the
     * notification every other long job has, and it ends the way they do.
     */
    private void runUpdate() {
        try {
            Updates.runQuietly(this, line -> {
                record(line);
                note(line);
            });
            boolean editorWanted;
            synchronized (updateLock) {
                updating = false;
                editorWanted = startAfterUpdate && busy;
                startAfterUpdate = false;
            }
            if (editorWanted) {
                runEditor();
                return;
            }
            stopEverything(null, null);
        } catch (Throwable failure) {
            synchronized (updateLock) {
                updating = false;
            }
            fail(failure);
        }
    }

    // ------------------------------------------------------------------ the editor

    private void runEditor() {
        try {
            Workspace.writeScripts(this);
            // The keys the editor's menu presses, rewritten on every start because three of
            // them name whichever agents are installed right now. See Extensions.
            Extensions.writeKeybindings(this);
            // Screen decides both when the owner has not: the zoom from this phone's own width
            // and font scale, the layout from whether the screen is wide enough for it.
            String layout = Screen.layout(this);
            // No zoom here. The web build has no window.zoomLevel; the text size is the
            // WebView's viewport, applied by WorkspaceActivity from the same Screen.
            String command = "PIDE_LAYOUT=" + layout
                    + " bash /opt/pocketide/pocketide-editor.sh start";
            // With the bridge directory bound in, and nothing of the phone's: the editor's
            // PRoot is the one an agent works in, and it gets the door, not the key.
            editor = Workspace.start(this, command, PhoneBroker.editorBinds(this));
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
                        // The door to the phone opens with the editor and closes with it.
                        openBroker();
                        // The phone as a test device connects by itself when it can: this
                        // Android has Wireless debugging and it is on. Quietly -- the
                        // Activity screen has the line either way, and a notification at
                        // every start would be noise.
                        if (Phone.supported() && Phone.wirelessDebuggingOn(this)) {
                            new Thread(() -> Phone.connect(this, false), "phone-connect")
                                    .start();
                        }
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

    /**
     * The door to the phone, opened and closed under one lock so the two cannot cross. A lock
     * of its own, not the service's: ensureAdbServer holds that one for up to four seconds,
     * and a Stop tapped on the main thread must not wait behind it.
     */
    private final Object brokerLock = new Object();

    private void openBroker() {
        synchronized (brokerLock) {
            PhoneBroker open = broker;
            if (open != null) open.stop();
            broker = PhoneBroker.start(this);
        }
    }

    private void closeBroker() {
        synchronized (brokerLock) {
            PhoneBroker door = broker;
            broker = null;
            if (door != null) door.stop();
        }
    }

    private void stopEverything(String state, String message) {
        final boolean hadJob = busy;
        busy = false;
        updating = false;
        startAfterUpdate = false;
        editorRunning = false;
        editorUrl = "";
        runningSince = 0L;
        final Process running = editor;
        editor = null;
        final Process server = adbServer;
        adbServer = null;
        closeBroker();
        unwatchHeat();
        // The held adb server goes with the editor. quit(), not destroy(): PRoot ignores the
        // SIGTERM that destroy() sends and answers SIGQUIT, and the sweep below reaches it
        // too. A job with no handle here -- the daily update keeps its PRoot to itself -- is
        // swept all the same: Stop used to do nothing at all to a running apt.
        if (server != null) Workspace.quit(server);
        if (running != null) stopTidily(running);
        else if (hadJob) stopTidily(null);
        if (worker != null) worker.interrupt();
        release();
        Prefs.of(this).edit().putBoolean(Prefs.LINUX_WAS_RUNNING, false).apply();
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
     * Makes sure an adb server is answering before a pair or a connect is run.
     *
     * An adb run on its own forks a server inside its own PRoot, where --kill-on-exit takes
     * it down the moment the command ends: that server said "connected" and was gone before
     * the next command asked, which showed as a phone that claimed to be connected and was
     * not. So the service holds one of its own: server nodaemon keeps adb in the foreground,
     * this process keeps its PRoot alive, and every later adb -- the bridge's, the pairing's
     * -- finds it on the socket in the key directory. It runs in the app's private adb root
     * (Phone.start), never in the Linux rootfs, and ends with the workspace, in
     * stopEverything.
     */
    synchronized boolean ensureAdbServer() {
        if (Phone.serverListening(this)) return true;
        // The APK's own adb, unpacked into the app's storage once per version; the Activity
        // log says why when the phone would not take it.
        if (!Phone.prepareRoot(this)) return false;
        try {
            final Process server = Phone.start(this, "server", "nodaemon");
            adbServer = server;
            new Thread(() -> {
                // Drained, not read: a pipe nobody empties fills, and a server blocked on
                // its own log line is a server that stops answering.
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        server.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) { /* nothing to keep */ }
                } catch (IOException gone) {
                    // The server ended. Nothing left to drain.
                }
            }, "adb-server").start();
            for (int tries = 0; tries < 40 && !Phone.serverListening(this); tries++) {
                waitBriefly(100);
            }
        } catch (IOException notStarted) {
            adbServer = null;
        }
        return Phone.serverListening(this);
    }

    /**
     * Freezes the workspace when the phone is too hot, and thaws it as the phone cools.
     *
     * The alternative is what every phone does on its own: throttle, then kill the most
     * expensive thing running, which is a set-up half way through apt or an agent half way
     * through a build. Killing a set-up mid-apt is what leaves dpkg half-configured and a
     * 400 MB download to do again. Stopping the processes instead costs nothing and loses
     * nothing: SIGSTOP holds every process in the container at its next system call, the
     * phone stops working and cools, and SIGCONT picks up exactly where it left off.
     *
     * Paused at CRITICAL and resumed only at MODERATE or below, not the moment it drops one
     * notch, so it does not flap on and off at the boundary. The editor's page shows its own
     * reconnecting overlay while the server is held; that is honest, and better than the
     * phone shutting itself down.
     */
    private void watchHeat() {
        if (thermal != null) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) return;
        thermal = this::onHeat;
        try {
            power.addThermalStatusListener(getMainExecutor(), thermal);
        } catch (Throwable refused) {
            // A build without thermal reporting. The phone's own throttling still applies.
            thermal = null;
        }
    }

    private void unwatchHeat() {
        PowerManager.OnThermalStatusChangedListener listening = thermal;
        thermal = null;
        if (listening != null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            try {
                if (power != null) power.removeThermalStatusListener(listening);
            } catch (Throwable alreadyGone) {
                // Nothing to undo.
            }
        }
        if (pausedForHeat) {
            pausedForHeat = false;
            sweep(18);
        }
    }

    private void onHeat(int status) {
        if (!busy) return;
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL && !pausedForHeat) {
            pausedForHeat = true;
            sweep(19);                                   // SIGSTOP
            record("Paused: the phone is too hot. It resumes by itself as the phone cools.");
            note("Paused — the phone is too hot. Resumes as it cools.");
            sendBroadcast(new Intent(EVENT).setPackage(getPackageName())
                    .putExtra(EXTRA_STATE, "paused")
                    .putExtra(EXTRA_LINE, "Paused: the phone is too hot."));
        } else if (status <= PowerManager.THERMAL_STATUS_MODERATE && pausedForHeat) {
            pausedForHeat = false;
            sweep(18);                                   // SIGCONT
            record("Resumed: the phone has cooled.");
            note(editorRunning ? "Editor running" : "Working…");
            sendBroadcast(new Intent(EVENT).setPackage(getPackageName())
                    .putExtra(EXTRA_STATE, "resumed")
                    .putExtra(EXTRA_LINE, "Resumed: the phone has cooled."));
        }
    }

    /**
     * Ends PRoot in a way that takes the whole container with it.
     *
     * Process.destroy() signals the tracer directly and does not wait, which leaves whatever
     * PRoot was tracing -- the editor's node, its extension host, a compiler part way through --
     * reparented and still running, because an Android process dying does not kill its children.
     * The class comment above once claimed --kill-on-exit covered this; it governs PRoot's own
     * exit path, and a SIGKILL skips that path entirely.
     *
     * SIGQUIT is the signal PRoot answers by killing every process it is tracing. SIGCONT goes
     * first in case the phone paused it for heat, since a stopped process cannot act on
     * anything. Then a couple of seconds to let it finish, and destroy() only as the backstop.
     * On its own thread, because the wait must not be on the one that draws.
     */
    private void stopTidily(final Process running) {
        new Thread(() -> {
            // SIGCONT first, in case the phone paused it for heat: a stopped process cannot
            // act on anything else it is sent. Then SIGQUIT, which is the signal PRoot answers
            // by killing every process it is tracing.
            sweep(18);
            sweep(3);
            waitBriefly(2000);
            if (running != null) Workspace.quit(running);
            // And the backstop. Anything still alive here outlived its tracer, which is what
            // being reparented to init looks like from the outside, and is exactly the state
            // that leaves a compiler running after the owner pressed Stop.
            waitBriefly(700);
            sweep(9);
        }, "stop-linux").start();
    }

    /**
     * Signals every process of this app's that belongs to the workspace.
     *
     * By walking /proc rather than by remembering a process id, because PRoot re-executes
     * itself and the editor spawns its own children -- an extension host, a language server,
     * whatever an agent ran -- and none of those are known in advance. Running.workspace()
     * already finds exactly that set for the Activity screen, and Android restricts /proc to a
     * process's own descendants, so this can only ever reach this app's own work.
     */
    private void sweep(int signal) {
        try {
            for (Running.Process process : Running.workspace(this)) {
                try {
                    android.os.Process.sendSignal(process.pid, signal);
                } catch (Throwable refused) {
                    // Already gone between the listing and the signal. Not a failure.
                }
            }
        } catch (Throwable unreadable) {
            // /proc changed under the walk. destroy() below is still the backstop.
        }
    }

    private void waitBriefly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException woken) {
            Thread.currentThread().interrupt();
        }
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
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketide:Linux");
            wakeLock.setReferenceCounted(false);
            // No timeout. There was one, of four hours, and an agent left on a long build
            // found it: the CPU went to sleep under a job that was still running, with the
            // notification still promising it was awake. The lock ends with the work, in
            // stopEverything, and the kernel drops it with the process if that never runs.
            wakeLock.acquire();
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
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION, notification(text));
        } catch (Throwable notAllowed) {
            // Notifications may be denied. The work continues; only the update is lost.
        }
    }

    private Notification notification(String text) {
        lastNote = text;
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
