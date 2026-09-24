package com.pocketide.projects

import com.pocketide.AppGraph
import com.pocketide.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createProjects(graph: AppGraph): Projects = StubProjects().also { graph.hashCode() }

private class StubProjects : Projects {
    private fun no(): Nothing = throw IllegalStateException("stub")
    override val all: StateFlow<List<Project>> = MutableStateFlow(emptyList())
    override suspend fun create(name: String, description: String): Project = no()
    override suspend fun import(owner: String, repo: String): Project = no()
    override suspend fun ensureCloned(projectId: String) = no()
    override suspend fun fetch(projectId: String) = no()
    override suspend fun remove(projectId: String) = no()
    override fun touched(projectId: String) = Unit
}
