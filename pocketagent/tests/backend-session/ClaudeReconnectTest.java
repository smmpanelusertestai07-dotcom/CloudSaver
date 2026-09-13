package com.pocketagent.mobile;
import java.util.ArrayList;
import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;
public final class ClaudeReconnectTest {
    static int checks;
    static final class Host implements ClaudeBridge.Host {
        ArrayList<String> starts = new ArrayList<>(); String error = "";
        public void send(JSONObject value) { }
        public void sendText(String value) { }
        public void event(String event, JSONObject value) { }
        public void restart(String command) { starts.add(command); }
        public void onReady() { }
        public void onError(String text) { error = text; }
        public void onLoginUrl(String url) { throw new AssertionError("no browser login should start"); }
    }
    public static void main(String[] args) throws Exception {
        Host loggedOut = new Host(); ClaudeBridge automatic = new ClaudeBridge(loggedOut, "/home/coder/Projects/demo");
        automatic.allowInteractiveLogin(false);
        automatic.receive(object("type", "pocketagent_claude_auth", "account", object("loggedIn", false)));
        automatic.onProcessExit(1);
        check(loggedOut.starts.isEmpty(), "automatic restore cannot initiate auth login");
        check(loggedOut.error.contains("Sign in"), "expired credentials require explicit sign-in");
        Host loggedIn = new Host(); ClaudeBridge reused = new ClaudeBridge(loggedIn, "/home/coder/Projects/demo", "actual_session");
        reused.allowInteractiveLogin(false);
        reused.receive(object("type", "pocketagent_claude_auth", "account", object("loggedIn", true, "authMethod", "claude.ai")));
        reused.onProcessExit(0);
        check(loggedIn.starts.size() == 1 && loggedIn.starts.get(0).contains("--resume='actual_session'"), "verified subscription resumes saved thread");
        check(!loggedIn.starts.get(0).contains("auth login"), "reusing credentials does not request login");
        Host api = new Host(); ClaudeBridge rejected = new ClaudeBridge(api, "/home/coder/Projects/demo");
        rejected.allowInteractiveLogin(false);
        rejected.receive(object("type", "pocketagent_claude_auth", "account", object("loggedIn", true, "authMethod", "api_key")));
        rejected.onProcessExit(0);
        check(api.starts.isEmpty() && api.error.contains("no API billing fallback"), "API credentials cannot replace subscription auth");
        Host interactive = new Host(); ClaudeBridge explicit = new ClaudeBridge(interactive, "/home/coder/Projects/demo");
        explicit.receive(object("type", "pocketagent_claude_auth", "account", object("loggedIn", false)));
        explicit.onProcessExit(1);
        check(interactive.starts.size() == 1 && interactive.starts.get(0).endsWith("auth login"), "explicit Connect retains official login");
        System.out.println("PASS ClaudeReconnectTest (" + checks + " assertions)");
    }
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
