package com.pocketide.secrets

import com.pocketide.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createProjectSecrets(graph: AppGraph): ProjectSecrets = StubSecrets().also { graph.hashCode() }

private class StubSecrets : ProjectSecrets {
    override val values: StateFlow<List<ProjectValue>> = MutableStateFlow(emptyList())
    override suspend fun set(projectId: String?, name: String, kind: SecretKind, value: CharArray) = Unit
    override suspend fun reveal(projectId: String?, name: String): CharArray? = null
    override suspend fun remove(projectId: String?, name: String) = Unit
    override suspend fun variablesFor(projectId: String): Map<String, String> = emptyMap()
    override suspend fun allValues(): List<String> = emptyList()
    override suspend fun pushToGitHub(projectId: String, name: String) = Unit
}
