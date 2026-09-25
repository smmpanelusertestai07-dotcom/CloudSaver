package com.pocketide.ui.manage

import com.pocketide.core.Settings
import com.pocketide.docs.ChatHomes

/**
 * Where an official agent's chats are saved, as the owner's settings make it true now: Help's
 * answer ([ChatHomes]) with Claude's following the "Also save Claude chats" switch.
 */
internal object ChatPlaces {
    /**
     * One agent's answer. [page] shows what the company keeps; [inOwnApp] lets the company's app
     * open it (the Claude app takes claude.ai links), while other pages open in the browser.
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

    const val CLAUDE_OFF = "${ChatHomes.BACKUP} only. Turn on Settings → Agents → \"${ChatHomes.CLAUDE_SWITCH}\" " +
        "to keep new sessions in your Claude account too."

    fun all(settings: Settings): List<Place> = ChatHomes.all.mapNotNull { of(it.agentId, settings) }

    fun of(agentId: String, settings: Settings): Place? {
        val home = ChatHomes.of(agentId) ?: return null
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

    /** The line on a session's details when its chat is also kept in the company's account. */
    fun sessionLine(agentId: String, settings: Settings): String? =
        ChatHomes.CLAUDE_ACCOUNT_LINE.takeIf { agentId == ChatHomes.claude.agentId && settings.claudeChatsInAccount }
}
