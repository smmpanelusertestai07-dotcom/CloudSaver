package com.pocketide.builds

import com.pocketide.github.WorkflowJob
import com.pocketide.github.WorkflowRun

/** A ready-made GitHub Actions workflow the app can add to a project (workflow_dispatch only). */
data class BuildTemplate(
    val id: String,
    val title: String,
    val description: String,
    val fileName: String,
    val runner: String,
    /** The workflow's `name:`, which GitHub shows on each run of it. */
    val workflowName: String = "",
    /** How many of the plan's included minutes one runner minute counts as (Linux 1, Windows 2, macOS 10). */
    val minutesMultiplier: Int = 1,
    /** It uses the project's Secrets (the release signing key), so only the owner starts it. */
    val usesSecrets: Boolean = false,
)

/**
 * One dispatched run as it stands: its jobs with their live steps and, once it ended, the runner
 * image that really ran it ([WorkflowRun.runnerImage], read from the job log). A run that did
 * not succeed names the first job that failed, its failed step and the last lines of its log.
 */
data class BuildProgress(
    val run: WorkflowRun,
    val jobs: List<WorkflowJob>,
    val failedJob: String? = null,
    val failedStep: String? = null,
    val failureLog: String? = null,
)

/**
 * Heavy builds run on the owner's GitHub Actions: Android release, iOS, macOS, Windows, Docker,
 * emulator tests. Results (APKs, screenshots, videos, reports) come back into the session's Media.
 */
interface Builds {
    fun templates(): List<BuildTemplate>

    /** Commits the template to the session's branch (through the agent or the check-post push). */
    suspend fun addTemplate(projectId: String, sessionId: String, templateId: String)

    /**
     * Pushes the session's branch through the check-post, then starts the template's workflow on
     * it and returns that exact run's id (null when GitHub has not listed it yet). Throws
     * [WorkflowApprovalNeeded] while the branch changes GitHub Actions code the owner has not
     * approved, so such a change never runs.
     */
    suspend fun run(projectId: String, templateId: String, ref: String): Long?

    /**
     * [run] for an agent's `run_build`. A template that uses the project's Secrets starts only
     * when the owner taps Build: an agent could otherwise have its own code signed with the
     * owner's release key. Failures are [IllegalStateException]s with a sentence for the agent,
     * except [WorkflowApprovalNeeded], which the caller turns into its own words.
     */
    suspend fun runForAgent(projectId: String, templateId: String, ref: String): Long? {
        val signing = templates().firstOrNull { it.id == templateId && it.usesSecrets }
        check(signing == null) {
            "The ${signing?.title} build uses the project's Secrets, so only the owner starts it: ask the owner to tap Build in PocketIDE."
        }
        return try {
            run(projectId, templateId, ref)
        } catch (held: WorkflowApprovalNeeded) {
            throw held
        } catch (refused: BuildsException) {
            throw IllegalStateException(refused.message, refused)
        }
    }

    suspend fun recentRuns(projectId: String): List<WorkflowRun>

    /** Downloads the run's artifacts into the session's Media; for a failed run, the end of its log too. */
    suspend fun collect(projectId: String, sessionId: String, runId: Long): Int

    /**
     * The templates that fit what the session's files look like (a Flutter, React Native, Gradle,
     * Xcode, Swift, Docker, .NET or Rust project), best first; every template when nothing matches.
     */
    suspend fun suggestedTemplates(projectId: String, sessionId: String): List<BuildTemplate> = templates()

    /** The run [runId] of this project, followed by its id; null when GitHub has no such run. */
    suspend fun progress(projectId: String, runId: Long): BuildProgress? = null
}
