package com.pocketide.builds

import com.pocketide.github.WorkflowRun

/** A ready-made GitHub Actions workflow the app can add to a project (workflow_dispatch only). */
data class BuildTemplate(val id: String, val title: String, val description: String, val fileName: String, val runner: String)

/**
 * Heavy builds run on the owner's GitHub Actions: Android release, iOS, macOS, Windows, Docker,
 * emulator tests. Results (APKs, screenshots, videos, reports) come back into the session's Media.
 */
interface Builds {
    fun templates(): List<BuildTemplate>

    /** Commits the template to the session's branch (through the agent or the check-post push). */
    suspend fun addTemplate(projectId: String, sessionId: String, templateId: String)

    suspend fun run(projectId: String, templateId: String, ref: String): Long?

    suspend fun recentRuns(projectId: String): List<WorkflowRun>

    /** Downloads the run's artifacts into the session's Media. */
    suspend fun collect(projectId: String, sessionId: String, runId: Long): Int
}
