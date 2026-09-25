package com.pocketide.docs

/**
 * Where an official agent's chats are kept besides this phone: [kept] is the short answer,
 * [elsewhere] what keeps them besides PocketIDE's backup, [note] one more line when the answer needs it, [open] the company page that shows what it
 * keeps, and [sources] the company pages behind the answer.
 */
internal data class ChatHome(
    val agentId: String,
    val agent: String,
    val kept: String,
    val elsewhere: String,
    val note: String?,
    val open: DocBlock.Link?,
    val sources: List<DocBlock.Link> = emptyList(),
)

/**
 * The one answer to "where are my chats saved?", per official agent, for Help, Privacy and Your
 * data, as each company offers it. PocketIDE's encrypted Drive backup keeps every agent's chats
 * either way: a new phone restores and resumes them from it.
 */
internal object ChatHomes {
    /** The Settings → Agents switch that connects Claude Code's sessions to Remote Control. */
    const val CLAUDE_SWITCH = "Also save Claude chats in your Claude account"

    /** Shown on a Claude session while that switch is on. */
    const val CLAUDE_ACCOUNT_LINE = "Also saved in your Claude account"

    const val CODEX_CLOUD_LINE =
        "Run in the cloud keeps the chat in your ChatGPT account (needs a Codex cloud environment for this repository)."

    const val JULES_LINE = "Jules, Google's cloud agent, keeps its tasks in your Google account."

    const val BACKUP = "PocketIDE's encrypted Drive backup"

    val claude = ChatHome(
        agentId = "claude",
        agent = "Claude Code",
        kept = "Your Claude account, while its switch is on, and $BACKUP.",
        elsewhere = "Your Claude account, while Settings → Agents allows it",
        note = "Anthropic stores the transcript (your messages, Claude's replies and tool activity) under its " +
            "data-usage policy and your \"Help improve Claude\" choice. The Claude app and claude.ai/code show it, " +
            "and you can continue it there. It needs a Claude plan sign-in, Pro or higher. To stop it, turn off " +
            "Settings → Agents → \"$CLAUDE_SWITCH\".",
        open = link("Open Claude Code on the web", DocLinks.CLAUDE_CODE_WEB),
        sources = listOf(link("Claude Code Remote Control", DocLinks.CLAUDE_REMOTE_CONTROL)),
    )

    val codex = ChatHome(
        agentId = "codex",
        agent = "Codex",
        kept = "Cloud tasks: your ChatGPT account. Local sessions: $BACKUP only; OpenAI cannot keep them yet.",
        elsewhere = "Your ChatGPT account (cloud tasks)",
        note = CODEX_CLOUD_LINE,
        open = link("Open Codex on the web", DocLinks.CODEX_WEB),
        sources = listOf(
            link("Codex in the cloud", DocLinks.CODEX_CLOUD),
            link("OpenAI: request to keep local chats", DocLinks.CODEX_LOCAL_SYNC_REQUEST),
        ),
    )

    val antigravity = ChatHome(
        agentId = "antigravity",
        agent = "Antigravity",
        kept = "$BACKUP only; Google offers no cloud history yet.",
        elsewhere = "Nowhere: Google has no cloud history yet",
        note = JULES_LINE,
        open = link("Open Jules", DocLinks.JULES),
    )

    val all = listOf(claude, codex, antigravity)

    fun of(agentId: String): ChatHome? = all.firstOrNull { it.agentId == agentId }

    const val TABLE_HEADER = "Also kept, besides PocketIDE's Drive backup"

    fun table(): DocBlock.Table = DocBlock.Table(listOf("Agent", TABLE_HEADER), all.map { row(it.agent, it.elsewhere) })
}
