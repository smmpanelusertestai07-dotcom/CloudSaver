package com.pocketide.github

import com.pocketide.core.AppJson
import com.pocketide.core.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.HashingSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale

/** Encrypts a value for a repository's Actions public key (libsodium sealed box). */
internal fun interface Sealer {
    fun seal(publicKey: ByteArray, value: ByteArray): ByteArray
}

/** [GitHubApi] over REST version 2026-03-10 with the signed-in user's token. */
internal class GitHubRestApi(
    private val rest: RestClient,
    private val sealer: Sealer,
    private val clock: Clock,
) : GitHubApi {

    override suspend fun me(): GitHubAccount = user().account()

    /** Repositories the owner and PocketIDE's App installations can both reach: the Import picker. */
    override suspend fun repos(): List<RepoInfo> {
        val installations = rest.pages(rest.url("user", "installations")) {
            decode(InstallationsPage.serializer(), it).installations
        }
        return installations
            .filter { it.suspendedAt == null }
            .flatMap { installation ->
                rest.pages(rest.url("user", "installations", installation.id.toString(), "repositories")) {
                    decode(InstallationReposPage.serializer(), it).repositories
                }
            }
            .map { it.info() }
            .distinctBy { "${it.owner}/${it.name}".lowercase(Locale.ROOT) }
    }

    override suspend fun repo(owner: String, name: String): RepoInfo? =
        rest.getOrNull(repoUrl(owner, name))?.let { decode(RepoJson.serializer(), it.text).info() }

    /** Always private (plan §0); `auto_init` gives the repository a first commit, so branches work at once. */
    override suspend fun createPrivateRepo(name: String, description: String, autoInit: Boolean): RepoInfo {
        val body = buildJsonObject {
            put("name", checkName(name))
            put("description", description)
            put("private", true)
            put("auto_init", autoInit)
        }
        val reply = checkNotNull(rest.send(Verb.POST, rest.url("user", "repos"), body))
        return decode(RepoJson.serializer(), reply.text).info()
    }

    override suspend fun collaborators(owner: String, name: String): List<String> =
        rest.pages(repoUrl(owner, name, "collaborators", query = mapOf("affiliation" to "all"))) {
            decode(ListSerializer(CollaboratorJson.serializer()), it).map { c -> c.login }
        }

    override suspend fun setActionsEnabled(owner: String, name: String, enabled: Boolean) {
        rest.send(Verb.PUT, repoUrl(owner, name, "actions", "permissions"), buildJsonObject { put("enabled", enabled) })
    }

    override suspend fun readFile(owner: String, name: String, path: String): RepoFile? {
        val url = repoUrl(owner, name, "contents", *pathSegments(path))
        val reply = rest.getOrNull(url) ?: return null
        val element = parse(reply.text)
        if (element is JsonArray) throw GitHubException(GitHubText.IS_FOLDER, reply.status)
        val content = decode(ContentJson.serializer(), reply.text)
        if (content.type != "file") throw GitHubException(GitHubText.IS_FOLDER, reply.status)
        val bytes = when {
            content.encoding == "base64" && content.content != null -> Base64.getMimeDecoder().decode(content.content)
            // Files between 1 and 100 MB come without content; the raw media type returns the bytes.
            content.encoding == "none" -> rest.get(url, accept = RestClient.ACCEPT_RAW).bytes
            else -> throw GitHubException(GitHubText.TOO_BIG, reply.status)
        }
        return RepoFile(path, content.sha, bytes)
    }

    override suspend fun writeFile(owner: String, name: String, path: String, bytes: ByteArray, message: String, sha: String?) {
        val body = buildJsonObject {
            put("message", message)
            put("content", Base64.getEncoder().encodeToString(bytes))
            if (sha != null) put("sha", sha)
        }
        rest.send(Verb.PUT, repoUrl(owner, name, "contents", *pathSegments(path)), body)
    }

    override suspend fun openPullRequest(owner: String, name: String, head: String, base: String, title: String, body: String): PullRequest {
        val request = buildJsonObject {
            put("title", title)
            put("head", head)
            put("base", base)
            put("body", body)
        }
        return try {
            val reply = checkNotNull(rest.send(Verb.POST, repoUrl(owner, name, "pulls"), request))
            decode(PullJson.serializer(), reply.text).pullRequest()
        } catch (e: GitHubException) {
            // Opening the same pull request twice returns the one already open.
            if (e.status != 422) throw e
            openPullFor(owner, name, head, base) ?: throw e
        }
    }

    override suspend fun pullRequest(owner: String, name: String, number: Int): PullRequest {
        val reply = rest.get(repoUrl(owner, name, "pulls", number.toString()))
        return decode(PullJson.serializer(), reply.text).pullRequest()
    }

    /** False when GitHub cannot merge it now (405 not mergeable, 409 the branch moved). */
    override suspend fun mergePullRequest(owner: String, name: String, number: Int, method: String): Boolean {
        require(method in MERGE_METHODS) { GitHubText.BAD_MERGE_METHOD }
        val body = buildJsonObject { put("merge_method", method) }
        return try {
            val reply = checkNotNull(rest.send(Verb.PUT, repoUrl(owner, name, "pulls", number.toString(), "merge"), body))
            decode(MergeJson.serializer(), reply.text).merged
        } catch (e: GitHubException) {
            if (e.status == 405 || e.status == 409) false else throw e
        }
    }

    override suspend fun dispatchWorkflow(owner: String, name: String, workflowFile: String, ref: String, inputs: Map<String, String>) {
        dispatchWorkflowRun(owner, name, workflowFile, ref, inputs)
    }

    override suspend fun dispatchWorkflowRun(
        owner: String,
        name: String,
        workflowFile: String,
        ref: String,
        inputs: Map<String, String>,
    ): DispatchedRun? {
        require(inputs.size <= MAX_INPUTS) { GitHubText.TOO_MANY_INPUTS }
        val body = buildJsonObject {
            put("ref", ref)
            if (inputs.isNotEmpty()) putJsonObject("inputs") { inputs.forEach { (k, v) -> put(k, v) } }
        }
        val url = repoUrl(owner, name, "actions", "workflows", checkPath(workflowFile), "dispatches")
        val reply = checkNotNull(rest.send(Verb.POST, url, body))
        // API version 2026-03-10 answers 200 with the run; an older answer is 204 without one.
        if (reply.status == 204 || reply.bytes.isEmpty()) return null
        val run = decode(DispatchJson.serializer(), reply.text)
        return DispatchedRun(run.runId, run.runUrl, run.htmlUrl)
    }

    /** The most recent runs (one page), the newest few with the runner they asked for. */
    override suspend fun runs(owner: String, name: String, branch: String?): List<WorkflowRun> = runs(owner, name, branch, runners = true)

    override suspend fun runs(owner: String, name: String, branch: String?, runners: Boolean): List<WorkflowRun> {
        val url = repoUrl(owner, name, "actions", "runs", query = mapOf("branch" to branch, "per_page" to RECENT_RUNS.toString()))
        val runs = decode(RunsPage.serializer(), rest.get(url).text).runs
        return runs.mapIndexed { index, run ->
            run.run(if (runners && index < RUNNER_LOOKUPS) runnerOf(owner, name, run.id) else null)
        }
    }

    override suspend fun run(owner: String, name: String, runId: Long): WorkflowRun? {
        val reply = rest.getOrNull(repoUrl(owner, name, "actions", "runs", runId.toString())) ?: return null
        return decode(RunJson.serializer(), reply.text).run()
    }

    override suspend fun jobs(owner: String, name: String, runId: Long): List<WorkflowJob> =
        rest.pages(repoUrl(owner, name, "actions", "runs", runId.toString(), "jobs", query = mapOf("filter" to "latest"))) {
            decode(JobsPage.serializer(), it).jobs.map { job -> job.job() }
        }

    override suspend fun jobLog(owner: String, name: String, jobId: Long): JobLog? {
        val url = repoUrl(owner, name, "actions", "jobs", jobId.toString(), "logs")
        return try {
            rest.stream(url) { source -> LogReader.read(source, LOG_HEAD_BYTES, LOG_TAIL_BYTES) }
        } catch (e: GitHubException) {
            if (e.status == 404 || e.status == 410) null else throw e
        }
    }

    override suspend fun artifacts(owner: String, name: String, runId: Long): List<RunArtifact> =
        rest.pages(repoUrl(owner, name, "actions", "runs", runId.toString(), "artifacts")) {
            decode(ArtifactsPage.serializer(), it).artifacts.map { a -> a.artifact() }
        }

    /**
     * Downloads the zip. GitHub answers with a redirect to a one-minute storage link; OkHttp
     * follows it and drops the Authorization header because the host changes, which is right:
     * that link is pre-signed and must never receive the owner's token.
     */
    override suspend fun downloadArtifact(artifact: RunArtifact, dest: File) {
        val url = artifact.downloadUrl.toHttpUrlOrNull()?.takeIf(rest::isOurs)
            ?: throw GitHubException(GitHubText.NOT_FROM_GITHUB, 0)
        val expected = artifact.digest?.let(::sha256Of)
        val parent = dest.absoluteFile.parentFile ?: throw IOException("No folder for ${dest.name}")
        val partial = File(parent, ".${dest.name}.part")
        try {
            val actual = rest.stream(url) { source ->
                parent.mkdirs()
                HashingSink.sha256(partial.sink()).use { hashing ->
                    hashing.buffer().use { it.writeAll(source) }
                    hashing.hash.hex()
                }
            }
            if (expected != null && !expected.equals(actual, ignoreCase = true)) {
                throw GitHubException(GitHubText.DIGEST_MISMATCH, 0)
            }
            Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(partial.toPath())
        }
    }

    override suspend fun setActionsSecret(owner: String, name: String, secretName: String, value: ByteArray) {
        require(SECRET_NAME.matches(secretName) && !secretName.uppercase(Locale.ROOT).startsWith("GITHUB_")) {
            GitHubText.BAD_SECRET_NAME
        }
        val key = decode(PublicKeyJson.serializer(), rest.get(repoUrl(owner, name, "actions", "secrets", "public-key")).text)
        // A key that is not base64, or not an X25519 key, is GitHub's answer gone wrong.
        val sealed = try {
            sealer.seal(Base64.getDecoder().decode(key.key), value)
        } catch (e: IllegalArgumentException) {
            throw GitHubException(GitHubText.UNEXPECTED, 200)
        }
        val body = buildJsonObject {
            put("encrypted_value", Base64.getEncoder().encodeToString(sealed))
            put("key_id", key.keyId)
        }
        rest.send(Verb.PUT, repoUrl(owner, name, "actions", "secrets", secretName), body)
    }

    /**
     * This month's usage from the enhanced billing platform. Accounts GitHub does not report
     * for (not on that platform, or "Plan: read" not accepted) get the plan and a reason, never
     * zeros that look real.
     */
    override suspend fun accountUsage(): AccountUsage {
        val user = user()
        val month = YearMonth.from(Instant.ofEpochMilli(clock.now()).atZone(ZoneOffset.UTC))
        val url = rest.url(
            "users", checkName(user.login), "settings", "billing", "usage",
            query = mapOf("year" to month.year.toString(), "month" to month.monthValue.toString()),
        )
        val plan = user.plan?.name
        val start = month.atDay(1).toString()
        val end = month.atEndOfMonth().toString()
        return try {
            val report = decode(UsageReport.serializer(), rest.get(url).text)
            AccountUsage(plan, report.usageItems.map { it.line() }, start, end)
        } catch (e: GitHubRateLimitException) {
            throw e
        } catch (e: GitHubException) {
            if (e.status != 403 && e.status != 404) throw e
            AccountUsage(plan, emptyList(), start, end, unavailableReason = GitHubText.USAGE_UNAVAILABLE)
        }
    }

    override suspend fun repoUsage(owner: String, name: String): RepoUsage {
        val repo = repo(owner, name) ?: throw GitHubException(GitHubText.NOT_FOUND, 404)
        val cache = rest.getOrNull(repoUrl(owner, name, "actions", "cache", "usage"))
            ?.let { decode(CacheUsageJson.serializer(), it.text) }
            ?: CacheUsageJson()
        val artifacts = rest.pages(repoUrl(owner, name, "actions", "artifacts")) {
            decode(ArtifactsPage.serializer(), it).artifacts
        }.filterNot { it.expired }
        return RepoUsage(repo, cache.bytes, artifacts.sumOf { it.sizeInBytes }, artifacts.size)
    }

    private suspend fun user(): UserJson = decode(UserJson.serializer(), rest.get(rest.url("user")).text)

    private suspend fun runnerOf(owner: String, name: String, runId: Long): String? = try {
        runnerLabels(jobs(owner, name, runId))
    } catch (e: GitHubException) {
        // The runner is a detail: a run without it still shows.
        null
    }

    override suspend fun pullRequestFor(owner: String, name: String, head: String): PullRequest? {
        val query = mapOf("head" to qualifiedHead(owner, head), "state" to "all", "sort" to "created", "direction" to "desc", "per_page" to "1")
        return decode(ListSerializer(PullJson.serializer()), rest.get(repoUrl(owner, name, "pulls", query = query)).text).firstOrNull()?.pullRequest()
    }

    private suspend fun openPullFor(owner: String, name: String, head: String, base: String): PullRequest? {
        val url = repoUrl(owner, name, "pulls", query = mapOf("head" to qualifiedHead(owner, head), "base" to base, "state" to "open"))
        return decode(ListSerializer(PullJson.serializer()), rest.get(url).text).firstOrNull()?.pullRequest()
    }

    private fun qualifiedHead(owner: String, head: String) = if (':' in head) head else "$owner:$head"

    private fun repoUrl(owner: String, name: String, vararg rest: String, query: Map<String, String?> = emptyMap()): HttpUrl =
        this.rest.url("repos", checkName(owner), checkName(name), *rest, query = query)

    private fun checkName(value: String): String {
        require(NAME.matches(value) && value != "." && value != "..") { GitHubText.BAD_NAME }
        return value
    }

    private fun checkPath(value: String): String {
        require(value.isNotEmpty() && value != "." && value != ".." && '/' !in value && '\\' !in value) { GitHubText.BAD_PATH }
        return value
    }

    private fun pathSegments(path: String): Array<String> {
        val segments = path.trim('/').split('/')
        require(path.isNotBlank()) { GitHubText.BAD_PATH }
        return segments.map(::checkPath).toTypedArray()
    }

    private fun sha256Of(digest: String): String? =
        DIGEST.matchEntire(digest.trim())?.groupValues?.get(1)

    private fun parse(text: String): JsonElement = try {
        AppJson.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        throw GitHubException(GitHubText.UNEXPECTED, 200)
    }

    private companion object {
        const val RECENT_RUNS = 20
        /** Each lookup is one more call against the owner's 5,000 an hour, so only the newest runs get one. */
        const val RUNNER_LOOKUPS = 3
        const val MAX_INPUTS = 25
        const val LOG_HEAD_BYTES = 64 * 1024L
        const val LOG_TAIL_BYTES = 64 * 1024L
        val MERGE_METHODS = setOf("merge", "squash", "rebase")
        val NAME = Regex("[A-Za-z0-9._-]{1,100}")
        val SECRET_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val DIGEST = Regex("sha256:([0-9a-fA-F]{64})")
    }
}
