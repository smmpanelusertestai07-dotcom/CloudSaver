package com.pocketide.agents

import com.pocketide.AppGraph
import com.pocketide.model.AgentCandidate
import com.pocketide.model.AgentInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createAgentCatalog(graph: AppGraph): AgentCatalog = StubCatalog().also { graph.hashCode() }

private class StubCatalog : AgentCatalog {
    private fun no(): Nothing = throw IllegalStateException("stub")
    override val installed: StateFlow<List<AgentInfo>> = MutableStateFlow(emptyList())
    override val candidates: StateFlow<List<AgentCandidate>> = MutableStateFlow(emptyList())
    override fun find(agentId: String): AgentInfo? = null
    override suspend fun discover() = Unit
    override suspend fun add(candidate: AgentCandidate): DoctorReport = no()
    override suspend fun remove(agentId: String) = no()
    override suspend fun ensureInstalled(agentId: String) = no()
    override suspend fun doctor(agentId: String): DoctorReport = no()
    override suspend fun updateAll() = Unit
}
