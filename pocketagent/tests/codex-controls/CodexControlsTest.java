package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import static com.pocketagent.mobile.AgentProtocol.*;

public final class CodexControlsTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        JSONArray catalog = array(object("mode", "plan", "name", "Plan"), object("mode", "default", "name", "Default"));
        JSONArray raw = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0], "tests/agent-protocol/schemas/codex-0.154.0/ModelListResponse.json")), StandardCharsets.UTF_8)).getJSONArray("data");
        JSONArray normalized = CodexControls.models(raw);
        for (int i = 0; i < raw.length(); i++) {
            JSONObject source = raw.getJSONObject(i), model = normalized.getJSONObject(i);
            if (source.has("inputModalities")) check(source.getJSONArray("inputModalities").similar(model.getJSONArray("inputModalities")), "actual model modalities preserved");
            check(source.getJSONArray("supportedReasoningEfforts").similar(model.getJSONArray("supportedReasoningEfforts")), "effort catalog preserved");
        }
        check(!CodexControls.imageSupported(object()), "unknown does not invent image support");
        check(!CodexControls.imageSupported(object("inputModalities", array("text"))), "text-only model blocks images");
        check(CodexControls.imageSupported(object("inputModalities", array("text", "image"))), "advertised images accepted");
        check(!CodexControls.models(array(object("id", "unknown"))).getJSONObject(0).has("inputModalities"), "schema default is not fabricated as runtime capability");
        check(!CodexControls.modeOptions(new JSONArray(), "m").getJSONObject(2).getBoolean("available"), "unknown Plan unavailable");
        check(CodexControls.modeOptions(catalog, "m").getJSONObject(2).getBoolean("available"), "real Plan/default available");
        reject(() -> CodexControls.validateMode("plan", catalog, ""), "Plan requires real known model");
        reject(() -> CodexControls.validateMode("plan", array(object("mode", "plan")), "m"), "must be able to leave Plan using default");
        reject(() -> CodexControls.validateMode("magic", catalog, "m"), "no invented mode");

        JSONObject plan = CodexControls.applyTurn(base(), "plan", catalog, "actual-model", false, false);
        JSONObject cm = plan.getJSONObject("collaborationMode"), settings = cm.getJSONObject("settings");
        eq("plan", cm.getString("mode"), "official mode field");
        eq("actual-model", settings.getString("model"), "selected engine model controls mode");
        eq("high", settings.getString("reasoning_effort"), "selected actual effort controls mode");
        check(settings.has("developer_instructions") && settings.isNull("developer_instructions"), "null uses official built-in instructions, no wrapper prompt");
        eq("untrusted", plan.getString("approvalPolicy"), "Plan retains user approvals");
        eq("user", plan.getString("approvalsReviewer"), "no automatic permission reviewer");
        eq("workspaceWrite", plan.getJSONObject("sandboxPolicy").getString("type"), "turn sandbox uses pinned camelcase tag");
        JSONObject normal = CodexControls.applyTurn(base(), "ask", catalog, "actual-model", false, true);
        eq("default", normal.getJSONObject("collaborationMode").getString("mode"), "leaving Plan explicitly resets sticky mode");
        JSONObject automatic = CodexControls.applyTurn(base(), "auto", catalog, "actual-model", false, true);
        eq("on-request", automatic.getString("approvalPolicy"), "Auto still permits explicit approval requests");
        check(!"never".equals(automatic.getString("approvalPolicy")), "auto never silently disables approvals");
        JSONObject unrestricted = CodexControls.applyTurn(base(), "ask", catalog, "actual-model", true, false);
        eq("dangerFullAccess", unrestricted.getJSONObject("sandboxPolicy").getString("type"), "explicit legacy full access retained honestly");
        JSONObject session = CodexControls.sessionParams("/home/coder/Projects/project", true, "saved-id", "actual-model", "auto");
        eq("saved-id", session.getString("threadId"), "resume context retained");
        eq("danger-full-access", session.getString("sandbox"), "thread schema has different enum spelling");
        eq("on-request", session.getString("approvalPolicy"), "session and turn share selected policy");
        reject(() -> CodexControls.applyTurn(base(), "ask", new JSONArray(), "actual-model", false, true), "can't falsely claim sticky Plan reset without advertised default");
        check(!CodexControls.applyTurn(base(), "ask", new JSONArray(), "actual-model", false, false).has("collaborationMode"), "fresh ordinary task works when experimental mode catalog absent");

        check(CodexControls.attachmentPaths(null).length() == 0, "old prompts remain compatible");
        check(CodexControls.attachmentPaths("[\"assets/mock.png\",\"images/mobile.jpg\"]").length() == 2, "project relative attachments accepted");
        for (String bad : new String[]{"{}", "[null]", "[17]", "[\"/etc/passwd\"]", "[\"../secret.png\"]", "[\"x/../a.png\"]", "[\"x//a.png\"]", "[\"x/./a.png\"]", "[\"x.png\",\"x.png\"]", "[\"1\",\"2\",\"3\",\"4\",\"5\"]"})
            reject(() -> CodexControls.attachmentPaths(bad), "invalid attachment path rejected");
        plan.getJSONArray("input").put(object("type", "localImage", "path", "/home/coder/Projects/project/assets/mock.png"));
        JSONArray wire = array(object("schema", "TurnStartParams.json", "params", plan), object("schema", "TurnStartParams.json", "params", normal), object("schema", "TurnStartParams.json", "params", automatic));
        JSONObject imageOnly = CodexControls.applyTurn(CodexEffort.turnParams("thread-fixture",
            object("type", "localImage", "path", "/home/coder/Projects/project/assets/mock.png"),
            "actual-model", object(), CodexEffort.AUTO), "ask", catalog, "actual-model", false, false);
        eq(1, imageOnly.getJSONArray("input").length(), "image-only send has one real attachment block");
        eq("localImage", imageOnly.getJSONArray("input").getJSONObject(0).getString("type"), "no invented text for image-only task");
        wire.put(object("schema", "TurnStartParams.json", "params", imageOnly));
        for (JSONObject target : new JSONObject[]{object(), object("type", "baseBranch", "branch", "main"), object("type", "commit", "sha", "0123456789abcdef"), object("type", "custom", "instructions", "Check error recovery")}) {
            JSONObject params = CodexControls.reviewParams("thread-fixture", target);
            eq("inline", params.getString("delivery"), "review stays in current conversation");
            wire.put(object("schema", "ReviewStartParams.json", "params", params));
        }
        reject(() -> CodexControls.reviewParams("t", object("type", "commit", "sha", "main")), "commit target validates hash");
        reject(() -> CodexControls.reviewParams("t", object("type", "arbitraryRPC")), "unsupported review target rejected");
        reject(() -> CodexControls.reviewParams("t", object("type", "custom", "instructions", "")), "review instructions required");

        JSONObject thread = object("turns", array(object("items", array(
            object("type", "userMessage", "id", "u", "content", array(object("type", "text", "text", "First"), object("type", "localImage", "path", "/private/path.png"))),
            object("type", "commandExecution", "id", "tool", "aggregatedOutput", "Build completed"),
            object("type", "agentMessage", "id", "a", "text", "Second")))));
        JSONArray messages = CodexControls.threadMessages(thread);
        check(messages.length() == 3, "resume imports actual user, tool activity and assistant messages");
        eq("user", messages.getJSONObject(0).getString("role"), "resume chronological user first");
        eq("Second", messages.getJSONObject(2).getString("text"), "resume assistant follows activity");
        check(messages.getJSONObject(1).getJSONObject("activity").getString("details").contains("Build completed"), "original tool result remains inspectable");
        check(!messages.toString().contains("/private/path.png"), "unvalidated attachment paths excluded from imported history");
        check(CodexControls.threadMessages(object()).length() == 0, "missing turns no fabricated history");
        CodexControls.TaskEpoch ordering = new CodexControls.TaskEpoch();
        long first = ordering.begin();
        check(ordering.current(first), "first task owns its acknowledgement");
        ordering.invalidate();
        check(!ordering.current(first), "completion before ACK invalidates that ACK");
        long second = ordering.begin();
        check(!ordering.current(first), "late first ACK cannot replace second task turn ID");
        check(ordering.current(second), "same-operation second task accepts its own ACK");
        ordering.invalidate();
        check(!ordering.current(second), "detach/disconnect invalidates pending task ACK");
        Files.write(Paths.get(args[1]), wire.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("PASS CodexControlsTest (" + assertions + " assertions)");
    }
    private static JSONObject base() { return object("threadId", "thread-fixture", "input", array(object("type", "text", "text", "Build the app")), "effort", "high", "model", "actual-model"); }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static void eq(Object expected, Object actual, String label) { check(expected.equals(actual), label + ": " + actual); }
    private interface Attempt { void run() throws Exception; }
    private static void reject(Attempt attempt, String label) { try { attempt.run(); } catch (IllegalArgumentException | IllegalStateException expected) { assertions++; return; } catch (Exception unexpected) { throw new AssertionError(unexpected); } throw new AssertionError(label); }
}
