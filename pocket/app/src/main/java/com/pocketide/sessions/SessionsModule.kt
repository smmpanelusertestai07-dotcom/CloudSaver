package com.pocketide.sessions

import com.pocketide.AppGraph
import com.pocketide.core.Device
import com.pocketide.git.GitGate
import com.pocketide.github.GitHubAuth
import com.pocketide.linux.Computer
import com.pocketide.media.MediaLibrary
import com.pocketide.projects.Projects
import com.pocketide.rooms.Rooms
import com.pocketide.secrets.ProjectSecrets
import com.pocketide.sync.SyncEngine
import kotlinx.coroutines.Dispatchers

fun createSessions(graph: AppGraph): Sessions = SessionManager(
    env = GraphSessionEnv(graph),
    dirs = graph.dirs,
    clock = graph.clock,
    scope = graph.scope,
    io = Dispatchers.IO,
)

/** Other modules are looked up when used, so creating this module never creates theirs. */
private class GraphSessionEnv(private val graph: AppGraph) : SessionEnv {
    override val projects: Projects get() = graph.projects
    override val computer: Computer get() = graph.computer
    override val git: GitGate get() = graph.git
    override val gitHubAuth: GitHubAuth get() = graph.gitHubAuth
    override val secrets: ProjectSecrets get() = graph.secrets
    override val rooms: Rooms get() = graph.rooms
    override val sync: SyncEngine get() = graph.sync
    override val media: MediaLibrary get() = graph.media
    override val deviceId: String by lazy { Device.id(graph.context) }

    override fun agentName(agentId: String): String = graph.agents.find(agentId)?.displayName ?: agentId
}
