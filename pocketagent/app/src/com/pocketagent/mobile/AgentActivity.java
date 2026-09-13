package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.Locale;

/** Bounded presentation of public agent events. Private reasoning content is never retained. */
final class AgentActivity {
    static final int SUMMARY_LIMIT = 8000, DETAILS_LIMIT = 12000;
    private AgentActivity() { }

    static JSONObject codex(JSONObject item, boolean complete) {
        String type = text(item, "type"), kind, title, summary = "";
        if ("reasoning".equals(type)) {
            JSONArray parts = publicSummary(item.optJSONArray("summary"));
            return activity("reasoning", "Thinking", complete ? "completed" : "running", join(parts), "", parts);
        } else if ("webSearch".equals(type)) {
            kind = "search"; JSONObject action = child(item, "action"); String actionType = text(action, "type");
            title = "openPage".equals(actionType) ? "Reading a web page" : "findInPage".equals(actionType) ? "Finding text on a page" : "Searching the web";
            summary = first(text(item, "query"), text(action, "query"), text(action, "url"));
        } else if ("commandExecution".equals(type)) {
            kind = "command"; title = "Running command"; summary = text(item, "command");
        } else if ("fileChange".equals(type)) {
            kind = "files"; title = "Updating files"; JSONArray changes = item.optJSONArray("changes");
            if (changes != null) { StringBuilder paths = new StringBuilder(); for (int i = 0; i < changes.length() && i < 20; i++) {
                JSONObject change = changes.optJSONObject(i); if (change == null) continue;
                if (paths.length() > 0) paths.append('\n'); paths.append(clip(text(change, "path"), 250));
            } summary = paths.toString(); }
        } else if ("mcpToolCall".equals(type) || "dynamicToolCall".equals(type) || "collabAgentToolCall".equals(type)) {
            kind = "mcpToolCall".equals(type) ? "mcp" : "collabAgentToolCall".equals(type) ? "subagent" : "tool";
            String tool = first(text(item, "tool"), text(item, "server"));
            title = tool.isEmpty() ? ("subagent".equals(kind) ? "Working with an agent" : "Using a tool") : "Using " + clip(tool, 72);
            summary = first(text(item, "message"), text(item, "server"));
        } else if ("imageView".equals(type)) { kind = "image"; title = "Viewing image"; summary = text(item, "path"); }
        else if ("subAgentActivity".equals(type)) { kind = "subagent"; title = "Agent activity"; summary = text(item, "agentPath"); }
        else if (type.endsWith("ToolCall")) { kind = "tool"; title = "Using a tool"; }
        else return null;
        return activity(kind, title, status(text(item, "status"), complete), clip(summary, SUMMARY_LIMIT), details(item), null);
    }

    /** Summary indices are from the official summary notifications; textDelta/content are not accepted. */
    static JSONObject summaryDelta(JSONObject previous, JSONObject params) {
        Object indexValue = params.opt("summaryIndex");
        if (!(indexValue instanceof Number)) return previous;
        long index = ((Number) indexValue).longValue();
        if (index < 0 || index >= 32 || ((Number) indexValue).doubleValue() != index) return previous;
        JSONArray parts = publicSummary(previous == null ? null : previous.optJSONArray("summaryParts"));
        while (parts.length() <= index) parts.put("");
        int available = SUMMARY_LIMIT - join(parts).length();
        String delta = clip(text(params, "delta"), Math.max(0, available));
        try { parts.put((int) index, parts.optString((int) index, "") + delta); } catch (Exception ignored) { }
        return activity("reasoning", "Thinking", "running", join(parts), "", parts);
    }

    static JSONObject progress(JSONObject previous, String kind, String title, String delta) {
        JSONObject result = previous == null ? activity(kind, title, "running", "", "", null) : copy(previous);
        put(result, "details", clip(result.optString("details") + delta, DETAILS_LIMIT));
        put(result, "status", "running"); return result;
    }

    static JSONObject acp(JSONObject update, JSONObject previous) {
        String kind = first(text(update, "kind"), text(previous, "kind")), title = first(text(update, "title"), text(previous, "title"));
        if (title.isEmpty()) title = "search".equals(kind) ? "Searching" : "execute".equals(kind) ? "Running command" : "edit".equals(kind) ? "Updating files" : "Agent action";
        String status = first(text(update, "status"), text(previous, "status"));
        String detail = details(update);
        if (previous != null && !text(previous, "details").isEmpty()) detail = clip(text(previous,"details") + "\n" + detail, DETAILS_LIMIT);
        return activity(kind.isEmpty() ? "tool" : kind, clip(title, 100), status(status, false), "", detail, null);
    }

    static long secondsToMillis(JSONObject source, String key) {
        Object raw = source == null ? null : source.opt(key);
        if (!(raw instanceof Number)) return 0;
        long value = ((Number) raw).longValue();
        return value > 0 && value < Long.MAX_VALUE / 1000 && ((Number) raw).doubleValue() == value ? value * 1000 : 0;
    }

