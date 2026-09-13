package com.pocketagent.mobile;

/** Official engines; PocketAgent never supplies an API key or replaces an engine's login. */
final class AgentCatalog {
    static final String[] IDS = {"codex", "cursor", "claude", "antigravity"};
    private AgentCatalog() {}
    static boolean isValid(String id) {
        for (String value : IDS) if (value.equals(id)) return true;
        return false;
    }
    static String name(String id) {
        if ("codex".equals(id)) return "Codex";
        if ("cursor".equals(id)) return "Cursor";
        if ("claude".equals(id)) return "Claude Code";
        if ("antigravity".equals(id)) return "Antigravity";
        return "Agent";
    }
    static String label(String id) { return name(id); }
    static String command(String id) {
        if (!isValid(id)) throw new IllegalArgumentException("Unknown agent");
        return "/usr/local/bin/pocketagent-" + id + ("codex".equals(id) ? " app-server" : "");
    }
    static String accountUrl(String id) {
        if ("codex".equals(id)) return "https://chatgpt.com/codex/settings/usage";
        if ("cursor".equals(id)) return "https://cursor.com/dashboard";
        if ("claude".equals(id)) return "https://claude.ai/settings/usage";
        return "https://antigravity.google/";
    }
    static String description(String id) {
        if ("codex".equals(id)) return "ChatGPT sign-in · models and account limits from Codex";
        if ("cursor".equals(id)) return "Official Cursor agent · your eligible Cursor allowance";
        if ("claude".equals(id)) return "Official Claude Code CLI · eligible Claude subscription required";
        return "Official Antigravity ACP engine · Google account eligibility applies";
    }
}
