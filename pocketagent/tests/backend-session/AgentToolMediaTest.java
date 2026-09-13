package com.pocketagent.mobile;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.pocketagent.mobile.AgentProtocol.*;
public final class AgentToolMediaTest {
    static int checks;
    public static void main(String[] args) {
        JSONObject item = object("type", "mcpToolCall", "arguments", object("url", "https://example.com/not-output.png"), "result", object("content", array(
                object("type", "resource_link", "uri", "https://example.com/image.png", "name", "Photo", "mimeType", "image/png"),
                object("type", "resource_link", "uri", "https://example.com/image.png"),
                object("type", "text", "text", "Actual tool response"), object("type", "image", "data", "secret-base64"))));
        JSONArray result = AgentToolMedia.remote(item);
        check(result.length() == 1, "only deduplicated actual resource links become media");
        check("https://example.com/image.png".equals(result.optJSONObject(0).optString("url")), "source URL unchanged");
        check("image/png".equals(result.optJSONObject(0).optString("mime")), "actual MIME forwarded");
        check("Actual tool response".equals(AgentToolMedia.text(item)), "tool output text preserved");
        check(!result.toString().contains("base64"), "raw encoded bytes never enter snapshot metadata");
        for (String url : new String[]{"http://example.com/a", "file:///etc/passwd", "content://private", "data:image/png;base64,AAAA", "https://user:pass@example.com/a", "javascript:alert(1)", "https://bad host/a"}) {
            JSONObject bad = object("type", "dynamicToolCall", "contentItems", array(object("type", "inputImage", "imageUrl", url)));
            check(AgentToolMedia.remote(bad).length() == 0, "untrusted or unsupported URL form rejected");
        }
        JSONObject dynamic = object("type", "dynamicToolCall", "contentItems", array(object("type", "inputImage", "imageUrl", "https://example.com/a.png"), object("type", "inputAudio", "audioUrl", "https://example.com/a.mp3"), object("type", "inputText", "text", "Transcript")));
        check(AgentToolMedia.remote(dynamic).length() == 2, "official dynamic image and audio outputs exposed");
        check("Transcript".equals(AgentToolMedia.text(dynamic)), "dynamic output text handled");
        JSONArray many = new JSONArray();
        for (int i = 0; i < 30; i++) many.put(object("type", "inputImage", "imageUrl", "https://example.com/" + i + ".png"));
        check(AgentToolMedia.remote(object("type", "dynamicToolCall", "contentItems", many)).length() == 12, "metadata output bounded");
        check(AgentToolMedia.remote(object("type", "unrecognized", "contentItems", many)).length() == 0, "unrecognized protocol never inferred");
        System.out.println("PASS AgentToolMediaTest (" + checks + " assertions)");
    }
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
}
