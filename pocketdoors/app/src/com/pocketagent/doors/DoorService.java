package com.pocketagent.doors;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps one agent's daemon alive, and says out loud what it is doing.
 *
 * An agent working on a change takes minutes, and a phone will happily end a process that has
 * no visible reason to exist. So the daemon runs under a foreground service with a notification
 * that names the agent -- which is also the honest thing, because something is genuinely
 * running on the owner's phone and they should be able to see it and stop it.
 *
 * One at a time, on purpose. This phone has under four gigabytes, and a second Node process
 * beside the first is the difference between working and being killed mid-task.
 */
public final class DoorService extends Service {
    static final String CHANNEL = "doors";
    static final String ACTION_START = "com.pocketagent.doors.START";
    static final String ACTION_STOP = "com.pocketagent.doors.STOP";
    /** A line typed by the owner, on its way to whatever the door is asking. */
    static final String ACTION_INPUT = "com.pocketagent.doors.INPUT";
    static final String EXTRA_AGENT = "agent";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_TEXT = "text";
    static final String MODE_START = "start";
    static final String MODE_LOGIN = "login";

    /** Broadcast so the screens can follow along without polling. */
    static final String EVENT = "com.pocketagent.doors.EVENT";
    static final String EXTRA_LINE = "line";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_URL = "url";
    /** A link the owner has to open in a real browser, when the door asks for one. */
    static final String EXTRA_LINK = "link";

    private static volatile String runningAgent;
    private volatile Thread worker;
    private volatile Process process;
    /** The door's own input. Sign-in is a conversation, not a one-way stream. */
    private java.io.Writer input;
    /**
     * Which run owns this service, counted up on every start.
     *
     * One screen starts this service three times in a normal sign-in: once on opening, once to
     * sign in, and once more when the sign-in finishes. Each of those used to overwrite the
     * process and thread of the one before without ending it, and the older run would then reach
     * its own clean-up and call stopSelf() -- which destroyed the process the NEW run was in the
     * middle of reading, and surfaced as "read interrupted by close() on another thread" over
     * whatever the owner was actually doing. A run now carries the number it was given and
     * touches nothing once a newer one exists.
     */
    private volatile long generation;

    static String runningAgent() {
        return runningAgent;
    }

    static void start(Context context, String agentId) {
        start(context, agentId, MODE_START);
    }

    static void start(Context context, String agentId, String mode) {
        Intent intent = new Intent(context, DoorService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_AGENT, agentId)
                .putExtra(EXTRA_MODE, mode);
        context.startForegroundService(intent);
    }

    /** Sends one typed line to the door, for the code a sign-in asks to have pasted back. */
    static void send(Context context, String text) {
        context.startService(new Intent(context, DoorService.class)
                .setAction(ACTION_INPUT).putExtra(EXTRA_TEXT, text));
    }

