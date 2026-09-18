package com.pocketide;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The agents' chats as they sit on the phone, read from each agent's own storage.
 *
 * Read from the publishers' documentation rather than assumed: Claude Code stores each session
 * as JSONL under ~/.claude/projects/<project>/<session-id>.jsonl and resumes one from any
 * directory with `claude --resume <id>`; Codex keeps one rollout file per thread under
 * ~/.codex/sessions/YYYY/MM/DD/ and resumes with `codex resume <id>`; Kilo Code keeps one folder
 * per task in its extension storage and opens one from its own History view. None of the three
 * fetches a chat back from the owner's account: the copy here is the copy. Antigravity does not
 * document where it keeps anything, so it is not listed.
 *
 * Every format here is the publisher's own and can change with a release, so each reader takes
 * what it recognises and falls back to what the file system knows: a title of "Chat" with the
 * date, a size, a modification time. Nothing here is written to; deleting is the one action.
 */
final class Chats {

    static final String CLAUDE = "Claude Code";
    static final String CODEX = "Codex";
    static final String KILO = "Kilo Code";

    /** One chat: what to show, what to delete, and how the agent resumes it. */
    static final class Chat {
        final String agent;
        final String id;
        final String title;
        final String project;
        final long updated;
        final long bytes;
        final File path;
        /** The command that resumes it in a terminal, or null where the agent has none. */
        final String resume;

        Chat(String agent, String id, String title, String project, long updated, long bytes,
             File path, String resume) {
            this.agent = agent;
            this.id = id;
            this.title = title;
            this.project = project;
            this.updated = updated;
            this.bytes = bytes;
            this.path = path;
            this.resume = resume;
        }
    }

    /** How much of a transcript is read for its first prompt: enough for any first message. */
    private static final int READ_LIMIT = 256 * 1024;
    private static final int TITLE_LIMIT = 80;

    private Chats() {}

    /** Every chat on the phone, newest first. Reads files; call off the main thread. */
    static List<Chat> all(Context context) {
        List<Chat> chats = new ArrayList<>();
        File home = Stored.home(context);
        claude(new File(home, ".claude/projects"), chats);
        codex(new File(home, ".codex/sessions"), new File(home, ".codex/session_index.jsonl"), chats);
        kilo(new File(home, ".local/share/code-server/User/globalStorage/kilocode.kilo-code/tasks"),
                chats);
        Collections.sort(chats, (a, b) -> Long.compare(b.updated, a.updated));
        return chats;
    }

    /** Deletes one chat's file or folder. Symlink-safe, like everything else that deletes. */
    static boolean delete(Chat chat) {
        Workspace.delete(chat.path);
        return !chat.path.exists();
    }

    // ------------------------------------------------------------------ Claude Code

