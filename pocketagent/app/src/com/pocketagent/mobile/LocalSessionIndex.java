package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Private metadata cache. Observed account scope is not a claim of original thread ownership. */
final class LocalSessionIndex {
    private LocalSessionIndex() { }

    /** Persist every engine page before its bounded transport window rolls forward. */
    static synchronized void observe(File files, String provider, String project, JSONObject account, JSONObject rateLimits, JSONArray rows) throws IOException {
        JSONObject state = object("provider", provider, "project", project, "accountConnected", true, "account", account, "rateLimits", rateLimits);
        File directory = directory(files, provider, project, state, true); if (directory == null) return;
        for (int i = 0; i < rows.length(); i++) save(directory, rows.optJSONObject(i));
    }

    /** Record a confirmed provider mutation even when the UI is backgrounded. */
    static synchronized void changed(File files, String provider, String project, JSONObject account, JSONObject rateLimits, JSONObject change) throws IOException {
        update(files, provider, project, object("provider", provider, "project", project, "accountConnected", true,
                "account", account, "rateLimits", rateLimits, "sessions", object("lastChange", change)));
    }

    /** Call on a worker; never stores credentials or complete message bodies. */
    static synchronized void update(File files, String provider, String project, JSONObject state) throws IOException {
        if (!matches(state, provider, project)) return;
        File directory = directory(files, provider, project, state, true);
        if (directory == null) return;
        JSONObject sessions = child(state, "sessions");
        if (!sessions.optBoolean("loading")) {
            JSONArray rows = list(sessions, "rows");
            for (int i = 0; i < rows.length(); i++) save(directory, rows.optJSONObject(i));
        }
        JSONObject current = active(state);
        if (current != null) {
            File previousFile = direct(directory, digest(current.optString("id")) + ".json");
            JSONObject previous = readObject(previousFile);
            // Engine titles win over a prompt-derived fallback; activity updates must not revive an archived thread.
            if (previous != null) {
                if (!"New chat".equals(previous.optString("name"))) put(current, "name", previous.optString("name", current.optString("name")));
                put(current, "updatedAt", Math.max(previous.optLong("updatedAt"), current.optLong("updatedAt")));
                put(current, "archived", previous.optBoolean("archived"));
                put(current, "deleted", previous.optBoolean("deleted"));
                put(current, "changeRevision", previous.optString("changeRevision"));
                put(current, "changedAt", previous.optLong("changedAt"));
            }
            save(directory, current);
        }
        JSONObject change = child(sessions, "lastChange");
        String id = change.optString("id");
        if (validId(id)) {
            File file = direct(directory, digest(id) + ".json");
            JSONObject previous = readObject(file);
            if (previous == null) previous = object("id", id, "name", change.optString("name", "Conversation"));
            if (change.optLong("revision") > previous.optLong("changeRevision")) {
                String operation = change.optString("operation");
                if ("delete".equals(operation)) put(previous, "deleted", true);
                else if ("archive".equals(operation) || "unarchive".equals(operation)) put(previous, "archived", "archive".equals(operation));
                else if ("rename".equals(operation)) put(previous, "name", clean(change.optString("name"), 160));
                put(previous, "changeRevision", change.optString("revision"));
                put(previous, "changedAt", change.optLong("changedAt", System.currentTimeMillis())); save(directory, previous);
            }
        }
    }

    /** Every observed page stays searchable; no first-20/60-row cutoff. Offline returns the last observed account's local copies. */
    static synchronized JSONArray read(File files, String provider, String project, JSONObject state,
                                       boolean archived, String search) throws IOException {
        return combine(matches(state, provider, project) ? state : new JSONObject(), load(files, provider, project, state), archived, search);
    }

    /** Raw private metadata including tombstones, for asynchronous UI caches. Render using combine(). */
    static synchronized JSONArray load(File files, String provider, String project, JSONObject state) throws IOException {
        File directory = directory(files, provider, project, state, false);
        JSONArray saved = new JSONArray();
        File[] entries = directory == null ? null : directory.listFiles((parent, name) -> name.matches("[a-f0-9]{64}\\.json"));
        if (entries != null) for (File entry : entries) {
            JSONObject row = readObject(direct(directory, entry.getName()));
            if (row != null && validId(row.optString("id"))) saved.put(row);
        }
        return saved;
    }

    /** Pure in-memory merge so a newly active conversation appears before any list RPC completes. */
    static JSONArray combine(JSONObject state, JSONArray saved, boolean archived, String search) {
        Map<String, JSONObject> rows = new LinkedHashMap<>();
        for (int i = 0; i < saved.length(); i++) add(rows, saved.optJSONObject(i));
        JSONObject sessions = child(state, "sessions");
        if (!sessions.optBoolean("loading")) {
            JSONArray official = list(sessions, "rows");
            for (int i = 0; i < official.length(); i++) add(rows, official.optJSONObject(i));
        }
        JSONObject current = active(state);
        if (current != null && !rows.containsKey(current.optString("id"))) add(rows, current);
        JSONObject change = child(sessions, "lastChange"); JSONObject changed = rows.get(change.optString("id"));
        if (changed != null && change.optLong("revision") >= changed.optLong("changeRevision")) {
            String op = change.optString("operation");
            if ("delete".equals(op)) rows.remove(change.optString("id"));
            else if ("archive".equals(op) || "unarchive".equals(op)) put(changed, "archived", "archive".equals(op));
            else if ("rename".equals(op)) put(changed, "name", clean(change.optString("name"), 160));
        }
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        ArrayList<JSONObject> sorted = new ArrayList<>();
        for (JSONObject row : rows.values()) if (!row.optBoolean("deleted") && row.optBoolean("archived") == archived
                && (query.isEmpty() || row.optString("name").toLowerCase(Locale.ROOT).contains(query))) sorted.add(row);
        sorted.sort((left, right) -> Long.compare(right.optLong("updatedAt"), left.optLong("updatedAt")));
        JSONArray result = new JSONArray(); for (JSONObject row : sorted) result.put(row); return result;
    }

