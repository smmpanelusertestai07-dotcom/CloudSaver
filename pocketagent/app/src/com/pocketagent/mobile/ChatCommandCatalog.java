package com.pocketagent.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native routes for the documented Codex commands implemented by this client.
 * This is deliberately not a list of every command exposed by the desktop TUI.
 * A catalog entry must have an actual native or app-server route in DeskActivity.
 */
final class ChatCommandCatalog {
    static final class Entry {
        final String key, label;
        final boolean acceptsText, codexOnly;
        Entry(String key, String label, boolean acceptsText, boolean codexOnly) {
            this.key = key; this.label = label;
            this.acceptsText = acceptsText; this.codexOnly = codexOnly;
        }
    }

    private static final Entry[] ENTRIES = {
        new Entry("model", "Model", false, false),
        new Entry("reasoning", "Reasoning effort", false, true),
        new Entry("permissions", "Permissions", false, true),
        new Entry("plan", "Plan", true, true),
        new Entry("new", "New chat", true, true),
        new Entry("resume", "Chats", false, true),
        new Entry("compact", "Compact chat", false, true),
        new Entry("review", "Review changes", true, true),
        new Entry("diff", "Changes", false, false),
        new Entry("status", "Usage", false, false),
        new Entry("ps", "Background tasks", false, true),
        new Entry("mention", "Add a project file", true, true),
        new Entry("mcp", "MCP servers", false, true),
        new Entry("skills", "Skills", false, true),
        new Entry("apps", "Connected apps", false, true),
        new Entry("plugins", "Plugins", false, true),
        new Entry("project", "Projects", false, true),
        new Entry("logout", "Sign out", false, true)
    };

    private ChatCommandCatalog() {}

    static Entry[] entries() { return ENTRIES.clone(); }

    static List<Entry> filter(String query, String provider) {
        String prefix = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (prefix.startsWith("/")) prefix = prefix.substring(1);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : ENTRIES)
            if ((!entry.codexOnly || "codex".equals(provider)) && entry.key.startsWith(prefix)) result.add(entry);
        return result;
    }

    static Entry find(String key) {
        if (key == null) return null;
        for (Entry entry : ENTRIES) if (entry.key.equalsIgnoreCase(key)) return entry;
        return null;
    }
}
