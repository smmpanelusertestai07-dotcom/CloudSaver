package com.pocketide.git

import org.eclipse.jgit.lib.Repository
import java.io.File

enum class FindingKind { SECRET, AI_DATA, TOO_LARGE, VARIABLE_OR_SECRET_VALUE }

data class Finding(val kind: FindingKind, val path: String, val commit: String, val detail: String)

/**
 * Why a push waits even though nothing in it must stay off GitHub:
 * - [BUILD_OUTPUT]: an APK, AAB or other build output. Builds are kept in Media, so the commit
 *   has to drop it; this hold cannot be approved.
 * - [WORKFLOW_CHANGE]: GitHub Actions code the session added or changed. An agent that can
 *   change a workflow can make it print the project's Secrets, so the owner reads the diff and
 *   approves exactly that content with [GitGate.approveWorkflowChange].
 */
enum class HoldKind { BUILD_OUTPUT, WORKFLOW_CHANGE }

data class Hold(
    val kind: HoldKind,
    val path: String,
    val commit: String,
    val detail: String,
    /** For a workflow change: what [GitGate.approveWorkflowChange] takes once the owner saw [diff]. */
    val approvalKey: String? = null,
    /** For a workflow change: the unified diff from the version GitHub has to the branch's. */
    val diff: String = "",
)

/** [ok] only when there are no [findings] and no [holds]. */
data class Verdict(
    val ok: Boolean,
    val findings: List<Finding>,
    val commitsScanned: Int,
    val holds: List<Hold> = emptyList(),
)

sealed interface PushResult {
    data object Pushed : PushResult
    data class Blocked(val verdict: Verdict) : PushResult
    data class Rejected(val why: String) : PushResult
    data class Failed(val why: String) : PushResult
}

/** A git step that could not be done. [message] is a plain sentence the owner can act on. */
class GitGateException(override val message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Every network git operation happens on the Android side with JGit, so the GitHub token never
 * enters Linux. Before any push, the check-post scans every new commit for secrets, AI data
 * (.claude, .codex, .gemini, transcripts), the project's Variables and Secrets, and files over
 * 100 MB; one finding blocks the push. Build outputs and unapproved workflow changes hold it
 * ([Hold]).
 *
 * The gate's failures are [GitGateException]s, except that [push] and [deleteRemoteBranch]
 * report them as [PushResult.Failed].
 */
interface GitGate {
    /** Bare clone into [bareRepo] with remote-tracking refs. */
    suspend fun clone(cloneUrl: String, bareRepo: File, token: String)

    suspend fun fetch(bareRepo: File, token: String)

    /** Commits on [branch] not yet on the remote, checked. [knownValues] are Variables/Secrets. */
    suspend fun checkPost(bareRepo: File, branch: String, knownValues: List<String> = emptyList()): Verdict

    /** Check-post, then push [branch] to the same name on origin. */
    suspend fun push(bareRepo: File, branch: String, token: String, knownValues: List<String> = emptyList()): PushResult

    /** Commits and changed files on [branch] compared with [base], for session details. */
    suspend fun stats(bareRepo: File, branch: String, base: String): Pair<Int, Int>

    /**
     * Deletes a session branch (`pocket/…`) on GitHub, for example after "Put on main". Other
     * branches are never deleted. A branch that is already gone counts as deleted.
     */
    suspend fun deleteRemoteBranch(bareRepo: File, branch: String, token: String): PushResult

    /**
     * The owner approved a held workflow change after reading its diff: from now on the
     * check-post of [bareRepo] lets exactly that content at that path through. [approvalKey]
     * comes from [Hold.approvalKey]. The approval is kept on the Android side, out of Linux's
     * reach. The default does nothing, for test fakes.
     */
    suspend fun approveWorkflowChange(bareRepo: File, approvalKey: String) = Unit

    /**
     * Runs [block] on [bareRepo] opened the safe way: its config replaced with the canonical one,
     * hooks never run. For local work only, such as merging a session into the default branch;
     * anything that goes to GitHub goes through [push], so the check-post always runs.
     */
    suspend fun <T> withRepository(bareRepo: File, block: (Repository) -> T): T
}
