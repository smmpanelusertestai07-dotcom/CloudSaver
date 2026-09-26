package com.pocketide.projects

import com.pocketide.git.GitGate
import com.pocketide.github.GitHubApi
import com.pocketide.github.GitHubAuth
import com.pocketide.sync.DataBudget

/** What the project registry uses from other modules, resolved when used (never at start-up). */
internal interface ProjectEnv {
    val gitHub: GitHubApi
    val gitHubAuth: GitHubAuth
    val git: GitGate
    val dataBudget: DataBudget

    /** The sessions' side of a project, consulted before a project is removed. */
    val work: ProjectWork?

    /** The keyring's Half G was cloned onto the phone, within Linux's reach: the vault key changes. */
    suspend fun keyringCloned()
}

/** What sessions hold of a project on this phone, so removing a project never loses work. */
internal interface ProjectWork {
    /** One plain sentence when the project has work that is not on GitHub yet; null when removing is safe. */
    suspend fun unsaved(projectId: String): String?

    /** Deletes this phone's worktrees of the project. The chats and their media stay. */
    suspend fun release(projectId: String)
}
