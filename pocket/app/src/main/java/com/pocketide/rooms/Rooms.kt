package com.pocketide.rooms

import kotlinx.coroutines.flow.StateFlow

sealed interface RoomState {
    data object Stopped : RoomState
    data class Starting(val step: String) : RoomState
    /** [url] is loaded by the agent screen's WebView; it goes through the port bridge. */
    data class Running(val url: String, val sessionId: String?, val memoryBytes: Long) : RoomState
    data class Failed(val why: String) : RoomState
}

/** A shell in a session's room, for the `>_` tab. [url] serves the terminal page. */
data class TerminalHandle(val url: String, val sessionId: String)

/**
 * Each agent runs in its own room: a separate proot session that binds only its own home,
 * its own sessions' worktrees, the shared bare repos and shared tools. Other rooms' homes and
 * worktrees are not bound, so they do not exist from its point of view.
 *
 * Claude and Codex rooms run code-server (the hidden engine) with the official extension,
 * opened full screen; the Antigravity room runs Google's `agy` hub and nothing else.
 */
interface Rooms {
    /** Keyed by agent id. */
    val states: StateFlow<Map<String, RoomState>>

    /** Opens (starting if needed) the agent's room on this session's worktree. */
    suspend fun open(agentId: String, sessionId: String): RoomState

    suspend fun stop(agentId: String)

    suspend fun stopAll()

    /** A shell in the room of this session, in its worktree. */
    suspend fun terminal(sessionId: String): TerminalHandle

    /** Writes each room's user-level instructions, MCP registration and phone-friendly settings. */
    suspend fun configure(agentId: String)

    /** Removes a room completely (used when a discovered agent is removed). */
    suspend fun delete(agentId: String)
}
