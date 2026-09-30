package com.pocketide.cloudshell

import com.pocketide.agents.Agent

/**
 * What PocketIDE opens in Cloud Shell, each in a Chrome tab: an agent full screen, Cloud Shell's
 * terminal, or its editor, where the home folder's files show.
 */
enum class IdePlace(val label: String) {
    CLAUDE(Agent.CLAUDE.displayName),
    CODEX(Agent.CODEX.displayName),
    ANTIGRAVITY(Agent.ANTIGRAVITY.displayName),
    TERMINAL("Terminal"),
    FILES("Files"),
    ;

    /** The agent this place shows, or null for Cloud Shell's own pages. */
    val agent: Agent?
        get() = when (this) {
            CLAUDE -> Agent.CLAUDE
            CODEX -> Agent.CODEX
            ANTIGRAVITY -> Agent.ANTIGRAVITY
            TERMINAL, FILES -> null
        }

    /** Its address, opened with the Google account [account]. */
    fun url(account: String): String = when (this) {
        TERMINAL -> CloudShell.terminal(account)
        FILES -> CloudShell.editor(account)
        CLAUDE, CODEX, ANTIGRAVITY -> CloudShell.screen(checkNotNull(agent), account)
    }

    companion object {
        fun of(agent: Agent): IdePlace = entries.first { it.agent == agent }

        /** The place a name in an intent stands for; anything else is null. */
        fun named(name: String?): IdePlace? = entries.firstOrNull { it.name == name }
    }
}
