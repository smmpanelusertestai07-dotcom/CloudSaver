package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;

/** Small wire helpers shared by the service and protocol fixture tests. */
final class AgentProtocol {
    private AgentProtocol() {}
    static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2)
                value.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
        } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        return value;
    }
    static JSONArray array(Object... values) {
        JSONArray result = new JSONArray();
        for (Object value : values) result.put(value);
        return result;
    }
    static JSONObject child(JSONObject parent, String key) {
        JSONObject result = parent == null ? null : parent.optJSONObject(key);
        return result == null ? new JSONObject() : result;
    }
    static JSONArray list(JSONObject parent, String key) {
        JSONArray result = parent == null ? null : parent.optJSONArray(key);
        return result == null ? new JSONArray() : result;
    }
    static JSONObject request(boolean acp, Object id, String method, JSONObject params) {
        JSONObject result = object("method", method, "params", params);
        try {
            if (acp) result.put("jsonrpc", "2.0");
            if (id != null) result.put("id", id);
        } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        return result;
    }
    static JSONObject response(boolean acp, Object id, JSONObject value, boolean error) {
        JSONObject result = object("id", id, error ? "error" : "result", value);
        try { if (acp) result.put("jsonrpc", "2.0"); }
        catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        return result;
    }
    static JSONObject codexThreadParams(String cwd, boolean fullAccess) {
        return object("cwd", cwd, "approvalPolicy", "untrusted", "sandbox", fullAccess ? "danger-full-access" : "workspace-write");
    }
    /** Exact failure emitted by the pinned Codex engine for a missing saved rollout. */
    static boolean missingCodexRollout(String method, String requestedThread, JSONObject error) {
        return "thread/resume".equals(method) && requestedThread != null && !requestedThread.isEmpty()
                && error != null && error.opt("code") instanceof Number && ((Number) error.opt("code")).doubleValue() == -32600d
                && ("no rollout found for thread id " + requestedThread).equals(error.optString("message"));
    }
    static JSONObject codexSessionParams(String cwd, boolean fullAccess, String savedThread, String model) {
        JSONObject params = codexThreadParams(cwd, fullAccess);
        try {
            if (savedThread != null && !savedThread.isEmpty()) params.put("threadId", savedThread);
            if (model != null && !model.isEmpty()) params.put("model", model);
        } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
        return params;
    }
    static final class CodexRecovery {
        private boolean attempted;
        boolean claim(String method, String requestedThread, JSONObject error) {
            if (attempted || !missingCodexRollout(method, requestedThread, error)) return false;
            attempted = true; return true;
        }
        void reset() { attempted = false; }
    }
    static boolean internalRolloutDiagnostic(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("no rollout found for thread id ") || lower.contains("failed to find rollout path")
                || lower.contains("state db missing rollout path");
    }
    static boolean chatgptAccount(JSONObject account) {
        if (account == null) return false;
        // account/read uses type; account/updated uses authMode in the pinned protocol.
        String field = account.has("authMode") ? "authMode" : "type";
        return "chatgpt".equalsIgnoreCase(account.optString(field));
    }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static String project(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}") || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("Choose a project name using letters, digits, dots, hyphens or underscores.");
        return value;
    }
    static String clean(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "").replace("\u0000", "");
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "\n[Display truncated]";
    }
    /** Only an HTTPS URL on this engine's identity domains may become a login button. */
    static boolean loginUrl(String provider, String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            String[] domains;
            if ("codex".equals(provider)) domains = new String[]{"openai.com", "chatgpt.com"};
            else if ("cursor".equals(provider)) domains = new String[]{"cursor.com", "cursor.sh"};
            else if ("claude".equals(provider)) domains = new String[]{"claude.ai", "claude.com", "anthropic.com"};
            else if ("antigravity".equals(provider)) domains = new String[]{"google.com", "antigravity.google"};
            else return false;
            for (String domain : domains) if (host.equals(domain) || host.endsWith("." + domain)) return true;
        } catch (Exception ignored) {}
        return false;
    }
    static JSONArray models(JSONArray source) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length() && result.length() < 100; i++) {
            JSONObject entry = source.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("modelId", entry.optString("model", entry.optString("id", "")));
            String name = entry.optString("name", entry.optString("displayName", id));
            if (!id.isEmpty()) {
                JSONObject normalized = CodexEffort.metadata(entry);
                try { normalized.put("id", id).put("name", name); }
                catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
                result.put(normalized);
            }
        }
        return result;
    }

    /** Keep delayed messages from an earlier conversation out of the active approval UI. */
    static boolean matchesScope(boolean codex, String session, String turn, boolean busy,
                                String method, JSONObject params, boolean request) {
        String field = codex ? "threadId" : "sessionId";
        String remote = params.isNull(field) ? "" : params.optString(field, "");
        if (!remote.isEmpty() && (session.isEmpty() || !session.equals(remote))) return false;
        if (request && remote.isEmpty() && (method.startsWith("item/") || "session/request_permission".equals(method))) return false;
        if (!codex && "session/update".equals(method) && remote.isEmpty()) return false;
        if (codex) {
            String remoteTurn = params.isNull("turnId") ? "" : params.optString("turnId", "");
            if (request && method.startsWith("item/") && remoteTurn.isEmpty()) return false;
            if (remoteTurn.isEmpty() && method.startsWith("turn/")) remoteTurn = child(params, "turn").optString("id", "");
            if (!remoteTurn.isEmpty()) {
                if (!turn.isEmpty() && !turn.equals(remoteTurn)) return false;
                if (turn.isEmpty() && !busy) return false;
            }
        }
        if (request && method.startsWith("cursor/") && !busy) return false;
        return true;
    }
}
