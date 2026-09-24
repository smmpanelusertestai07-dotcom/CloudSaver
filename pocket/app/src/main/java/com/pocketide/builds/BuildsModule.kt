package com.pocketide.builds

import com.pocketide.AppGraph
import com.pocketide.github.WorkflowRun

fun createBuilds(graph: AppGraph): Builds = StubBuilds().also { graph.hashCode() }

private class StubBuilds : Builds {
    override fun templates(): List<BuildTemplate> = emptyList()
    override suspend fun addTemplate(projectId: String, sessionId: String, templateId: String) = Unit
    override suspend fun run(projectId: String, templateId: String, ref: String): Long? = null
    override suspend fun recentRuns(projectId: String): List<WorkflowRun> = emptyList()
    override suspend fun collect(projectId: String, sessionId: String, runId: Long) = 0
}
