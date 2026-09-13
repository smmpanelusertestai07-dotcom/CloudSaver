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
    static final String EXTRA_AGENT = "agent";

    /** Broadcast so the screens can follow along without polling. */
    static final String EVENT = "com.pocketagent.doors.EVENT";
    static final String EXTRA_LINE = "line";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_URL = "url";

    private static volatile String runningAgent;
    private Thread worker;
    private Process process;

    static String runningAgent() {
        return runningAgent;
    }

    static void start(Context context, String agentId) {
        Intent intent = new Intent(context, DoorService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_AGENT, agentId);
        context.startForegroundService(intent);
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
        String agentId = intent.getStringExtra(EXTRA_AGENT);
        Doors.Agent agent = Doors.byId(agentId);
        if (agent == null || !agent.implemented()) {
            shutdown();
            return START_NOT_STICKY;
        }
        startInForeground(agent);
        return START_NOT_STICKY;
    }

    private void startInForeground(Doors.Agent agent) {
        ensureChannel(this);
        startForeground(7, notification(agent.name, "Starting…"));
        runningAgent = agent.id;
        worker = new Thread(() -> run(agent), "door-" + agent.id);
        worker.start();
    }

    private void run(Doors.Agent agent) {
        List<String> transcript = new ArrayList<>();
        String route = "";
        String url = agent.surface;
        boolean ready = false;
        try {
            // Scripts are rewritten on every start so an app update's fixes take effect
            // without the owner reinstalling anything.
            Ubuntu.writeScripts(this);
            process = Ubuntu.start(this, "bash /opt/doors/" + agent.start);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String clean = Ubuntu.clean(line);
                    if (clean.isEmpty()) continue;
                    transcript.add(clean);
                    if (transcript.size() > 200) transcript.remove(0);

                    // The scripts speak two words the app acts on, and everything else is
                    // just news for the screen.
                    if (clean.startsWith("READY ")) {
                        ready = true;
                        url = clean.substring(6).trim();
                        notify(agent.name, "Running");
                        broadcast(clean, "ready", url);
                        continue;
                    }
                    if (clean.startsWith("SERVICE ")) { route = "service"; }
                    if (clean.startsWith("FOREGROUND ")) { route = "foreground"; }
                    broadcast(clean, ready ? "ready" : "working", url);
                }
            }
            int code = process.waitFor();
            if (ready && code == 0) {
                // A door that reported READY and then finished cleanly is a daemon that
                // detached; it is still working even though this pipe closed.
                Probe.record(this, agent.id, Probe.WORKS, "", route);
                broadcast("Running in the background.", "ready", url);
                return;
            }
            if (ready) {
                Probe.record(this, agent.id, Probe.WORKS, "", route);
                broadcast("The door closed with code " + code + ".", "stopped", url);
            } else {
                Probe.record(this, agent.id, Probe.FAILED, join(transcript), route);
                broadcast("This door did not open. Code " + code + ".", "failed", url);
            }
        } catch (Exception problem) {
            String reason = problem.getMessage() == null
                    ? problem.getClass().getSimpleName() : problem.getMessage();
            transcript.add(reason);
            Probe.record(this, agent.id, Probe.FAILED, join(transcript), route);
            broadcast(reason, "failed", url);
        } finally {
            runningAgent = null;
            stopSelf();
        }
    }

    private void shutdown() {
        String agentId = runningAgent;
        runningAgent = null;
        if (process != null) process.destroy();
        if (worker != null) worker.interrupt();
        if (agentId != null) {
            Doors.Agent agent = Doors.byId(agentId);
            if (agent != null) {
                // Ask the door to close itself as well, so a detached daemon does not keep
                // running after the notification has gone.
                try {
                    Ubuntu.run(this, "bash /opt/doors/"
                            + agent.start.replaceFirst(" start", " stop"), null);
                } catch (Exception ignored) { }
            }
        }
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
        runningAgent = null;
        if (process != null) process.destroy();
        super.onDestroy();
    }
}
