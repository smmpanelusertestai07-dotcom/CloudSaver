package com.pocketide.secrets

import kotlinx.coroutines.flow.StateFlow

enum class SecretKind {
    /** The agent sees it: set in the room's environment for that project. */
    VARIABLE,
    /** Never the agent, nor anything in Linux: only GitHub Actions builds, after the owner's [ProjectSecrets.pushToGitHub]. */
    SECRET,
}

/** Values are masked on screen; revealing needs the fingerprint (the screen asks). */
data class ProjectValue(
    val projectId: String?,
    val name: String,
    val kind: SecretKind,
    val updatedAt: Long,
    /** A project's own Secret is in its GitHub Actions. A global one is never marked so: see [sentTo]. */
    val pushedToGitHub: Boolean,
    /** A Variable only this agent's room sees (a community agent's API key); null for every room. */
    val agentId: String? = null,
    /** A global Secret: the projects whose GitHub Actions have this value, each sent from that project. */
    val sentTo: Set<String> = emptySet(),
)

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

    /**
     * Environment for [agentId]'s room on this project: the Variables every room sees plus the
     * ones limited to that room.
     */
    suspend fun variablesFor(projectId: String, agentId: String): Map<String, String> = variablesFor(projectId)

    /** Limits a Variable to one agent's room, or opens it to every room again with null. */
    suspend fun limitToRoom(projectId: String?, name: String, agentId: String?): Unit =
        throw UnsupportedOperationException("Room-only Variables are not available here.")

    /** Every Variable and Secret value, for the check-post. */
    suspend fun allValues(): List<String>

    /** Writes a Secret as a GitHub Actions secret of the project (owner's tap). */
    suspend fun pushToGitHub(projectId: String, name: String)

    /**
     * Everything, serialized, for the encrypted vault (the sync engine encrypts and uploads it).
     * A removed name stays in it without its value, so the removal reaches other phones.
     */
    suspend fun exportBlob(): ByteArray

    /** Replaces the local set with the vault's copy (restore on a new phone). */
    suspend fun importBlob(bytes: ByteArray)

    /**
     * Merges the vault's copy into the local set when both phones changed it since they last
     * synced: value by value, the later change wins, a removal included.
     */
    suspend fun mergeBlob(bytes: ByteArray): Unit =
        throw UnsupportedOperationException("Merging Variables and Secrets is not available here.")
}
