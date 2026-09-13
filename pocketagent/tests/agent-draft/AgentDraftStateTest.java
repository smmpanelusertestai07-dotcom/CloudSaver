package com.pocketagent.mobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AgentDraftStateTest {
    private static int assertions;
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static String encode(String provider, String project, String text) {
        return AgentDraftState.encode(provider, project, text, new JSONArray().put(".pocketagent/imports/photo.png"),
                Arrays.asList("src/Main.java"), "README.md", "/home/coder/.agents/skills/build/SKILL.md", "Build", "app_github", "GitHub");
    }
    private static AgentDraftState.State read(String value) { return AgentDraftState.decode("codex", "Project-1", value); }
    public static void main(String[] args) throws Exception {
        String encoded = encode("codex", "Project-1", "Build **this**\nहिन्दी 😀");
        AgentDraftState.State state = read(encoded);
        check(state.prompt.equals("Build **this**\nहिन्दी 😀"), "exact multilingual prompt");
        check(state.fileAttachments.equals(Arrays.asList("src/Main.java")), "file paths");
        check(state.imageAttachments().optString(0).equals(".pocketagent/imports/photo.png"), "image paths");
        check(state.attachedPath.equals("README.md"), "context path");
        check(state.selectedSkillPath.endsWith("/build/SKILL.md") && state.selectedSkillName.equals("Build"), "skill context");
        check(state.selectedAppId.equals("app_github") && state.selectedAppName.equals("GitHub"), "app context");
        check(AgentDraftState.decode("claude", "Project-1", encoded).prompt.isEmpty(), "cross provider rejected");
        check(AgentDraftState.decode("codex", "Project-2", encoded).prompt.isEmpty(), "cross project rejected");
        check(!AgentDraftState.key("codex", "Project-1").equals(AgentDraftState.key("claude", "Project-1")), "provider key isolation");
        check(!AgentDraftState.key("codex", "Project-1").equals(AgentDraftState.key("codex", "Project-2")), "project key isolation");
        check(!AgentDraftState.key("codex", "Project-1").equals(AgentDraftState.key("codex", "project-1")), "case retained");
        for (String bad : new String[]{"", "..", "../Project-1", "A:B", "A/B", "A\\B", "a\n", " a", "a b"})
            check(AgentDraftState.key("codex", bad).isEmpty(), "invalid project " + bad);
        check(AgentDraftState.key("unknown", "Project-1").isEmpty(), "unknown provider");
        check(AgentDraftState.key(null, "Project-1").isEmpty(), "null provider");
        check(AgentDraftState.key("codex", null).isEmpty(), "null project");
        check(encode("unknown", "Project-1", "secret").isEmpty(), "encode invalid scope");
        check(read("not json").prompt.isEmpty(), "broken JSON");
        check(read("{}").prompt.isEmpty(), "missing fields");
        check(read(null).prompt.isEmpty(), "null JSON");
        JSONObject tampered = new JSONObject(encoded); tampered.put("version", 2);
        check(read(tampered.toString()).prompt.isEmpty(), "unknown version");
        tampered.put("version", "1"); check(read(tampered.toString()).prompt.isEmpty(), "version type");
        tampered = new JSONObject(encoded); tampered.put("prompt", 42);
        check(read(tampered.toString()).prompt.isEmpty(), "no prompt coercion");
        tampered = new JSONObject(encoded); tampered.put("images", "photo.png");
        check(read(tampered.toString()).prompt.isEmpty(), "array shape");
        JSONArray malformed = new JSONArray();
        for (Object bad : new Object[]{"../secret", "/sdcard/photo.png", "C:\\secret", "a/../b", "a//b", "a/./b", "a/", "file://x", "x\n.txt", "x\u0000.png", 12, JSONObject.NULL, new JSONObject()}) malformed.put(bad);
        malformed.put("safe/file.java");
        tampered = new JSONObject(encoded); tampered.put("images", malformed); tampered.put("files", malformed);
        tampered.put("attachedPath", "../../secrets"); tampered.put("skillPath", "/home/coder/../secret");
        state = read(tampered.toString());
        check(state.imageAttachments().length() == 1 && state.imageAttachments().optString(0).equals("safe/file.java"), "invalid images omitted");
        check(state.fileAttachments.equals(Arrays.asList("safe/file.java")), "invalid files omitted");
        check(state.attachedPath.isEmpty(), "unsafe context omitted");
        check(state.selectedSkillPath.isEmpty() && state.selectedSkillName.isEmpty(), "unsafe skill drops label too");
        tampered = new JSONObject(encoded); tampered.put("provider", "claude");
        state = AgentDraftState.decode("claude", "Project-1", tampered.toString());
        check(state.selectedSkillPath.isEmpty() && state.selectedSkillName.isEmpty(), "non codex no skill");
        check(state.selectedAppId.isEmpty() && state.selectedAppName.isEmpty(), "non codex no app");
        check(!state.prompt.isEmpty(), "non codex own prompt preserved");
        state = AgentDraftState.decode("cursor", "Project-1", encode("cursor", "Project-1", "Review"));
        check(state.selectedAppId.isEmpty() && state.selectedSkillPath.isEmpty(), "encode strips foreign context");
        JSONArray images = new JSONArray(); List<String> files = new ArrayList<>();
        for (int i = 0; i < 30; i++) { images.put("image" + i + ".png"); files.add("file" + i + ".txt"); }
        StringBuilder longText = new StringBuilder(); for(int i = 0; i < 65000; i++) longText.append('x');
        encoded = AgentDraftState.encode("codex", "Project-1", longText.toString(), images, files, "", "", "", "", "");
        images.put("late.png"); files.clear(); state = read(encoded);
        check(state.prompt.length() == 64000, "prompt bound");
        check(state.imageAttachments().length() == 4, "image bound");
        check(state.fileAttachments.size() == 12, "files bounded copied");
        JSONArray copy = state.imageAttachments(); copy.put("changed.png");
        check(state.imageAttachments().length() == 4, "immutable JSON copies");
        boolean immutable = false; try { state.fileAttachments.add("changed.txt"); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "immutable files");
        encoded = AgentDraftState.encode("codex", "Project-1", "x\u0000y", new JSONArray().put("a.png").put("a.png"), Arrays.asList("a.txt", "a.txt"), "", "", "", "bad\nID", "Misleading");
        state = read(encoded); check(state.prompt.equals("xy"), "nul removed");
        check(state.imageAttachments().length() == 1 && state.fileAttachments.size() == 1, "duplicate paths removed");
        check(state.selectedAppId.isEmpty() && state.selectedAppName.isEmpty(), "invalid app drops label");
        check(AgentDraftState.decode("codex", "../invalid", encoded).provider.isEmpty(), "invalid expected scope empty");
        System.out.println("AgentDraftState: " + assertions + " assertions passed");
    }
}
