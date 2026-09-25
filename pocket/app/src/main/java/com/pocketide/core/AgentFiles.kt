package com.pocketide.core

/**
 * What each file in a room's home is, from PocketIDE's point of view. One list, shared by the
 * sync engine (what may go to Drive), the check-post (what may never reach GitHub) and the
 * Your data screen (what is shown), so the three can never disagree.
 */
enum class FileClass {
    /** A login or token. Never leaves the room: not synced, not shown, never pushed. */
    SECRET,

    /** Chats, memory and instructions: encrypted and synced to the owner's Drive. */
    SYNC,

    /**
     * Configuration that can run code (hooks, MCP servers, permission rules, env blocks).
     * PocketIDE writes it from its own templates at every room start; a change made inside
     * Linux reaches Drive only after the owner approves it on a diff card.
     */
    GENERATED,

    /** Caches, logs and state the agent rebuilds. Left alone. */
    LOCAL,
}

/**
 * Classification of paths relative to a room's home (`/root` inside the room), per agent.
 * Paths use "/" and never start with "/". Unknown paths are LOCAL, except that anything that
 * looks like a credential is SECRET wherever it is.
 */
object AgentFiles {
    /** Exact secret files, relative to the home (all agents). */
    private val secretExact = setOf(
        ".claude/.credentials.json",
        ".claude.json",
        // What room.py moves out of .claude.json, which holds the account (rooms/room.py).
        ".claude.json.pocketide-unreadable",
        ".claude/.pocketide-held-servers.json",
        ".codex/auth.json",
        ".gemini/jetski-standalone-oauth-token",
        ".gemini/antigravity/antigravity-oauth-token",
        ".gemini/antigravity/mcp_oauth_tokens.json",
        ".gemini/oauth_creds.json",
        ".config/gcloud/application_default_credentials.json",
        ".git-credentials",
        ".config/gh/hosts.yml",
        ".npmrc",
        ".pypirc",
        ".netrc",
    )

    /** Secret folders, relative to the home. */
    private val secretDirs = listOf(
        ".claude/backups/",
        ".config/anthropic/",
        ".ssh/",
        ".gnupg/",
        ".local/share/code-server/User/globalStorage/",
    )

    /** The pattern net for renamed or new credential files. */
    private val secretNames = listOf(
        Regex("(?i).*credential.*"),
        Regex("(?i)(^|.*/)auth\\.json$"),
        Regex("(?i).*oauth.*token.*"),
        Regex("(?i).*(^|[._-])token(s)?(\\.json|\\.txt)?$"),
        Regex("(?i).*\\.(key|pem|p12|pfx|jks|keystore)$"),
        Regex("(?i)(^|.*/)id_(rsa|ed25519|ecdsa)(\\.pub)?$"),
        Regex("(?i)(^|.*/)\\.env(\\..+)?$"),
    )

    private val generated = setOf(
        ".claude/settings.json",
        ".codex/config.toml",
        ".codex/hooks.json",
        ".gemini/config/mcp_config.json",
        ".gemini/config/hooks.json",
        ".gemini/antigravity-cli/settings.json",
        ".gemini/config/config.json",
        ".local/share/code-server/User/settings.json",
    )

    private val syncRules: List<Regex> = listOf(
        // Claude Code
        Regex("^\\.claude/CLAUDE\\.md$"),
        Regex("^\\.claude/rules/[^/]+\\.md$"),
        Regex("^\\.claude/projects/[^/]+/[^/]+\\.jsonl$"),
        Regex("^\\.claude/projects/[^/]+/[^/]+/(subagents|tool-results)/.+$"),
        Regex("^\\.claude/projects/[^/]+/memory/.+$"),
        Regex("^\\.claude/history\\.jsonl$"),
        Regex("^\\.claude/plans/.+$"),
        // Codex
        Regex("^\\.codex/AGENTS(\\.override)?\\.md$"),
        Regex("^\\.codex/(sessions|archived_sessions)/.+\\.jsonl$"),
        Regex("^\\.codex/history\\.jsonl$"),
        Regex("^\\.codex/rules/[^/]+\\.rules$"),
        Regex("^\\.codex/skills/(?!\\.system/).+$"),
        // Antigravity
        Regex("^\\.gemini/(GEMINI|AGENTS)\\.md$"),
        Regex("^\\.gemini/config/(GEMINI|AGENTS)\\.md$"),
        Regex("^\\.gemini/config/rules/[^/]+\\.md$"),
        Regex("^\\.gemini/config/memory\\.txtpb$"),
        Regex("^\\.gemini/antigravity(-cli|-ide)?/conversations/.+$"),
        Regex("^\\.gemini/antigravity(-cli|-ide)?/conversation_summaries\\.db(-wal|-shm)?$"),
        Regex("^\\.gemini/antigravity(-cli|-ide)?/brain/.+$"),
        Regex("^\\.gemini/antigravity-cli/rules/[^/]+\\.md$"),
    )

    /** Files whose text is scanned for pasted secrets before they are uploaded. */
    private val scanBeforeUpload = listOf(
        Regex("^\\.claude/history\\.jsonl$"),
        Regex("^\\.codex/history\\.jsonl$"),
    )

    fun classify(relativePath: String): FileClass {
        val path = relativePath.trimStart('/')
        if (isSecret(path)) return FileClass.SECRET
        if (path in generated) return FileClass.GENERATED
        if (syncRules.any { it.matches(path) }) return FileClass.SYNC
        return FileClass.LOCAL
    }

    fun isSecret(relativePath: String): Boolean {
        val path = relativePath.trimStart('/')
        if (path in secretExact) return true
        if (secretDirs.any { path.startsWith(it) }) return true
        val name = path.substringAfterLast('/')
        // Instruction files and transcripts never count as credentials by name alone.
        if (name.endsWith(".md") || name.endsWith(".jsonl")) return false
        return secretNames.any { it.matches(path) }
    }

    fun needsSecretScan(relativePath: String): Boolean =
        scanBeforeUpload.any { it.matches(relativePath.trimStart('/')) }
}
