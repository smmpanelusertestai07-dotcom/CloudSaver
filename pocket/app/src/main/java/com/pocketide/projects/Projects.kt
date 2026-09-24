package com.pocketide.projects

import com.pocketide.model.Project
import kotlinx.coroutines.flow.StateFlow

/**
 * The owner's projects: one private GitHub repository each. The phone keeps a bare clone
 * (made on first open, not all at once); sessions add worktrees to it.
 */
interface Projects {
    val all: StateFlow<List<Project>>

    /** Creates a new private repository on GitHub and adds it. */
    suspend fun create(name: String, description: String): Project

    /** Adds an existing repository the app's installation can reach. */
    suspend fun import(owner: String, repo: String): Project

    /** Clones (first time) or fetches. Asks the data rules first for a big clone on mobile data. */
    suspend fun ensureCloned(projectId: String)

    suspend fun fetch(projectId: String)

    /** Removes the project from this app (the GitHub repository is never deleted). */
    suspend fun remove(projectId: String)

    /** Records agent work or a commit (drives the 30-day cache rule). */
    fun touched(projectId: String)
}
