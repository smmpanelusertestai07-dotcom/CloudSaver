package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Reads explicit engine output references. Never infers files from a tool's arguments or claims. */
final class AgentToolMedia {
    private AgentToolMedia() { }
    static JSONArray remote(JSONObject item) {
        JSONArray result = new JSONArray();
        if (item == null) return result;
        String kind = item.optString("type");
        JSONArray content = "mcpToolCall".equals(kind) ? list(child(item, "result"), "content")
                : "dynamicToolCall".equals(kind) ? list(item, "contentItems") : new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < content.length() && i < 100 && result.length() < 12; i++) {
            JSONObject block = content.optJSONObject(i); if (block == null) continue;
            String type = block.optString("type"), url = "", name = "", mime = "";
            if ("mcpToolCall".equals(kind) && "resource_link".equals(type)) {
                url = block.optString("uri"); name = block.optString("name"); mime = block.optString("mimeType");
            } else if ("dynamicToolCall".equals(kind) && "inputImage".equals(type)) url = block.optString("imageUrl");
            else if ("dynamicToolCall".equals(kind) && "inputAudio".equals(type)) url = block.optString("audioUrl");
            if (!https(url) || !seen.add(url)) continue;
            JSONObject media = object("url", url);
            if (!name.isEmpty()) put(media, "name", clean(name, 160));
            if (mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) put(media, "mime", mime);
            result.put(media);
        }
        return result;
    }
    static String text(JSONObject item) {
        String kind = item.optString("type");
        JSONArray content = "mcpToolCall".equals(kind) ? list(child(item, "result"), "content")
                : "dynamicToolCall".equals(kind) ? list(item, "contentItems") : new JSONArray();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < content.length() && i < 100 && result.length() < 12000; i++) {
            JSONObject block = content.optJSONObject(i); if (block == null) continue;
            if (("mcpToolCall".equals(kind) && "text".equals(block.optString("type")))
                    || ("dynamicToolCall".equals(kind) && "inputText".equals(block.optString("type")))) {
                if (result.length() > 0) result.append('\n');
                result.append(clean(block.optString("text"), 12000 - result.length()));
            }
        }
        return clean(result.toString(), 12000);
    }
    private static boolean https(String text) {
        if (text == null || text.length() > 4096) return false;
        try {
            URI uri = new URI(text);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception invalid) { return false; }
    }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception ignored) { } }
}
