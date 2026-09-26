package com.pocketide.agents

import com.pocketide.core.AppJson
import com.pocketide.model.AgentCandidate
import com.pocketide.model.AgentInfo
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What an "add agent" card shows beyond [AgentCandidate]: the exact identifier, the licence, the
 * source link (or none: closed source), ratings, and where Open VSX serves its build from.
 */
@Serializable
data class CandidateFacts(
    /** Lower case, as VS Code compares ids. */
    val extensionId: String,
    /** Exactly as the publisher wrote it, for the owner to read letter by letter. */
    val identifier: String,
    /** "linux-arm64" or "universal". */
    val target: String,
    val license: String? = null,
    /** An https link to the source, or null when the publisher gives none. */
    val repository: String? = null,
    val averageRating: Double? = null,
    val reviewCount: Long = 0,
    /** The command that shows the agent's own screen. */
    val openCommand: String,
    val checkedAt: Long,
) {
    companion object {
        /** What "Verified publisher" means on Open VSX, for the card. */
        const val VERIFIED_MEANS =
            "Verified means Open VSX checked who owns this publisher name. It does not review the code, and it " +
                "does not say who makes the AI model."
    }
}

/** An agent package on this phone: what is installed in its room, from which checked file. */
@Serializable
internal data class InstallRecord(
    val agentId: String,
    val version: String,
    /** SHA-256 of the package kept as the last good one (the .vsix, or agy's archive). */
    val sha256: String,
    val installedAt: Long,
    /** The command that opens this version full screen, as the doctor found it. */
    val openCommand: String? = null,
)

/** Old entries drop off: only the newest versions are ever candidates. */
private const val MAX_REJECTED = 50

/** A version the doctor turned down for what it is (not for the phone's state then): never fetched again. */
@Serializable
internal data class RejectedVersion(val agentId: String, val version: String)

@Serializable
internal data class AgentsState(
    /** Agents the owner added from discovery (kept even while "Only official agents" hides them). */
    val added: List<AgentInfo> = emptyList(),
    val candidates: List<AgentCandidate> = emptyList(),
    val facts: List<CandidateFacts> = emptyList(),
    /** Candidate ids a notification was already posted for. */
    val announced: List<String> = emptyList(),
    val installs: List<InstallRecord> = emptyList(),
    val discoveredAt: Long = 0,
    val rejected: List<RejectedVersion> = emptyList(),
) {
    fun install(agentId: String): InstallRecord? = installs.firstOrNull { it.agentId == agentId }

    fun withInstall(record: InstallRecord) = copy(installs = installs.filterNot { it.agentId == record.agentId } + record)

    fun rejectedVersions(agentId: String): Set<String> = rejected.filter { it.agentId == agentId }.map { it.version }.toSet()

    fun withRejected(agentId: String, version: String) =
        copy(rejected = (rejected.filterNot { it.agentId == agentId && it.version == version } + RejectedVersion(agentId, version)).takeLast(MAX_REJECTED))

    fun withoutAgent(agentId: String) = copy(
        added = added.filterNot { it.id == agentId },
        installs = installs.filterNot { it.agentId == agentId },
        rejected = rejected.filterNot { it.agentId == agentId },
    )
}

/** agents.json in the app's private storage, replaced atomically so a kill mid-write keeps the old copy. */
internal class AgentStore(private val file: File) {

    fun read(): AgentsState {
        if (!file.isFile) return AgentsState()
        return try {
            AppJson.decodeFromString<AgentsState>(file.readText())
        } catch (unreadable: IllegalArgumentException) {
            // A file that no longer parses is moved aside, never silently overwritten.
            file.renameTo(File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}"))
            AgentsState()
        }
    }

    fun write(state: AgentsState) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("No folder for ${file.name}")
        Files.createDirectories(dir.toPath())
        val temp = File(dir, "${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(AppJson.encodeToString(AgentsState.serializer(), state).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
