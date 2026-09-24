package com.pocketide.schedule

import com.pocketide.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createSchedules(graph: AppGraph): Schedules = StubSchedules().also { graph.hashCode() }

private class StubSchedules : Schedules {
    override val tasks: StateFlow<List<ScheduledTask>> = MutableStateFlow(emptyList())
    override suspend fun save(task: ScheduledTask) = Unit
    override suspend fun remove(id: String) = Unit
    override suspend fun runNow(id: String): String? = null
}
