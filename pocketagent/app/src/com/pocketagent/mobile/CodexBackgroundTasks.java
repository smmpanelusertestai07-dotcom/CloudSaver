package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Displays only background terminals actually reported for the active Codex thread. */
final class CodexBackgroundTasks {
    interface Reply { void receive(JSONObject result, JSONObject error); }
    interface Host { void request(String method, JSONObject params, Reply reply) throws Exception; void changed(); }
    private final Host host;
    private String threadId = "", operation = "", error = "", status = "Refresh to read the active conversation's tasks", cursor = "";
    private JSONArray rows = new JSONArray();
    private boolean known, truncated;
    private long epoch, updatedAt;
    private final Set<String> seen = new HashSet<>();
    CodexBackgroundTasks(Host host) { this.host = host; }
    void reset(String id) { epoch++; threadId = id == null ? "" : id; clear(); host.changed(); }
    void closed() { reset(""); status = "Connect Codex to read background tasks"; host.changed(); }
    boolean pending() { return !operation.isEmpty(); }
    boolean changing() { return "tasks_terminate".equals(operation) || "tasks_clean".equals(operation); }
    JSONObject snapshot() { return object("rows", rows, "threadId", threadId, "known", known, "loading", pending(), "operation", operation,
        "error", error, "status", status, "hasMore", !cursor.isEmpty() && !truncated, "truncated", truncated, "updatedAt", updatedAt); }
    void dispatch(String op, JSONObject payload) {
        if (pending()) { error = "Wait for the current task action to finish."; host.changed(); return; }
        try {
            valid(threadId);
            if ("tasks_refresh".equals(op)) { clear(); begin(op, "Loading background tasks…"); load(""); }
            else if ("tasks_more".equals(op)) {
                if (cursor.isEmpty() || truncated) throw new IllegalArgumentException("There are no more tasks to display.");
                begin(op, "Loading more tasks…"); load(cursor);
            } else if ("tasks_terminate".equals(op) || "tasks_clean".equals(op)) {
                if (!Boolean.TRUE.equals(payload.opt("confirmed"))) throw new IllegalArgumentException("Confirm which background tasks to stop.");
                if (!threadId.equals(payload.optString("threadId", ""))) throw new IllegalArgumentException("The conversation changed. Refresh and review its current tasks before stopping anything.");
                if (!known) throw new IllegalArgumentException("Refresh the task list before stopping a process.");
                String processId = payload.optString("processId", "");
                if ("tasks_terminate".equals(op)) require(processId);
                // A truncated page cannot give informed consent to stop unseen processes.
                if ("tasks_clean".equals(op) && (truncated || !cursor.isEmpty())) throw new IllegalArgumentException("Load the complete task list before choosing Stop all.");
                if (rows.length() == 0) throw new IllegalArgumentException("The engine has not reported a running background task.");
                begin(op, "Stopping background task" + ("tasks_clean".equals(op) ? "s…" : "…"));
                String method = "tasks_clean".equals(op) ? "thread/backgroundTerminals/clean" : "thread/backgroundTerminals/terminate";
                JSONObject params = "tasks_clean".equals(op) ? object("threadId", threadId) : terminateParams(threadId, processId);
                call(method, params, (result, failure) -> {
                    if (failure != null) { reject(failure); return; }
                    clear(); begin("tasks_refresh", "Checking remaining tasks…"); load("");
                });
            } else throw new IllegalArgumentException("This task action is not supported.");
        } catch (Exception invalid) { operation = ""; error = clean(invalid.getMessage(), 500); status = error; host.changed(); }
    }
    private void load(String page) {
        call("thread/backgroundTerminals/list", listParams(threadId, page), (result, failure) -> {
            if (failure != null) { reject(failure); return; }
            JSONArray source = list(result, "data"); Set<String> ids = new HashSet<>();
            for (int i = 0; i < rows.length(); i++) ids.add(childAt(rows, i).optString("processId"));
            for (int i = 0; i < source.length(); i++) {
                JSONObject value = source.optJSONObject(i); if (value == null) continue;
                String id = value.optString("processId", "");
                if (!validId(id) || !ids.add(id)) continue;
                JSONObject compact = compact(value); JSONArray candidate = new JSONArray();
                for (int j = 0; j < rows.length(); j++) candidate.put(rows.opt(j)); candidate.put(compact);
                if (candidate.length() > 50 || candidate.toString().length() > 16000) { truncated = true; break; }
                rows = candidate;
            }
            cursor = result.isNull("nextCursor") ? "" : result.optString("nextCursor", "");
            if (!cursor.isEmpty() && (cursor.length() > 4096 || !seen.add(cursor))) { cursor = ""; truncated = true; reject(object("message", "The engine returned an invalid task cursor. Refresh to retry.")); return; }
            if (rows.length() >= 50 && !cursor.isEmpty()) truncated = true;
            operation = ""; error = ""; known = true; updatedAt = System.currentTimeMillis();
            status = truncated ? "Task display limit reached" : rows.length() + " background tasks"; host.changed();
        });
    }
    private void clear() { rows = new JSONArray(); known = false; truncated = false; cursor = ""; operation = ""; error = ""; seen.clear(); updatedAt = 0; }
    private void begin(String op, String message) { operation = op; status = message; error = ""; host.changed(); }
    private void reject(JSONObject failure) { operation = ""; error = clean(failure.optString("message", "The engine could not manage background tasks."), 500); status = error; host.changed(); }
    private void call(String method, JSONObject params, Reply reply) {
        long own = epoch;
        try { host.request(method, params, (result, failure) -> { if (own == epoch) reply.receive(result, failure); }); }
        catch (Exception failure) { reject(object("message", failure.getMessage())); }
    }
    private void require(String id) { valid(id); for (int i = 0; i < rows.length(); i++) if (id.equals(childAt(rows, i).optString("processId"))) return; throw new IllegalArgumentException("Choose a process from the latest task list."); }
    static JSONObject listParams(String thread, String cursor) {
        valid(thread); JSONObject params = object("threadId", thread, "limit", 20);
        if (cursor != null && !cursor.isEmpty()) { if (cursor.length() > 4096) throw new IllegalArgumentException("Invalid task cursor."); try { params.put("cursor", cursor); } catch (Exception invalid) { throw new IllegalArgumentException(invalid); } }
        return params;
    }
    static JSONObject terminateParams(String thread, String process) { valid(thread); valid(process); return object("threadId", thread, "processId", process); }
    static JSONObject compact(JSONObject raw) {
        JSONObject result = object("processId", raw.optString("processId"), "itemId", clean(raw.optString("itemId"), 160), "command", clean(raw.optString("command"), 1000), "cwd", clean(raw.optString("cwd"), 300));
        try {
            for (String key : new String[]{"cpuPercent", "rssKb", "osPid"}) if (raw.opt(key) instanceof Number) result.put(key, raw.opt(key));
        } catch (Exception ignored) { }
        return result;
    }
    private static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}"); }
    private static void valid(String value) { if (!validId(value)) throw new IllegalArgumentException("Connect a conversation and choose a task reported by Codex."); }
    private static JSONObject childAt(JSONArray array, int index) { JSONObject value = array.optJSONObject(index); return value == null ? new JSONObject() : value; }
}
