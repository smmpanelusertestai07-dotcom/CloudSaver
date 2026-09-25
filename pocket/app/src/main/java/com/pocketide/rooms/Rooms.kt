package com.pocketide.rooms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RoomState {
    data object Stopped : RoomState
    data class Starting(val step: String) : RoomState
    /** [url] is loaded by the agent screen's WebView; it goes through the port bridge. */
    data class Running(val url: String, val sessionId: String?, val memoryBytes: Long) : RoomState
    data class Failed(val why: String) : RoomState
}

/** A shell in a session's room, for the `>_` tab. [url] serves the terminal page. */
data class TerminalHandle(val url: String, val sessionId: String)

/** Why a room stopped, for the banner that says so ("Nothing was lost"). */
enum class StopReason {
    /** The owner stopped it (Stop, Stop everything). */
    OWNER,

    /** Nothing happened in it for the idle time. */
    IDLE,

    /** The phone's limits (battery, heat, memory) or a safe stop. */
    LIMITS,

    /** Its engine ended by itself (Android, a crash, or the engine's own exit). */
    ENDED,

    /** It restarted for another session or project. */
    SWITCHED,
}

/** The last stop of a room: why, when (UTC epoch ms), and one sentence for the owner. */
data class RoomStop(val reason: StopReason, val at: Long, val message: String)

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

    /** Dev-server ports agents announced (MCP `preview_port`), keyed by session id, for Preview. */
    val previewPorts: StateFlow<Map<String, List<Int>>>

    /** Why each room last stopped, keyed by agent id. Cleared when the room starts again. */
    val stops: StateFlow<Map<String, RoomStop>> get() = NO_STOPS

    /**
     * When each running room goes to sleep if nothing happens in it (UTC epoch ms), keyed by agent
     * id; the earliest is the most idle room. Rooms that never sleep are left out.
     */
    val sleepsAt: StateFlow<Map<String, Long>> get() = NO_TIMES

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

    /** The owner is using the agent's screen (a tap, typing): the room stays awake. */
    fun touch(agentId: String) = Unit

    /**
     * Starts the room's engine again on the session it shows (the fix-it ladder's second step).
     * Files, sign-ins and chats are kept.
     */
    suspend fun restart(agentId: String): RoomState = states.value[agentId] ?: RoomState.Stopped

    /** The room's last output lines, with secrets removed, for diagnostics. */
    fun recentOutput(agentId: String): List<String> = emptyList()

    /**
     * Linux processes of each running room (its engine, everything under it, and its terminals),
     * keyed by agent id, for Android's phantom-process budget. Measured about once a minute.
     */
    val processes: StateFlow<Map<String, Int>> get() = NO_COUNTS

    /**
     * [open], then hands [firstPrompt] to the agent's composer (not sent), when the agent can take
     * one ([takesPrompts]): a hand-off gives the new agent its note this way.
     */
    suspend fun open(agentId: String, sessionId: String, firstPrompt: String?): RoomState = open(agentId, sessionId)

    /** True when [open] can hand this agent a first prompt; otherwise the screen offers to copy it. */
    fun takesPrompts(agentId: String): Boolean = false

    /**
     * Runs [argv] to its end in [agentId]'s room (a scheduled task's agent CLI), as the room's own
     * programs run: the room's binds and environment (the project's Variables, reserved names left
     * out), through the room's launcher, with the phone bridge up so PocketIDE's tools answer. The
     * room counts as busy meanwhile, so it is not put to sleep under the run. [programEnv] is the
     * program's own settings. Returns the exit code; throws with a plain sentence when it cannot run.
     */
    suspend fun runHeadless(
        agentId: String,
        projectId: String,
        argv: List<String>,
        workDir: String,
        programEnv: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int = throw IllegalStateException("The agent's room cannot run programs yet.")

    /**
     * Whether [agentId] is signed in in its room: its sign-in file is there. The file is only
     * looked at, never read. Null for an agent whose sign-in PocketIDE cannot see.
     */
    suspend fun signedIn(agentId: String): Boolean? = null

    /**
     * For "Delete everything": stops every room, then runs each signed-in agent's own sign-out in
     * its room, so the vendor ends that sign-in too. Returns one sentence per agent it tried.
     */
    suspend fun signOutAll(): List<String> = emptyList()

    /**
     * Settings that can run code (hooks, MCP servers, permission rules, environment variables)
     * that agents added in their rooms. Every room start takes them out of the agent's files
     * again; each waits here until the owner keeps it ([keepConfigChange]) or lets it go.
     */
    val configChanges: StateFlow<List<ConfigChange>> get() = NO_CHANGES

    /** The ones the owner kept: written into the agent's files at every room start. */
    val keptConfig: StateFlow<List<ConfigChange>> get() = NO_CHANGES

    /** Keeps a waiting [change] from now on, and writes it into its room at once. */
    suspend fun keepConfigChange(change: ConfigChange) = Unit

    /** Lets a waiting [change] go; it is already out of its room. */
    suspend fun dropConfigChange(change: ConfigChange) = Unit

    /** Stops keeping [change], and takes it out of its room at once. */
    suspend fun stopKeepingConfigChange(change: ConfigChange) = Unit
}

private val NO_CHANGES: StateFlow<List<ConfigChange>> = MutableStateFlow<List<ConfigChange>>(emptyList()).asStateFlow()
private val NO_STOPS: StateFlow<Map<String, RoomStop>> = MutableStateFlow<Map<String, RoomStop>>(emptyMap()).asStateFlow()
private val NO_TIMES: StateFlow<Map<String, Long>> = MutableStateFlow<Map<String, Long>>(emptyMap()).asStateFlow()
private val NO_COUNTS: StateFlow<Map<String, Int>> = MutableStateFlow<Map<String, Int>>(emptyMap()).asStateFlow()
