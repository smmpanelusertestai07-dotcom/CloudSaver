package com.pocketide.rooms

import com.pocketide.agents.OfficialAgents
import com.pocketide.model.AgentInfo
import com.pocketide.model.AgentSurface
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The program a room runs as its agent's screen. */
internal enum class Engine {
    /** code-server with the agent's extension, opened full screen by the companion. */
    CODE_SERVER,

    /** Google's `agy` hub, which serves Antigravity's own web app. */
    AGY_HUB,
}

/** Where an agent's view lives in code-server, so the companion maximizes the right part. */
internal enum class ViewPlace(val word: String) { EDITOR("editor"), SIDEBAR("sidebar"), AUTO("auto") }

/** Everything that differs from one room to another. Paths are relative to the room's home. */
internal data class RoomProfile(
    val agentId: String,
    val name: String,
    val engine: Engine,
    /** Open VSX id of the agent's extension, for code-server rooms. */
    val extensionId: String? = null,
    /** The extension's command that shows the agent's view. */
    val openCommand: String? = null,
    val place: ViewPlace = ViewPlace.AUTO,
    /** View types of the agent's editor tab, so a tab restored after a reload is reused. */
    val viewTypes: List<String> = emptyList(),
    /** The extension's own settings, written into the room's code-server user settings. */
    val extensionSettings: Map<String, JsonElement> = emptyMap(),
    /**
     * Settings that make the agent ask before it runs anything, set while the room works on
     * someone else's code and taken out again (when still these values) on the owner's own.
     */
    val carefulSettings: Map<String, JsonElement> = emptyMap(),
    /** The extension's command that opens the agent with a prompt: (session id, prompt). */
    val promptCommand: String? = null,
    /** The user-level instruction files the agent reads; PocketIDE's rules go into each. */
    val instructionFiles: List<String> = emptyList(),
)

internal object RoomProfiles {
    const val CLAUDE = "claude"
    const val CODEX = "codex"
    const val ANTIGRAVITY = "antigravity"
    val OFFICIAL = listOf(CLAUDE, CODEX, ANTIGRAVITY)

    private val AGENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val COMMAND_ID = Regex("[A-Za-z0-9_.-]{1,200}")
    private val EXTENSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9-]*\\.[A-Za-z0-9][A-Za-z0-9._-]*")
    private val RELATIVE_FILE = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")

    fun isAgentId(agentId: String) = AGENT_ID.matches(agentId) && agentId != "." && agentId != ".."

    /** The room for [agentId]: built in for the official three, from [info] for a discovered agent. */
    fun of(agentId: String, info: AgentInfo?): RoomProfile? = when (agentId) {
        CLAUDE -> claude(info)
        CODEX -> codex(info)
        ANTIGRAVITY -> RoomProfile(
            agentId = ANTIGRAVITY,
            name = info?.displayName ?: "Antigravity",
            engine = Engine.AGY_HUB,
            instructionFiles = listOf(".gemini/GEMINI.md"),
        )
        else -> discovered(agentId, info)
    }

    /**
     * The command that opens the installed version, as the agent doctor found it in that
     * version's package.json, or the pinned one before the doctor has run.
     */
    private fun installedOpenCommand(info: AgentInfo?, pinned: String): String =
        info?.openCommand?.takeIf { COMMAND_ID.matches(it) } ?: pinned

    private fun claude(info: AgentInfo?): RoomProfile {
        // Opens in the primary editor and never splits; editor.open would split and lock a group.
        val open = installedOpenCommand(info, OfficialAgents.CLAUDE_OPEN)
        val pinned = open == OfficialAgents.CLAUDE_OPEN
        return RoomProfile(
            agentId = CLAUDE,
            name = info?.displayName ?: "Claude Code",
            engine = Engine.CODE_SERVER,
            extensionId = "anthropic.claude-code",
            openCommand = open,
            place = if (pinned || open.startsWith("claude-vscode.editor.")) ViewPlace.EDITOR else ViewPlace.AUTO,
            viewTypes = listOf("claudeVSCodePanel"),
            extensionSettings = mapOf(
                "claudeCode.useCtrlEnterToSend" to JsonPrimitive(true),
                "claudeCode.lockEditorGroups" to JsonPrimitive(false),
                "claudeCode.hideOnboarding" to JsonPrimitive(true),
            ),
            carefulSettings = mapOf("claudeCode.initialPermissionMode" to JsonPrimitive("default")),
            // Only the pinned command is known to take (session id, prompt).
            promptCommand = OfficialAgents.CLAUDE_OPEN.takeIf { pinned },
            instructionFiles = listOf(".claude/CLAUDE.md"),
        )
    }

    private fun codex(info: AgentInfo?): RoomProfile {
        // The sidebar command is safe to repeat on every reload; the panel command opens a new chat each time.
        val open = installedOpenCommand(info, OfficialAgents.CODEX_OPEN)
        return RoomProfile(
            agentId = CODEX,
            name = info?.displayName ?: "Codex",
            engine = Engine.CODE_SERVER,
            extensionId = "openai.chatgpt",
            openCommand = open,
            place = if (open == OfficialAgents.CODEX_OPEN) ViewPlace.SIDEBAR else ViewPlace.AUTO,
            extensionSettings = mapOf(
                "chatgpt.composerEnterBehavior" to JsonPrimitive("cmdAlways"),
                "chatgpt.openOnStartup" to JsonPrimitive(false),
                "workbench.secondarySideBar.defaultVisibility" to JsonPrimitive("maximized"),
            ),
            instructionFiles = listOf(".codex/AGENTS.md"),
        )
    }

    private fun discovered(agentId: String, info: AgentInfo?): RoomProfile? {
        if (info == null || !isAgentId(agentId) || info.surface != AgentSurface.CODE_SERVER_EXTENSION) return null
        val extension = info.extensionId?.takeIf { EXTENSION_ID.matches(it) } ?: return null
        val instructions = info.instructionsFile.trim().takeIf { RELATIVE_FILE.matches(it) && ".." !in it.split('/') }
        return RoomProfile(
            agentId = agentId,
            name = info.displayName,
            engine = Engine.CODE_SERVER,
            extensionId = extension.lowercase(),
            openCommand = info.openCommand?.takeIf { COMMAND_ID.matches(it) },
            place = ViewPlace.AUTO,
            instructionFiles = listOfNotNull(instructions),
        )
    }
}
