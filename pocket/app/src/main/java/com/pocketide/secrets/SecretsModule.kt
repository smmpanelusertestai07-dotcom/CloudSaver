package com.pocketide.secrets

import com.pocketide.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun createProjectSecrets(graph: AppGraph): ProjectSecrets {
    val secrets = SealedProjectSecrets(
        store = graph.secureStore,
        clock = graph.clock,
        io = Dispatchers.IO,
        pushSecret = { projectId, name, value ->
            val project = graph.projects.all.value.firstOrNull { it.id == projectId }
                ?: throw SecretsException("This project is not on this phone any more.")
            graph.gitHub.setActionsSecret(project.owner, project.repo, name, value)
        },
    )
    graph.scope.launch {
        try {
            secrets.preload()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The screens show the same problem when they ask for a value.
        }
    }
    return secrets
}
