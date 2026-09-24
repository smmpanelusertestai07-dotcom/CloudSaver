package com.pocketide.rooms

import com.pocketide.bridge.PhoneBridge
import com.pocketide.bridge.PortBridge
import com.pocketide.builds.BuildTemplate
import com.pocketide.core.AppDirs
import com.pocketide.github.PullRequest
import com.pocketide.github.WorkflowRun
import com.pocketide.linux.Computer
import com.pocketide.media.MediaItem
import com.pocketide.model.AgentInfo
import com.pocketide.model.Decision
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.sessions.PutOnMainResult
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** What rooms use from the rest of the app, resolved when used (never while the app starts). */
internal interface RoomsEnv {
    val dirs: AppDirs
    val scope: CoroutineScope
    val computer: Computer
    val portBridge: PortBridge
    val phoneBridge: PhoneBridge
    val assets: RoomAssets

    fun now(): Long

    fun agentInfo(agentId: String): AgentInfo?

    /** Every agent the owner has (the official three and added ones). */
    fun agents(): List<String>

    fun sessions(): List<SessionRecord>
    fun activeSession(agentId: String): String?
    fun project(projectId: String): Project?
    suspend fun variables(projectId: String): Map<String, String>

    fun canStartAgent(agentId: String): Decision
    fun canStartHeavyWork(what: String): Decision
    fun allowDownload(bytes: Long, kind: String): Decision
    fun recordDownload(bytes: Long, kind: String)

    /** Installs the agent's extension (the agents module); throws with a reason when it cannot. */
    suspend fun ensureInstalled(agentId: String)

    /** Editor and terminal text size in code-server, from Android's font scale. */
    fun fontSize(): Int

    /** Node's heap limit for code-server, sized to the phone's memory. */
    fun heapMegabytes(): Int

    /** GIT_AUTHOR_* and GIT_COMMITTER_* for the owner, so agents' commits carry their name. */
    suspend fun gitIdentity(): Map<String, String>

    /** Shows an agent's notification ("needs you", "finished"), when the owner is not looking. */
    fun notify(agentId: String, sessionId: String?, title: String, text: String)

    fun phone(): PhoneSnapshot
    fun guard(): Guard
    fun maxAgents(): Int
    fun ownerPresent(): Boolean
    suspend fun autosave(sessionId: String): String?
    suspend fun putOnMain(sessionId: String): PutOnMainResult
    fun templates(): List<BuildTemplate>
    suspend fun runBuild(projectId: String, templateId: String, ref: String): Long?
    suspend fun recentRuns(projectId: String): List<WorkflowRun>
    suspend fun collect(projectId: String, sessionId: String, runId: Long): Int
    suspend fun openPullRequest(project: Project, head: String, title: String, body: String): PullRequest
    suspend fun addMedia(sessionId: String, file: File, name: String): MediaItem
}
