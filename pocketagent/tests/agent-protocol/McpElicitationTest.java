package com.pocketagent.mobile;

import java.math.BigDecimal;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Parser and wire-response fixtures: no account, network or Android runtime is used. */
public final class McpElicitationTest {
    private static int checks;
    private interface Attempt { void run() throws Exception; }
    private static void check(boolean success, String message) { checks++; if (!success) throw new AssertionError(message); }
    private static void rejects(Attempt action, String message) throws Exception {
        checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static JSONObject form(JSONObject properties, JSONArray required) {
        return object("mode", "form", "message", "Choose project settings", "serverName", "settings", "threadId", "thread-current",
            "requestedSchema", object("type", "object", "properties", properties, "required", required));
    }
    private static JSONObject text() { return object("type", "string"); }
    private static JSONObject url(String value) { return object("mode", "url", "message", "Connect the project service", "serverName", "project", "elicitationId", "oauth-1", "url", value); }

    public static void main(String[] args) throws Exception {
        JSONObject properties = object(
            "name", object("type", "string", "minLength", 2, "maxLength", 20),
            "count", object("type", "integer", "minimum", 1, "maximum", 5),
            "ratio", object("type", "number", "minimum", 0, "maximum", 1),
            "enabled", object("type", "boolean"),
            "region", object("type", "string", "enum", array("in", "us"), "enumNames", array("India", "United States")),
            "targets", object("type", "array", "items", object("type", "string", "enum", array("android", "web", "ios")), "minItems", 1, "maxItems", 2));
        McpElicitation.Request request = McpElicitation.parse(form(properties, array("name", "count", "enabled")));
        check(request.fields.size() == 6 && !request.isUrl(), "Typed fields parsed");
        JSONObject content = object("name", "Demo", "count", 2, "ratio", 0.5, "enabled", false, "region", "in", "targets", array("web", "android"));
        JSONObject accepted = request.response("accept", content);
        check("accept".equals(accepted.getString("action")), "Exact accept action");
        check(child(accepted, "content").get("enabled") instanceof Boolean, "Boolean type preserved");
        check(!child(accepted, "content").getBoolean("enabled"), "Required false is not missing");
        check(child(accepted, "content").get("count") instanceof Number, "Numeric type preserved");
        check(child(accepted, "content").getJSONArray("targets").length() == 2, "Multi enum preserved");
        check(!request.response("decline", content).has("content"), "Decline cannot transmit form values");
        check(!request.response("cancel", object("password", "DO_NOT_SEND")).has("content"), "Cancel cannot transmit arbitrary secret values");
        rejects(() -> request.response("approved", content), "Unknown actions rejected");
        rejects(() -> request.response("accept", new JSONObject()), "Required fields checked at submission");
        JSONObject extra = new JSONObject(content.toString()); extra.put("hidden", "value");
        rejects(() -> request.response("accept", extra), "Unexpected fields not forwarded");
        JSONObject wrongNumber = new JSONObject(content.toString()); wrongNumber.put("count", "2");
        rejects(() -> request.response("accept", wrongNumber), "Numeric strings not silently accepted");
        wrongNumber.put("count", 2.5); rejects(() -> request.response("accept", wrongNumber), "Fractional integer denied");
        wrongNumber.put("count", 6); rejects(() -> request.response("accept", wrongNumber), "Maximum enforced");
        wrongNumber.put("count", 0); rejects(() -> request.response("accept", wrongNumber), "Minimum enforced");
        JSONObject wrongBool = new JSONObject(content.toString()); wrongBool.put("enabled", "false");
        rejects(() -> request.response("accept", wrongBool), "Boolean strings denied");
        JSONObject wrongEnum = new JSONObject(content.toString()); wrongEnum.put("region", "other");
        rejects(() -> request.response("accept", wrongEnum), "Unknown enum values denied");
        wrongEnum.put("region", "in"); wrongEnum.put("targets", array("android", "android"));
        rejects(() -> request.response("accept", wrongEnum), "Duplicate selections denied");
        wrongEnum.put("targets", array("unknown")); rejects(() -> request.response("accept", wrongEnum), "Unlisted selections denied");
        wrongEnum.put("targets", new JSONArray()); rejects(() -> request.response("accept", wrongEnum), "Minimum items enforced");
        check(McpElicitation.parse(request.toJson()).fields.size() == 6, "UI serialization reparses the same form");
        for (McpElicitation.Field field : request.fields) if ("count".equals(field.name)) {
            check(field.valueFromText("3") instanceof BigDecimal, "UI converts integer input to a JSON number");
            rejects(() -> field.valueFromText("3.5"), "UI validates whole number before send");
            rejects(() -> field.valueFromText("NaN"), "Nonfinite text rejected");
            rejects(() -> field.validate(Double.POSITIVE_INFINITY), "Nonfinite numeric value rejected");
        }
        McpElicitation.Request browser = McpElicitation.parse(url("https://accounts.example.com/authorize?state=a%2Fb"));
        check(browser.isUrl() && browser.url.startsWith("https://"), "Browser mode carries visible destination");
        check(browser.response("accept", null).length() == 1, "Browser completion has exact action-only shape");
        check(browser.response("cancel", object("value", true)).length() == 1, "Browser cancel never grants or leaks content");
        rejects(() -> browser.response("accept", object("unexpected", true)), "Browser flow cannot collect generic form values");
        for (String bad : new String[]{"http://example.com", "javascript:alert(1)", "file:///tmp/key", "data:text/plain,x", "https://user:pass@example.com", "https://example.com:0", "https://example.com:70000", "https://example.com/\npath", "//example.com/path"})
            rejects(() -> McpElicitation.parse(url(bad)), "Unsafe browser URL rejected: " + bad);
        for (String mode : new String[]{"openai/form", "openaiForm"}) {
            JSONObject alternative = form(object("title", text()), new JSONArray()); alternative.put("mode", mode);
            check(McpElicitation.parse(alternative).fields.size() == 1, "Pinned form alias accepted");
        }
        rejects(() -> McpElicitation.parse(form(object("nested", object("type", "object", "properties", object("a", text()))), new JSONArray())), "Nested forms explicitly unsupported");
        rejects(() -> McpElicitation.parse(form(object("nested", object("type", "array", "items", text())), new JSONArray())), "Unbounded arrays unsupported");
        rejects(() -> McpElicitation.parse(form(object("text", object("type", "string", "pattern", "[a-z]+")), new JSONArray())), "Unsupported regex constraint not ignored");
        rejects(() -> McpElicitation.parse(form(object("text", object("type", "string", "maxLength", 5000)), new JSONArray())), "Oversized fields declined");
        rejects(() -> McpElicitation.parse(form(object("text", object("type", "string", "maxLength", 1.5)), new JSONArray())), "Fractional size limit denied");
        rejects(() -> McpElicitation.parse(form(object("text", object("type", "string", "minLength", 9, "maxLength", 2)), new JSONArray())), "Contradictory bounds denied");
        rejects(() -> McpElicitation.parse(form(object("text", text()), array("missing"))), "Required unknown field denied");
        rejects(() -> McpElicitation.parse(form(object("text", text()), array("text", "text"))), "Duplicate required field denied");
        for (String secret : new String[]{"password", "apiKey", "access_token", "clientSecret", "privateKey", "token", "pin", "verificationCode"})
            rejects(() -> McpElicitation.parse(form(object(secret, text()), new JSONArray())), "Credential field requires browser: " + secret);
        JSONObject disguised = form(object("value", text()), new JSONArray()); disguised.put("message", "Paste your password to continue");
        rejects(() -> McpElicitation.parse(disguised), "Secret request in overall message is not collected");
        check(McpElicitation.parse(form(object("tokenBudget", object("type", "integer")), new JSONArray())).fields.size() == 1, "Nonsecret numeric token budget remains supported");
        JSONObject many = new JSONObject(); for (int i = 0; i < 21; i++) many.put("field" + i, text());
        rejects(() -> McpElicitation.parse(form(many, new JSONArray())), "Field count bounded");
        JSONObject titled = object("type", "string", "oneOf", array(object("const", "prod", "title", "Production"), object("const", "dev", "title", "Development")), "default", "dev");
        McpElicitation.Request choice = McpElicitation.parse(form(object("environment", titled), array("environment")));
        check(choice.fields.get(0).choiceLabels.contains("Production"), "Titled enum displays labels");
        rejects(() -> choice.response("accept", new JSONObject()), "Default is not silently accepted without form submission");
        titled.put("default", "unknown"); rejects(() -> McpElicitation.parse(form(object("environment", titled), new JSONArray())), "Invalid defaults declined");
        for (String format : new String[]{"email", "uri", "date", "date-time"}) {
            McpElicitation.Request formatted = McpElicitation.parse(form(object("value", object("type", "string", "format", format)), array("value")));
            String valid = "email".equals(format) ? "person@example.com" : "uri".equals(format) ? "https://example.com/project" : "date".equals(format) ? "2026-09-12" : "2026-09-12T10:30:00Z";
            check(formatted.response("accept", object("value", valid)).has("content"), "Valid format " + format);
            rejects(() -> formatted.response("accept", object("value", "not-a-" + format)), "Invalid format " + format);
        }
        System.out.println("McpElicitationTest: " + checks + " assertions passed");
    }
}
