package com.pocketlinux;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Stops identified PRoot sessions and cleans recorded children after unexpected tracer death. */
final class ProotProcess {
    private static final long GRACE_MS = 2000;
    private static final ConcurrentHashMap<Process, StopRequest> STOPPING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Process, Session> TRACKED = new ConcurrentHashMap<>();
    private static volatile String lastTrackingEvent = "No session tracked yet.";
    private static final ScheduledExecutorService TRACKER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "pocketdesk-session-tracker");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService CLEANUP = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "pocketdesk-session-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private ProotProcess() {}

    static {
        TRACKER.scheduleWithFixedDelay(() -> {
            try { sampleSessions(); }
            catch (RuntimeException error) {
                lastTrackingEvent = "Session sampling unavailable: " + error.getClass().getSimpleName();
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    /** Called on the spawning worker, before handing the process to any other owner. */
    static void track(Process process) {
        if (process == null || TRACKED.containsKey(process) || !process.isAlive()) return;
        Identity root = readIdentity(pidOf(process));
        // Never establish ownership from a PID after the original child has exited: that
        // number might already describe an unrelated process. Historical sessions are safe.
        if (!process.isAlive()) return;
        if (root == null || root.uid != android.os.Process.myUid()) return;
        Session session = new Session(process, root);
        if (TRACKED.putIfAbsent(process, session) != null) return;
        // A first snapshot narrows the gap before the periodic sampler; later snapshots use
        // one shared /proc inventory for every live session, with no native helper process.
        collectMembers(session, ownProcesses());
        if (!process.isAlive()) requestStop(process);
    }

    private static void sampleSessions() {
        List<Session> sessions = new ArrayList<>(TRACKED.values());
        if (sessions.isEmpty()) return;
        Map<Integer, Identity> visible = ownProcesses();
        for (Session session : sessions) {
            if (!STOPPING.containsKey(session.process)) collectMembers(session, visible);
            if (!session.process.isAlive()) requestStop(session.process);
        }
    }

    /** UI-safe: capture only the direct child's identity; process-tree work runs off the UI. */
    static void requestStop(Process process) {
        if (process == null || STOPPING.containsKey(process)) return;
        Session session = TRACKED.get(process);
        if (session == null) {
            if (!process.isAlive()) return;
            session = new Session(process, readIdentity(pidOf(process)));
            if (!process.isAlive()) return;
        }
        StopRequest request = new StopRequest(session);
        if (STOPPING.putIfAbsent(process, request) != null) return;
        if (session.root == null || session.root.uid != android.os.Process.myUid()) {
            request.done.completeExceptionally(new IOException("Could not verify the previous computer process."));
            return;
        }
        CLEANUP.execute(() -> {
            try {
                stopSession(request);
                request.done.complete(null);
            } catch (Throwable error) {
                request.done.completeExceptionally(error);
            }
            // Retain a failed request so a new session cannot overlap surviving old processes.
            if (!request.done.isCompletedExceptionally()) {
                TRACKED.remove(process, request.session);
                STOPPING.remove(process, request);
            }
        });
    }

    /** Worker cleanup, including an interrupted worker. Restore interruption after teardown. */
    static void stopAndWait(Process process) {
        requestStop(process);
        StopRequest request = STOPPING.get(process);
        if (request == null) return;
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (System.nanoTime() < deadline) {
                try {
                    request.done.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    return;
                } catch (InterruptedException error) {
                    interrupted = true;
                } catch (ExecutionException | TimeoutException error) {
                    return;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Call before spawning a new container: any old VNC or app children must be gone first. */
    static void awaitPendingStops() throws IOException {
        // Do not wait for the next sampler tick when a previous root just died.
        for (Session session : new ArrayList<>(TRACKED.values())) {
            if (!session.process.isAlive()) requestStop(session.process);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        for (StopRequest request : new ArrayList<>(STOPPING.values())) {
            try {
                request.done.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Stopping the previous computer was interrupted.", error);
            } catch (ExecutionException error) {
                if (!hasSurvivors(request)) {
                    TRACKED.remove(request.process, request.session);
                    STOPPING.remove(request.process, request);
                    continue;
                }
                throw new IOException("The previous computer is still stopping. Wait a moment and retry.", error);
            } catch (TimeoutException error) {
                throw new IOException("The previous computer is still stopping. Wait a moment and retry.", error);
            }
        }
    }

    private static void stopSession(StopRequest request) throws IOException, InterruptedException {
        // Capture the tree before touching the tracer. After its death, PPid may become 1 and
        // TracerPid becomes 0, so a fresh ancestry-only search would miss the orphaned children.
        collectMembers(request.session, ownProcesses());
        boolean tracerWasAlive = request.process.isAlive();
        lastTrackingEvent = "Cleanup root=" + request.root.pid + " rootAlive=" + tracerWasAlive
                + " recorded=" + request.session.recordedCount();
        if (request.process.isAlive()) {
            signal(request.root, 18); // SIGCONT: allow a thermally paused tracer to clean up.
            signal(request.root, 3);  // SIGQUIT: PRoot calls kill_all_tracees; SIGTERM is ignored.
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(tracerWasAlive ? GRACE_MS : 0);
        long nextCollection = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
        while (System.nanoTime() < deadline) {
            // Include a child forked between the initial snapshot and delivery of SIGQUIT.
            if (System.nanoTime() >= nextCollection) {
                collectMembers(request.session, ownProcesses());
                nextCollection = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            }
            if (!hasSurvivors(request)) return;
            Thread.sleep(60);
        }
        // Only our recorded, same-UID, same-start-time processes may receive the fallback.
        // Never match command-line text, process names or all processes owned by this app.
        collectMembers(request.session, ownProcesses());
        for (Identity member : request.session.membersSnapshot()) {
            if (member.pid != request.root.pid) signal(member, 9);
        }
        signal(request.root, 9);
        long killedDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (System.nanoTime() < killedDeadline) {
            if (!hasSurvivors(request)) return;
            Thread.sleep(40);
        }
        throw new IOException("Some previous computer processes have not exited yet.");
    }

    private static void collectMembers(Session session, Map<Integer, Identity> visible) {
        synchronized (session) {
            boolean changed;
            do {
                changed = false;
                for (Identity candidate : visible.values()) {
                    Identity known = session.members.get(candidate.pid);
                    if (known != null && known.same(candidate)) continue;
                    Identity parent = session.members.get(candidate.ppid);
                    Identity tracer = session.members.get(candidate.tracer);
                    // Validate the parent/tracer in this same snapshot, excluding PID reuse.
                    if ((parent != null && parent.same(visible.get(parent.pid)))
                            || (tracer != null && tracer.same(visible.get(tracer.pid)))) {
                        session.members.put(candidate.pid, candidate);
                        changed = true;
                    }
                }
            } while (changed);
            int live = 0;
            // Resolve the complete descendant tree before discarding dead parents. Keeping
            // every exited compiler/browser helper forever made each later sample and stop
            // revisit the full session history. Only proven exits or identity replacements
            // can be discarded; inaccessible /proc metadata still preserves ownership.
            Iterator<Identity> records = session.members.values().iterator();
            while (records.hasNext()) {
                Identity recorded = records.next();
                Identity now = visible.get(recorded.pid);
                if (recorded.same(now) && now.live()) {
                    live++;
                } else if (recorded.pid != session.root.pid && (now != null
                        || confirmedExited(recorded.pid))) {
                    records.remove();
                }
            }
            session.liveCount = live;
            session.peakCount = Math.max(session.peakCount, live);
            session.sampledAt = System.nanoTime();
        }
    }

    private static boolean confirmedExited(int pid) {
        try {
            // notExists returns false for access failures too. An uncertain process must
            // stay recorded so teardown never abandons a still-live orphaned descendant.
            return Files.notExists(new File("/proc/" + pid).toPath());
        } catch (RuntimeException unavailable) { return false; }
    }

    private static boolean hasSurvivors(StopRequest request) {
        for (Identity member : request.session.membersSnapshot()) {
            Identity now = readIdentity(member.pid);
            if (member.same(now) && now.live()) return true;
            // ENOENT confirms exit; access/parse failures do not. Keep the start barrier
            // closed on uncertain metadata, but never signal an identity we cannot verify.
            if (now == null) {
                try {
                    if (!Files.notExists(new File("/proc/" + member.pid).toPath())) return true;
                } catch (RuntimeException ignored) { return true; }
            }
        }
        // /proc may become unreadable on a vendor kernel: the direct Process remains evidence.
        return request.process.isAlive();
    }

    private static void signal(Identity recorded, int signal) {
        Identity now = readIdentity(recorded.pid);
        if (!recorded.same(now) || !now.live() || now.uid != android.os.Process.myUid()) return;
        try {
            android.os.Process.sendSignal(recorded.pid, signal);
        } catch (RuntimeException ignored) {
            // Re-check actual liveness; never broaden the signal target after a refusal.
        }
    }

    /** Android 10–13 exposes the PID in UNIXProcess.toString(), but has no Process.pid() API. */
    static int pidOf(Process process) {
        if (process == null) return -1;
        try {
            // This is the public AOSP representation, not hidden-field reflection. Accept only
            // its exact prefix and a positive decimal PID; every signal also validates /proc.
            String description = process.toString();
            if (!description.startsWith("Process[pid=") || !description.endsWith("]")) return -1;
            int comma = description.indexOf(',', 12);
            if (comma < 0) return -1;
            String digits = description.substring(12, comma).trim();
            if (digits.isEmpty() || digits.length() > 10) return -1;
            for (int i = 0; i < digits.length(); i++) {
                if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return -1;
            }
            long value = Long.parseLong(digits);
            return value > 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static Map<Integer, Identity> ownProcesses() {
        Map<Integer, Identity> found = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return found;
        int ownUid = android.os.Process.myUid();
        for (File entry : entries) {
            int pid;
            try { pid = Integer.parseInt(entry.getName()); }
            catch (NumberFormatException ignored) { continue; }
            Identity identity = readIdentity(pid);
            if (identity != null && identity.uid == ownUid) found.put(pid, identity);
        }
        return found;
    }

    private static Identity readIdentity(int pid) {
        if (pid <= 0) return null;
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()),
                    StandardCharsets.US_ASCII);
            int close = stat.lastIndexOf(')');
            if (close < 0) return null;
            String[] fields = stat.substring(close + 1).trim().split("\\s+");
            if (fields.length < 20) return null;
            String status = new String(Files.readAllBytes(new File("/proc/" + pid + "/status").toPath()),
                    StandardCharsets.US_ASCII);
            int uid = -1, tracer = 0;
            for (String line : status.split("\n")) {
                if (line.startsWith("Uid:")) uid = Integer.parseInt(line.trim().split("\\s+")[2]);
                else if (line.startsWith("TracerPid:")) tracer = Integer.parseInt(line.substring(10).trim());
            }
            if (uid < 0) return null;
            return new Identity(pid, Integer.parseInt(fields[1]), tracer, uid,
                    Long.parseLong(fields[19]), fields[0].charAt(0));
        } catch (IOException | RuntimeException ignored) {
            return null; // exited process, inaccessible metadata, or malformed kernel output
        }
    }

    /** Metadata only: never reads command-line arguments, environment, URLs or account data. */
    static String diagnosticSummary() {
        Map<Integer, Identity> processes = ownProcesses();
        StringBuilder output = new StringBuilder("Android same-UID native/app processes: ")
                .append(processes.size()).append('\n');
        int printed = 0;
        for (Identity process : processes.values()) {
            if (printed++ >= 40) { output.append("Additional process metadata omitted.\n"); break; }
            output.append("pid=").append(process.pid).append(" ppid=").append(process.ppid)
                    .append(" tracer=").append(process.tracer).append(" procState=").append(process.state)
                    .append(" start=").append(process.start).append('\n');
        }
        return output.append(trackingSummary()).toString();
    }

    /** Cached metadata only; safe for diagnostic sampling without scanning /proc again. */
    static String trackingSummary() {
        StringBuilder output = new StringBuilder("Tracked sessions: ").append(TRACKED.size())
                .append("; pending cleanup: ").append(STOPPING.size()).append('\n');
        long now = System.nanoTime();
        for (Session session : TRACKED.values()) {
            synchronized (session) {
                output.append("root=").append(session.root.pid)
                        .append(" rootAlive=").append(session.process.isAlive())
                        .append(" sampledLive=").append(session.liveCount)
                        .append(" peak=").append(session.peakCount)
                        .append(" recorded=").append(session.members.size())
                        .append(" sampleAgeMs=").append(session.sampledAt == 0 ? -1
                                : TimeUnit.NANOSECONDS.toMillis(now - session.sampledAt)).append('\n');
            }
        }
        return output.append(lastTrackingEvent).append('\n').toString();
    }

    private static final class Session {
        final Process process;
        final Identity root;
        final Map<Integer, Identity> members = new LinkedHashMap<>();
        long sampledAt;
        int liveCount, peakCount;
        Session(Process process, Identity root) {
            this.process = process; this.root = root;
            if (root != null) members.put(root.pid, root);
        }
        synchronized List<Identity> membersSnapshot() { return new ArrayList<>(members.values()); }
        synchronized int recordedCount() { return members.size(); }
    }

    private static final class StopRequest {
        final Process process;
        final Identity root;
        final Session session;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        StopRequest(Session session) {
            this.session = session; this.process = session.process; this.root = session.root;
        }
    }

    private static final class Identity {
        final int pid, ppid, tracer, uid;
        final long start;
        final char state;
        Identity(int pid, int ppid, int tracer, int uid, long start, char state) {
            this.pid = pid; this.ppid = ppid; this.tracer = tracer;
            this.uid = uid; this.start = start; this.state = state;
        }
        boolean same(Identity other) {
            return other != null && pid == other.pid && uid == other.uid && start == other.start;
        }
        boolean live() { return state != 'Z' && state != 'X'; }
    }
}
