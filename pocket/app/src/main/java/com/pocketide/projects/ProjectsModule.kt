package com.pocketide.projects

import com.pocketide.AppGraph
import com.pocketide.git.GitGate
import com.pocketide.github.GitHubApi
import com.pocketide.github.GitHubAuth
import com.pocketide.model.Project
import com.pocketide.sync.DataBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

fun createProjects(graph: AppGraph): Projects = ProjectRegistry(
    env = GraphProjectEnv(graph),
    dirs = graph.dirs,
    file = JsonFile(File(graph.dirs.vault, "projects.json"), ListSerializer(Project.serializer())),
    trustFile = JsonFile(File(graph.dirs.vault, "project-trust.json"), MapSerializer(String.serializer(), ProjectTrust.serializer())),
    clock = graph.clock,
    scope = graph.scope,
    io = Dispatchers.IO,
)

/** Other modules are looked up when used, so creating this module never creates theirs. */
private class GraphProjectEnv(private val graph: AppGraph) : ProjectEnv {
    override val gitHub: GitHubApi get() = graph.gitHub
    override val gitHubAuth: GitHubAuth get() = graph.gitHubAuth
    override val git: GitGate get() = graph.git
    override val dataBudget: DataBudget get() = graph.dataBudget
    override val work: ProjectWork? get() = graph.sessions as? ProjectWork
}
