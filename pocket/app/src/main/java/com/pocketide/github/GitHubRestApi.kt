package com.pocketide.github

import com.pocketide.core.AppJson
import com.pocketide.core.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import java.net.HttpURLConnection
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale

/** [GitHubApi] over REST version 2026-03-10 with the signed-in user's token. */
internal class GitHubRestApi(
    private val rest: RestClient,
    private val clock: Clock,
) : GitHubApi {

    override suspend fun me(): GitHubAccount = user().account()

    override suspend fun repos(): List<RepoInfo> =
        activeInstallations()
            .flatMap { installation ->
                rest.pages(rest.url("user", "installations", installation.id.toString(), "repositories")) {
                    decode(InstallationReposPage.serializer(), it).repositories
                }
            }
            .map { it.info() }
            .distinctBy { it.fullName.lowercase(Locale.ROOT) }
            .sortedByDescending { it.pushedAt.orEmpty() }

    override suspend fun installedOn(login: String): Boolean =
        activeInstallations().any { it.account?.login.equals(login, ignoreCase = true) }

    /** The App's installations the signed-in user can reach, suspended ones left out. */
    private suspend fun activeInstallations(): List<InstallationJson> =
        rest.pages(rest.url("user", "installations")) { decode(InstallationsPage.serializer(), it).installations }
            .filter { it.suspendedAt == null }

    override suspend fun repo(owner: String, name: String): RepoInfo? =
        rest.getOrNull(repoUrl(owner, name))?.let { decode(RepoJson.serializer(), it.text).info() }

    override suspend fun createPrivateRepo(name: String, description: String): RepoInfo {
        val body = buildJsonObject {
            put("name", checkName(name))
            put("description", description)
            put("private", true)
            put("auto_init", true)
        }
        val reply = checkNotNull(rest.send(Verb.POST, rest.url("user", "repos"), body))
        return decode(RepoJson.serializer(), reply.text).info()
    }

    override suspend fun readFile(owner: String, name: String, path: String, ref: String?): RepoFile? {
        val url = repoUrl(owner, name, "contents", *pathSegments(path), query = mapOf("ref" to ref))
        val reply = rest.getOrNull(url) ?: return null
        if (parse(reply.text) is JsonArray) throw GitHubException(GitHubText.IS_FOLDER, reply.status)
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

    /**
     * Git's own steps through the Git Database API: read the branch, add a tree on top of its
     * tree, make the commit, then move the branch with force off. If someone pushed in the
     * meantime GitHub refuses the move (422), and nothing of theirs is overwritten.
     */
    override suspend fun commitFiles(owner: String, name: String, branch: String, files: List<NewFile>, message: String): String {
        require(files.isNotEmpty()) { GitHubText.BAD_PATH }
        val head = decode(RefJson.serializer(), rest.get(repoUrl(owner, name, "git", "ref", "heads", *branchSegments(branch))).text).target.sha
        val baseTree = decode(CommitJson.serializer(), rest.get(repoUrl(owner, name, "git", "commits", head)).text).tree.sha
        val tree = buildJsonObject {
            put("base_tree", baseTree)
            putJsonArray("tree") {
                files.forEach { file ->
                    addJsonObject {
                        put("path", pathSegments(file.path).joinToString("/"))
                        put("mode", if (file.executable) "100755" else "100644")
                        put("type", "blob")
                        put("content", file.text)
                    }
                }
            }
        }
        val treeSha = decode(GitObjectJson.serializer(), post(repoUrl(owner, name, "git", "trees"), tree)).sha
        val commit = buildJsonObject {
            put("message", message)
            put("tree", treeSha)
            putJsonArray("parents") { add(head) }
        }
        val commitSha = decode(GitObjectJson.serializer(), post(repoUrl(owner, name, "git", "commits"), commit)).sha
        val move = buildJsonObject {
            put("sha", commitSha)
            put("force", false)
        }
        rest.send(Verb.PATCH, repoUrl(owner, name, "git", "refs", "heads", *branchSegments(branch)), move)
        return commitSha
    }

    override suspend fun runs(owner: String, name: String): List<WorkflowRun> {
        val url = repoUrl(owner, name, "actions", "runs", query = mapOf("per_page" to RECENT_RUNS.toString()))
        return decode(RunsPage.serializer(), rest.get(url).text).runs.map { it.run() }
    }

    /**
     * This month's usage from the enhanced billing platform. Accounts GitHub does not report
     * for (not on that platform, or "Plan: read" not accepted) get the plan and a reason, never
     * zeros that look real. GitHub's billing month is a calendar month in UTC.
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
            if (e.status != HttpURLConnection.HTTP_FORBIDDEN && e.status != HttpURLConnection.HTTP_NOT_FOUND) throw e
            AccountUsage(plan, emptyList(), start, end, unavailableReason = GitHubText.USAGE_UNAVAILABLE)
        }
    }

    private suspend fun user(): UserJson = decode(UserJson.serializer(), rest.get(rest.url("user")).text)

    private suspend fun post(url: HttpUrl, body: JsonElement): String = checkNotNull(rest.send(Verb.POST, url, body)).text

    private fun repoUrl(owner: String, name: String, vararg rest: String, query: Map<String, String?> = emptyMap()): HttpUrl =
        this.rest.url("repos", checkName(owner), checkName(name), *rest, query = query)

    private fun checkName(value: String): String {
        require(NAME.matches(value) && value != "." && value != "..") { GitHubText.BAD_NAME }
        return value
    }

    private fun pathSegments(path: String): Array<String> {
        require(path.isNotBlank()) { GitHubText.BAD_PATH }
        return path.trim('/').split('/').map { segment ->
            require(segment.isNotEmpty() && segment != "." && segment != ".." && '\\' !in segment) { GitHubText.BAD_PATH }
            segment
        }.toTypedArray()
    }

    /** A branch name may hold slashes (`feature/x`); each part is its own path segment. */
    private fun branchSegments(branch: String): Array<String> = pathSegments(branch)

    private fun parse(text: String): JsonElement = try {
        AppJson.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        throw GitHubException(GitHubText.UNEXPECTED, HttpURLConnection.HTTP_OK)
    }

    private companion object {
        const val RECENT_RUNS = 20
        val NAME = Regex("[A-Za-z0-9._-]{1,100}")
    }
}
