package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Locale;

import static com.pocketagent.mobile.AgentProtocol.*;

/** Reasoning controls use the connected model's catalog, never a guessed model enum. */
final class CodexEffort {
    static final String AUTO = "auto";
    private CodexEffort() {}

    static JSONObject metadata(JSONObject source) {
        JSONObject result = new JSONObject();
        JSONArray supported = source.optJSONArray("supportedReasoningEfforts");
        if (supported != null) {
            JSONArray normalized = new JSONArray();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < supported.length() && normalized.length() < 32; i++) {
                JSONObject entry = supported.optJSONObject(i);
                if (entry == null || !(entry.opt("reasoningEffort") instanceof String)) continue;
                String id = entry.optString("reasoningEffort");
                if (id.trim().isEmpty() || id.length() > 64 || AUTO.equals(id) || !seen.add(id)) continue;
                normalized.put(object("reasoningEffort", id, "description", clean(entry.optString("description"), 300)));
            }
            try { result.put("supportedReasoningEfforts", normalized); }
            catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        }
        try {
            if (source.opt("defaultReasoningEffort") instanceof String)
                result.put("defaultReasoningEffort", source.optString("defaultReasoningEffort"));
            if (source.opt("isDefault") instanceof Boolean) result.put("isDefault", source.optBoolean("isDefault"));
        } catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        return result;
    }

    static JSONObject model(JSONArray catalog, String id) {
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject entry = catalog.optJSONObject(i);
            if (entry != null && id != null && id.equals(entry.optString("id"))) return entry;
        }
        return new JSONObject();
    }

    static String effectiveModel(JSONArray catalog, String selected, String confirmed) {
        if (selected != null && !selected.isEmpty()) return selected;
        if (confirmed != null && !confirmed.isEmpty()) return confirmed;
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject entry = catalog.optJSONObject(i);
            if (entry != null && entry.optBoolean("isDefault")) return entry.optString("id", "");
        }
        return "";
    }

    static boolean supported(JSONObject model, String id) {
        if (id == null || id.isEmpty() || AUTO.equals(id)) return false;
        JSONArray choices = list(model, "supportedReasoningEfforts");
        for (int i = 0; i < choices.length(); i++)
            if (id.equals(childAt(choices, i).optString("reasoningEffort"))) return true;
        return false;
    }

    static String defaultEffort(JSONObject model) {
        String value = model.optString("defaultReasoningEffort", "");
        return supported(model, value) ? value : "";
    }

    static String reconcile(JSONObject model, String selected) {
        return supported(model, selected) ? selected : AUTO;
    }

    static JSONArray options(JSONObject model) {
        String fallback = defaultEffort(model);
        JSONArray result = array(object("id", AUTO, "label", fallback.isEmpty() ? "Auto" : "Auto · " + label(fallback)));
        JSONArray choices = list(model, "supportedReasoningEfforts");
        for (int i = 0; i < choices.length(); i++) {
            JSONObject entry = childAt(choices, i);
            String id = entry.optString("reasoningEffort", "");
            if (!supported(model, id)) continue;
            String title = label(id);
            result.put(object("id", id, "label", title, "description", entry.optString("description", "")));
        }
        return result;
    }

    static String effectiveEffort(JSONObject model, String selected, String confirmed) {
        if (supported(model, selected)) return selected;
        String fallback = defaultEffort(model);
        return fallback.isEmpty() ? (confirmed == null ? "" : confirmed) : fallback;
    }

    /** Auto explicitly restores an advertised default: omitted effort otherwise stays sticky in Codex. */
    static JSONObject turnParams(String thread, JSONObject input, String modelId, JSONObject model, String selected) {
        JSONObject params = object("threadId", thread, "input", array(input));
        String effort;
        if (AUTO.equals(selected)) effort = defaultEffort(model);
        else {
            if (!supported(model, selected)) throw new IllegalArgumentException("This reasoning level is no longer offered by the selected model. Choose an available level.");
            effort = selected;
        }
        try {
            if (modelId != null && !modelId.isEmpty()) params.put("model", modelId);
            if (!effort.isEmpty()) params.put("effort", effort);
        } catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        return params;
    }

    static String label(String id) {
        if (id == null || id.isEmpty()) return "";
        if ("xhigh".equals(id)) return "Extra high";
        return id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1).replace('_', ' ').replace('-', ' ');
    }

    private static JSONObject childAt(JSONArray values, int index) {
        JSONObject entry = values.optJSONObject(index);
        return entry == null ? new JSONObject() : entry;
    }
}
