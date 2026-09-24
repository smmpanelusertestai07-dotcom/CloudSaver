package com.pocketide.secrets

import kotlinx.coroutines.flow.StateFlow

enum class SecretKind {
    /** The agent sees it: set in the room's environment for that project. */
    VARIABLE,
    /** Never the agent: only PocketIDE's set-up steps and GitHub Actions builds. */
    SECRET,
}

/** Values are masked on screen; revealing needs the fingerprint (the screen asks). */
data class ProjectValue(val projectId: String?, val name: String, val kind: SecretKind, val updatedAt: Long, val pushedToGitHub: Boolean)

/**
 * Variables and Secrets, per project with an optional global set (projectId null). Stored in the
 * encrypted vault; never in git (the check-post blocks their values).
 */
interface ProjectSecrets {
    val values: StateFlow<List<ProjectValue>>

    suspend fun set(projectId: String?, name: String, kind: SecretKind, value: CharArray)

    suspend fun reveal(projectId: String?, name: String): CharArray?

    suspend fun remove(projectId: String?, name: String)

    /** Environment for an agent's room on this project: Variables only. */
    suspend fun variablesFor(projectId: String): Map<String, String>

    /** Every Variable and Secret value, for the check-post. */
    suspend fun allValues(): List<String>

    /** Writes a Secret as a GitHub Actions secret of the project (owner's tap). */
    suspend fun pushToGitHub(projectId: String, name: String)
}
