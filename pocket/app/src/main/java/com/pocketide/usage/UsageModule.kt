package com.pocketide.usage

import com.pocketide.AppGraph
import com.pocketide.github.AccountUsage
import com.pocketide.github.GitHubApi
import com.pocketide.github.NotConnectedException
import com.pocketide.github.RepoUsage
import com.pocketide.github.WorkflowRun
import com.pocketide.google.DriveQuota
import com.pocketide.model.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException

fun createUsageReporter(graph: AppGraph): UsageReporter = LiveUsage(
    gitHub = { graph.gitHub },
    projects = { graph.projects.all.value },
    driveQuota = { graph.drive.quota() },
)

/**
 * Asks GitHub and Google each time: the screen shows live numbers with the time they were read.
 * GitHub's billing-usage API answers only for accounts on its newer billing platform, with the
 * "Plan: read" permission; without it the screen points to GitHub's billing page instead.
 */
internal class LiveUsage(
    private val gitHub: () -> GitHubApi,
    private val projects: () -> List<Project>,
    private val driveQuota: suspend () -> DriveQuota,
) : UsageReporter {

    override suspend fun github(): AccountUsage? = try {
        gitHub().accountUsage()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: NotConnectedException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (_: Exception) {
        // Not on the newer billing platform, or the permission was not granted.
        null
    }

    override suspend fun repo(projectId: String): RepoUsage? {
        val project = projects().firstOrNull { it.id == projectId } ?: return null
        return gitHub().repoUsage(project.owner, project.repo)
    }

    override suspend fun google(): DriveQuota? = driveQuota()

    override suspend fun estimate(): BuildEstimate? {
        val usage = github() ?: return null
        // Minutes on public repositories are free, so only private ones teach the average.
        val (private, public) = projects().partition { it.isPrivate }
        if (private.isEmpty()) return null
        val runs = coroutineScope {
            private.map { project -> async { runsOf(project) } }.awaitAll().flatten()
        }
        val recent = BuildMinutes.samples(runs.sortedByDescending { it.createdAt })
            .groupBy { it.family }
            .values.flatMap { it.take(PER_KIND) }
        return BuildMinutes.estimate(usage, recent, public.mapTo(HashSet()) { "${it.owner}/${it.repo}" })
    }

    private suspend fun runsOf(project: Project): List<WorkflowRun> = try {
        gitHub().runs(project.owner, project.repo)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // One unreachable repository does not stop the estimate from the others.
        emptyList()
    }

    private companion object {
        /** Recent builds only: an average from last year's builds says little about today's. */
        const val PER_KIND = 20
    }
}
