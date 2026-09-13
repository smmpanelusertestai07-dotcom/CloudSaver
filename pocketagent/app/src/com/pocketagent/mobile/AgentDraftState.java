package com.pocketagent.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private, provider-and-project scoped draft data. This class never opens any file. */
final class AgentDraftState {
    static final int MAX_PROMPT = 64000, MAX_IMAGES = 4, MAX_FILES = 12;
    private static final int MAX_ENCODED = 512 * 1024;
    private AgentDraftState() { }

    /** Exact identity, without case folding or lossy replacement of separators. */
    static String key(String provider, String project) {
        if (!validScope(provider, project)) return "";
        return "agent_draft_v1:" + provider.length() + ":" + provider + ":" + project.length() + ":" + project;
    }

    static String encode(String provider, String project, String prompt, JSONArray images,
                         List<String> files, String attachedPath, String skillPath,
                         String skillName, String appId, String appName) {
        if (!validScope(provider, project)) return "";
        State value = new State(provider, project, prompt, images, files, attachedPath, skillPath, skillName, appId, appName);
        try {
            JSONObject out = new JSONObject();
            out.put("version", 1); out.put("provider", provider); out.put("project", project);
            out.put("prompt", value.prompt); out.put("images", value.imageAttachments());
            JSONArray documents = new JSONArray(); for (String path : value.fileAttachments) documents.put(path);
            out.put("files", documents); out.put("attachedPath", value.attachedPath);
            out.put("skillPath", value.selectedSkillPath); out.put("skillName", value.selectedSkillName);
            out.put("appId", value.selectedAppId); out.put("appName", value.selectedAppName);
            String result = out.toString(); return result.length() <= MAX_ENCODED ? result : "";
        } catch (Exception malformed) { return ""; }
    }

    /** Embedded identity must match the requested scope; a preference key alone is insufficient. */
    static State decode(String provider, String project, String encoded) {
        State empty = new State(provider, project, "", null, null, "", "", "", "", "");
        if (!validScope(provider, project) || encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED) return empty;
        try {
            JSONObject data = new JSONObject(encoded);
            Object version = data.opt("version");
            if (!(version instanceof Number) || ((Number) version).doubleValue() != 1d
                    || !provider.equals(data.opt("provider")) || !project.equals(data.opt("project"))) return empty;
            // Wrong field types cannot be coerced into prompt text, paths, or tool identifiers.
            for (String field : new String[]{"prompt", "attachedPath", "skillPath", "skillName", "appId", "appName"})
                if (!(data.opt(field) instanceof String)) return empty;
            JSONArray images = data.optJSONArray("images"), files = data.optJSONArray("files");
            if (images == null || files == null) return empty;
            List<String> documents = paths(files, MAX_FILES);
            return new State(provider, project, (String)data.opt("prompt"), images, documents,
                    (String)data.opt("attachedPath"), (String)data.opt("skillPath"), (String)data.opt("skillName"),
                    (String)data.opt("appId"), (String)data.opt("appName"));
        } catch (Exception malformed) { return empty; }
    }

    static final class State {
        final String provider, project, prompt, attachedPath;
        final String selectedSkillPath, selectedSkillName, selectedAppId, selectedAppName;
        final List<String> fileAttachments;
        private final List<String> images;

        private State(String provider, String project, String prompt, JSONArray imagePaths,
                      List<String> documents, String attachedPath, String skillPath,
                      String skillName, String appId, String appName) {
            boolean valid = validScope(provider, project);
            this.provider = valid ? provider : ""; this.project = valid ? project : "";
            this.prompt = valid ? clip(prompt, MAX_PROMPT) : "";
            images = Collections.unmodifiableList(valid ? paths(imagePaths, MAX_IMAGES) : new ArrayList<String>());
            LinkedHashSet<String> files = new LinkedHashSet<>();
            if (valid && documents != null) for (String path : documents) {
                if (validPath(path, false)) files.add(path);
                if (files.size() == MAX_FILES) break;
            }
            fileAttachments = Collections.unmodifiableList(new ArrayList<>(files));
            this.attachedPath = valid && validPath(attachedPath, false) ? attachedPath : "";
            boolean codex = valid && "codex".equals(provider);
            selectedSkillPath = codex && validPath(skillPath, true) ? skillPath : "";
            selectedSkillName = selectedSkillPath.isEmpty() ? "" : label(skillName, 160);
            selectedAppId = codex && validId(appId) ? appId : "";
            selectedAppName = selectedAppId.isEmpty() ? "" : label(appName, 160);
        }

        /** JSON is mutable: callers receive a fresh copy, never the stored collection. */
        JSONArray imageAttachments() { JSONArray result = new JSONArray(); for (String path : images) result.put(path); return result; }
    }

    private static boolean validScope(String provider, String project) {
        return ("codex".equals(provider) || "cursor".equals(provider) || "claude".equals(provider) || "antigravity".equals(provider))
                && project != null && project.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    }

    private static List<String> paths(JSONArray source, int maximum) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (source != null) for (int i = 0; i < source.length() && i < 128; i++) {
            Object raw = source.opt(i); if (raw instanceof String && validPath((String) raw, false)) result.add((String)raw);
            if (result.size() == maximum) break;
        }
        return new ArrayList<>(result);
    }

    /** Syntactic checks only; WorkspaceMedia/agent submission still perform canonical containment checks. */
    private static boolean validPath(String path, boolean allowAbsolute) {
        if (path == null || path.isEmpty() || path.length() > 2048 || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) return false;
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) return false;
        String relative = path;
        if (relative.startsWith("/")) { if (!allowAbsolute) return false; relative = relative.substring(1); }
        for (String part : relative.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}");
    }
    private static String label(String text, int maximum) { return clip(text, maximum).replaceAll("[\\p{Cntrl}]", ""); }
    private static String clip(String text, int maximum) {
        if (text == null) return "";
        String value = text.replace("\u0000", "");
        if (value.length() <= maximum) return value;
        int end = maximum; if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
