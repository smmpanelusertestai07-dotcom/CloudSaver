package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Clock-injected metadata refresh state: notifications never invent a reset or quota balance. */
final class CodexRefresh {
    static final long CLIENT_LEASE = 120000, MIN_GAP = 10000;
    private static final String[] SECTIONS = {"account", "usage", "models"};
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Long> clients = new LinkedHashMap<>();
    private long epoch, nextResetAt, resetCheckedAt;
    private String pausedReason = "Connect Codex to refresh account data";
    private static final class State {
        long attempt, updated, updatedMono, retry, sequence, revision, demand;
        boolean loading, dirty = true, urgent;
        int failures; String error = "";
    }
    static final class Ticket {
        final String section; final long epoch, sequence, revision, demand;
        Ticket(String section, long epoch, State state) {
            this.section = section; this.epoch = epoch; sequence = state.sequence; revision = state.revision; demand = state.demand;
        }
    }
    CodexRefresh() { for (String name : SECTIONS) states.put(name, new State()); }
    void reset() {
        epoch++; nextResetAt = 0; resetCheckedAt = 0;
        for (String name : SECTIONS) states.put(name, new State());
    }
    void closed() { reset(); clients.clear(); pausedReason = "Connect Codex to refresh account data"; }
    void client(String id, boolean active, long now) {
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,100}")) return;
        prune(now);
        if (!active) { clients.remove(id); return; }
        boolean resumed = !clients.containsKey(id);
        if (clients.size() < 16 || clients.containsKey(id)) clients.put(id, now);
        if (resumed) for (String section : SECTIONS) {
            State state = states.get(section);
            if (state.updated == 0 || now - state.updatedMono >= 30000) need(section, false);
        }
    }
    boolean active(long now) { prune(now); return !clients.isEmpty(); }
    private void prune(long now) {
        Iterator<Map.Entry<String, Long>> entries = clients.entrySet().iterator();
        while (entries.hasNext()) if (now - entries.next().getValue() >= CLIENT_LEASE) entries.remove();
    }
    void need(String section, boolean urgent) {
        State state = states.get(section); if (state == null) return;
        state.dirty = true; state.urgent |= urgent; state.demand++;
    }
    void all(boolean urgent) { for (String name : SECTIONS) need(name, urgent); }
    void pause(String reason) { pausedReason = reason == null ? "" : reason; }
    Ticket next(long now, long wall, boolean eligible) {
        boolean visible = active(now);
        if (!eligible) return null;
        State identity = states.get("account");
        if (identity.loading) return null;
        for (String name : SECTIONS) {
            State state = states.get(name);
            if (!visible && !state.urgent) continue;
            if (!"account".equals(name) && (identity.updated == 0 || identity.dirty)) continue;
            boolean resetDue = "usage".equals(name) && nextResetAt > resetCheckedAt && nextResetAt > 0
                    && nextResetAt <= wall / 1000L - 2;
            boolean due = state.dirty || state.updated == 0 || now - state.updatedMono >= interval(name) || resetDue;
            if (!due || state.loading || now < state.retry || (state.sequence > 0 && now - state.attempt < MIN_GAP)) continue;
            state.loading = true; state.attempt = now; state.sequence++; state.urgent = false;
            if (resetDue) resetCheckedAt = nextResetAt;
            return new Ticket(name, epoch, state);
        }
        return null;
    }
    boolean current(Ticket ticket) {
        State state = states.get(ticket.section);
        return ticket.epoch == epoch && state.loading && ticket.sequence == state.sequence && ticket.revision == state.revision;
    }
    void discard(Ticket ticket) {
        State state = states.get(ticket.section);
        if (ticket.epoch == epoch && ticket.sequence == state.sequence) state.loading = false;
    }
    void success(Ticket ticket, long now, long wall) {
        if (!current(ticket)) { discard(ticket); return; }
        State state = states.get(ticket.section); state.loading = false;
        state.updated = wall; state.updatedMono = now; state.retry = 0; state.failures = 0; state.error = "";
        state.dirty = state.demand != ticket.demand;
    }
    void failure(Ticket ticket, long now, String message) {
        if (!current(ticket)) { discard(ticket); return; }
        State state = states.get(ticket.section); state.loading = false; state.dirty = true; state.failures++;
        state.error = clean(message, 350);
        state.retry = now + Math.min(300000L, 15000L * (1L << Math.min(5, state.failures - 1)));
    }
    void observed(String section, long now, long wall) {
        State state = states.get(section); if (state == null) return;
        state.revision++; state.updated = wall; state.updatedMono = now; state.retry = 0; state.failures = 0; state.error = "";
        state.dirty = false; state.urgent = false;
    }
    void resetDeadline(JSONObject payload) {
        long next = 0;
        JSONObject primary = payload.optJSONObject("rateLimits");
        next = bucketDeadline(primary, next);
        JSONObject buckets = payload.optJSONObject("rateLimitsByLimitId");
        if (buckets != null) for (Iterator<String> names = buckets.keys(); names.hasNext();) next = bucketDeadline(buckets.optJSONObject(names.next()), next);
        nextResetAt = next;
    }
    private long bucketDeadline(JSONObject bucket, long next) {
        if (bucket == null) return next;
        for (String key : new String[]{"primary", "secondary", "individualLimit"}) {
            JSONObject window = bucket.optJSONObject(key);
            Object raw = window == null ? null : window.opt("resetsAt");
            if (!(raw instanceof Number)) continue;
            long value = ((Number) raw).longValue();
            if (value > resetCheckedAt && value > 0 && value < Long.MAX_VALUE / 1000L && (next == 0 || value < next)) next = value;
        }
        return next;
    }
    JSONObject snapshot(long now, long wall) {
        JSONObject result = object("active", active(now), "clientCount", clients.size(), "pausedReason", pausedReason,
                "nextResetAt", nextResetAt > 0 ? nextResetAt : JSONObject.NULL);
        for (String name : SECTIONS) {
            State state = states.get(name);
            try { result.put(name, object("loading", state.loading, "updatedAt", state.updated,
                "lastAttemptAt", state.sequence == 0 ? 0 : wall - Math.max(0, now - state.attempt),
                "nextRetryAt", state.retry > now ? wall + (state.retry - now) : 0,
                "error", state.error, "failures", state.failures,
                "stale", state.updated == 0 || state.dirty || now - state.updatedMono >= interval(name))); }
            catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        }
        return result;
    }
    private static long interval(String section) { return "usage".equals(section) ? 60000L : "account".equals(section) ? 300000L : 600000L; }
}
