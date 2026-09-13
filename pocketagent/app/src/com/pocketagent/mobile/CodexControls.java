package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Wire controls verified against Codex 0.154's experimental schema export. */
final class CodexControls {
    static final String ASK = "ask", AUTO = "auto", PLAN = "plan";
    private CodexControls() {}

    /** ACKs may arrive after completion; an old task never owns the next task's state. */
    static final class TaskEpoch {
        private long value;
        long begin() { return ++value; }
        void invalidate() { ++value; }
        boolean current(long token) { return token == value; }
    }

    /** Disable only the reserved legacy phone-tools server, without creating an invalid new entry. */
    static String nativeCommand() {
        String cli = "/usr/local/bin/pocketagent-codex";
        return "if " + cli + " mcp get pocketagent --json >/dev/null 2>&1; then exec " + cli
            + " app-server -c 'mcp_servers.pocketagent.enabled=false'; else exec " + cli + " app-server; fi";
    }

    static String logoutCommand() { return "exec /usr/local/bin/pocketagent-codex logout"; }

    static JSONArray models(JSONArray raw) {
        JSONArray result = AgentProtocol.models(raw);
        for (int i = 0; i < result.length(); i++) {
            JSONObject row = result.optJSONObject(i);
            if (row == null) continue;
            for (int j = 0; j < raw.length(); j++) {
                JSONObject source = raw.optJSONObject(j);
                if (source == null || !row.optString("id").equals(source.optString("model", source.optString("id")))) continue;
                JSONArray modalities = source.optJSONArray("inputModalities");
                if (modalities != null) {
                    JSONArray known = new JSONArray(); HashSet<String> seen = new HashSet<>();
                    for (int k = 0; k < modalities.length() && known.length() < 12; k++) {
                        Object value = modalities.opt(k);
                        if (value instanceof String && ((String) value).matches("[A-Za-z][A-Za-z0-9_-]{0,39}") && seen.add((String) value)) known.put(value);
                    }
                    put(row, "inputModalities", known);
                }
                break;
            }
        }
        return result;
    }

    static boolean imageSupported(JSONObject model) {
        JSONArray values = model.optJSONArray("inputModalities");
        if (values == null) return false;
        for (int i = 0; i < values.length(); i++) if ("image".equals(values.optString(i))) return true;
        return false;
    }