    static void stop(Context context) {
        context.startService(new Intent(context, DoorService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_INPUT.equals(action)) {
            write(intent.getStringExtra(EXTRA_TEXT));
            return START_NOT_STICKY;
        }
        String agentId = intent.getStringExtra(EXTRA_AGENT);
        String mode = intent.getStringExtra(EXTRA_MODE);
        Doors.Agent agent = Doors.byId(agentId);
        if (agent == null || !agent.implemented()) {
            shutdown();
            return START_NOT_STICKY;
        }
        startInForeground(agent, MODE_LOGIN.equals(mode) ? MODE_LOGIN : MODE_START);
        return START_NOT_STICKY;
    }

    private void startInForeground(Doors.Agent agent, String mode) {
        ensureChannel(this);
        boolean signingIn = MODE_LOGIN.equals(mode);
        startForeground(7, notification(agent.name, signingIn ? "Signing in…" : "Starting…"));
        long mine;
        synchronized (this) {
            // Everything already running is stale from this line onwards, and is ended here
            // rather than left to end itself on top of what replaces it.
            mine = ++generation;
            endProcess();
        }
        runningAgent = agent.id;
        Thread started = new Thread(() -> run(agent, mode, mine), "door-" + agent.id);
        worker = started;
        started.start();
    }

    /** True while this run is still the one the service belongs to. */
    private boolean current(long mine) {
        return mine == generation;
    }

    /** Ends whatever is running now, which under --kill-on-exit ends the workspace with it. */
    private synchronized void endProcess() {
        Process running = process;
        process = null;
        if (input != null) {
            try { input.close(); } catch (java.io.IOException ignored) { }
            input = null;
        }
        if (running != null) running.destroy();
    }

    /** Writes one line into the door, with the newline it is waiting for. */
    private synchronized void write(String text) {
        if (input == null || text == null) return;
        try {
            input.write(text);
            input.write("\n");
            input.flush();
        } catch (java.io.IOException closed) {
            broadcast("That answer could not be delivered; the door has closed.", "failed", "");
        }
    }

    private void run(Doors.Agent agent, String mode, long mine) {
        boolean signingIn = MODE_LOGIN.equals(mode);
        String command = signingIn ? agent.login : agent.start;
        List<String> transcript = new ArrayList<>();
        String route = "";
        String url = agent.surface;
        boolean ready = false;
        try {
            // Scripts are rewritten on every start so an app update's fixes take effect
            // without the owner reinstalling anything.
            Ubuntu.writeScripts(this);
            Process started = Ubuntu.start(this, "bash /opt/doors/" + command);
            synchronized (this) {
                if (!current(mine)) { started.destroy(); return; }
                process = started;
            }
            // Sign-in is a conversation: the door prints a link, the owner opens it in a real
            // browser, and the code that comes back is typed here. So its input stays open.
            synchronized (this) {
                input = new java.io.OutputStreamWriter(
                        started.getOutputStream(), StandardCharsets.UTF_8);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!current(mine)) return;
                    String clean = Ubuntu.clean(line);
                    if (clean.isEmpty()) continue;
                    transcript.add(clean);
                    if (transcript.size() > 200) transcript.remove(0);

                    // The scripts speak a few words the app acts on; everything else is just
                    // news for the screen.
                    if (clean.startsWith("SIGNEDIN")) {
                        broadcast("Signed in.", "signedin", url);
                        continue;
                    }
                    if (clean.startsWith("NEEDLOGIN ")) {
                        broadcast(clean.substring(10).trim(), "needlogin", url);
                        continue;
                    }
                    if (clean.startsWith("ASK ")) {
                        broadcast(clean.substring(4).trim(), "asking", url);
                        continue;
                    }
                    if (clean.startsWith("READY ")) {
                        ready = true;
                        url = clean.substring(6).trim();
                        notify(agent.name, "Running");
                        broadcast(clean, "ready", url);
                        continue;
                    }
                    if (clean.startsWith("SERVICE ")) { route = "service"; }
                    if (clean.startsWith("FOREGROUND ")) { route = "foreground"; }

                    // Only now, and only on a line that is not one of this app's own words.
                    //
                    // This used to come first, and it broke the editor door outright: the line
                    // it announces itself with is "READY http://127.0.0.1:8391/", which contains
                    // a link, so the scan claimed it, showed the owner a sign-in strip for a
                    // server that needs no sign-in, and skipped the READY that would have opened
                    // the door. The server was running and the app reported that it had failed.
                    // A marker is this app's own protocol; a guess about a line must never win
                    // over one.
                    String link = firstLink(clean);
                    if (link != null) {
                        sendBroadcast(new Intent(EVENT).setPackage(getPackageName())
                                .putExtra(EXTRA_LINE, clean)
                                .putExtra(EXTRA_STATE, "asking")
                                .putExtra(EXTRA_LINK, link)
                                .putExtra(EXTRA_URL, url));
                        continue;
                    }
                    broadcast(clean, ready ? "ready" : "working", url);
                }
            }
            int code = started.waitFor();
            if (!current(mine)) return;
            if (signingIn) {
                // Sign-in has no daemon to leave behind; it either recorded a credential or
                // it did not, and the script says which with its exit code.
                broadcast(code == 0 ? "Signed in." : "Sign-in did not finish.",
                        code == 0 ? "signedin" : "failed", url);
                return;
            }
            if (ready) {
                // Nothing here detaches. proot runs with --kill-on-exit, so when this script
                // finishes every process inside the workspace goes with it -- a door that
                // reached READY and then returned has stopped, however clean its exit code.
                // Saying "running in the background" here is what sent someone to a browser
                // that got ERR_CONNECTION_REFUSED.
                Probe.record(this, agent.id, Probe.WORKS, "", route);
                broadcast(code == 0
                        ? "This door has closed. Open it again when you need it."
                        : "The door closed with code " + code + ".", "stopped", url);
            } else {
                Probe.record(this, agent.id, Probe.FAILED, join(transcript), route);
                broadcast("This door did not open. Code " + code + ".", "failed", url);
            }
        } catch (Exception problem) {
            // A run that has already been replaced fails on the way out by design -- its pipe is
            // closed underneath it. That is this app ending it, not the door failing, and saying
            // so over whatever the owner is now doing is how a working sign-in looked broken.
            if (!current(mine)) return;
            String reason = problem.getMessage() == null
                    ? problem.getClass().getSimpleName() : problem.getMessage();
            transcript.add(reason);
            Probe.record(this, agent.id, Probe.FAILED, join(transcript), route);
            broadcast(reason, "failed", url);
        } finally {
            // Only the run that still owns the service may take the service down with it.
            if (current(mine)) {
                synchronized (this) {
                    if (input != null) {
                        try { input.close(); } catch (java.io.IOException ignored) { }
                        input = null;
                    }
                    process = null;
                }
                runningAgent = null;
                stopSelf();
            }
        }
    }

    /** The first http(s) link on a line, with trailing punctuation left off. */
    static String firstLink(String line) {
        int at = line.indexOf("https://");
        if (at < 0) at = line.indexOf("http://");
        if (at < 0) return null;
        int end = at;
        while (end < line.length() && " \t\"'<>".indexOf(line.charAt(end)) < 0) end++;
        while (end > at && ".,);:]".indexOf(line.charAt(end - 1)) >= 0) end--;
        String link = line.substring(at, end);
        return link.length() > 12 ? link : null;
    }

    private void shutdown() {
        synchronized (this) {
            // Past every run in flight, so none of them can report or clean up after this.
            generation++;
            endProcess();
        }
        Thread running = worker;
        if (running != null) running.interrupt();
        runningAgent = null;
        // Nothing is asked to stop itself. proot is started with --kill-on-exit, so destroying
        // the process above already ended everything inside the workspace; the previous build
        // booted a whole second workspace here just to run a "stop" that had nothing left to
        // stop -- on the main thread, where a slow phone would have held the interface still.
        broadcast("Stopped.", "stopped", "");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void broadcast(String line, String state, String url) {
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName())
                .putExtra(EXTRA_LINE, line)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_URL, url));
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        int from = Math.max(0, lines.size() - 12);
        for (int i = from; i < lines.size(); i++) text.append(lines.get(i)).append('\n');
        return text.toString();
    }

    private void notify(String title, String body) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(7, notification(title, body));
    }

    private Notification notification(String title, String body) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, DoorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_pocketagent)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Running agents",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while an agent is working on this phone, with a way to stop it.");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        synchronized (this) {
            generation++;
            endProcess();
        }
        runningAgent = null;
        super.onDestroy();
    }
}
