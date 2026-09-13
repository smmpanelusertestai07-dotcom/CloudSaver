package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/** Private local transcripts for review. Archived text is never injected into a new engine thread. */
final class ChatHistory {
    private static final long MAX_BYTES = 900000;
    private ChatHistory() {}

    static JSONArray list(File filesDir, String provider, String project) throws IOException {
        File directory = directory(filesDir, provider, project, false);
        JSONArray result = new JSONArray();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return result;
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        for (File file : files) {
            if (result.length() >= 100) break;
            String id = file.getName().substring(0, file.getName().length() - 5);
            try { result.put(metadata(read(filesDir, provider, project, id))); }
            catch (IOException | IllegalArgumentException ignored) { /* Leave corrupt/unexpected entries untouched. */ }
        }
        return result;
    }

    static JSONObject read(File filesDir, String provider, String project, String archiveId) throws IOException {
        if (archiveId == null || !archiveId.matches("[0-9]{1,19}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("Choose a saved conversation from Chat history.");
        File directory = directory(filesDir, provider, project, false);
        File source = direct(directory, archiveId + ".json");
        if (!source.isFile() || source.length() > MAX_BYTES) throw new IOException("This saved conversation is unavailable.");
        try (FileInputStream in = new FileInputStream(source); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("This saved conversation is too large to display.");
                bytes.write(buffer, 0, count);
            }
            JSONObject record = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (!provider.equals(record.optString("provider")) || !project.equals(record.optString("project"))
                    || !archiveId.equals(record.optString("id")) || record.optJSONArray("messages") == null)
                throw new IOException("The saved conversation does not match this project.");
            return record;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("This saved conversation cannot be read.", error); }
    }

    static JSONObject archive(File filesDir, String provider, String project, String threadId,
                              JSONArray messages, String reason) throws IOException {
        File directory = directory(filesDir, provider, project, true);
        long now = System.currentTimeMillis();
        String id = now + "-" + UUID.randomUUID().toString();
        JSONObject record = AgentProtocol.object("id", id, "provider", provider, "project", project,
                "threadId", threadId == null ? "" : threadId, "createdAt", now,
                "reason", reason, "messageCount", messages.length(), "messages", messages);
        byte[] bytes = record.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("The previous conversation is too large to archive safely.");
        File target = direct(directory, id + ".json"), temporary = direct(directory, id + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
            if (!temporary.renameTo(target)) throw new IOException("Could not save the previous conversation. Please free some storage and retry.");
        } finally { if (temporary.exists()) temporary.delete(); }
        return metadata(record);
    }

    private static JSONObject metadata(JSONObject record) {
        return AgentProtocol.object("id", record.optString("id"), "provider", record.optString("provider"),
                "project", record.optString("project"), "threadId", record.optString("threadId"),
                "createdAt", record.optLong("createdAt"), "reason", record.optString("reason"),
                "messageCount", record.optInt("messageCount"));
    }
    private static File directory(File filesDir, String provider, String project, boolean create) throws IOException {
        if (!AgentCatalog.isValid(provider)) throw new IOException("Choose a supported agent.");
        AgentProtocol.project(project);
        File base = filesDir.getCanonicalFile();
        File history = direct(base, "desk-chat-history");
        File agent = direct(history, provider);
        File directory = direct(agent, project);
        if (create && !directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private Chat history storage.");
        return directory;
    }
    private static File direct(File parent, String name) throws IOException {
        File expected = new File(parent, name).getAbsoluteFile(), actual = expected.getCanonicalFile();
        if (!actual.equals(expected) || !actual.getParentFile().equals(parent.getCanonicalFile()))
            throw new IOException("Chat history path is outside its private folder.");
        return actual;
    }
}
