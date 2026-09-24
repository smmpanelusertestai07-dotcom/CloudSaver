package com.pocketide.projects

import com.pocketide.model.Project
import kotlinx.coroutines.flow.StateFlow

/** Something the owner can act on, in one plain sentence (the screens show [message] as it is). */
open class ProjectException(message: String) : Exception(message)

/** The repository is not reachable through PocketIDE's GitHub App; [installUrl] is where to add it. */
class RepoNotReachableException(val installUrl: String) :
    ProjectException("Add this repository to PocketIDE's GitHub App, then try again.")

/** A GitHub repository, by owner and name. */
data class RepoAddress(val owner: String, val repo: String) {
    companion object {
        private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
        private val REPO = Regex("[A-Za-z0-9._-]{1,100}")
        private val GITHUB = Regex("^(?:(?:https?|ssh|git)://)?(?:[^@/]+@)?(?:www\\.)?github\\.com[:/]", RegexOption.IGNORE_CASE)
        private val OTHER_HOST = Regex("^(?:[a-z][a-z0-9+.-]*://|[^@/\\s]+@[^:/\\s]+:)|^[^/\\s]+\\.[a-z]{2,}/", RegexOption.IGNORE_CASE)

        /**
         * Reads the forms people paste: `owner/repo`, `https://github.com/owner/repo`, with or
         * without `www.`, `.git`, a trailing slash, a query, a fragment or a deeper path, and
         * `git@github.com:owner/repo.git`. Null for other hosts and for names GitHub would refuse.
         */
        fun parse(text: String): RepoAddress? {
            var rest = text.trim()
            val github = GITHUB.find(rest)
            rest = when {
                github != null -> rest.substring(github.range.last + 1)
                OTHER_HOST.containsMatchIn(rest) -> return null
                else -> rest
            }
            rest = rest.substringBefore('#').substringBefore('?').trim('/')
            val parts = rest.split('/')
            if (parts.size < 2) return null
            val owner = parts[0]
            val repo = parts[1].removeSuffix(".git")
            if (!OWNER.matches(owner) || !REPO.matches(repo) || repo == "." || repo == "..") return null
            return RepoAddress(owner, repo)
        }
    }
}

/**
 * The owner's projects: one private GitHub repository each. The phone keeps a bare clone
 * (made on first open, not all at once); sessions add worktrees to it.
 */
interface Projects {
    val all: StateFlow<List<Project>>

    /** Creates a new private repository on GitHub and adds it. */
    suspend fun create(name: String, description: String): Project

    /**
     * Adds an existing repository the app's installation can reach. With [repo] empty, [owner]
     * may be any pasted address of the repository (see [RepoAddress.parse]).
     */
    suspend fun import(owner: String, repo: String): Project

    /** Clones (first time) or fetches. Asks the data rules first for a big clone on mobile data. */
    suspend fun ensureCloned(projectId: String)

    suspend fun fetch(projectId: String)

    /** Removes the project from this app (the GitHub repository is never deleted). */
    suspend fun remove(projectId: String)

    /** Records agent work or a commit (drives the 30-day cache rule). */
    fun touched(projectId: String)

    /** Records agent work or a commit that happened at [at] (UTC milliseconds). */
    fun touched(projectId: String, at: Long) = touched(projectId)

    /**
     * Takes projects from the vault index (a restore on a new phone, or another phone's changes):
     * unknown ones are added, known ones updated. Whether a clone exists is always this phone's own.
     */
    suspend fun adopt(projects: List<Project>) = Unit
}
