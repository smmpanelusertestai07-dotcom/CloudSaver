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

    /** True when the build carries a GitHub App client ID (owner configuration). */
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

data class RepoInfo(
    val owner: String,
    val name: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val sizeKb: Long,
    val cloneUrl: String,
    val htmlUrl: String,
    val pushedAt: String?,
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

data class RunArtifact(val id: Long, val name: String, val sizeBytes: Long, val expired: Boolean, val downloadUrl: String)

/** One line of the account's usage this month, e.g. Actions minutes on Linux. */
data class UsageLine(val product: String, val sku: String, val quantity: Double, val unit: String, val netAmountUsd: Double)

data class AccountUsage(val plan: String?, val lines: List<UsageLine>, val periodStart: String?, val periodEnd: String?)

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
    suspend fun artifacts(owner: String, name: String, runId: Long): List<RunArtifact>
    suspend fun downloadArtifact(artifact: RunArtifact, dest: File)
    /** Encrypts with the repository's public key (libsodium sealed box) and stores the secret. */
    suspend fun setActionsSecret(owner: String, name: String, secretName: String, value: ByteArray)
    suspend fun accountUsage(): AccountUsage
    suspend fun repoUsage(owner: String, name: String): RepoUsage
}
