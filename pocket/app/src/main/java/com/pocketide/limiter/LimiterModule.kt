package com.pocketide.limiter

import com.pocketide.AppGraph
import com.pocketide.model.Decision
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createPhoneMonitor(graph: AppGraph): PhoneMonitor = StubMonitor().also { graph.hashCode() }

fun createLimiter(graph: AppGraph): Limiter = StubLimiter().also { graph.hashCode() }

private class StubMonitor : PhoneMonitor {
    override val snapshot: StateFlow<PhoneSnapshot> = MutableStateFlow(PhoneSnapshot.UNKNOWN)
    override fun start() = Unit
    override fun stop() = Unit
    override suspend fun refresh() = PhoneSnapshot.UNKNOWN
}

private class StubLimiter : Limiter {
    override val guard: StateFlow<Guard> = MutableStateFlow(Guard.OK)
    override val conditions: StateFlow<List<Condition>> = MutableStateFlow(emptyList())
    override fun canStartAgent(agentId: String) = Decision.YES
    override fun canStartHeavyWork(what: String) = Decision.YES
    override fun maxAgents() = 1
    override fun unsupportedReason(): String? = null
}
