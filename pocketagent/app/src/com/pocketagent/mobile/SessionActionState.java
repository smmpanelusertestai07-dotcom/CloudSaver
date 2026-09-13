package com.pocketagent.mobile;

import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Scope and acknowledgement rules shared by the in-chat session action sheet. */
final class SessionActionState {
    private SessionActionState() { }

    static boolean sameScope(JSONObject state, String project, String token) {
        return "codex".equals(state.optString("provider")) && project.equals(state.optString("project"))
                && token.equals(state.optString("accountScopeToken"));
    }

    static boolean connected(JSONObject state) {
        return state.optBoolean("accountConnected") || state.optBoolean("connected");
    }

    static boolean idle(JSONObject state) {
        JSONObject controls = child(state, "controls");
        return connected(state) && !state.optBoolean("busy") && !state.optBoolean("connecting")
                && !state.optBoolean("installing") && !state.optBoolean("authenticating")
                && !state.optBoolean("sessionOpening") && !state.optBoolean("recoveringSession")
                && !child(state, "sessions").optBoolean("loading")
                && !child(state, "integrations").optBoolean("loading")
                && !child(controls, "background").optBoolean("loading")
                && controls.optString("operation").isEmpty() && child(state, "permission").length() == 0
                && !child(state, "voice").optBoolean("active");
    }

    static boolean changed(JSONObject sessions, String id, String operation, long beforeRevision) {
        JSONObject change = child(sessions, "lastChange");
        return id.equals(change.optString("id")) && operation.equals(change.optString("operation"))
                && change.optLong("revision") > beforeRevision;
    }

    static boolean exported(JSONObject sessions, String id, String beforeShare) {
        String share = sessions.optString("shareId");
        return share.startsWith(id + ":") && !share.equals(beforeShare) && !sessions.optString("shareText").isEmpty();
    }

    static String title(String value) {
        String title = value == null ? "" : value.trim();
        if (title.isEmpty()) return "Untitled chat";
        return clean(title, 160);
    }
}
