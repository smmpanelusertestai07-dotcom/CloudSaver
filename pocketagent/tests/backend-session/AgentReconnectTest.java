package com.pocketagent.mobile;
import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;
public final class AgentReconnectTest {
    static int checks;
    public static void main(String[] args) {
        JSONObject saved = AgentReconnect.remember("codex", "my-project", false);
        check(AgentReconnect.eligible(saved, "codex", "my-project"), "successful same workspace may reconnect");
        check(!AgentReconnect.eligible(saved, "claude", "my-project"), "another provider cannot inherit authorization");
        check(!AgentReconnect.eligible(saved, "codex", "other"), "another project cannot inherit authorization");
        check(!saved.optBoolean("fullAccess"), "restricted permission persists exactly");
        check(AgentReconnect.remember("codex", "my-project", true).optBoolean("fullAccess"), "prior explicit full access persists exactly");
        for (String project : new String[]{"../outside", "", "--flag", "/absolute", "a/b", "a;cmd"})
            check(!AgentReconnect.eligible(saved, "codex", project), "unsafe target rejected");
        for (JSONObject record : new JSONObject[]{new JSONObject(), object("enabled", "true", "provider", "codex", "project", "my-project", "fullAccess", false), object("enabled", true, "provider", "codex", "project", "my-project", "fullAccess", "true")})
            check(!AgentReconnect.eligible(record, "codex", "my-project"), "absent or malformed authorization rejected");
        String copy = "Previous messages are in Account → Chat history. This is a new conversation.";
        check(AgentReconnect.routingNotice(object("role", "system", "id", "new-conversation-1", "text", copy)), "old routing copy retires");
        check(!AgentReconnect.routingNotice(object("role", "user", "id", "new-conversation-1", "text", copy)), "user text is never hidden");
        check(!AgentReconnect.routingNotice(object("role", "system", "id", "new-conversation-1", "text", "Disk full")), "real errors remain visible");
        check(!AgentReconnect.routingNotice(object("role", "system", "id", "user-issued", "text", copy)), "arbitrary system content is preserved");
        check(saved.length() == 4 && !saved.has("token"), "connection record contains no account secrets");
        recovery();
        for (JSONObject error : new JSONObject[]{object("code", 401), object("code", 403), object("message", "refresh_token_expired"), object("message", "Authentication required")})
            check(AgentReconnect.authenticationRejected(error), "account credential rejection stops background retry");
        for (JSONObject error : new JSONObject[]{object("code", 429, "message", "Usage limit reached"), object("code", 503, "message", "Account server unavailable"), object("message", "Connection reset"), new JSONObject()})
            check(!AgentReconnect.authenticationRejected(error), "network or quota failure does not discard saved sign-in permission");
        System.out.println("PASS AgentReconnectTest (" + checks + " assertions)");
    }
    private static void recovery() {
        AgentReconnect.Recovery retry = new AgentReconnect.Recovery();
        check(retry.failureDelay("codex:p", 0) == -1, "no reconnect without visible client");
        retry.client("codex:p", "activity-1", true, 100);
        check(!retry.visible("codex:other", 100), "presence is bound to selected workspace");
        check(retry.failureDelay("codex:p", 100) == 1000, "first failure waits one second");
        retry.ready(1200);
        check(retry.failureDelay("codex:p", 1300) == 4000, "short connection does not reset crash-loop budget");
        check(retry.failureDelay("codex:p", 6000) == 15000, "third failure waits fifteen seconds");
        check(retry.failureDelay("codex:p", 22000) == -1, "three attempts exhaust automatic retry");
        retry.client("codex:p", "activity-1", false, 23000);
        retry.explicitConnect();
        check(retry.failureDelay("codex:p", 23000) == -1, "background pause disables even freshly reset retries");
        retry.client("codex:p", "activity-2", true, 24000);
        check(retry.failureDelay("codex:p", 24000) == 1000, "explicit connect resets budget");
        retry.ready(25000);
        retry.client("codex:p", "activity-2", true, 150000);
        check(retry.failureDelay("codex:p", 150000) == 1000, "two stable minutes restore retry budget");
        check(!retry.visible("codex:p", 270000), "abandoned foreground lease expires");
        retry.client("codex:p", "activity-2", true, 280000);
        retry.client("codex:next", "activity-3", true, 280100);
        check(!retry.visible("codex:p", 280100), "project switch retires prior foreground authorization");
        check(retry.visible("codex:next", 280100), "new selected project has its own lease");
        retry.client("codex:next", "activity-3", false, 280101);
        check(!retry.visible("codex:next", 280101), "closing last client pauses retries immediately");
    }
    static void check(boolean result, String reason) { checks++; if (!result) throw new AssertionError(reason); }
}
