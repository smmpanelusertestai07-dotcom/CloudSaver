package com.pocketagent.mobile;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

public final class LocalSessionIndexTest {
    private static int checks;
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static JSONObject state(String email) { return object("provider", "codex", "project", "demo", "accountConnected", true,
            "account", object("type", "chatgpt", "email", email, "accessToken", "DO_NOT_STORE_TOKEN"), "sessions", object("rows", new JSONArray())); }
    private static JSONArray read(File files, JSONObject state, boolean archived, String query) throws Exception { return LocalSessionIndex.read(files, "codex", "demo", state, archived, query); }
    public static void main(String[] args) throws Exception {
        File files = Files.createTempDirectory("pocketagent-local-index-test").toFile();
        JSONObject state = state("first@example.com");
        for (int page = 0; page < 5; page++) {
            JSONArray rows = new JSONArray(); for (int i = 0; i < 30; i++) rows.put(object("id", "chat-" + (page * 30 + i), "name", "Title " + (page * 30 + i), "updatedAt", page * 30 + i, "archived", false));
            state.put("sessions", object("rows", rows)); LocalSessionIndex.update(files, "codex", "demo", state);
        }
        check(read(files, state, false, "").length() == 150, "All observed pages persist beyond the old 20/60 cap");
        check(read(files, state, false, "Title 149").length() == 1, "Last page is searchable");
        check(read(files, state, false, "").getJSONObject(0).optString("id").equals("chat-149"), "Newest first");
        JSONObject offline = object("provider", "codex", "project", "demo", "accountConnected", false);
        check(read(files, offline, false, "").length() == 150, "Offline opens local observed-account history");
        check(LocalSessionIndex.read(files, "codex", "other", offline, false, "").length() == 0, "Project cache cannot leak");
        JSONObject second = state("second@example.com"); LocalSessionIndex.update(files, "codex", "demo", second);
        check(read(files, second, false, "").length() == 0, "Another account never receives the first account's observed rows");
        check(read(files, offline, false, "").length() == 0, "Offline now follows the most recent account scope");
        LocalSessionIndex.update(files, "codex", "demo", state);
        state.put("threadId", "current-chat"); state.put("messages", array(object("role", "user", "text", "Build my app", "time", 1700000000000L), object("role", "assistant", "text", "DO_NOT_STORE_BODY", "time", 1700000001000L)));
        JSONArray combined = LocalSessionIndex.combine(state, new JSONArray(), false, "Build");
        check(combined.length() == 1 && "current-chat".equals(combined.getJSONObject(0).optString("id")), "Active thread appears before list RPC");
        LocalSessionIndex.update(files, "codex", "demo", state);
        check(read(files, state, false, "Build").length() == 1, "Prompt title survives disconnect");
        JSONObject sessions = object("rows", new JSONArray(), "lastChange", object("id", "current-chat", "operation", "rename", "name", "Renamed", "revision", 1));
        state.put("sessions", sessions); LocalSessionIndex.update(files, "codex", "demo", state);
        check(read(files, offline, false, "Renamed").length() == 1, "Confirmed rename persists");
        sessions.put("lastChange", object("id", "current-chat", "operation", "archive", "revision", 2)); LocalSessionIndex.update(files, "codex", "demo", state);
        check(read(files, offline, true, "Renamed").length() == 1 && read(files, offline, false, "Renamed").length() == 0, "Confirmed archive reconciles both filters");
        sessions.put("lastChange", object("id", "current-chat", "operation", "delete", "revision", 3)); LocalSessionIndex.update(files, "codex", "demo", state);
        check(read(files, offline, true, "Renamed").length() == 0, "Deleted thread hidden from local index");
        JSONObject stalePage = state("first@example.com"); stalePage.put("sessions", object("rows", array(object("id", "current-chat", "name", "Build my app", "archived", false))));
        LocalSessionIndex.update(files, "codex", "demo", stalePage);
        check(read(files, offline, false, "Build").length() == 0, "Stale engine page cannot resurrect deleted metadata");
        check(LocalSessionIndex.combine(stalePage, LocalSessionIndex.load(files, "codex", "demo", offline), false, "Build").length() == 0, "Raw cache tombstones guard UI against late snapshots");
        stalePage.put("threadId", "current-chat"); stalePage.put("messages", array(object("role", "user", "text", "Build my app", "time", 1700000002000L)));
        LocalSessionIndex.update(files, "codex", "demo", stalePage);
        check(read(files, offline, false, "Build").length() == 0, "Late active snapshot cannot resurrect a deleted thread");
        boolean rejected = false; try { LocalSessionIndex.read(files, "codex", "../outside", state, false, ""); } catch (Exception expected) { rejected = true; }
        check(rejected, "Path traversal rejected");
        final boolean[] secret = {false}; Files.walk(files.toPath()).filter(Files::isRegularFile).forEach(path -> { try { String text = new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8); if (text.contains("DO_NOT_STORE")) secret[0] = true; } catch (Exception ignored) { } });
        check(!secret[0], "Index stores no credentials or assistant bodies");
        System.out.println("LocalSessionIndexTest: " + checks + " assertions passed");
    }
}
