package com.pocketide.docs

/**
 * Where the owner can open an official agent's chats again besides this phone: [kept] is the short
 * answer, [elsewhere] the company's own place besides PocketIDE's backup, [note] one more line when
 * the answer needs it, [open] the company page that shows them, and [sources] the company pages
 * behind the answer. Every company still keeps what its agent receives ([COMPANIES_KEEP]).
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
 * The one answer to "where can I open my chats again?", per official agent, for Help, Privacy and
 * Your data, as each company offers it. PocketIDE's encrypted Drive backup keeps every agent's
 * chats either way: a new phone restores and resumes them from it. It is never an answer to what a
 * company keeps: each keeps what its agent receives under its own policy, as Privacy says.
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

    /**
     * Remote Control lasts only while Claude's process runs, and PocketIDE closes idle rooms: the
     * session can be continued elsewhere only while its room is up.
     */
    const val CLAUDE_CONTINUE =
        "You can continue it there while Claude is running on this phone: a room that sleeps or stops takes it " +
            "offline until you open the session here again."

    /** Said wherever these answers are, so "only the backup" is never read as "the company kept nothing". */
    const val COMPANIES_KEEP = "Each company still keeps what its agent receives under its own policy."

    val claude = ChatHome(
        agentId = "claude",
        agent = "Claude Code",
        kept = "Your Claude account, while its switch is on, and $BACKUP.",
        elsewhere = "Your Claude account, while Settings → Agents allows it",
        note = "Anthropic stores the transcript (your messages, Claude's replies and tool activity) under its " +
            "data-usage policy and your \"Help improve Claude\" choice. The Claude app and claude.ai/code show it. " +
            "$CLAUDE_CONTINUE It needs a Claude plan sign-in, Pro or higher. To stop it, turn off " +
            "Settings → Agents → \"$CLAUDE_SWITCH\".",
        open = link("Open Claude Code on the web", DocLinks.CLAUDE_CODE_WEB),
        sources = listOf(link("Claude Code Remote Control", DocLinks.CLAUDE_REMOTE_CONTROL)),
    )

    val codex = ChatHome(
        agentId = "codex",
        agent = "Codex",
        kept = "Cloud tasks: your ChatGPT account. Local sessions: only $BACKUP can bring them back; OpenAI does " +
            "not list them in your account.",
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
        kept = "Only $BACKUP can bring them back; Google shows no chat history in your account.",
        elsewhere = "None yet",
        note = JULES_LINE,
        open = link("Open Jules", DocLinks.JULES),
    )

    val all = listOf(claude, codex, antigravity)

    fun of(agentId: String): ChatHome? = all.firstOrNull { it.agentId == agentId }

    const val TABLE_HEADER = "Where else to open it"

    fun table(): DocBlock.Table = DocBlock.Table(listOf("Agent", TABLE_HEADER), all.map { row(it.agent, it.elsewhere) })
}