    private static void claude(File projects, List<Chat> into) {
        File[] dirs = projects.listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".jsonl")) continue;
                String id = file.getName().substring(0, file.getName().length() - ".jsonl".length());
                String title = "";
                String cwd = "";
                for (String line : head(file)) {
                    try {
                        JSONObject entry = new JSONObject(line);
                        if (cwd.isEmpty()) cwd = entry.optString("cwd", "");
                        if (title.isEmpty() && "user".equals(entry.optString("type"))) {
                            title = text(entry.opt("message"));
                        }
                    } catch (JSONException notJson) {
                        // A line this app does not understand is skipped, not a reason to stop.
                    }
                    if (!title.isEmpty() && !cwd.isEmpty()) break;
                }
                into.add(new Chat(CLAUDE, id, trim(title), cwd, file.lastModified(), file.length(),
                        file, "claude --resume " + id));
            }
        }
    }

    // ------------------------------------------------------------------ Codex

    private static void codex(File sessions, File index, List<Chat> into) {
        Map<String, String> names = codexNames(index);
        List<File> files = new ArrayList<>();
        collect(sessions, 4, files);
        for (File file : files) {
            if (!file.getName().startsWith("rollout-") || !file.getName().endsWith(".jsonl")) continue;
            String id = "";
            String cwd = "";
            String title = "";
            for (String line : head(file)) {
                try {
                    JSONObject entry = new JSONObject(line);
                    String type = entry.optString("type");
                    JSONObject payload = entry.optJSONObject("payload");
                    if ("session_meta".equals(type) && payload != null) {
                        id = payload.optString("id", "");
                        cwd = payload.optString("cwd", "");
                    } else if ("event_msg".equals(type) && payload != null
                            && "user_message".equals(payload.optString("type")) && title.isEmpty()) {
                        title = payload.optString("message", "");
                    }
                } catch (JSONException notJson) {
                    // Skipped, as above.
                }
                if (!id.isEmpty() && !title.isEmpty()) break;
            }
            if (id.isEmpty()) {
                // The id is also in the file name, after the timestamp: rollout-<time>-<id>.jsonl
                String name = file.getName();
                int dash = name.lastIndexOf('-');
                if (dash > 0) id = name.substring(dash + 1, name.length() - ".jsonl".length());
            }
            String named = names.get(id);
            into.add(new Chat(CODEX, id, trim(named != null && !named.isEmpty() ? named : title),
                    cwd, file.lastModified(), file.length(), file,
                    id.isEmpty() ? null : "codex resume " + id));
        }
    }

    /** Names given with /rename, kept by Codex in one index file; best effort, any layout. */
    private static Map<String, String> codexNames(File index) {
        Map<String, String> names = new HashMap<>();
        if (!index.isFile()) return names;
        for (String line : head(index)) {
            try {
                JSONObject entry = new JSONObject(line);
                String id = entry.optString("id", entry.optString("session_id", ""));
                String name = entry.optString("thread_name",
                        entry.optString("name", entry.optString("title", "")));
                if (!id.isEmpty() && !name.isEmpty()) names.put(id, name);
            } catch (JSONException notJson) {
                // Skipped.
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ Kilo Code

    private static void kilo(File tasks, List<Chat> into) {
        File[] dirs = tasks.listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            String title = "";
            File messages = new File(dir, "ui_messages.json");
            if (messages.isFile() && messages.length() < 4L * 1024 * 1024) {
                try {
                    JSONArray list = new JSONArray(readAll(messages));
                    for (int i = 0; i < list.length() && title.isEmpty(); i++) {
                        JSONObject message = list.optJSONObject(i);
                        if (message == null) continue;
                        if ("say".equals(message.optString("type"))
                                && "text".equals(message.optString("say"))) {
                            title = message.optString("text", "");
                        }
                    }
                } catch (JSONException | IOException unreadable) {
                    // The folder is still a chat; it is just untitled.
                }
            }
            long newest = dir.lastModified();
            File[] inside = dir.listFiles();
            if (inside != null) for (File f : inside) newest = Math.max(newest, f.lastModified());
            into.add(new Chat(KILO, dir.getName(), trim(title), "", newest, Workspace.sizeOf(dir),
                    dir, null));
        }
    }

    /** Every file under {@code dir}, to {@code depth} levels down. */
    private static void collect(File dir, int depth, List<File> into) {
        File[] entries = dir.listFiles();
        if (entries == null || depth < 0) return;
        for (File entry : entries) {
            if (entry.isDirectory()) collect(entry, depth - 1, into);
            else if (entry.isFile()) into.add(entry);
        }
    }

    // ------------------------------------------------------------------ reading

    /** The first lines of a file, up to READ_LIMIT bytes. */
    private static List<String> head(File file) {
        List<String> lines = new ArrayList<>();
        long read = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && read < READ_LIMIT) {
                read += line.length() + 1;
                if (!line.isEmpty()) lines.add(line);
            }
        } catch (IOException unreadable) {
            // What was read is what there is.
        }
        return lines;
    }

    private static String readAll(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[16384];
            int count;
            while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
        }
        return text.toString();
    }

    /** A message's text, whether the publisher stored it as a string or as content parts. */
    private static String text(Object message) {
        if (message instanceof String) return (String) message;
        if (!(message instanceof JSONObject)) return "";
        Object content = ((JSONObject) message).opt("content");
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) {
                    return part.optString("text", "");
                }
            }
        }
        return "";
    }

    /** One line, cut to a row's width, with the noise a first prompt tends to start with gone. */
    static String trim(String title) {
        String one = title == null ? "" : title.replace('\n', ' ').replace('\r', ' ').trim();
        one = one.replaceAll("\\s+", " ");
        if (one.startsWith("<")) {
            // Claude Code wraps some first messages in tags; the words are after the last one.
            int close = one.lastIndexOf('>');
            if (close > 0 && close < one.length() - 1) one = one.substring(close + 1).trim();
        }
        if (one.length() > TITLE_LIMIT) one = one.substring(0, TITLE_LIMIT - 1).trim() + "…";
        return one;
    }
}
