package com.pocketide.docs

/** Where an official agent's chats are kept besides this phone, and the company page that shows them. */
data class ChatHome(val agentId: String, val agent: String, val kept: String, val page: DocBlock.Link?)

/**
 * The one answer to "where are my chats saved?", per official agent, for Help, Privacy and Your
 * data, as each company offers it. PocketIDE's encrypted Drive backup keeps every agent's chats
 * either way: a new phone restores and resumes them from it.
 */
object ChatHomes {
    val claude = ChatHome(
        agentId = "claude",
        agent = "Claude Code",
        kept = "Your Claude account (Anthropic), while \"Also save Claude chats in your Claude account\" is on, " +
            "and PocketIDE's encrypted backup in your Google Drive.",
        page = link("Open Claude Code on the web", DocLinks.CLAUDE_CODE_WEB),
    )

    val codex = ChatHome(
        agentId = "codex",
        agent = "Codex",
        kept = "Your ChatGPT account for cloud tasks. Local sessions: PocketIDE's encrypted backup in your " +
            "Google Drive; OpenAI cannot keep them yet.",
        page = link("Open Codex on the web", DocLinks.CODEX_WEB),
    )

    val antigravity = ChatHome(
        agentId = "antigravity",
        agent = "Antigravity",
        kept = "PocketIDE's encrypted backup in your Google Drive. Google offers no cloud history for it yet.",
        page = null,
    )

    val all = listOf(claude, codex, antigravity)

    /** Google's cloud agent, which keeps its own tasks in the Google account. */
    const val JULES_LINE = "Jules, Google's cloud agent, keeps its tasks in your Google account."
    val jules = link("Open Jules", DocLinks.JULES)

    /** Codex in the cloud: its chat lives in ChatGPT, and the repository needs a cloud environment. */
    const val CODEX_CLOUD_LINE =
        "Run in the cloud keeps the chat in your ChatGPT account (needs a Codex cloud environment for this repository)."

    const val CLAUDE_ACCOUNT_LINE = "Also saved in your Claude account"

    fun of(agentId: String): ChatHome? = all.firstOrNull { it.agentId == agentId }

    fun summaryTable(): DocBlock.Table = table(listOf("Agent", "Where its chats are saved"), *all.map { row(it.agent, it.kept) }.toTypedArray())
}