    static boolean modeAdvertised(JSONArray catalog, String mode) {
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject row = catalog.optJSONObject(i);
            if (row != null && mode.equals(row.optString("mode"))) return true;
        }
        return false;
    }

    static JSONArray modeOptions(JSONArray catalog, String model) {
        return array(
            object("id", ASK, "label", "Ask approvals", "available", true,
                "description", "Codex asks before untrusted commands. This does not guarantee a prompt for every file edit."),
            object("id", AUTO, "label", "Auto edits", "available", true,
                "description", "Codex can edit within its configured workspace and request extra approval when needed."),
            object("id", PLAN, "label", "Plan", "available", !model.isEmpty() && modeAdvertised(catalog, PLAN) && modeAdvertised(catalog, "default"),
                "description", "Official Codex Plan mode. Uses the engine's built-in planning instructions; this is not a security sandbox."));
    }

    static void validateMode(String mode, JSONArray catalog, String model) {
        if (ASK.equals(mode) || AUTO.equals(mode)) return;
        if (!PLAN.equals(mode)) throw new IllegalArgumentException("Choose an available Codex mode.");
        if (model.isEmpty() || !modeAdvertised(catalog, PLAN) || !modeAdvertised(catalog, "default"))
            throw new IllegalStateException("This engine has not advertised official Plan and default modes with a known model. Refresh Codex first.");
    }

    static JSONObject applyTurn(JSONObject params, String mode, JSONArray catalog, String model, boolean fullAccess, boolean previouslyPlan) {
        validateMode(mode, catalog, model);
        put(params, "approvalPolicy", AUTO.equals(mode) ? "on-request" : "untrusted");
        put(params, "approvalsReviewer", "user");
        put(params, "sandboxPolicy", object("type", fullAccess ? "dangerFullAccess" : "workspaceWrite"));
        String wireMode = PLAN.equals(mode) ? PLAN : "default";
        if (modeAdvertised(catalog, wireMode) && !model.isEmpty()) {
            JSONObject settings = object("model", model, "developer_instructions", JSONObject.NULL);
            put(settings, "reasoning_effort", params.has("effort") ? params.opt("effort") : JSONObject.NULL);
            put(params, "collaborationMode", object("mode", wireMode, "settings", settings));
        } else if (previouslyPlan) throw new IllegalStateException("Default mode metadata is unavailable. Refresh before leaving Plan mode.");
        return params;
    }

    static JSONObject sessionParams(String cwd, boolean fullAccess, String saved, String model, String mode) {
        JSONObject params = AgentProtocol.codexSessionParams(cwd, fullAccess, saved, model);
        put(params, "approvalPolicy", AUTO.equals(mode) ? "on-request" : "untrusted");
        put(params, "approvalsReviewer", "user");
        return params;
    }

    static JSONArray attachmentPaths(String encoded) {
        if (encoded == null || encoded.isEmpty()) return new JSONArray();
        if (encoded.length() > 8192) throw new IllegalArgumentException("Too many image attachments.");
        final JSONArray paths;
        try { paths = new JSONArray(encoded); } catch (Exception error) { throw new IllegalArgumentException("Choose image files from this project again."); }
        if (paths.length() > 4) throw new IllegalArgumentException("Attach at most four images per prompt.");
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < paths.length(); i++) {
            Object value = paths.opt(i);
            if (!(value instanceof String)) throw new IllegalArgumentException("An image attachment has an invalid project path.");
            String path = (String) value;
            if (path.isEmpty() || path.length() > 1024 || path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0 || !seen.add(path))
                throw new IllegalArgumentException("Choose distinct image files inside this project.");
            for (String piece : path.split("/", -1)) if (piece.isEmpty() || piece.equals(".") || piece.equals(".."))
                throw new IllegalArgumentException("Choose image files inside this project.");
        }
        return paths;
    }

    static JSONObject reviewParams(String threadId, JSONObject source) {
        String type = source.optString("type", "uncommittedChanges");
        JSONObject target = object("type", type);
        if ("baseBranch".equals(type)) put(target, "branch", required(source, "branch", 240));
        else if ("commit".equals(type)) {
            String sha = required(source, "sha", 64);
            if (!sha.matches("[0-9a-fA-F]{7,64}")) throw new IllegalArgumentException("Choose a valid commit hash to review.");
            put(target, "sha", sha);
        } else if ("custom".equals(type)) put(target, "instructions", required(source, "instructions", 8000));
        else if (!"uncommittedChanges".equals(type)) throw new IllegalArgumentException("Choose a supported review target.");
        return object("threadId", threadId, "delivery", "inline", "target", target);
    }

    /** Restore public messages and activity only; hidden reasoning and missing times stay unavailable. */
    static JSONArray threadMessages(JSONObject thread) {
        JSONArray turns = list(thread, "turns"), collected = new JSONArray();
        int chars = 0;
        for (int i = turns.length() - 1; i >= 0 && collected.length() < 60 && chars < 120000; i--) {
            JSONObject turn = turns.optJSONObject(i); if (turn == null) continue;
            JSONArray items = list(turn, "items");
            for (int j = items.length() - 1; j >= 0 && collected.length() < 60 && chars < 120000; j--) {
                JSONObject item = items.optJSONObject(j); if (item == null) continue;
                String type = item.optString("type"), role, text;
                JSONObject activity = null;
                if ("agentMessage".equals(type)) { role = "assistant"; text = item.optString("text"); }
                else if ("userMessage".equals(type)) {
                    role = "user"; StringBuilder builder = new StringBuilder();
                    JSONArray content = list(item, "content");
                    for (int k = 0; k < content.length(); k++) {
                        JSONObject part = content.optJSONObject(k);
                        if (part == null) continue;
                        if ("text".equals(part.optString("type"))) {
                            if (builder.length() > 0) builder.append('\n'); builder.append(clean(part.optString("text"), 16000));
                        } else if ("image".equals(part.optString("type")) || "localImage".equals(part.optString("type"))) builder.append("\n[Image attachment]");
                        if (builder.length() >= 24000) break;
                    }
                    text = builder.toString();
                } else {
                    activity = AgentActivity.codex(item, !"inProgress".equals(turn.optString("status")));
                    if (activity == null) continue;
                    role = "tool";
                    text = "reasoning".equals(type) ? activity.optString("summary") : activity.optString("details");
                    if (text.trim().isEmpty()) text = activity.optString("title");
                }
                text = clean(text, Math.min(24000, 120000 - chars));
                if (text.trim().isEmpty()) continue;
                JSONObject restored = object("id", "history-" + item.optString("id", i + "-" + j), "role", role, "text", text,
                        "streaming", "inProgress".equals(turn.optString("status")), "turnId", turn.optString("id"));
                if (activity != null) put(restored, "activity", activity);
                long started = AgentActivity.secondsToMillis(turn, "startedAt"), completed = AgentActivity.secondsToMillis(turn, "completedAt");
                if (started > 0) put(restored, "time", started);
                if (completed > 0 && !"user".equals(role)) put(restored, "completedAt", completed);
                int size = restored.toString().length();
                if (chars + size > 120000) break;
                chars += size;
                collected.put(restored);
            }
        }
        JSONArray result = new JSONArray();
        for (int i = collected.length() - 1; i >= 0; i--) result.put(collected.opt(i));
        return result;
    }

    private static String required(JSONObject source, String key, int max) {
        Object raw = source.opt(key);
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty() || ((String) raw).length() > max || ((String) raw).indexOf('\0') >= 0)
            throw new IllegalArgumentException("The review " + key + " is missing or too long.");
        return ((String) raw).trim();
    }

    private static void put(JSONObject value, String key, Object item) {
        try { value.put(key, item); } catch (Exception error) { throw new IllegalArgumentException(error); }
    }
}
