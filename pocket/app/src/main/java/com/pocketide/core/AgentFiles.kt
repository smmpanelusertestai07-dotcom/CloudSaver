package com.pocketide.core

/**
 * What each file in a room's home is, from PocketIDE's point of view. One list, shared by the
 * sync engine (what may go to Drive), the check-post (what may never reach GitHub) and the
 * Your data screen (what is shown), so the three can never disagree.
 */
enum class FileClass {
    /** A login or token. Never leaves the room: not synced, not shown, never pushed. */
    SECRET,

    /**
     * Chats, memory and instructions: encrypted and synced to the owner's Drive. A skill,
     * subagent, command or command rule that can run code ([AgentFiles.codeIn]) waits for the
     * owner first, as a GENERATED change does.
     */
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
        // The owner's own skills, subagents, slash commands and output styles.
        Regex("^\\.claude/skills/.+$"),
        Regex("^\\.claude/agents/.+\\.md$"),
        Regex("^\\.claude/commands/.+\\.md$"),
        Regex("^\\.claude/output-styles/[^/]+\\.md$"),
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

    /**
     * SYNC files that can carry what GENERATED files carry, settings that run a program or let
     * their agent act without asking, or that such settings run: Claude's skills (with every file
     * bundled in one), subagents and commands, and Codex's command rules.
     */
    private val mayCarryCode = listOf(
        Regex("^\\.claude/skills/.+$"),
        Regex("^\\.claude/agents/.+\\.md$"),
        Regex("^\\.claude/commands/.+\\.md$"),
        Regex("^\\.codex/rules/[^/]+\\.rules$"),
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

    /**
     * A SYNC file that goes to Drive only when [codeIn] finds nothing in it, or once the owner
     * kept that very version: otherwise a hook an agent wrote there would follow the owner to
     * every phone, and to the room restored after a reinstall, and run there unasked.
     */
    fun mayCarryCode(relativePath: String): Boolean {
        val path = relativePath.trimStart('/')
        return classify(path) == FileClass.SYNC && mayCarryCode.any { it.matches(path) }
    }

    /**
     * What in a [mayCarryCode] file runs a program by itself, or lets its agent act without
     * asking: one plain phrase for each, to follow the file's name ("runs commands by itself
     * (hooks)"); empty when nothing does. [text] is the file's whole text, or null when it was too
     * long to read through.
     */
    fun codeIn(relativePath: String, text: String?): List<String> {
        val path = relativePath.trimStart('/')
        return if (mayCarryCode(path)) AgentCode.of(path, text) else emptyList()
    }
}

/**
 * Reads Claude Code's skill, subagent and command files the way Claude Code reads them (research3
 * cc-claude-directory, "Frontmatter fields by file", and the patterns in Claude Code's own
 * parser). The frontmatter is what follows a first line of "---", up to the next "---"; Claude
 * Code runs every `` !`command` `` and "```!" block of a skill or command each time it is used.
 * A frontmatter setting not known to be harmless counts as one that runs code: a new one may,
 * and the owner reads it before it goes. So does a line there that is not a plain `key:`.
 */
private object AgentCode {
    private const val SKILLS = ".claude/skills/"
    private const val AGENTS = ".claude/agents/"
    private const val CODEX_RULES = ".codex/rules/"
    private const val SKILL_ENTRY = "SKILL.md"
    private const val BOM = "\uFEFF"

    /** JavaScript's `\s`, which Claude Code's pattern uses: Java's own `\s` misses some of these. */
    private const val SPACE = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private val FRONTMATTER = Regex("^---$SPACE*\\n([\\s\\S]*?)---")

    /** The frontmatter closed by a "---" line of its own, which Claude Code checks its reading against. */
    private val OWN_LINE = Regex("^---[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n---[ \\t]*(\\r?\\n|$)")
    private val KEY = Regex("^(?:\"([^\"\\\\]*)\"|'([^']*)'|([A-Za-z0-9_][A-Za-z0-9_-]*))[ \\t]*:(?:[ \\t]|$)")
    private val INLINE_SHELL = Regex("!`[^`]+`")
    private val SHOWN_KEY = Regex("[A-Za-z0-9_-]{1,40}")

