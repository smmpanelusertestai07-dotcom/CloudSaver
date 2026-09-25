package com.pocketide.github

import com.pocketide.model.LinkHealth
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class GitHubAccount(val login: String, val id: Long, val name: String?, val avatarUrl: String?)

/** What the device-code screen shows. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMs: Long,
    val intervalSeconds: Int,
)

sealed interface DevicePoll {
    data object Pending : DevicePoll
    /** GitHub asked us to poll less often. */
    data class SlowDown(val intervalSeconds: Int) : DevicePoll
    data class Connected(val account: GitHubAccount) : DevicePoll
    data object Denied : DevicePoll
    data object Expired : DevicePoll
    data class Failed(val why: String) : DevicePoll
}

/**
 * Sign-in through the PocketIDE GitHub App with the device flow: no client secret in the app.
 * The user token (8 hours) and refresh token (6 months) are kept encrypted with an Android
 * Keystore key and never enter Linux.
 */
interface GitHubAuth {
    val account: StateFlow<GitHubAccount?>

    /**
     * True when there is a GitHub App to sign in through: the one the owner entered in the app, or
     * else the build's. It can change while the app runs.
     */
    val configured: Boolean

    suspend fun startDeviceFlow(): DeviceCode

    suspend fun poll(code: DeviceCode): DevicePoll

    /** A valid user token, refreshed when needed. Throws [NotConnectedException] when signed out or revoked. */
    suspend fun token(): String

    /** Online check used by the lock: OK, OFFLINE (not a lock) or REVOKED (locks). */
    suspend fun health(): LinkHealth

    /** The page where the owner adds repositories to the app's installation. */
    fun installUrl(): String

    suspend fun signOut()
}

class NotConnectedException(message: String) : Exception(message)

/** GitHub refused or failed a call; the message is one plain sentence. [status] is the HTTP status. */
open class GitHubException(message: String, val status: Int) : Exception(message)

/** GitHub asked us to slow down for longer than is worth waiting; [retryAtMs] is when to try again. */
class GitHubRateLimitException(message: String, status: Int, val retryAtMs: Long?) : GitHubException(message, status)

data class RepoInfo(
    val owner: String,
    val name: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val sizeKb: Long,
    val cloneUrl: String,
    val htmlUrl: String,
    val pushedAt: String?,
    /** A fork: its code started as someone else's, even under the owner's own account. */
    val fork: Boolean = false,
)

data class RepoFile(val path: String, val sha: String, val bytes: ByteArray)

data class PullRequest(val number: Int, val url: String, val state: String, val merged: Boolean, val mergeable: Boolean?)

data class WorkflowRun(
    val id: Long,
    val name: String,
    val branch: String,
    val status: String,
    val conclusion: String?,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String,
    val runnerImage: String? = null,
)

data class RunArtifact(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val expired: Boolean,
    val downloadUrl: String,
    /** `sha256:<hex>` of the zip, set by upload-artifact v4 and newer; checked on download. */
    val digest: String? = null,
)

/** The run GitHub started for a dispatch (API version 2026-03-10 reports it directly). */
data class DispatchedRun(val runId: Long, val apiUrl: String, val htmlUrl: String)

data class JobStep(val number: Int, val name: String, val status: String, val conclusion: String?)

data class WorkflowJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    /** Runner labels the job asked for, such as `ubuntu-latest` or `macos-15`. */
    val labels: List<String>,
    val runnerName: String?,
    val steps: List<JobStep>,
)

/** The runner labels of the first job that names any, such as `ubuntu-latest`; null when none does. */
fun runnerLabels(jobs: List<WorkflowJob>): String? =
    jobs.firstNotNullOfOrNull { job -> job.labels.takeIf { it.isNotEmpty() }?.joinToString(", ") }

/** A job's log, trimmed: the runner image named at its top and its last lines. */
data class JobLog(val runnerImage: String?, val tail: String)

