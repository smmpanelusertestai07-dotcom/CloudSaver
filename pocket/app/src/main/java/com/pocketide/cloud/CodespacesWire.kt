package com.pocketide.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

// GitHub's codespace JSON, REST API version 2026-03-10. Only the fields PocketIDE reads.

@Serializable
internal data class CodespaceOwnerJson(val login: String)

@Serializable
internal data class CodespaceRepoJson(
    val id: Long,
    val name: String,
    val owner: CodespaceOwnerJson,
    val private: Boolean = true,
)

@Serializable
internal data class MachineJson(
    val name: String,
    @SerialName("display_name") val displayName: String = "",
    val cpus: Int = 0,
    @SerialName("memory_in_bytes") val memoryInBytes: Long = 0,
    @SerialName("storage_in_bytes") val storageInBytes: Long = 0,
) {
    fun machine() = Machine(name, displayName.ifBlank { name }, cpus, memoryInBytes, storageInBytes)
}

@Serializable
internal data class GitStatusJson(
    val ahead: Int = 0,
    @SerialName("has_uncommitted_changes") val uncommitted: Boolean = false,
    @SerialName("has_unpushed_changes") val unpushed: Boolean = false,
    val ref: String? = null,
)

@Serializable
internal data class CodespaceJson(
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val repository: CodespaceRepoJson,
    val state: String = "Unknown",
    @SerialName("web_url") val webUrl: String,
    val machine: MachineJson? = null,
    val location: String? = null,
    @SerialName("idle_timeout_minutes") val idleTimeoutMinutes: Int? = null,
    @SerialName("retention_period_minutes") val retentionPeriodMinutes: Int? = null,
    @SerialName("retention_expires_at") val retentionExpiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("git_status") val gitStatus: GitStatusJson? = null,
    @SerialName("devcontainer_path") val devcontainerPath: String? = null,
    @SerialName("pending_operation") val pendingOperation: Boolean = false,
    @SerialName("pending_operation_disabled_reason") val pendingOperationReason: String? = null,
) {
    fun computer() = Computer(
        name = name,
        displayName = displayName?.takeIf { it.isNotBlank() } ?: repository.name,
        repo = RepoRef(repository.id, repository.owner.login, repository.name, repository.private),
        state = stateOf(state),
        webUrl = webUrl,
        machine = machine?.machine(),
        region = location,
        idleMinutes = idleTimeoutMinutes,
        keepMinutes = retentionPeriodMinutes,
        deletesAtMs = epochMs(retentionExpiresAt),
        createdAtMs = epochMs(createdAt),
        lastUsedAtMs = epochMs(lastUsedAt),
        git = gitStatus?.let { GitState(it.ahead, it.uncommitted, it.unpushed, it.ref) },
        configPath = devcontainerPath,
        busyReason = pendingOperationReason?.takeIf { pendingOperation && it.isNotBlank() },
    )
}

@Serializable
internal data class CodespacesPage(val codespaces: List<CodespaceJson> = emptyList())

@Serializable
internal data class MachinesPage(val machines: List<MachineJson> = emptyList())

/** GitHub's codespace states, grouped the way the app talks about them. */
internal fun stateOf(state: String): ComputerState = when (state) {
    "Available" -> ComputerState.AVAILABLE
    "Created", "Queued", "Provisioning" -> ComputerState.CREATING
    "Starting", "Awaiting" -> ComputerState.STARTING
    "ShuttingDown" -> ComputerState.STOPPING
    "Shutdown" -> ComputerState.STOPPED
    "Rebuilding", "Updating", "Exporting" -> ComputerState.UPDATING
    "Failed", "Unavailable" -> ComputerState.FAILED
    "Deleted", "Archived", "Moved" -> ComputerState.GONE
    else -> ComputerState.UNKNOWN
}

internal fun epochMs(iso: String?): Long? = try {
    iso?.let { Instant.parse(it).toEpochMilli() }
} catch (e: DateTimeParseException) {
    null
}
