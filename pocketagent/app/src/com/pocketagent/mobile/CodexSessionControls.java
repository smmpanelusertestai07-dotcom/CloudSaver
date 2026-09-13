package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Official local Codex history; account changes invalidate cached data and pending requests. */
final class CodexSessionControls {
    interface Reply { void receive(JSONObject result, JSONObject error); }
    interface Host {
        void request(String method, JSONObject params, Reply reply) throws Exception;
        void resume(String threadId) throws Exception;
        void changed();
        default void closedThread(String id, String operation) throws Exception { }
        default void observed(JSONArray rows) throws Exception { }
        default void changedThread(JSONObject change) throws Exception { }
    }
    private final Host host;
    private String cwd = "", accountScope = "", activeThread = "", operation = "", error = "";
    private String status = "Connect Codex to load this project's conversations", cursor = "", search = "";
    private String shareText = "", shareId = "", shareTitle = "";
    private JSONArray rows = new JSONArray();
    private boolean archived, known, truncated, shareTruncated;
    private boolean replacePage;
    private JSONObject lastChange = new JSONObject();
    private long epoch, sequence, updatedAt;
    private final Set<String> seenCursors = new HashSet<>();
    static final int PAGE_SIZE = 30, MAX_ROWS = 60, MAX_CATALOG_CHARS = 24000, MAX_SHARE_CHARS = 30000;

    CodexSessionControls(Host host) { this.host = host; }
    void reset(String directory, String scope, String active) {
        epoch++; sequence++; cwd = directory == null ? "" : directory; accountScope = scope == null ? "" : scope;
        activeThread = active == null ? "" : active; rows = new JSONArray(); known = false; truncated = false;
        cursor = ""; search = ""; operation = ""; error = ""; seenCursors.clear();
        shareText = ""; shareId = ""; shareTitle = ""; shareTruncated = false;
        status = "Loading local conversations"; updatedAt = 0; lastChange = new JSONObject(); host.changed();
    }
    void active(String id) { activeThread = id == null ? "" : id; if ("resume".equals(operation)) finish("Conversation opened"); else host.changed(); }
    void resumed(String id) { active(id); }
    void closed() { epoch++; sequence++; operation = ""; shareText = ""; shareId = ""; status = "Saved on this phone · reconnect to continue"; host.changed(); }
    boolean changing() { return !operation.isEmpty() && !"refresh".equals(operation) && !"more".equals(operation) && !"export".equals(operation); }
    boolean pending() { return !operation.isEmpty(); }
    void reject(String message) { operation = ""; error = clean(message, 500); status = error; host.changed(); }

    JSONObject snapshot() {
        return object("rows", rows, "known", known, "loading", !operation.isEmpty(), "operation", operation,
            "status", status, "error", error, "archived", archived, "search", search, "hasMore", !cursor.isEmpty() && !truncated,
            "truncated", truncated, "updatedAt", updatedAt, "activeThread", activeThread, "lastChange", lastChange,
            "shareText", shareText, "shareId", shareId, "shareTitle", shareTitle, "shareTruncated", shareTruncated,
            "scopeNote", "Local Codex history for this project on this phone. The engine does not report which ChatGPT account originally created each conversation.");
    }

