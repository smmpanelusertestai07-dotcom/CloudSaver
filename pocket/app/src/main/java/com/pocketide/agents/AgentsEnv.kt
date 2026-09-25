package com.pocketide.agents

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.model.AgentCandidate
import com.pocketide.model.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * What the agents module needs from the rest of the app. The app wires it to the graph; tests
 * give it a temporary folder, a local web server and a pretend computer.
 */
internal interface AgentsEnv {
    val dirs: AppDirs
    val clock: Clock
    val scope: CoroutineScope

    /** For registry answers and manifests. */
    val http: OkHttpClient

    /** For packages (longer read timeouts). */
    val downloads: OkHttpClient

    /** Settings → "Only official agents". */
    val onlyOfficial: StateFlow<Boolean>

    /** Null when programs can run inside Linux now; otherwise what the owner should do. */
    fun computerProblem(): String?

    /** The VS Code version the installed code-server is built on, or null when none is installed. */
    fun vscodeVersion(): SemVer?

    /**
     * Runs [argv] inside [agentId]'s room (its home is /root, with the room's own binds), to
     * completion; returns the exit code.
     */
    suspend fun runInRoom(agentId: String, argv: List<String>, onLine: (String) -> Unit = {}): Int

    /** True while the room is starting or running: its files are not swapped under it. */
    fun roomInUse(agentId: String): Boolean

    /** Writes the room's settings, rules and companion again (after an install). */
    suspend fun configureRoom(agentId: String)

    /**
     * Before a room is deleted: stops it, pushes its sessions' work to GitHub and uploads its
     * chats to Drive ([AgentRemoval]). Returns what is still only on this phone, in plain
     * sentences; empty when deleting the room loses nothing.
     */
    suspend fun saveBeforeRemoving(agentId: String): List<String>

    /** Stops and deletes a room with everything in its home and its sessions' work folders. */
    suspend fun deleteRoom(agentId: String)

    /** The data rules for a package of [bytes]: big downloads wait for Wi-Fi by default. */
    fun allowDownload(bytes: Long): Decision

    fun recordDownload(bytes: Long)

    /** Null when the phone has the memory an agent needs; otherwise why not. */
    fun memoryProblem(): String?

    /** "New agents on Open VSX" notification for candidates the owner has not been told about. */
    fun announce(candidates: List<AgentCandidate>)
}
