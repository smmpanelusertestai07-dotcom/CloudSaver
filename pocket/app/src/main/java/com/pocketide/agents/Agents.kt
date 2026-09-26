package com.pocketide.agents

import com.pocketide.model.AgentCandidate
import com.pocketide.model.AgentInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Result of testing an agent on this phone (it runs, RAM, full screen). [openCommand] is the
 * command that opens the tested version full screen, when it has one.
 */
data class DoctorReport(
    val agentId: String,
    val ok: Boolean,
    val checks: List<Pair<String, Boolean>>,
    val note: String?,
    val openCommand: String? = null,
) {
    /** True when the version itself failed (wrong code-server, no screen), not the phone's state at the time. */
    val versionFailed: Boolean get() = checks.any { (name, passed) -> !passed && name in VERSION_CHECKS }

    private companion object {
        val VERSION_CHECKS = setOf(AgentDoctor.FITS, AgentDoctor.FULL_SCREEN)
    }
}

/**
 * The official three are built in. Others are found weekly on Open VSX (verified publisher,
 * AI/Chat, arm64 or universal, ≥ 50,000 downloads, first published ≥ 14 days ago, has an agent
 * view) and are added only by the owner's tap. "Only official agents" hides everything else.
 */
interface AgentCatalog {
    /** Official three plus the ones the owner added. */
    val installed: StateFlow<List<AgentInfo>>

    /** Candidates waiting for a tap under "More agents → New". */
    val candidates: StateFlow<List<AgentCandidate>>

    fun find(agentId: String): AgentInfo?

    /** What the add card shows beyond the candidate (identifier, licence, source, rating), or null. */
    fun facts(extensionId: String): CandidateFacts? = null

    suspend fun discover()

    suspend fun add(candidate: AgentCandidate): DoctorReport

    suspend fun remove(agentId: String)

    /** Installs or updates the agent's extension (sha256-verified, verified namespace, rollback). */
    suspend fun ensureInstalled(agentId: String)

    suspend fun doctor(agentId: String): DoctorReport

    /** Checks each agent for a newer compatible version and updates with rollback. */
    suspend fun updateAll()
}
