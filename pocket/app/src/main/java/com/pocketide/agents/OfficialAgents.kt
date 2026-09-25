package com.pocketide.agents

import com.pocketide.model.AgentInfo
import com.pocketide.model.AgentSurface

/**
 * The three built-in agents, labelled Official. Command ids were read from each publisher's own
 * package.json on Open VSX (Claude Code 2.1.281, Codex 26.908.40401 stable, 24 Sep 2026).
 */
internal object OfficialAgents {
    const val CLAUDE = "claude"
    const val CODEX = "codex"
    const val ANTIGRAVITY = "antigravity"

    /** Opens the chat as a tab in the main editor area; it also takes a prompt. */
    const val CLAUDE_OPEN = "claude-vscode.primaryEditor.open"

    /** The secondary side bar, which the room maximises. */
    const val CODEX_OPEN = "chatgpt.openSidebar"

    val claude = AgentInfo(
        id = CLAUDE,
        displayName = "Claude Code",
        publisher = "Anthropic",
        extensionId = "anthropic.claude-code",
        surface = AgentSurface.CODE_SERVER_EXTENSION,
        official = true,
        verifiedPublisher = true,
        dataGoesTo = "Your prompts, and the code and files Claude Code reads, go to Anthropic.",
        signIn = "Sign in with a Claude account on a Pro, Max, Team or Enterprise plan, or with an Anthropic Console account.",
        instructionsFile = "~/.claude/CLAUDE.md",
        // Opens the chat as a tab in the main editor area without splitting it
        // (claude-vscode.editor.open splits and locks a new group when tabs are open).
        openCommand = CLAUDE_OPEN,
    )

    val codex = AgentInfo(
        id = CODEX,
        displayName = "Codex",
        publisher = "OpenAI",
        extensionId = "openai.chatgpt",
        surface = AgentSurface.CODE_SERVER_EXTENSION,
        official = true,
        verifiedPublisher = true,
        dataGoesTo = "Your prompts, and the code and files Codex reads, go to OpenAI.",
        signIn = "Sign in with your ChatGPT account, on a plan that includes Codex.",
        instructionsFile = "~/.codex/AGENTS.md",
        // The secondary side bar, maximised by the room; safe to repeat on every reload, while
        // chatgpt.newCodexPanel would open a new chat each time.
        openCommand = CODEX_OPEN,
    )

    /** Runs Google's agy hub in its own screen. Its Open VSX extension is never installed (it fetches agy by itself). */
    val antigravity = AgentInfo(
        id = ANTIGRAVITY,
        displayName = "Antigravity",
        publisher = "Google",
        extensionId = null,
        surface = AgentSurface.NATIVE_HUB,
        official = true,
        verifiedPublisher = true,
        dataGoesTo = "Your prompts, and the code and files Antigravity reads, go to Google.",
        signIn = "Sign in with your Google account.",
        instructionsFile = "~/.gemini/GEMINI.md",
    )

    val all = listOf(claude, codex, antigravity)

    /** The publisher namespace each official extension must come from (Open VSX ignores case). */
    val namespaces = mapOf(CLAUDE to "anthropic", CODEX to "openai")

    /**
     * Official identifiers that discovery must never offer as a new agent, including the
     * Antigravity extension: installed in a room it would download agy itself, past the pin
     * and the doctor, with its telemetry on by default.
     */
    val extensionIds = setOf("anthropic.claude-code", "openai.chatgpt", "google.google-antigravity")

    fun find(agentId: String): AgentInfo? = all.firstOrNull { it.id == agentId }

    /**
     * The commands that open an official agent's screen, best first: the pinned one, then the
     * one it had before. The doctor takes the first one the installed version declares and
     * otherwise the view that version contributes, so a renamed command costs a slightly
     * different layout instead of every update until a new PocketIDE.
     */
    fun openCommands(agentId: String): List<String> = when (agentId) {
        CLAUDE -> listOf(CLAUDE_OPEN, "claude-vscode.editor.open")
        CODEX -> listOf(CODEX_OPEN)
        else -> emptyList()
    }
}
