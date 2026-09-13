package com.pocketagent.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

/** Pure notification identities: no prompt, account address, project path or token is persisted. */
final class AgentNotificationPolicy {
    static final int HISTORY_LIMIT = 256;
    private AgentNotificationPolicy() {}

    static boolean category(String kind) {
        return "complete".equals(kind) || "approval".equals(kind) || "error".equals(kind);
    }

    static String eventKey(String kind, String provider, String project, String session, String event) {
        if (!category(kind) || !AgentCatalog.isValid(provider) || project == null || project.length() > 128
                || session == null || session.isEmpty() || session.length() > 512
                || event == null || event.isEmpty() || event.length() > 512) return "";
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{kind, provider, project, session, event}) {
                hash.update((part.length() + ":").getBytes(StandardCharsets.UTF_8));
                hash.update(part.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder result = new StringBuilder();
            for (byte value : hash.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (Exception unavailable) { return ""; }
    }

    /** Null means duplicate/invalid; bounded FIFO state survives ordinary service restarts. */
    static String remember(String existing, String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) return null;
        ArrayList<String> entries = new ArrayList<>();
        if (existing != null && existing.length() <= HISTORY_LIMIT * 65) {
            for (String entry : existing.split("\n")) {
                if (key.equals(entry)) return null;
                if (entry.matches("[0-9a-f]{64}")) entries.add(entry);
            }
        }
        while (entries.size() >= HISTORY_LIMIT) entries.remove(0);
        entries.add(key);
        return String.join("\n", entries);
    }
}
