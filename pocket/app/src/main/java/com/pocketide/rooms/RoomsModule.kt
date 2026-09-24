package com.pocketide.rooms

import com.pocketide.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createRooms(graph: AppGraph): Rooms = StubRooms().also { graph.hashCode() }

private class StubRooms : Rooms {
    override val states: StateFlow<Map<String, RoomState>> = MutableStateFlow(emptyMap())
    override suspend fun open(agentId: String, sessionId: String): RoomState = RoomState.Failed("stub")
    override suspend fun stop(agentId: String) = Unit
    override suspend fun stopAll() = Unit
    override suspend fun terminal(sessionId: String): TerminalHandle = throw UnsupportedOperationException("stub")
    override suspend fun configure(agentId: String) = Unit
    override suspend fun delete(agentId: String) = Unit
}
