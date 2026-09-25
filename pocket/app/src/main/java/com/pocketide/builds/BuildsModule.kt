package com.pocketide.builds

import com.pocketide.AppGraph
import com.pocketide.core.Clock
import com.pocketide.git.Hold
import com.pocketide.git.HoldKind
import com.pocketide.github.GitHubApi
import com.pocketide.media.MediaLibrary
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.UUID

fun createBuilds(graph: AppGraph): Builds = GitHubBuilds(GraphBuildsPorts(graph))

private class GraphBuildsPorts(private val graph: AppGraph) : BuildsPorts {
    private val committer = WorktreeCommitter({ graph.computer }, graph.dirs)

    override val gitHub: GitHubApi get() = graph.gitHub
    override val media: MediaLibrary get() = graph.media
    override val clock: Clock get() = graph.clock
    override val io: CoroutineDispatcher = Dispatchers.IO

    override fun project(projectId: String): Project? = graph.projects.all.value.firstOrNull { it.id == projectId }

    override fun sessions(): List<SessionRecord> = graph.sessions.all.value

    override fun worktree(session: SessionRecord): File = graph.dirs.worktree(session.agentId, session.projectId, session.id)

    override fun scratch(): File = File(graph.dirs.downloads, "actions-${UUID.randomUUID()}")

    override fun templateBytes(template: BuildTemplate): ByteArray =
        graph.context.assets.open(TemplateCatalog.assetPath(template)).use { it.readBytes() }

    override suspend fun commit(session: SessionRecord, path: String, message: String) {
        val account = graph.gitHubAuth.account.value ?: throw BuildsException("Connect GitHub first.")
        val identity = GitIdentity(
            name = account.name?.takeIf { it.isNotBlank() } ?: account.login,
            email = "${account.id}+${account.login}@users.noreply.github.com",
        )
        committer.commit(session, path, message, identity)
    }

    override suspend fun autosave(sessionId: String): String? = graph.sessions.autosave(sessionId)

    /**
     * A branch the check-post cannot read here yields no holds: the push in [autosave] runs the
     * same check-post and refuses what is held, so nothing unapproved reaches GitHub either way.
     */
    override suspend fun workflowHolds(projectId: String, branch: String): List<Hold> = try {
        graph.git.checkPost(graph.dirs.bareRepo(projectId), branch).holds.filter { it.kind == HoldKind.WORKFLOW_CHANGE }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    override fun downloadRefusal(bytes: Long): String? {
        val decision = graph.dataBudget.allow(bytes, DATA_KIND, big = bytes >= BIG_DOWNLOAD)
        return if (decision.allowed) null else decision.reason ?: "Wait for Wi-Fi to bring these results."
    }

    override fun downloaded(bytes: Long) = graph.dataBudget.record(bytes, DATA_KIND)

    override fun follow(projectId: String, runId: Long, title: String) =
        BuildWatchWorker.watch(graph.context, projectId, runId, title)

    private companion object {
        const val DATA_KIND = "builds"
        const val BIG_DOWNLOAD = 20L * 1024 * 1024
    }
}