/** One line of the account's usage this month, e.g. Actions minutes on Linux. */
data class UsageLine(
    val product: String,
    val sku: String,
    val quantity: Double,
    val unit: String,
    val netAmountUsd: Double,
    /** Before discounts; the difference is what the plan (or a public repository) covered. */
    val grossAmountUsd: Double = netAmountUsd,
    val discountAmountUsd: Double = 0.0,
    val repository: String? = null,
    val date: String? = null,
)

data class AccountUsage(
    val plan: String?,
    val lines: List<UsageLine>,
    val periodStart: String?,
    val periodEnd: String?,
    /** Set when GitHub does not share usage with apps for this account; [lines] is then empty, not zero. */
    val unavailableReason: String? = null,
)

data class RepoUsage(val repo: RepoInfo, val cacheBytes: Long, val artifactsBytes: Long, val artifactCount: Int)

/** The GitHub REST calls PocketIDE makes, with the current user token. */
interface GitHubApi {
    suspend fun me(): GitHubAccount
    suspend fun repos(): List<RepoInfo>
    suspend fun repo(owner: String, name: String): RepoInfo?
    suspend fun createPrivateRepo(name: String, description: String, autoInit: Boolean = true): RepoInfo
    suspend fun collaborators(owner: String, name: String): List<String>
    suspend fun setActionsEnabled(owner: String, name: String, enabled: Boolean)
    suspend fun readFile(owner: String, name: String, path: String): RepoFile?
    suspend fun writeFile(owner: String, name: String, path: String, bytes: ByteArray, message: String, sha: String?)
    suspend fun openPullRequest(owner: String, name: String, head: String, base: String, title: String, body: String): PullRequest
    suspend fun pullRequest(owner: String, name: String, number: Int): PullRequest
    suspend fun mergePullRequest(owner: String, name: String, number: Int, method: String = "merge"): Boolean
    suspend fun dispatchWorkflow(owner: String, name: String, workflowFile: String, ref: String, inputs: Map<String, String> = emptyMap())
    suspend fun runs(owner: String, name: String, branch: String? = null): List<WorkflowRun>

    /**
     * [runs], and with [runners] false without looking up the runner of the newest few (one more
     * request each): for a refresh that already knows them, or a search that needs none.
     */
    suspend fun runs(owner: String, name: String, branch: String?, runners: Boolean): List<WorkflowRun> = runs(owner, name, branch)
    suspend fun artifacts(owner: String, name: String, runId: Long): List<RunArtifact>
    suspend fun downloadArtifact(artifact: RunArtifact, dest: File)

    /** Encrypts with the repository's public key (libsodium sealed box) and stores the secret. */
    suspend fun setActionsSecret(owner: String, name: String, secretName: String, value: ByteArray)
    suspend fun accountUsage(): AccountUsage
    suspend fun repoUsage(owner: String, name: String): RepoUsage

    /** Like [dispatchWorkflow], and returns the exact run GitHub started, so a build never follows "the latest run". */
    suspend fun dispatchWorkflowRun(
        owner: String,
        name: String,
        workflowFile: String,
        ref: String,
        inputs: Map<String, String> = emptyMap(),
    ): DispatchedRun? {
        dispatchWorkflow(owner, name, workflowFile, ref, inputs)
        return null
    }

    /** One run by id; null when it does not exist. The runner it asked for is in its [jobs]. */
    suspend fun run(owner: String, name: String, runId: Long): WorkflowRun? = runs(owner, name).find { it.id == runId }

    /** The run's jobs with their live steps. */
    suspend fun jobs(owner: String, name: String, runId: Long): List<WorkflowJob> = emptyList()

    /** The job's log: runner image and last lines. Null when GitHub no longer has it. */
    suspend fun jobLog(owner: String, name: String, jobId: Long): JobLog? = null

    /**
     * The newest pull request from the branch [head] of this repository, open, merged or closed
     * (a session's pull request, found by its branch); null when there is none.
     */
    suspend fun pullRequestFor(owner: String, name: String, head: String): PullRequest? = null
}
