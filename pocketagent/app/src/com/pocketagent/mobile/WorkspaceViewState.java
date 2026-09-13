package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Project/thread boundaries and bounded presentation of actual engine snapshots. */
final class WorkspaceViewState {
    static boolean matches(JSONObject state, String project) { return project != null && project.equals(state.optString("project")); }
    static boolean canReadTasks(JSONObject state, String project) {
        return matches(state, project) && "codex".equals(state.optString("provider")) && state.optBoolean("connected")
                && state.optBoolean("accountConnected") && !state.optBoolean("sessionOpening") && !state.optString("threadId").isEmpty();
    }
    static JSONObject tasks(JSONObject state, String project) {
        if (!canReadTasks(state, project)) return new JSONObject();
        JSONObject tasks = child(child(state, "controls"), "background");
        return state.optString("threadId").equals(tasks.optString("threadId")) ? tasks : new JSONObject();
    }
    static JSONArray output(JSONObject state, String project) {
        JSONArray result = new JSONArray(); if (!matches(state, project)) return result;
        JSONArray messages = list(state, "messages");
        for (int index = Math.max(0, messages.length() - 14); index < messages.length(); index++) {
            JSONObject entry = messages.optJSONObject(index); if (entry == null) continue;
            String role = entry.optString("role"), text = entry.optString("text");
            if (!role.matches("user|assistant|tool|plan|system") || text.isEmpty()) continue;
            result.put(object("role", role, "text", clean(text, 6000), "id", clean(entry.optString("id"), 200)));
        }
        return result;
    }
    static boolean previewMatches(JSONObject state, String project) {
        return matches(state, project) && state.optBoolean("running") && state.optInt("port") >= 1024 && state.optInt("port") <= 65535;
    }
    private WorkspaceViewState() {}
}
