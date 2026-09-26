package com.pocketide.github

import com.pocketide.model.LinkHealth
import kotlinx.coroutines.flow.StateFlow

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
 * Keystore key, on this phone only.
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

    /** Online check: OK, OFFLINE (nothing to fix) or REVOKED (sign in again). */
    suspend fun health(): LinkHealth

    /** The page where the owner chooses which repositories the App may use. */
    fun installUrl(): String

    /** The page where the owner can revoke PocketIDE's access on GitHub. */
    fun authorizationsUrl(): String = "https://github.com/settings/apps/authorizations"

    suspend fun signOut()
}

class NotConnectedException(message: String) : Exception(message)

/** GitHub refused or failed a call; the message is one plain sentence. [status] is the HTTP status. */
open class GitHubException(message: String, val status: Int) : Exception(message)

/** GitHub asked us to slow down for longer than is worth waiting; [retryAtMs] is when to try again. */
class GitHubRateLimitException(message: String, status: Int, val retryAtMs: Long?) : GitHubException(message, status)

data class RepoInfo(
    val id: Long,
    val owner: String,
    val name: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val htmlUrl: String,
    val pushedAt: String?,
    val description: String? = null,
    /** A fork: its code started as someone else's, even under the owner's own account. */
    val fork: Boolean = false,
) {
    val fullName: String get() = "$owner/$name"
}

data class RepoFile(val path: String, val sha: String, val bytes: ByteArray)

/** A file for [GitHubApi.commitFiles]; [executable] sets git's 100755 mode. */
data class NewFile(val path: String, val text: String, val executable: Boolean = false)

data class WorkflowRun(
    val id: Long,
    val name: String,
    val branch: String,
    val status: String,
    val conclusion: String?,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String,
)

/** One line of the account's usage this month, e.g. Codespaces compute on 2 cores. */
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
    /** The day the usage happened, `yyyy-MM-dd`, when GitHub reports it per day. */
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

/** The GitHub REST calls PocketIDE makes, with the current user token. */
interface GitHubApi {
    suspend fun me(): GitHubAccount

    /** Repositories the owner and PocketIDE's App installation can both reach. */
    suspend fun repos(): List<RepoInfo>

    suspend fun repo(owner: String, name: String): RepoInfo?

    /** Always private; `auto_init` gives the repository a first commit, so later commits have a parent. */
    suspend fun createPrivateRepo(name: String, description: String): RepoInfo

    suspend fun readFile(owner: String, name: String, path: String, ref: String? = null): RepoFile?

    /** Adds [files] to [branch] as one commit, fast-forward only; returns the new commit's sha. */
    suspend fun commitFiles(owner: String, name: String, branch: String, files: List<NewFile>, message: String): String

    /** The most recent Actions runs (one page). */
    suspend fun runs(owner: String, name: String): List<WorkflowRun>

    suspend fun accountUsage(): AccountUsage

    /**
     * True when PocketIDE's GitHub App is installed, and not suspended, on the account [login].
     * Signing in does not install it, and without it GitHub refuses to make repositories there.
     */
    suspend fun installedOn(login: String): Boolean
}
