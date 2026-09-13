package com.pocketagent.mobile;

import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Remembers permission to reopen one successful local connection, never credentials. */
final class AgentReconnect {
    private AgentReconnect() { }
    static JSONObject remember(String provider, String project, boolean fullAccess) {
        if (!AgentCatalog.isValid(provider)) throw new IllegalArgumentException("Unsupported agent");
        return object("enabled", true, "provider", provider, "project", AgentProtocol.project(project), "fullAccess", fullAccess);
    }
    static boolean eligible(JSONObject remembered, String provider, String project) {
        try {
            return remembered != null && Boolean.TRUE.equals(remembered.opt("enabled"))
                    && AgentCatalog.isValid(provider) && AgentProtocol.project(project).equals(remembered.opt("project"))
                    && provider.equals(remembered.opt("provider")) && remembered.opt("fullAccess") instanceof Boolean;
        } catch (RuntimeException invalid) { return false; }
    }
    /** Used only for account/read failures, not model quotas or a tool's HTTP errors. */
    static boolean authenticationRejected(JSONObject error) {
        if (error == null) return false;
        if (error.optInt("code") == 401 || error.optInt("code") == 403) return true;
        String message = error.optString("message", "").toLowerCase(java.util.Locale.ROOT);
        return message.contains("refresh_token_reused") || message.contains("refresh_token_expired")
                || message.contains("refresh_token_invalidated") || message.contains("authentication required")
                || message.contains("not authenticated") || message.contains("invalid authentication token");
    }
    /** Reopen a transport only; never resends a prompt, payment, approval or login. */
    static final class Recovery {
        private final java.util.Map<String, Long> clients = new java.util.LinkedHashMap<>();
        private String scope = "";
        private int attempts;
        private long readyAt = -1;
        void client(String currentScope, String id, boolean active, long now) {
            if (id == null || !id.matches("[A-Za-z0-9._:-]{1,100}")) return;
            if (!scope.equals(currentScope)) { clients.clear(); scope = currentScope; }
            if (!active) clients.remove(id);
            else if (clients.size() < 16 || clients.containsKey(id)) clients.put(id, now);
        }
        boolean visible(String currentScope, long now) {
            if (!scope.equals(currentScope)) return false;
            java.util.Iterator<java.util.Map.Entry<String, Long>> entries = clients.entrySet().iterator();
            while (entries.hasNext()) {
                long age = now - entries.next().getValue();
                if (age < 0 || age >= 120000L) entries.remove();
            }
            return !clients.isEmpty();
        }
        void explicitConnect() { attempts = 0; readyAt = -1; }
        void ready(long now) { readyAt = now; }
        long failureDelay(String currentScope, long now) {
            if (!visible(currentScope, now)) return -1;
            if (readyAt >= 0 && now - readyAt >= 120000L) attempts = 0;
            readyAt = -1;
            if (attempts >= 3) return -1;
            return new long[]{1000L, 4000L, 15000L}[attempts++];
        }
    }
    /** Retire only exact old UI routing copy, never errors, user text or agent context. */
    static boolean routingNotice(JSONObject message) {
        if (message == null || !"system".equals(message.optString("role"))) return false;
        String id = message.optString("id"), text = message.optString("text");
        return (id.startsWith("new-conversation-") && "Previous messages are in Account → Chat history. This is a new conversation.".equals(text))
                || (id.startsWith("resume-") && "Opened this conversation from local Codex history. The messages below are from the engine's saved conversation.".equals(text));
    }
}
