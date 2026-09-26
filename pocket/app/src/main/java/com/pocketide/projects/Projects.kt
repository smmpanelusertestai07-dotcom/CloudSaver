package com.pocketide.projects

import com.pocketide.model.Project
import com.pocketide.vault.VaultKeyFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Something the owner can act on, in one plain sentence (the screens show [message] as it is). */
open class ProjectException(message: String) : Exception(message)

/**
 * The repository is not reachable through PocketIDE's GitHub App; [installUrl] is where to add it
 * (an app cannot add a repository to its own installation). [address] is the repository to try
 * again when the owner comes back.
 */
class RepoNotReachableException(val installUrl: String, val address: RepoAddress? = null) :
    ProjectException("Add this repository to PocketIDE's GitHub App, then try again.")

/**
 * Whose code a project is. For someone else's (a fork or a third-party repository) agents start
 * in ask-before-running mode and the browser tools stay off until allowed: files, issues and
 * READMEs there may carry instructions written to mislead an agent.
 */
@Serializable
enum class ProjectTrust { YOURS, SOMEONE_ELSES }

/**
 * The repository that holds half of the vault key. It is never a project: a clone would put that
 * half, and every older one in its history, where each room and agent can read it.
 */
fun isVaultKeyring(repoName: String): Boolean = repoName.equals(VaultKeyFiles.KEYRING_REPO, ignoreCase = true)

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
// The module's one contract, as Sessions is: splitting it would only scatter the callers.
@Suppress("TooManyFunctions")
interface Projects {
    /** What is known so far: empty until `vault/projects.json` is read, so a job in a fresh process uses [loaded]. */
    val all: StateFlow<List<Project>>

    /** The projects once this phone's list is read from the disk. Background jobs read this, not [all]. */
    suspend fun loaded(): List<Project> = all.value

    /** Creates a new private repository on GitHub and adds it. */
    suspend fun create(name: String, description: String): Project

    /**
     * Adds an existing repository the app's installation can reach. With [repo] empty, [owner]
     * may be any pasted address of the repository (see [RepoAddress.parse]).
     */
    suspend fun import(owner: String, repo: String): Project

    /**
     * Clones (first time) or fetches. Asks the data rules first: a big clone on mobile data throws
     * [com.pocketide.sync.NeedsMobileData], so the screen can ask the owner with its size.
     */
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

    /**
     * "Delete everything" removed the phone's data: the projects are forgotten in memory too, so
     * nothing writes the old list back or sends it to the next vault.
     */
    suspend fun forgetEverything() = Unit

    /**
     * Whose code each project is, by project id. Projects made in the app and the owner's own
     * repositories are [ProjectTrust.YOURS]; anything else, and anything unknown, is not.
     */
    val trust: StateFlow<Map<String, ProjectTrust>> get() = MutableStateFlow(emptyMap())

    fun trustOf(projectId: String): ProjectTrust = trust.value[projectId] ?: ProjectTrust.SOMEONE_ELSES

    /** [trustOf] once this phone's list is read: unread, every project would be someone else's. Background jobs ask this. */
    suspend fun loadedTrustOf(projectId: String): ProjectTrust {
        loaded()
        return trustOf(projectId)
    }

    /** The owner's own answer to "Is this your code?", which replaces the automatic one. */
    suspend fun setTrust(projectId: String, trust: ProjectTrust) = Unit
}