    private val SKILL_KEYS = setOf(
        "name", "description", "when_to_use", "argument-hint", "arguments", "disable-model-invocation", "user-invocable",
        "disallowed-tools", "model", "effort", "context", "agent", "background", "paths", "metadata", "license",
        "compatibility", "version",
    )
    private val AGENT_KEYS = setOf(
        "name", "description", "tools", "disallowedTools", "model", "maxTurns", "skills", "memory", "background", "effort",
        "isolation", "color", "initialPrompt", "omitClaudeMd", "version",
    )
    private val SETTINGS = mapOf(
        "hooks" to "runs commands by itself (hooks)",
        "mcpServers" to "starts tool servers, programs of their own (mcpServers)",
        "permissionMode" to "changes how much Claude may do without asking (permissionMode)",
        "allowed-tools" to "lets Claude use tools without asking (allowed-tools)",
        "shell" to "sets the shell its commands run in (shell)",
    )

    private const val RULES = "can let Codex run commands without asking"
    private const val BUNDLED = "is a file the skill can run"
    private const val TOO_LONG = "is too long for PocketIDE to read through"
    private const val UNREADABLE = "has settings PocketIDE cannot read"
    private const val SHELL = "runs a command each time it is used (!`…`)"

    fun of(path: String, text: String?): List<String> {
        val name = path.substringAfterLast('/')
        return when {
            path.startsWith(CODEX_RULES) -> listOf(RULES)
            path.startsWith(SKILLS) && !name.equals(SKILL_ENTRY, ignoreCase = true) -> if (name.endsWith(".md")) emptyList() else listOf(BUNDLED)
            text == null -> listOf(TOO_LONG)
            else -> settings(text, if (path.startsWith(AGENTS)) AGENT_KEYS else SKILL_KEYS) + inlineShell(text)
        }
    }

    /**
     * The frontmatter settings that are not known to be harmless. Claude Code ends the frontmatter
     * at the first "---", even inside a line; where a "---" line of its own would end it
     * elsewhere, a stricter reading could find settings this one does not, so it counts as unread.
     */
    private fun settings(text: String, harmless: Set<String>): List<String> {
        val body = text.removePrefix(BOM)
        val block = FRONTMATTER.find(body)?.groupValues?.get(1) ?: return openedAsFrontmatter(body)
        return readableKeys(body, block)?.filter { it !in harmless }?.distinct()?.map(::settingPhrase) ?: listOf(UNREADABLE)
    }

    /** No frontmatter Claude Code reads today, though the file opens like one: a later version might read it. */
    private fun openedAsFrontmatter(body: String): List<String> = if (body.trimStart().startsWith("---")) listOf(UNREADABLE) else emptyList()

    /** [block]'s top-level keys, when a "---" line of its own ends the frontmatter just where Claude Code's reading does. */
    private fun readableKeys(body: String, block: String): List<String>? {
        val ownLine = OWN_LINE.find(body)?.groupValues?.get(1)
        return topLevelKeys(block)?.takeIf { block.isBlank() || ownLine?.trim() == block.trim() }
    }

    /** The block's top-level keys; null when a line at that level is not a plain `key:`, or tabs indent one. */
    private fun topLevelKeys(block: String): List<String>? {
        val lines = block.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        val tabs = lines.any { line -> line.takeWhile { it == ' ' || it == '\t' }.contains('\t') }
        val root = lines.firstOrNull()?.let(::indentOf) ?: 0
        val keys = lines.filter { indentOf(it) <= root }.map { keyOf(it, root) }
        return if (tabs || keys.any { it == null }) null else keys.filterNotNull()
    }

    /** What a line at the [root] level names, or null when it is not a plain `key:` there. */
    private fun keyOf(line: String, root: Int): String? {
        if (indentOf(line) != root) return null
        return KEY.find(line.substring(root))?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
    }

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' }.length

    private fun settingPhrase(key: String): String =
        SETTINGS[key] ?: if (SHOWN_KEY.matches(key)) "has a setting PocketIDE does not know ($key)" else UNREADABLE

    private fun inlineShell(text: String): List<String> =
        if (text.contains("```!") || INLINE_SHELL.containsMatchIn(text)) listOf(SHELL) else emptyList()
}
