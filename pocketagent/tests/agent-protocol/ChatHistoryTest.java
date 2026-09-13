package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real-file tests verify archive durability, scope isolation and failed-write behavior. */
public final class ChatHistoryTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("pocketagent-history-test-");
        try {
            JSONArray messages = AgentProtocol.array(AgentProtocol.object("id", "one", "role", "user", "text", "Build मेरी app\nWith a preview"),
                    AgentProtocol.object("id", "two", "role", "assistant", "text", "Previous response"));
            Path active = root.resolve("desk-chats/codex-project.json"); Files.createDirectories(active.getParent());
            byte[] original = AgentProtocol.object("messages", messages).toString().getBytes(StandardCharsets.UTF_8);
            Files.write(active, original);
            JSONObject metadata = ChatHistory.archive(root.toFile(), "codex", "project", "old-thread", messages, "missing-session");
            String id = metadata.getString("id");
            check(metadata.getInt("messageCount") == 2 && !metadata.has("messages"), "history menu contains metadata rather than the full sensitive transcript");
            JSONObject reopened = ChatHistory.read(root.toFile(), "codex", "project", id);
            check(reopened.getJSONArray("messages").similar(messages), "closed/reopened archive preserves Unicode and every local message");
            check(reopened.getString("threadId").equals("old-thread"), "archive associates its original engine context");
            check(java.util.Arrays.equals(Files.readAllBytes(active), original), "archiving does not delete or rewrite active transcript");
            check(ChatHistory.list(root.toFile(), "codex", "project").length() == 1, "saved conversation is reviewable through list/read contract");
            check(ChatHistory.list(root.toFile(), "cursor", "project").length() == 0, "another provider does not inherit history");
            check(ChatHistory.list(root.toFile(), "codex", "another-project").length() == 0, "another project does not inherit history");
            reject(() -> ChatHistory.read(root.toFile(), "cursor", "project", id), "provider scope cannot be crossed by archive ID");
            reject(() -> ChatHistory.read(root.toFile(), "codex", "project", "../../desk-chats/codex-project"), "archive path traversal rejected");
            reject(() -> ChatHistory.list(root.toFile(), "codex", "../project"), "project traversal rejected");
            reject(() -> ChatHistory.list(root.toFile(), "unknown", "project"), "unknown providers cannot name directories");

            Path archive = root.resolve("desk-chat-history/codex/project/" + id + ".json");
            Files.write(archive, reopened.put("project", "wrong-project").toString().getBytes(StandardCharsets.UTF_8));
            reject(() -> ChatHistory.read(root.toFile(), "codex", "project", id), "tampered record must match its requested scope");
            check(ChatHistory.list(root.toFile(), "codex", "project").length() == 0, "corrupt record does not become a history menu choice");
            check(Files.exists(archive), "unreadable record remains available for recovery instead of deletion");

            Path external = Files.createTempFile("pocketagent-history-external-", ".json");
            try {
                Files.delete(archive); Files.createSymbolicLink(archive, external);
                reject(() -> ChatHistory.read(root.toFile(), "codex", "project", id), "file symlink cannot escape private history directory");
            } finally { Files.deleteIfExists(external); }
            Path blocked = root.resolve("blocked"); Files.createDirectories(blocked); Files.write(blocked.resolve("desk-chat-history"), new byte[]{1});
            reject(() -> ChatHistory.archive(blocked.toFile(), "codex", "project", "saved", messages, "missing-session"), "failed archive creation must report failure before session reset");
            check(java.util.Arrays.equals(Files.readAllBytes(active), original), "failed archive also leaves source transcript untouched");

            Path linked = root.resolve("linked"); Files.createDirectories(linked);
            Files.createSymbolicLink(linked.resolve("desk-chat-history"), root.resolve("desk-chat-history"));
            reject(() -> ChatHistory.archive(linked.toFile(), "codex", "project", "saved", messages, "missing-session"), "history directory alias cannot cross app-private scope");
            System.out.println("PASS ChatHistoryTest (" + assertions + " assertions)");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
            }
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action, String why) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { rejected = true; }
        check(rejected, why);
    }
    private static void check(boolean okay, String why) { assertions++; if (!okay) throw new AssertionError(why); }
}