    void dispatch(String op, JSONObject payload) {
        if ("dismiss_share".equals(op)) { shareText = ""; shareId = ""; shareTitle = ""; shareTruncated = false; host.changed(); return; }
        if (!operation.isEmpty()) { error = "Wait for the current conversation action to finish."; host.changed(); return; }
        try {
            if (cwd.isEmpty() || accountScope.isEmpty()) throw new IllegalStateException("Reconnect your ChatGPT account before opening session history.");
            if ("refresh".equals(op)) {
                boolean nextArchived = payload.optBoolean("archived", false); String nextSearch = boundedSearch(payload.optString("search", ""));
                if (nextArchived != archived || !nextSearch.equals(search)) { rows = new JSONArray(); known = false; }
                archived = nextArchived; search = nextSearch;
                cursor = ""; replacePage = true; truncated = false; seenCursors.clear();
                begin(op, "Loading conversations…"); loadPage("");
            } else if ("more".equals(op)) {
                if (cursor.isEmpty() || truncated) throw new IllegalStateException("All available conversations are loaded.");
                begin(op, "Loading more conversations…"); loadPage(cursor);
            } else if ("resume".equals(op) || "rename".equals(op) || "archive".equals(op) || "unarchive".equals(op) || "delete".equals(op) || "export".equals(op)) {
                String id = id(payload.optString("threadId", ""));
                if (("archive".equals(op) || "unarchive".equals(op) || "delete".equals(op)) && !Boolean.TRUE.equals(payload.opt("confirmed"))) throw new IllegalArgumentException("Confirm this conversation action first.");
                String title = "rename".equals(op) ? name(payload.optString("name", "")) : "";
                begin(op, "export".equals(op) ? "Reading conversation for sharing…" : "Checking conversation…");
                // Cached and active thread IDs need not be in the current page. Always verify official metadata before using them.
                call("thread/read", object("threadId", id, "includeTurns", "export".equals(op)), (result, failure) -> {
                    if (failure != null) { reject(message(failure)); return; }
                    try {
                        JSONObject thread = child(result, "thread"); verify(thread, id, cwd);
                        if ("resume".equals(op)) { status = "Opening conversation…"; host.changed(); host.resume(id); }
                        else if ("export".equals(op)) export(thread);
                        else {
                            String method = "rename".equals(op) ? "thread/name/set" : "thread/" + op;
                            JSONObject params = "rename".equals(op) ? renameParams(id, title) : object("threadId", id);
                            call(method, params, (answer, rpcError) -> {
                                if (rpcError != null) { reject(message(rpcError)); return; }
                                long changedAt = System.currentTimeMillis();
                                lastChange = object("id", id, "operation", op, "name", title, "revision", changedAt * 1000L + (++sequence % 1000), "changedAt", changedAt);
                                Exception cacheFailure = null;
                                try { host.changedThread(lastChange); } catch (Exception persistenceError) { cacheFailure = persistenceError; }
                                if (id.equals(activeThread) && ("archive".equals(op) || "delete".equals(op))) {
                                    try { host.closedThread(id, op); activeThread = ""; }
                                    catch (Exception followup) { reject("Conversation " + ("delete".equals(op) ? "deleted" : "archived") + ". Reconnect to start another chat. " + clean(followup.getMessage(), 200)); return; }
                                }
                                if (cacheFailure != null) { reject("Conversation changed in Codex, but its local title could not be saved. " + clean(cacheFailure.getMessage(), 200)); return; }
                                finish("rename".equals(op) ? "Conversation renamed" : "delete".equals(op) ? "Conversation deleted from Codex" : "unarchive".equals(op) ? "Conversation restored" : "Conversation archived");
                                refreshAfterChange();
                            });
                        }
                    } catch (Exception error) { reject(error.getMessage()); }
                });
            } else throw new IllegalArgumentException("This conversation action is not supported.");
        } catch (Exception failure) { reject(failure.getMessage()); }
    }

