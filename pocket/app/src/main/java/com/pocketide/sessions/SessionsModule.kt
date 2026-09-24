package com.pocketide.sessions

import com.pocketide.AppGraph
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createSessions(graph: AppGraph): Sessions = StubSessions().also { graph.hashCode() }

private class StubSessions : Sessions {
    private fun no(): Nothing = throw IllegalStateException("stub")
    override val all: StateFlow<List<SessionRecord>> = MutableStateFlow(emptyList())
    override suspend fun start(projectId: String, agentId: String, title: String?): SessionRecord = no()
    override suspend fun rename(sessionId: String, title: String) = no()
    override suspend fun continueSession(sessionId: String) = no()
    override suspend fun delete(sessionId: String) = no()
    override suspend fun restore(sessionId: String) = no()
    override suspend fun deleteForever(sessionId: String) = no()
    override suspend fun putOnMain(sessionId: String): PutOnMainResult = no()
    override suspend fun changes(sessionId: String): SessionChanges = no()
    override suspend fun transcript(sessionId: String): List<TranscriptEntry> = no()
    override suspend fun setBackUp(sessionId: String, backUp: Boolean) = no()
    override suspend fun removeMedia(sessionId: String) = no()
    override suspend fun refresh() = Unit
    override fun activeSession(agentId: String): String? = null
}
