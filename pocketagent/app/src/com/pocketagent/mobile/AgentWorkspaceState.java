package com.pocketagent.mobile;

import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Non-secret user preferences only; each official engine still owns its credentials and history. */
final class AgentWorkspaceState {
    private AgentWorkspaceState() { }
    static String scope(String provider, String project) {
        if (!AgentCatalog.isValid(provider)) throw new IllegalArgumentException("Choose an available agent.");
        return provider + ":" + AgentProtocol.project(project);
    }
    static String connectionKey(String provider, String project) { return "connection:" + scope(provider, project); }
    static String settingsKey(String provider, String project) { return "settings:" + scope(provider, project); }
    static JSONObject settings(JSONObject saved) {
        return settings(saved == null ? "" : saved.optString("model"), saved == null ? "ask" : saved.optString("mode", "ask"));
    }
    static JSONObject settings(String model, String mode) {
        // Opaque catalog IDs remain JSON values; they are never interpolated into a shell command.
        String safeModel = model == null || model.length() > 200 || model.matches("(?s).*[\\p{Cntrl}].*") ? "" : model;
        String safeMode = "ask".equals(mode) || "auto".equals(mode) || "plan".equals(mode) ? mode : "ask";
        return object("model", safeModel, "mode", safeMode);
    }
}
