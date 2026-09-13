package com.pocketagent.mobile;

import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

public final class SessionActionStateTest {
    private static int checks;
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        JSONObject state = object("provider", "codex", "project", "demo", "accountScopeToken", "account-a", "connected", true);
        check(SessionActionState.sameScope(state, "demo", "account-a"), "Current account and project are accepted");
        check(!SessionActionState.sameScope(state, "different", "account-a"), "Cross-project confirmation is rejected");
        check(!SessionActionState.sameScope(state, "demo", "account-b"), "Cross-account confirmation is rejected");
        check(SessionActionState.idle(state), "Idle connected session is usable");
        for (String key : new String[]{"busy", "connecting", "installing", "authenticating", "sessionOpening", "recoveringSession"}) {
            state.put(key, true); check(!SessionActionState.idle(state), key + " blocks mutation"); state.remove(key);
        }
        state.put("voice", object("active", true)); check(!SessionActionState.idle(state), "Voice activity blocks mutation"); state.remove("voice");
        state.put("permission", object("id", "approval")); check(!SessionActionState.idle(state), "Unanswered permission blocks mutation"); state.remove("permission");
        state.put("sessions", object("loading", true)); check(!SessionActionState.idle(state), "Pending session operation blocks mutation"); state.remove("sessions");
        state.put("controls", object("operation", "env_set")); check(!SessionActionState.idle(state), "Control mutation blocks session action"); state.remove("controls");
        state.put("connected", false); check(!SessionActionState.idle(state), "Disconnected session cannot mutate");
        JSONObject sessions = object("lastChange", object("id", "thread-a", "operation", "archive", "revision", 22));
        check(SessionActionState.changed(sessions, "thread-a", "archive", 21), "Confirmed matching receipt is accepted");
        check(!SessionActionState.changed(sessions, "thread-a", "archive", 22), "Pre-existing receipt cannot report success");
        check(!SessionActionState.changed(sessions, "thread-b", "archive", 21), "Other-thread receipt cannot report success");
        check(!SessionActionState.changed(sessions, "thread-a", "delete", 21), "Other-operation receipt cannot report success");
        sessions = object("loading", false, "status", "Ready");
        check(!SessionActionState.changed(sessions, "thread-a", "archive", 0), "Generic ready state cannot report mutation success");
        sessions = object("shareId", "thread-a:123", "shareText", "# Saved chat");
        check(SessionActionState.exported(sessions, "thread-a", "thread-a:122"), "Fresh matching export is accepted");
        check(!SessionActionState.exported(sessions, "thread-a", "thread-a:123"), "Old share draft cannot complete a new export");
        check(!SessionActionState.exported(sessions, "thread", ""), "Similar thread prefix cannot leak a different export");
        sessions.put("shareText", ""); check(!SessionActionState.exported(sessions, "thread-a", ""), "Empty share payload is not successful");
        System.out.println("SessionActionStateTest: " + checks + " assertions passed");
    }
}
