package com.pocketide.model

import kotlinx.serialization.Serializable

/** How an agent is shown full screen. */
@Serializable
enum class AgentSurface {
    /** The agent's official VS Code extension, in code-server, maximised to its own view. */
    CODE_SERVER_EXTENSION,

    /** The agent's own local web app (Antigravity's hub), in a WebView of its own. */
    NATIVE_HUB,
}

/**
 * One agent PocketIDE can run. The three official ones are built in; others come from Open VSX
 * discovery (verified publisher, AI/Chat category, arm64, popular, not brand-new) and are added
 * only by the owner's tap.
 */
@Serializable
data class AgentInfo(
    /** "claude", "codex", "antigravity", or "<namespace>.<name>" for a discovered agent. */
    val id: String,
    val displayName: String,
    val publisher: String,
    /** Open VSX id "<namespace>.<name>", or null when the agent is not an extension. */
    val extensionId: String?,
    val surface: AgentSurface,
    /** Only the built-in three are Official. */
    val official: Boolean,
    val verifiedPublisher: Boolean,
    /** One plain sentence: which company's service receives prompts and code. */
    val dataGoesTo: String,
    /** One plain sentence: how the owner signs in. */
    val signIn: String,
    /** The user-level instructions file the agent reads, e.g. "CLAUDE.md". */
    val instructionsFile: String,
    /** The command that opens the agent's own view, for [AgentSurface.CODE_SERVER_EXTENSION]. */
    val openCommand: String? = null,
    val version: String? = null,
    val downloads: Long? = null,
    /** Stronger note for community publishers: they are not the model maker. */
    val communityNote: String? = null,
)

/** A candidate found on Open VSX that passed every check and waits for the owner's tap. */
@Serializable
data class AgentCandidate(
    val extensionId: String,
    val displayName: String,
    val publisher: String,
    val version: String,
    val downloads: Long,
    val firstPublishedAt: Long,
    val categories: List<String>,
    val description: String,
    val iconUrl: String? = null,
    val foundAt: Long,
)