    private void loadPage(String pageCursor) {
        long own = ++sequence;
        call("thread/list", listParams(cwd, archived, search, pageCursor), (result, failure) -> {
            if (own != sequence) return;
            if (failure != null) { reject(message(failure)); return; }
            try {
                JSONArray incoming = list(result, "data"), observed = new JSONArray();
                for (int i = 0; i < incoming.length(); i++) { JSONObject raw = childAt(incoming, i);
                    if (cwd.equals(raw.optString("cwd")) && "openai".equals(raw.optString("modelProvider")) && validId(raw.optString("id"))) observed.put(compact(raw, archived));
                }
                host.observed(observed);
                if (replacePage) { rows = new JSONArray(); replacePage = false; }
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < rows.length(); i++) seen.add(childAt(rows, i).optString("id"));
                for (int i = 0; i < incoming.length(); i++) {
                    JSONObject raw = childAt(incoming, i);
                    if (!cwd.equals(raw.optString("cwd")) || !"openai".equals(raw.optString("modelProvider"))) continue;
                    String id = raw.optString("id"); if (!validId(id) || !seen.add(id)) continue;
                    JSONObject row = compact(raw, archived);
                    JSONArray candidate = copyRows(rows); candidate.put(row);
                    while (candidate.length() > MAX_ROWS || candidate.toString().length() > MAX_CATALOG_CHARS) candidate.remove(0);
                    rows = candidate;
                }
                cursor = nonNull(result, "nextCursor");
                if (!cursor.isEmpty() && (!seenCursors.add(cursor) || cursor.length() > 4096)) { cursor = ""; truncated = true; throw new IllegalStateException("The engine returned an invalid conversation cursor. Refresh to retry."); }
                // Keep the transport snapshot bounded, but retain the engine cursor. Older pages are held by the private local index.
                known = true; updatedAt = System.currentTimeMillis();
                finish(truncated ? "Display limit reached · narrow your search" : rows.length() + (archived ? " archived conversations" : " conversations"));
            } catch (Exception invalid) { reject(invalid.getMessage()); }
        });
    }
    private void refreshAfterChange() {
        cursor = ""; replacePage = true; truncated = false; seenCursors.clear();
        begin("refresh", "Updating conversations…"); loadPage("");
    }
    private void export(JSONObject thread) {
        JSONObject rendered = transcript(thread);
        shareText = rendered.optString("text"); shareTruncated = rendered.optBoolean("truncated");
        shareId = thread.optString("id") + ":" + System.nanoTime(); shareTitle = title(thread);
        if (shareText.isEmpty()) { reject("The engine returned no readable conversation text. This chat may be empty or use a history format this build cannot export."); return; }
        finish("Review the conversation before sharing");
    }
    private void call(String method, JSONObject params, Reply reply) {
        long ownEpoch = epoch; String ownAccount = accountScope;
        try { host.request(method, params, (result, error) -> { if (ownEpoch == epoch && ownAccount.equals(accountScope)) reply.receive(result, error); }); }
        catch (Exception error) { reject(error.getMessage()); }
    }
    private void begin(String action, String message) { operation = action; status = message; error = ""; host.changed(); }
    private void finish(String message) { operation = ""; status = message; error = ""; host.changed(); }

    static JSONObject listParams(String cwd, boolean archived, String search, String cursor) {
        if (cwd == null || !cwd.startsWith("/") || cwd.length() > 2048 || control(cwd)) throw new IllegalArgumentException("Choose a valid workspace.");
        JSONObject params = object("cwd", cwd, "archived", archived, "limit", PAGE_SIZE, "sortKey", "updated_at", "sortDirection", "desc", "modelProviders", array("openai"));
        try { if (search != null && !search.isEmpty()) params.put("searchTerm", boundedSearch(search)); if (cursor != null && !cursor.isEmpty()) params.put("cursor", cursor); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
        return params;
    }
    static JSONObject renameParams(String threadId, String title) { return object("threadId", id(threadId), "name", name(title)); }
    static void verify(JSONObject thread, String expectedId, String expectedCwd) {
        if (!expectedId.equals(thread.optString("id")) || !expectedCwd.equals(thread.optString("cwd")) || !"openai".equals(thread.optString("modelProvider")))
            throw new IllegalArgumentException("This conversation no longer belongs to the selected local workspace/provider. Refresh before continuing.");
    }
    static JSONObject compact(JSONObject raw, boolean archived) {
        return object("id", id(raw.optString("id")), "name", title(raw), "preview", clean(nonNull(raw, "preview"), 240),
            "updatedAt", raw.optLong("updatedAt"), "createdAt", raw.optLong("createdAt"), "archived", archived,
            "model", clean(nonNull(raw, "model"), 100), "historyMode", clean(nonNull(raw, "historyMode"), 30), "observedAt", System.currentTimeMillis());
    }
    static JSONObject transcript(JSONObject thread) {
        String heading = title(thread); StringBuilder result = new StringBuilder("# " + heading + "\n\n");
        boolean truncated = false, any = false; JSONArray turns = list(thread, "turns");
        outer: for (int t = 0; t < turns.length(); t++) {
            JSONArray items = list(childAt(turns, t), "items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = childAt(items, i); String type = item.optString("type"), role, text = "";
                if ("agentMessage".equals(type)) { role = "Assistant"; text = nonNull(item, "text"); }
                else if ("userMessage".equals(type)) {
                    role = "You"; JSONArray content = list(item, "content"); StringBuilder user = new StringBuilder();
                    for (int c = 0; c < content.length(); c++) { JSONObject block = childAt(content, c); if ("text".equals(block.optString("type"))) user.append(nonNull(block, "text")).append('\n'); }
                    text = user.toString().trim();
                } else continue; // Share conversation text without tool payloads or internal reasoning.
                if (text.isEmpty()) continue;
                any = true; String chunk = "## " + role + "\n\n" + text + "\n\n";
                int remaining = MAX_SHARE_CHARS - 80 - result.length();
                if (chunk.length() > remaining) { if (remaining > 0) result.append(chunk, 0, remaining); truncated = true; break outer; }
                result.append(chunk);
            }
        }
        if (truncated) result.append("\n\n[Shared excerpt: this conversation exceeds the mobile sharing limit.]\n");
        return object("text", any ? result.toString() : "", "truncated", truncated);
    }
    private static String title(JSONObject row) { String value = nonNull(row, "name").trim(); return clean(value.isEmpty() ? "Untitled conversation" : value, 160); }
    private static String boundedSearch(String value) { String text = value == null ? "" : value.trim(); if (text.length() > 120 || control(text)) throw new IllegalArgumentException("Use up to 120 characters to search titles."); return text; }
    private static String name(String value) { String text = value == null ? "" : value.trim(); if (text.isEmpty() || text.length() > 160 || control(text)) throw new IllegalArgumentException("Use a conversation name with 1–160 characters."); return text; }
    private static String id(String value) { if (!validId(value)) throw new IllegalArgumentException("Choose a valid conversation ID."); return value; }
    private static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}"); }
    private static boolean control(String value) { for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true; return false; }
    private static String nonNull(JSONObject value, String key) { return value.isNull(key) ? "" : value.optString(key, ""); }
    private static JSONObject childAt(JSONArray values, int at) { JSONObject value = values.optJSONObject(at); return value == null ? new JSONObject() : value; }
    private static JSONArray copyRows(JSONArray source) { JSONArray copy = new JSONArray(); for (int i = 0; i < source.length(); i++) copy.put(source.opt(i)); return copy; }
    private static String message(JSONObject error) { return clean(error.optString("message", "The engine rejected this conversation action."), 500); }
}
