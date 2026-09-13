package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.pocketagent.mobile.AgentProtocol.*;

/**
 * A local display/controller for the unmodified Claude Code executable.
 * Authentication, token storage, tool execution, policies and model access stay
 * in that executable. This class neither implements OAuth nor calls model APIs.
 * Wire reference: anthropics/claude-agent-sdk-python _internal/query.py and
 * types.py; CLI flags: code.claude.com/docs/en/cli-reference.
 */
final class ClaudeBridge {
    interface Host {
        void send(JSONObject wire) throws Exception;
        void sendText(String text) throws Exception;
        void event(String event, JSONObject data);
        void restart(String command) throws Exception;
        void onReady();
        void onError(String message);
        void onLoginUrl(String url);
    }

    private enum Phase { STATUS, LOGIN, STREAM, STOPPED }
    private static final String CLI = "/usr/local/bin/pocketagent-claude";
    private static final String LOGIN_CARD = "claude-login-code";
    private static final Pattern URL = Pattern.compile("https://[^\\s<>\\\"']+");
    private final Host host;
    private final String cwd;
    private final Map<String, String> outgoing = new LinkedHashMap<>();
    private final Map<String, String> modelRequests = new LinkedHashMap<>();
    private final Map<String, ToolPermission> permissions = new LinkedHashMap<>();
    private Phase phase = Phase.STATUS;
    private boolean initialized, authenticated, attemptedLogin, busy, stopped;
    private boolean interactiveLogin = true;
    private String session = "", selectedModel = "", streamMessage = "";
    private JSONObject lastAccount = new JSONObject(), lastLimits = new JSONObject(), lastUsage = new JSONObject();
    private JSONArray lastModels = new JSONArray();
    private int counter;

    private static final class ToolPermission {
        String id, name;
        JSONObject request, input, answers = new JSONObject();
        JSONArray questions;
        int question;
    }

    ClaudeBridge(Host host, String cwd) { this(host, cwd, ""); }
    ClaudeBridge(Host host, String cwd, String savedSessionId) {
        this.host = host;
        this.cwd = cwd;
        setResumeSession(savedSessionId);
    }

    void allowInteractiveLogin(boolean allowed) { interactiveLogin = allowed; }

    void setResumeSession(String id) {
        // Session ids are opaque, but the actual CLI currently emits UUIDs.
        // Reject flags, shell syntax and arbitrary paths supplied by stale state.
        session = id != null && id.matches("[A-Za-z0-9_-]{1,160}") ? id : "";
    }

    String command() {
        // auth status prints pretty JSON. Normalize stdout, without reading any
        // credential file or exposing login material to transcript persistence.
        String normalize = "import json,sys; a=json.load(sys.stdin); "
                + "print(json.dumps({'type':'pocketagent_claude_auth','account':a}),flush=True); "
                + "sys.exit(0 if a.get('loggedIn') else 1)";
        return "cd -- " + quote(cwd) + " && " + CLI + " auth status | python3 -c " + quote(normalize);
    }

    void start() throws Exception {
        if (phase == Phase.STREAM) {
            initialized = false;
            request("initialize", object("subtype", "initialize", "hooks", new JSONObject()));
            status("Starting Claude Code", true);
        } else if (phase == Phase.LOGIN) {
            status("Complete Claude Code sign-in in your browser", true);
        } else status("Checking Claude Code account", true);
    }

    private String streamCommand() {
        String command = "cd -- " + quote(cwd) + " && exec " + CLI
                + " -p --input-format stream-json --output-format stream-json"
                + " --verbose --include-partial-messages --permission-mode default"
                // Legacy desktop setup may have registered this server. Do not offer its
                // phone-control tools in the native app; preserve all saved user config.
                + " --disallowedTools 'mcp__pocketagent__*'"
                + " --permission-prompt-tool stdio --permission-prompts host";
        if (!session.isEmpty()) command += " --resume=" + quote(session);
        if (!selectedModel.isEmpty()) command += " --model " + quote(selectedModel);
        return command;
    }

    void onProcessExit(int code) {
        if (stopped) return;
        outgoing.clear();
        modelRequests.clear();
        try {
            if (phase == Phase.STATUS) {
                if (authenticated && code == 0) {
                    phase = Phase.STREAM;
                    restart(streamCommand());
                } else if (!interactiveLogin) {
                    fail("Sign in to reconnect your Claude Code account.");
                } else if (!attemptedLogin) {
                    attemptedLogin = true;
                    phase = Phase.LOGIN;
                    restart("cd -- " + quote(cwd) + " && exec " + CLI + " auth login");
                } else fail("Claude Code could not verify subscription sign-in. Reconnect and complete its official login.");
            } else if (phase == Phase.LOGIN && code == 0) {
                host.event("permissionResolved", object("id", LOGIN_CARD));
                phase = Phase.STATUS;
                authenticated = false;
                restart(command());
            } else if (phase == Phase.LOGIN) {
                fail("Claude Code sign-in ended before it completed (exit " + code + "). Reconnect to try again.");
            } else {
                initialized = false; busy = false;
                status("Claude Code stopped (exit " + code + ")", false);
                if (code != 0) host.onError("Claude Code stopped. Review its last message, then reconnect.");
            }
        } catch (Exception error) { fail(error.getMessage()); }
    }

