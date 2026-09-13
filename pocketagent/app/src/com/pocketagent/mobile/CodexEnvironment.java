package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/** Fixed Codex shell environment config surface; never serializes stored values for the UI. */
final class CodexEnvironment {
    private CodexEnvironment() {}

    static JSONObject readParams() throws JSONException {
        return new JSONObject().put("includeLayers", false);
    }

    /** Upsert one leaf, preserving all other configured entries. Does not change CLI login. */
    static JSONObject writeEntryParams(String name, String value) throws JSONException {
        validateName(name);
        if (value == null || value.length() > 4096 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Use a value of at most 4096 characters without NUL characters.");
        return new JSONObject().put("keyPath", "shell_environment_policy.set." + name)
                .put("mergeStrategy", "upsert").put("value", value);
    }

    static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Use a variable name with letters, digits and underscores; start with a letter or underscore.");
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.matches("HOME|PATH|TMPDIR|TMP|TEMP|SHELL|USER|LOGNAME|BASH_ENV|ENV|IFS|CDPATH|NODE_OPTIONS|NODE_PATH|PYTHONPATH|PYTHONHOME|RUBYOPT|PERL5OPT|GIT_CONFIG|GIT_CONFIG_COUNT")
                || upper.startsWith("LD_") || upper.startsWith("DYLD_")
                || upper.startsWith("PROOT_") || upper.startsWith("POCKETAGENT_")
                || upper.startsWith("OPENAI_") || upper.startsWith("ANTHROPIC_")
                || upper.startsWith("CODEX_") || upper.startsWith("CLAUDE_")
                || upper.startsWith("CURSOR_") || upper.startsWith("GEMINI_")
                || upper.startsWith("GOOGLE_") || upper.startsWith("GIT_CONFIG_"))
            throw new IllegalArgumentException("This variable controls agent login or runtime behavior and cannot be changed here.");
    }

    /** Reads only the actual returned config; no values or inferred defaults leave this helper. */
    static JSONArray names(JSONObject configReadResult) throws JSONException {
        JSONObject config = configReadResult == null ? null : configReadResult.optJSONObject("config");
        JSONObject policy = config == null ? null : config.optJSONObject("shell_environment_policy");
        JSONObject entries = policy == null ? null : policy.optJSONObject("set");
        ArrayList<String> names = new ArrayList<>();
        if (entries != null) {
            java.util.Iterator<String> keys = entries.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) names.add(key);
            }
        }
        Collections.sort(names);
        JSONArray result = new JSONArray();
        for (String name : names) {
            if (result.length() >= 128) break;
            result.put(name);
        }
        return result;
    }
}
