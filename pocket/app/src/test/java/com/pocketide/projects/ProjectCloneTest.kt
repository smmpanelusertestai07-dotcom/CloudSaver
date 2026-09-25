package com.pocketide.projects

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.git.CheckPost
import com.pocketide.git.GitWorld
import com.pocketide.git.JGitGate
import com.pocketide.git.LocalRemotes
import com.pocketide.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Clones through the real git gate (JGit), with a repository on this machine standing in for GitHub. */
class ProjectCloneTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `the first open clones through the real gate into the project's own place`() = runBlocking<Unit> {
        val world = GitWorld(File(temp.root, "world"))
        val remote = world.githubRepo("alice__demo", mapOf("README.md" to "Demo\n"))
        val dirs = AppDirs(File(temp.root, "files"), File(temp.root, "cache"))
        val gate = JGitGate(dirs.repos, File(temp.root, "gate-state"), LocalRemotes, CheckPost(), Dispatchers.IO)
        val env = FakeProjectEnv(git = FakeCloneGate())
        env.gitHub.reachable["alice/demo"] = repoInfo("alice", "demo").copy(cloneUrl = world.url(remote))
        val projects = ProjectRegistry(
            env = object : ProjectEnv by env {
                override val git = gate
            },
            dirs = dirs,
            file = JsonFile(File(dirs.vault, "projects.json"), ListSerializer(Project.serializer())),
            trustFile = JsonFile(File(dirs.vault, "project-trust.json"), MapSerializer(String.serializer(), ProjectTrust.serializer())),
            clock = Clock { 1_000L },
            scope = scope,
            io = Dispatchers.IO,
        )

        val project = projects.import("alice/demo", "")
        projects.ensureCloned(project.id)

        val bare = dirs.bareRepo(project.id)
        assertTrue(BareRefs(bare).isCloned())
        assertTrue(BareRefs(bare).exists("refs/remotes/origin/main"))
        assertTrue(projects.all.value.single().cloned)
        assertEquals("nothing but the clone is left in the projects folder", listOf(bare.name), dirs.repos.list()!!.toList())

        world.commitToRemote(remote, "main", mapOf("CHANGES.md" to "one\n"), "Change")
        projects.ensureCloned(project.id)
        assertEquals(world.revParse(remote, "refs/heads/main"), BareRefs(bare).sha("refs/remotes/origin/main"))
        assertFalse(File(dirs.repos, ".${bare.name}.partial").exists())
    }
}
