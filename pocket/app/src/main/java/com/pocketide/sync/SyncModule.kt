package com.pocketide.sync

import com.pocketide.AppGraph
import com.pocketide.model.Decision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createSyncEngine(graph: AppGraph): SyncEngine = StubSync().also { graph.hashCode() }

fun createDataBudget(graph: AppGraph): DataBudget = StubBudget().also { graph.hashCode() }

private class StubSync : SyncEngine {
    private fun no(): Nothing = throw IllegalStateException("stub")
    override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)
    override val waiting: StateFlow<List<PendingUpload>> = MutableStateFlow(emptyList())
    override val usage: StateFlow<DataUsage> = MutableStateFlow(DataUsage(0, 0, emptyMap()))
    override val leaseHolder: StateFlow<String?> = MutableStateFlow(null)
    override fun requestSync(reason: String) = Unit
    override suspend fun syncNow() = Unit
    override suspend fun uploadNow(sessionIds: List<String>) = no()
    override suspend fun restorePlan(): RestorePlan = no()
    override suspend fun restore(choice: RestoreChoice) = no()
    override suspend fun fetchSession(sessionId: String) = no()
    override suspend fun takeOver() = no()
    override suspend fun moveToAnotherAccount() = no()
    override suspend fun deleteEverything() = no()
    override fun schedule() = Unit
}

private class StubBudget : DataBudget {
    override fun allow(bytes: Long, kind: String, big: Boolean) = Decision.YES
    override fun record(bytes: Long, kind: String) = Unit
}