    /** Disk state cannot keep a process-local stream alive after restart. Never invent its end time. */
    static void restoreCachedMessage(JSONObject message) {
        if (message == null || !message.optBoolean("streaming")) return;
        put(message, "streaming", false);
        JSONObject activity = message.optJSONObject("activity");
        if (activity != null && ("running".equals(activity.optString("status")) || "pending".equals(activity.optString("status"))))
            put(activity, "status", "cancelled");
    }

    private static JSONObject activity(String kind, String title, String status, String summary, String details, JSONArray parts) {
        JSONObject result = new JSONObject(); put(result,"kind",kind); put(result,"title",title); put(result,"status",status);
        put(result,"summary",summary); put(result,"details",details); if (parts != null) put(result,"summaryParts",parts); return result;
    }
    private static JSONArray publicSummary(JSONArray source) {
        JSONArray result = new JSONArray(); int remaining = SUMMARY_LIMIT;
        if (source != null) for (int i = 0; i < source.length() && i < 32 && remaining > 0; i++) {
            Object raw = source.opt(i); String part = raw instanceof String ? clip((String) raw, remaining) : "";
            result.put(part); remaining -= part.length() + 2;
        }
        return result;
    }
    private static String join(JSONArray parts) {
        StringBuilder out = new StringBuilder(); for (int i = 0; i < parts.length(); i++) {
            String part = parts.optString(i, ""); if (part.isEmpty()) continue;
            if (out.length() > 0) out.append("\n\n"); out.append(part);
        } return clip(out.toString(), SUMMARY_LIMIT);
    }
    private static String status(String raw, boolean complete) {
        if ("failed".equals(raw) || "error".equals(raw) || "declined".equals(raw)) return "failed";
        if ("cancelled".equals(raw) || "canceled".equals(raw) || "interrupted".equals(raw)) return "cancelled";
        if (complete || "completed".equals(raw) || "success".equals(raw)) return "completed";
        if ("pending".equals(raw)) return "pending";
        return "running";
    }
    /** Preserve public tool fields as bounded JSON details, omitting encoded media and secret fields. */
    private static String details(JSONObject item) {
        StringBuilder out = new StringBuilder(); append(out, item, 0); return clip(out.toString(), DETAILS_LIMIT);
    }
    private static void append(StringBuilder out, Object value, int depth) {
        if (out.length() >= DETAILS_LIMIT) return;
        if (depth > 7) { out.append("\"…\""); return; }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String type = text(object,"type");
            if ("reasoning".equals(type) || "thinking".equals(type) || "redacted_thinking".equals(type)) { out.append("\"[Private reasoning omitted]\""); return; }
            out.append('{'); boolean comma = false; int count = 0;
            for (Iterator<String> keys = object.keys(); keys.hasNext() && out.length() < DETAILS_LIMIT && count++ < 80;) {
                String key = keys.next(); String lower = key.toLowerCase(Locale.ROOT);
                if (lower.contains("encrypted") || lower.equals("data") || lower.equals("signature") || lower.equals("thinking") || lower.equals("rawreasoning")
                        || lower.contains("token") || lower.contains("password") || lower.contains("authorization") || lower.contains("apikey") || lower.contains("api_key")) continue;
                if (comma) out.append(','); out.append('\n').append(JSONObject.quote(clip(key, 80))).append(": ");
                append(out, object.opt(key), depth + 1); comma = true;
            } out.append("\n}");
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; out.append('[');
            for (int i = 0; i < array.length() && i < 80 && out.length() < DETAILS_LIMIT; i++) { if (i > 0) out.append(','); append(out,array.opt(i),depth+1); }
            out.append(']');
        } else if (value instanceof String) {
            String string = (String) value;
            if (string.startsWith("data:image/") || string.startsWith("data:audio/") || string.startsWith("data:video/")) string = "[Media shown in attachment]";
            out.append(JSONObject.quote(clip(string, Math.max(0, DETAILS_LIMIT - out.length()))));
        } else out.append(String.valueOf(value));
    }
    private static String clip(String value, int limit) { if(value==null || limit<=0)return ""; String result=value.length()>limit?value.substring(0,limit):value; return result.replace("\u0000", ""); }
    private static String text(JSONObject object,String key) { Object value=object==null?null:object.opt(key);return value instanceof String?(String)value:""; }
    private static String first(String... values) { for(String value:values)if(!value.isEmpty())return value;return ""; }
    private static JSONObject child(JSONObject object,String key) { JSONObject result=object.optJSONObject(key);return result==null?new JSONObject():result; }
    private static JSONObject copy(JSONObject value) { try{return new JSONObject(value.toString());}catch(Exception ignored){return new JSONObject();} }
    private static void put(JSONObject object,String key,Object value) { try{object.put(key,value);}catch(Exception ignored){} }
}
