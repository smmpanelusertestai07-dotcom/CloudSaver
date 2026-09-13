package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** No credentials or model calls: generated pinned-engine schema and its real model/list output. */
public final class CodexEffortTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path fixtures = Paths.get(args[0]).resolve("tests/agent-protocol/schemas/codex-0.154.0");
        JSONObject schema = read(fixtures.resolve("TurnStartParams.json"));
        JSONArray raw = read(fixtures.resolve("ModelListResponse.json")).getJSONArray("data");
        JSONArray catalog = AgentProtocol.models(raw);
        check(catalog.length() == raw.length(), "actual catalog entries must survive normalization");
        check(schema.getJSONObject("properties").has("effort"), "installed engine must accept effort property");
        check(!schema.getJSONObject("properties").has("reasoningEffort"), "turn wire property is effort, not reasoningEffort");
        check("string".equals(schema.getJSONObject("definitions").getJSONObject("ReasoningEffort").getString("type")),
                "pinned reasoning type is a dynamic string, not a guessed hard-coded enum");
        for (int i = 0; i < raw.length(); i++) {
            JSONObject source = raw.getJSONObject(i), model = catalog.getJSONObject(i);
            check(source.getJSONArray("supportedReasoningEfforts").similar(model.getJSONArray("supportedReasoningEfforts")),
                    "real reasoning options and descriptions must survive catalog normalization");
            equal(source.getString("defaultReasoningEffort"), model.getString("defaultReasoningEffort"), "default effort retained");
            check(source.getBoolean("isDefault") == model.getBoolean("isDefault"), "default model flag retained");
            JSONArray choices = model.getJSONArray("supportedReasoningEfforts");
            for (int j = 0; j < choices.length(); j++) {
                String effort = choices.getJSONObject(j).getString("reasoningEffort");
                JSONObject params = turn(model, effort);
                equal(effort, params.getString("effort"), "explicit effort sent exactly as advertised");
                equal(model.getString("id"), params.getString("model"), "selected model sent with effort");
                validateTurnShape(schema, params);
            }
            JSONObject automatic = turn(model, CodexEffort.AUTO);
            equal(model.getString("defaultReasoningEffort"), automatic.getString("effort"),
                    "Auto explicitly clears sticky previous-turn effort to advertised default");
            validateTurnShape(schema, automatic);
            JSONArray options = CodexEffort.options(model);
            equal("auto", options.getJSONObject(0).getString("id"), "Auto appears first");
            String lastWire = choices.getJSONObject(choices.length() - 1).getString("reasoningEffort");
            JSONObject last = options.getJSONObject(options.length() - 1);
            equal(lastWire, last.getString("id"), "effort label preserves its exact wire identity");
            equal(CodexEffort.label(lastWire), last.getString("label"), "highest effort has its actual name without an invented Max alias");
        }

        JSONObject unknown = AgentProtocol.models(new JSONArray("[{\"id\":\"unknown\"}]")).getJSONObject(0);
        check(CodexEffort.options(unknown).length() == 1, "missing metadata exposes Auto only");
        check(!turn(unknown, "auto").has("effort"), "no fabricated effort when engine gives no metadata");
        equal("high", CodexEffort.effectiveEffort(unknown, "auto", "high"), "known engine effort stays visible when default unknown");
        equal("auto", CodexEffort.reconcile(unknown, "high"), "unadvertised saved override is cleared");
        reject(() -> turn(unknown, "low"), "unknown model cannot inherit another model's reasoning range");
        reject(() -> turn(catalog.getJSONObject(0), "imaginary-super-max"), "unadvertised values cannot enter turn wire params");

        JSONObject limited = AgentProtocol.models(new JSONArray("[{\"id\":\"limited\",\"supportedReasoningEfforts\":["
                + "{\"reasoningEffort\":\"low\"},{\"reasoningEffort\":\"high\"}],\"defaultReasoningEffort\":\"low\"}]")).getJSONObject(0);
        equal("auto", CodexEffort.reconcile(limited, "ultra"), "switch/reconnect drops unsupported prior-model effort");
        equal("high", CodexEffort.reconcile(limited, "high"), "switch/reconnect retains still-supported preference");
        JSONObject invalidDefault = new JSONObject(limited.toString()).put("defaultReasoningEffort", "ultra");
        check(!turn(invalidDefault, "auto").has("effort"), "even engine default must be in its own supported options");
        equal("default", CodexEffort.effectiveModel(new JSONArray("[{\"id\":\"default\",\"isDefault\":true}]"), "", ""), "advertised default is effective before response");
        equal("confirmed", CodexEffort.effectiveModel(catalog, "", "confirmed"), "session response takes precedence over catalog default");
        equal("chosen", CodexEffort.effectiveModel(catalog, "chosen", "confirmed"), "next-turn model choice is explicit");

        JSONArray malformed = AgentProtocol.models(new JSONArray("[{\"id\":\"future\",\"supportedReasoningEfforts\":["
                + "null,{\"reasoningEffort\":17},{\"reasoningEffort\":\"\"},{\"reasoningEffort\":\"auto\"},"
                + "{\"reasoningEffort\":\"future_level\"},{\"reasoningEffort\":\"future_level\"}]}]"));
        check(CodexEffort.options(malformed.getJSONObject(0)).length() == 2, "malformed/duplicate options do not become controls");
        equal("future_level", turn(malformed.getJSONObject(0), "future_level").getString("effort"),
                "future non-empty engine-advertised values work without an APK enum update");
        check(!CodexEffort.options(malformed.getJSONObject(0)).getJSONObject(1).getString("label").contains("Max"),
                "an unfamiliar future level has no fabricated relative strength");
        JSONObject shuffled = AgentProtocol.models(new JSONArray("[{\"id\":\"shuffled\",\"supportedReasoningEfforts\":["
                + "{\"reasoningEffort\":\"high\"},{\"reasoningEffort\":\"low\"},{\"reasoningEffort\":\"medium\"}]}]")).getJSONObject(0);
        JSONArray shuffledOptions = CodexEffort.options(shuffled);
        equal("High", shuffledOptions.getJSONObject(1).getString("label"), "shuffled High remains High, not Max");
        check(!shuffledOptions.getJSONObject(3).getString("label").contains("Max"), "last array element is not assumed strongest");
        System.out.println("PASS CodexEffortTest (" + assertions + " assertions)");
    }

    private static JSONObject turn(JSONObject model, String effort) {
        return CodexEffort.turnParams("thread-fixture", AgentProtocol.object("type", "text", "text", "Plan this change"),
                model.optString("id"), model, effort);
    }
    private static void validateTurnShape(JSONObject schema, JSONObject params) throws Exception {
        JSONObject properties = schema.getJSONObject("properties");
        java.util.Iterator<String> keys = params.keys();
        while (keys.hasNext()) check(properties.has(keys.next()), "wire property must exist in pinned TurnStartParams");
        JSONArray required = schema.getJSONArray("required");
        for (int i = 0; i < required.length(); i++) check(params.has(required.getString(i)), "pinned turn required property present");
        check(params.getJSONArray("input").getJSONObject(0).getString("text").equals("Plan this change"), "effort control preserves prompt");
        if (params.has("effort")) check(params.getString("effort").length() >= schema.getJSONObject("definitions")
                .getJSONObject("ReasoningEffort").getInt("minLength"), "effort conforms to actual pinned value schema");
    }
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action, String why) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, why);
    }
    private static JSONObject read(Path path) throws Exception { return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)); }
    private static void equal(String expected, String actual, String why) { check(expected.equals(actual), why + ": " + actual); }
    private static void check(boolean okay, String why) { assertions++; if (!okay) throw new AssertionError(why); }
}
