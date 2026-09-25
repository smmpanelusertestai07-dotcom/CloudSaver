package com.pocketide.github

import com.pocketide.core.AppJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

// GitHub's JSON, field names as documented for REST API version 2026-03-10. Only the fields
// PocketIDE reads are declared; AppJson ignores the rest.

/** Replies from github.com/login: device code, token, or an `error` (sent with HTTP 200). */
@Serializable
internal data class OAuthReply(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_token_expires_in") val refreshTokenExpiresIn: Long? = null,
    val error: String? = null,
)

@Serializable
internal data class PlanJson(val name: String? = null)

@Serializable
internal data class UserJson(
    val login: String,
    val id: Long,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val plan: PlanJson? = null,
) {
    fun account() = GitHubAccount(login, id, name?.takeIf { it.isNotBlank() }, avatarUrl)
}

@Serializable
internal data class OwnerJson(val login: String)

@Serializable
internal data class RepoJson(
    val name: String,
    val owner: OwnerJson,
    val private: Boolean,
    @SerialName("default_branch") val defaultBranch: String? = null,
    val size: Long = 0,
    @SerialName("clone_url") val cloneUrl: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("pushed_at") val pushedAt: String? = null,
) {
    fun info() = RepoInfo(
        owner = owner.login,
        name = name,
        isPrivate = private,
        defaultBranch = defaultBranch ?: DEFAULT_BRANCH,
        sizeKb = size,
        cloneUrl = cloneUrl,
        htmlUrl = htmlUrl,
        pushedAt = pushedAt,
    )
}

/** GitHub leaves default_branch out only for odd repositories; main is its default for new ones. */
private const val DEFAULT_BRANCH = "main"

@Serializable
internal data class InstallationJson(val id: Long, @SerialName("suspended_at") val suspendedAt: String? = null)

@Serializable
internal data class InstallationsPage(val installations: List<InstallationJson> = emptyList())

@Serializable
internal data class InstallationReposPage(val repositories: List<RepoJson> = emptyList())

@Serializable
internal data class CollaboratorJson(val login: String)

@Serializable
internal data class ContentJson(
    val type: String,
    val sha: String,
    val encoding: String? = null,
    val content: String? = null,
)

@Serializable
internal data class PullJson(
    val number: Int,
    @SerialName("html_url") val htmlUrl: String,
    val state: String,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
) {
    fun pullRequest() = PullRequest(number, htmlUrl, state, merged, mergeable)
}

@Serializable
internal data class MergeJson(val merged: Boolean = false)

@Serializable
internal data class DispatchJson(
    @SerialName("workflow_run_id") val runId: Long,
    @SerialName("run_url") val runUrl: String,
    @SerialName("html_url") val htmlUrl: String,
)

@Serializable
internal data class RunJson(
    val id: Long,
    val name: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    @SerialName("head_branch") val headBranch: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("html_url") val htmlUrl: String,
) {
    fun run(runnerImage: String? = null) = WorkflowRun(
        id = id,
        name = name ?: displayTitle.orEmpty(),
        branch = headBranch.orEmpty(),
        status = status.orEmpty(),
        conclusion = conclusion,
        createdAt = createdAt,
        updatedAt = updatedAt,
        htmlUrl = htmlUrl,
        runnerImage = runnerImage,
    )
}

@Serializable
internal data class RunsPage(@SerialName("workflow_runs") val runs: List<RunJson> = emptyList())

@Serializable
internal data class StepJson(val number: Int, val name: String, val status: String, val conclusion: String? = null)

@Serializable
internal data class JobJson(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val labels: List<String> = emptyList(),
    @SerialName("runner_name") val runnerName: String? = null,
    val steps: List<StepJson> = emptyList(),
) {
    fun job() = WorkflowJob(
        id = id,
        name = name,
        status = status,
        conclusion = conclusion,
        htmlUrl = htmlUrl.orEmpty(),
        labels = labels,
        runnerName = runnerName,
        steps = steps.map { JobStep(it.number, it.name, it.status, it.conclusion) },
    )
}

@Serializable
internal data class JobsPage(val jobs: List<JobJson> = emptyList())

@Serializable
internal data class ArtifactJson(
    val id: Long,
    val name: String,
    @SerialName("size_in_bytes") val sizeInBytes: Long,
    val expired: Boolean,
    @SerialName("archive_download_url") val archiveDownloadUrl: String,
    val digest: String? = null,
) {
    fun artifact() = RunArtifact(id, name, sizeInBytes, expired, archiveDownloadUrl, digest)
}

@Serializable
internal data class ArtifactsPage(val artifacts: List<ArtifactJson> = emptyList())

@Serializable
internal data class PublicKeyJson(@SerialName("key_id") val keyId: String, val key: String)

@Serializable
internal data class CacheUsageJson(
    @SerialName("active_caches_size_in_bytes") val bytes: Long = 0,
    @SerialName("active_caches_count") val count: Int = 0,
)

@Serializable
internal data class UsageItemJson(
    val date: String? = null,
    val product: String,
    val sku: String,
    val quantity: Double = 0.0,
    val unitType: String = "",
    val grossAmount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val netAmount: Double = 0.0,
    val repositoryName: String? = null,
) {
    fun line() = UsageLine(
        product = product,
        sku = sku,
        quantity = quantity,
        unit = unitType,
        netAmountUsd = netAmount,
        grossAmountUsd = grossAmount,
        discountAmountUsd = discountAmount,
        repository = repositoryName,
        date = date,
    )
}

@Serializable
internal data class UsageReport(val usageItems: List<UsageItemJson> = emptyList())

/** Parses a GitHub answer; a shape we do not understand becomes a plain sentence, not a crash. */
internal fun <T> decode(serializer: KSerializer<T>, text: String): T = try {
    AppJson.decodeFromString(serializer, text)
} catch (e: SerializationException) {
    throw GitHubException(GitHubText.UNEXPECTED, 200)
} catch (e: IllegalArgumentException) {
    throw GitHubException(GitHubText.UNEXPECTED, 200)
}
