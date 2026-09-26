package com.pocketide.ui.manage

import com.pocketide.core.Settings
import com.pocketide.docs.ChatHomes
import com.pocketide.model.SessionRecord

/**
 * Where an official agent's chats are saved. Per agent ([all]), as the owner's settings make it
 * true now: Help's answer ([ChatHomes]) with Claude's following the "Also save Claude chats"
 * switch. Per session ([forSession]), from what the session itself records, since the switch and
 * the backup choice can change after the chat ran.
 */
internal object ChatPlaces {
    /**
     * One answer. [page] shows what the company keeps (null when that page does not hold the
     * chat); [inOwnApp] lets the company's app open it (the Claude app takes claude.ai links),
     * while other pages open in the browser.
     */
    data class Place(
        val agentId: String,
        val agent: String,
        val kept: String,
        val note: String?,
        val pageLabel: String?,
        val page: String?,
        val inOwnApp: Boolean,
    )

    const val CLAUDE_ON = "Your Claude account (Anthropic) and ${ChatHomes.BACKUP}."

    const val CLAUDE_OFF = "Only ${ChatHomes.BACKUP} can bring new sessions back. Turn on Settings → Agents → " +
        "\"${ChatHomes.CLAUDE_SWITCH}\" to open new sessions in your Claude account too."

    const val SESSION_BACKED_UP = "${ChatHomes.BACKUP} has it, so a new phone brings it back."

    const val SESSION_NOT_BACKED_UP =
        "Not backed up: \"Don't back up this chat\" is on, so PocketIDE keeps it on this phone only and a new phone cannot bring it back."

    const val SESSION_IN_CLAUDE =
        "You can open it in your Claude account too: it ran while \"${ChatHomes.CLAUDE_SWITCH}\" was on."

    const val SESSION_NOT_IN_CLAUDE =
        "Your Claude account does not list it: it has not run while \"${ChatHomes.CLAUDE_SWITCH}\" was on."

    const val SESSION_CODEX = "OpenAI does not list sessions run on this phone in your ChatGPT account."

    const val SESSION_ANTIGRAVITY = "Google shows no chat history for it in your account."

    fun all(settings: Settings): List<Place> = ChatHomes.all.map { agentPlace(it.agentId, settings) }

    private fun agentPlace(agentId: String, settings: Settings): Place {
        val home = checkNotNull(ChatHomes.of(agentId))
        val claude = agentId == ChatHomes.claude.agentId
        val off = claude && !settings.claudeChatsInAccount
        return Place(
            agentId = agentId,
            agent = home.agent,
            kept = when {
                off -> CLAUDE_OFF
                claude -> CLAUDE_ON
                else -> home.kept
            },
            note = home.note.takeUnless { off },
            pageLabel = home.open?.label,
            page = home.open?.url,
            inOwnApp = claude,
        )
    }

    /**
     * "Where this chat is saved" for one session of an official agent, or null for another agent.
     * Only Claude's page can show a chat PocketIDE ran, and only one that ran with Remote Control:
     * Codex on the web lists cloud tasks, and Jules is a different product.
     */
    fun forSession(session: SessionRecord): Place? {
        val home = ChatHomes.of(session.agentId) ?: return null
        val backup = if (session.backUp) SESSION_BACKED_UP else SESSION_NOT_BACKED_UP
        val inClaude = inClaudeAccount(session)
        val company = when {
            inClaude -> SESSION_IN_CLAUDE
            home == ChatHomes.claude -> SESSION_NOT_IN_CLAUDE
            home == ChatHomes.codex -> SESSION_CODEX
            else -> SESSION_ANTIGRAVITY
        }
        return Place(
            agentId = session.agentId,
            agent = home.agent,
            kept = "$backup $company",
            note = home.note.takeIf { inClaude },
            pageLabel = home.open?.label.takeIf { inClaude },
            page = home.open?.url.takeIf { inClaude },
            inOwnApp = inClaude,
        )
    }

    /** The line on a session's details when its chat is also kept in the company's account. */
    fun sessionLine(session: SessionRecord): String? = ChatHomes.CLAUDE_ACCOUNT_LINE.takeIf { inClaudeAccount(session) }

    private fun inClaudeAccount(session: SessionRecord) = session.agentId == ChatHomes.claude.agentId && session.claudeAccount
}
