package com.pocketide.sessions

import com.pocketide.git.GitGate
import com.pocketide.github.GitHubAuth
import com.pocketide.linux.Computer
import com.pocketide.media.MediaLibrary
import com.pocketide.projects.Projects
import com.pocketide.rooms.Rooms
import com.pocketide.secrets.ProjectSecrets
import com.pocketide.sync.SyncEngine

/** What sessions use from other modules, resolved when used (never while the app starts). */
internal interface SessionEnv {
    val projects: Projects
    val computer: Computer
    val git: GitGate
    val gitHubAuth: GitHubAuth
    val secrets: ProjectSecrets
    val rooms: Rooms
    val sync: SyncEngine
    val media: MediaLibrary

    /** This install's id, recorded on the sessions it creates. */
    val deviceId: String

    /** The agent's name as the owner knows it ("Claude Code"). */
    fun agentName(agentId: String): String
}