    private static JSONObject active(JSONObject state) {
        String id = state.optString("threadId"); if (!validId(id)) return null;
        JSONArray messages = list(state, "messages"); String title = ""; long updated = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i); if (message == null) continue;
            if ("user".equals(message.optString("role"))) {
                String text = message.optString("text").trim();
                if (title.isEmpty() && !text.isEmpty()) title = clean(text.replace('\n', ' '), 160);
            }
            long stamp = message.optLong("time", message.optLong("timestamp", message.optLong("createdAt")));
            if (stamp > 100000000000L) stamp /= 1000L;
            updated = Math.max(updated, stamp);
        }
        if (title.isEmpty()) title = "New chat";
        return object("id", id, "name", title, "preview", "", "updatedAt", updated, "archived", false, "local", true);
    }
    private static void add(Map<String, JSONObject> rows, JSONObject row) {
        if (row != null && validId(row.optString("id"))) {
            JSONObject previous = rows.get(row.optString("id")); rows.put(row.optString("id"), merge(previous, copy(row)));
        }
    }
    private static void save(File directory, JSONObject row) throws IOException {
        if (row == null || !validId(row.optString("id"))) return;
        File target = direct(directory, digest(row.optString("id")) + ".json");
        row = merge(readObject(target), copy(row));
        JSONObject bounded = object("id", row.optString("id"), "name", clean(row.optString("name", "New chat"), 160),
                "preview", clean(row.optString("preview"), 240), "updatedAt", row.optLong("updatedAt"),
                "createdAt", row.optLong("createdAt"), "archived", row.optBoolean("archived"), "local", true,
                "deleted", row.optBoolean("deleted"), "changeRevision", row.optString("changeRevision"),
                "changedAt", row.optLong("changedAt"), "observedAt", row.optLong("observedAt"));
        write(target, bounded.toString());
    }
    private static JSONObject merge(JSONObject previous, JSONObject row) {
        if (previous == null) return row;
        if (previous.optBoolean("deleted")) return copy(previous);
        if (row.optLong("changeRevision") > previous.optLong("changeRevision")) return row;
        if (row.optLong("observedAt") < previous.optLong("changedAt")) {
            put(row, "name", previous.optString("name")); put(row, "archived", previous.optBoolean("archived"));
        }
        put(row, "changeRevision", previous.optString("changeRevision"));
        put(row, "changedAt", previous.optLong("changedAt"));
        return row;
    }
    private static File directory(File files, String provider, String project, JSONObject state, boolean create) throws IOException {
        if (!provider.matches("[a-z][a-z0-9_-]{0,30}")) throw new IOException("Choose an agent.");
        AgentProtocol.project(project);
        File base = direct(direct(direct(files.getCanonicalFile(), "desk-session-index"), provider), project);
        if (create && !base.isDirectory() && !base.mkdirs()) throw new IOException("Could not save chat titles.");
        String scope = "";
        if (matches(state, provider, project) && state.optBoolean("accountConnected")) {
            JSONObject account = child(state, "account");
            String identity = account.optString("email").trim().toLowerCase(Locale.ROOT);
            if (identity.isEmpty()) identity = child(state, "rateLimits").optString("accountId");
            if (!identity.isEmpty()) scope = digest(provider + ":" + identity);
            else scope = digest(provider + ":unidentified-local-account");
        }
        File marker = direct(base, "observed-account.json");
        if (!scope.isEmpty() && create) write(marker, object("scope", scope).toString());
        if (scope.isEmpty()) { JSONObject previous = readObject(marker); if (previous != null) scope = previous.optString("scope"); }
        if (!scope.matches("[a-f0-9]{64}")) return null;
        File directory = direct(base, scope);
        if (create && !directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not save chat titles.");
        return directory;
    }
    private static boolean matches(JSONObject state, String provider, String project) { return provider.equals(state.optString("provider")) && project.equals(state.optString("project")); }
    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,159}"); }
    private static JSONObject copy(JSONObject row) { try { return new JSONObject(row.toString()); } catch (Exception ignored) { return new JSONObject(); } }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception ignored) { } }
    private static JSONObject readObject(File file) throws IOException {
        if (!file.isFile() || file.length() > 4096) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()]; int read = 0, count;
            while (read < bytes.length && (count = in.read(bytes, read, bytes.length - read)) > 0) read += count;
            if (read != bytes.length) return null;
            try { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); } catch (Exception malformed) { return null; }
        }
    }
    private static void write(File target, String value) throws IOException {
        JSONObject existing = readObject(target); if (existing != null && existing.toString().equals(value)) return;
        File temporary = direct(target.getParentFile(), target.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            if (!temporary.renameTo(target)) throw new IOException("Could not save chat titles.");
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private static File direct(File parent, String name) throws IOException {
        File expected = new File(parent, name).getAbsoluteFile(), actual = expected.getCanonicalFile();
        if (!expected.equals(actual) || !actual.getParentFile().equals(parent.getCanonicalFile())) throw new IOException("Chat metadata must stay inside app storage.");
        return actual;
    }
    private static String digest(String value) throws IOException {
        try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte item : bytes) result.append(String.format(Locale.ROOT, "%02x", item & 255)); return result.toString(); }
        catch (Exception unavailable) { throw new IOException("Could not save chat titles.", unavailable); }
    }
}