    void onDiagnostic(String line) {
        if (phase != Phase.LOGIN || line == null) return;
        Matcher match = URL.matcher(clean(line, 20000));
        while (match.find()) {
            String url = match.group();
            if (loginUrl("claude", url)) {
                host.onLoginUrl(url);
                // A local browser callback normally completes the login. The
                // official CLI also accepts a browser-provided code on stdin.
                showLoginCode();
            }
        }
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("paste") && lower.contains("code")) showLoginCode();
    }

    private void showLoginCode() {
        host.event("permission", object("id", LOGIN_CARD, "title", "Finish Claude Code sign-in",
                "detail", "Finish signing in in the browser. If the browser shows a verification code, paste that code here. The official Claude Code process receives it directly.",
                "allowText", true, "options", array(object("id", "submit", "label", "Submit code", "kind", "allow_once"))));
    }

    void receive(JSONObject wire) throws Exception {
        if (stopped) return;
        String type = wire.optString("type");
        if ("pocketagent_claude_auth".equals(type)) {
            JSONObject account = child(wire, "account");
            authenticated = account.optBoolean("loggedIn", false);
            String method = account.optString("authMethod", "");
            // An existing Console/API login must never silently turn this
            // subscription-only connection into separately metered API usage.
            if (authenticated && !"claude.ai".equalsIgnoreCase(method)) {
                authenticated = false;
                fail("Claude Code is signed in with " + (method.isEmpty() ? "an unrecognized authentication method" : method)
                        + ". This connection requires a Claude subscription account; no API billing fallback is enabled.");
                return;
            }
            lastAccount = account;
            host.event("account", account);
            return;
        }
        rememberSession(wire.optString("session_id", ""));
        if ("control_response".equals(type)) {
            JSONObject response = child(wire, "response");
            String responseId = response.optString("request_id");
            String method = outgoing.remove(responseId);
            String requestedModel = modelRequests.remove(responseId);
            if (method == null) return;
            if ("error".equals(response.optString("subtype"))) {
                if ("initialize".equals(method)) fail(response.optString("error", "Claude Code could not initialize."));
                else host.onError(response.optString("error", "Claude Code rejected " + method));
                return;
            }
            JSONObject data = child(response, "response");
            if ("initialize".equals(method)) {
                emitModels(list(data, "models"));
                initialized = true;
                host.onReady();
                host.event("ready", object("sessionId", session));
                status("Claude Code ready", false);
            } else if ("set_model".equals(method)) {
                if (requestedModel != null) selectedModel = requestedModel;
                status("Claude Code model updated", false);
                host.event("model", object("id", selectedModel));
            }
        } else if ("control_request".equals(type)) {
            permission(wire);
        } else if ("control_cancel_request".equals(type)) {
            String id = wire.optString("request_id");
            permissions.remove(id);
            host.event("permissionResolved", object("id", id));
        } else if ("stream_event".equals(type)) {
            JSONObject event = child(wire, "event");
            String kind = event.optString("type");
            if ("message_start".equals(kind)) {
                streamMessage = child(event, "message").optString("id", "claude-" + UUID.randomUUID());
            } else if ("content_block_delta".equals(kind)) {
                JSONObject delta = child(event, "delta");
                if ("text_delta".equals(delta.optString("type"))) {
                    if (streamMessage.isEmpty()) streamMessage = "claude-" + UUID.randomUUID();
                    message(streamMessage, "assistant", delta.optString("text"), true);
                }
            }
        } else if ("assistant".equals(type)) {
            JSONObject msg = child(wire, "message");
            String id = msg.optString("id", wire.optString("uuid", "claude-" + UUID.randomUUID()));
            JSONArray content = list(msg, "content");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block == null) continue;
                if ("text".equals(block.optString("type"))) text.append(block.optString("text"));
                else if ("tool_use".equals(block.optString("type"))) {
                    message(block.optString("id", id + "-tool-" + i), "tool",
                            block.optString("name", "Tool") + "\n" + child(block, "input").toString(), false);
                }
            }
            if (text.length() > 0) message(id, "assistant", text.toString(), false);
        } else if ("user".equals(type)) {
            JSONArray content = list(child(wire, "message"), "content");
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block != null && "tool_result".equals(block.optString("type")))
                    message(block.optString("tool_use_id", "claude-result-" + i), "tool", toolText(block.opt("content")), false);
            }
        } else if ("result".equals(type)) {
            busy = false;
            String result = wire.optString("result", "");
            boolean error = wire.optBoolean("is_error", false);
            if (error) {
                if (result.isEmpty()) result = list(wire, "errors").toString();
                message("claude-error-" + UUID.randomUUID(), "system", result, false);
            }
            lastUsage = object("tokens", child(wire, "usage"), "modelUsage", child(wire, "modelUsage"),
                    "durationMs", wire.optLong("duration_ms"), "numTurns", wire.optInt("num_turns"));
            host.event("usage", lastUsage);
            status(error ? "Claude Code needs your attention" : "Claude Code ready", false);
        } else if ("rate_limit_event".equals(type)) {
            lastLimits = child(wire, "rate_limit_info");
            host.event("rateLimits", lastLimits);
        } else if ("system".equals(type)) {
            if ("init".equals(wire.optString("subtype"))) {
                selectedModel = wire.optString("model", selectedModel);
                host.event("model", object("id", selectedModel));
            } else if ("status".equals(wire.optString("subtype"))) {
                String value = wire.optString("status", "");
                if (!value.isEmpty() && !"null".equals(value)) status("Claude Code: " + value, busy);
            }
        }
    }

    private void permission(JSONObject wire) throws Exception {
        JSONObject request = child(wire, "request");
        String id = wire.optString("request_id");
        if (id.isEmpty()) return;
        if (!"can_use_tool".equals(request.optString("subtype"))) {
            host.send(object("type", "control_response", "response", object("subtype", "error", "request_id", id,
                    "error", "PocketAgent does not support this control request: " + request.optString("subtype"))));
            return;
        }
        ToolPermission tool = new ToolPermission();
        tool.id = id; tool.request = request; tool.name = request.optString("tool_name", "Tool");
        tool.input = child(request, "input");
        tool.questions = "AskUserQuestion".equals(tool.name) ? list(tool.input, "questions") : new JSONArray();
        permissions.put(id, tool);
        showPermission(tool);
    }

    private void showPermission(ToolPermission tool) {
        if (tool.question < tool.questions.length()) {
            JSONObject question = tool.questions.optJSONObject(tool.question);
            if (question == null) question = new JSONObject();
            JSONArray options = new JSONArray();
            JSONArray choices = list(question, "options");
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.optJSONObject(i);
                if (choice != null) options.put(object("id", "answer:" + i, "label", choice.optString("label", "Option " + (i + 1)), "kind", "allow_once"));
            }
            options.put(object("id", "answer:text", "label", "Send answer", "kind", "answer"));
            options.put(object("id", "deny", "label", "Skip question", "kind", "reject_once"));
            host.event("permission", object("id", tool.id, "title", question.optString("header", "Claude Code asks"),
                    "detail", question.optString("question", "Answer Claude Code")
                            + (question.optBoolean("multiSelect") ? "\nFor several choices, type their labels separated by commas." : ""),
                    "options", options, "allowText", true));
        } else {
            host.event("permission", object("id", tool.id, "title", tool.request.optString("title", tool.name + " permission"),
                    "detail", tool.request.optString("description", "") + "\n" + clean(tool.input.toString(), 24000),
                    "allowText", false, "options", array(
                            object("id", "allow", "label", "Allow once", "kind", "allow_once"),
                            object("id", "deny", "label", "Deny", "kind", "reject_once"))));
        }
    }

    void answer(String id, String reply, String text) throws Exception {
        if (LOGIN_CARD.equals(id)) {
            if (phase != Phase.LOGIN) return;
            String code = text == null ? "" : text.trim();
            if (code.isEmpty() || code.length() > 8192 || code.indexOf('\n') >= 0 || code.indexOf('\r') >= 0)
                throw new IllegalArgumentException("Paste the single verification code shown by Claude Code's sign-in page.");
            host.sendText(code);
            host.event("permissionResolved", object("id", id));
            return;
        }
        ToolPermission tool = permissions.get(id);
        if (tool == null) throw new IllegalArgumentException("This Claude Code permission is no longer pending.");
        boolean deny = "deny".equals(reply) || "reject".equals(reply) || "cancel".equals(reply);
        if (!deny && tool.question < tool.questions.length()) {
            JSONObject question = tool.questions.getJSONObject(tool.question);
            String value = text == null ? "" : text.trim();
            if (value.isEmpty() && reply != null && reply.matches("answer:[0-9]+")) {
                int index = Integer.parseInt(reply.substring(7));
                value = list(question, "options").getJSONObject(index).getString("label");
            }
            if (value.isEmpty()) throw new IllegalArgumentException("Choose an answer or type a reply.");
            tool.answers.put(question.optString("question"), value);
            tool.question++;
            if (tool.question < tool.questions.length()) { showPermission(tool); return; }
            tool.input.put("answers", tool.answers);
        } else if (!deny && !"allow".equals(reply)) {
            throw new IllegalArgumentException("Choose Allow once or Deny.");
        }
        JSONObject response = deny ? object("behavior", "deny", "message", "The user declined this action.")
                : object("behavior", "allow", "updatedInput", tool.input);
        host.send(object("type", "control_response", "response", object("subtype", "success", "request_id", id, "response", response)));
        permissions.remove(id);
        host.event("permissionResolved", object("id", id));
    }

    void prompt(String text) throws Exception {
        if (!initialized || phase != Phase.STREAM) throw new IllegalStateException("Finish Claude Code sign-in and connection first.");
        if (busy) throw new IllegalStateException("Wait for the current task or stop it first.");
        if (text == null || text.trim().isEmpty()) return;
        streamMessage = "";
        host.send(object("type", "user", "message", object("role", "user", "content", text),
                "parent_tool_use_id", null, "session_id", session));
        busy = true;
        status("Claude Code is working", true);
    }

    void cancel() throws Exception {
        if (phase == Phase.STREAM && initialized) {
            request("interrupt", object("subtype", "interrupt"));
            status("Stopping Claude Code task", true);
        } else {
            stopped = true;
            phase = Phase.STOPPED;
            status("Claude Code connection cancelled", false);
        }
    }

    void newSession() throws Exception {
        if (busy) throw new IllegalStateException("Stop the task before starting a new chat.");
        session = "";
        initialized = false;
        permissions.clear();
        host.event("session", object("id", ""));
        if (phase == Phase.STREAM) restart(streamCommand());
    }

    void restartSession() throws Exception { newSession(); }
    boolean isAuthenticating() { return phase == Phase.STATUS || phase == Phase.LOGIN; }
    void refresh() {
        // The protocol pushes quota changes. There is no invented polling API.
        host.event("account", lastAccount);
        host.event("models", object("models", lastModels));
        if (lastLimits.length() > 0) host.event("rateLimits", lastLimits);
        if (lastUsage.length() > 0) host.event("usage", lastUsage);
    }

    private void restart(String command) throws Exception {
        host.restart(command);
        start();
    }

    void selectModel(String model) throws Exception {
        if (!initialized || phase != Phase.STREAM) throw new IllegalStateException("Connect Claude Code first.");
        if (busy) throw new IllegalStateException("Wait for the current task before changing model.");
        if (model == null || model.isEmpty() || model.length() > 200) throw new IllegalArgumentException("Choose a model reported by Claude Code.");
        String id = request("set_model", object("subtype", "set_model", "model", model));
        modelRequests.put(id, model);
    }

    private String request(String method, JSONObject data) throws Exception {
        String id = "pocketagent-claude-" + (++counter);
        outgoing.put(id, method);
        host.send(object("type", "control_request", "request_id", id, "request", data));
        return id;
    }

    private void emitModels(JSONArray source) {
        JSONArray models = new JSONArray();
        for (int i = 0; i < source.length() && i < 100; i++) {
            JSONObject model = source.optJSONObject(i);
            if (model == null) continue;
            String id = model.optString("value", model.optString("id", ""));
            if (!id.isEmpty()) models.put(object("id", id, "name", model.optString("displayName", model.optString("name", id))));
        }
        lastModels = models;
        host.event("models", object("models", models));
    }

    private void rememberSession(String id) {
        if (!id.isEmpty() && !id.equals(session) && id.matches("[A-Za-z0-9_-]{1,160}")) {
            session = id;
            host.event("session", object("id", id));
        }
    }
    private void status(String text, boolean active) { host.event("status", object("text", text, "busy", active)); }
    private void message(String id, String role, String text, boolean append) {
        if (!text.isEmpty()) host.event("message", object("id", id, "role", role, "text", clean(text, 50000), "append", append));
    }
    private void fail(String text) {
        stopped = true; initialized = false; busy = false; phase = Phase.STOPPED;
        host.onError(text == null ? "Claude Code connection failed." : text);
    }
    private static String toolText(Object content) {
        if (content instanceof JSONArray) {
            StringBuilder result = new StringBuilder();
            JSONArray blocks = (JSONArray) content;
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                if (block != null && "text".equals(block.optString("type"))) result.append(block.optString("text")).append('\n');
            }
            return result.toString();
        }
        return content == null || content == JSONObject.NULL ? "Tool finished" : String.valueOf(content);
    }
}
