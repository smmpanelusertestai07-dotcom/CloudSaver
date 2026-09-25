package com.pocketide.projects

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.model.Decision
import com.pocketide.model.Project
import com.pocketide.sync.MeteredDataBudget
import com.pocketide.sync.NeedsMobileData
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
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val env = FakeProjectEnv()
    private var now = 1_000_000L
    private val dirs: AppDirs by lazy { AppDirs(File(temp.root, "files"), File(temp.root, "cache")) }

    @After
    fun tearDown() = scope.cancel()

    private fun registry() = ProjectRegistry(
        env = env,
        dirs = dirs,
        file = JsonFile(File(dirs.vault, "projects.json"), ListSerializer(Project.serializer())),
        trustFile = JsonFile(File(dirs.vault, "project-trust.json"), MapSerializer(String.serializer(), ProjectTrust.serializer())),
        clock = Clock { now },
        scope = scope,
        io = Dispatchers.IO,
    )

    private inline fun <reified T : Throwable> failsWith(block: () -> Unit): T {
        try {
            block()
        } catch (expected: Throwable) {
            if (expected is T) return expected
            throw expected
        }
        fail("Expected ${T::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun `create makes a private repository with a first commit and it is the owner's`() = runBlocking<Unit> {
        val projects = registry()

        val project = projects.create("  My App ", " A small\n app ")

        assertEquals(Triple("My-App", "A small app", true), env.gitHub.created.single())
        assertEquals("alice/my-app", project.id)
        assertEquals(now, project.addedAt)
        assertEquals(listOf(project), projects.all.value)
        assertEquals(ProjectTrust.YOURS, projects.trustOf(project.id))
        failsWith<ProjectException> { runBlocking { projects.create("bad/name", "") } }
    }

    @Test
    fun `import takes any pasted form and says when the app cannot reach the repository`() = runBlocking<Unit> {
        val projects = registry()
        env.gitHub.reachable["bob/tool"] = repoInfo("bob", "tool", isPrivate = false, defaultBranch = "trunk")

        val project = projects.import("https://github.com/Bob/Tool.git?tab=readme#top", "")

        assertEquals("bob/tool", project.id)
        assertEquals("trunk", project.defaultBranch)
        assertFalse(project.isPrivate)
        assertEquals(ProjectTrust.SOMEONE_ELSES, projects.trustOf(project.id))

        val missing = failsWith<RepoNotReachableException> { runBlocking { projects.import("carol", "secret") } }
        assertEquals("https://github.com/settings/installations/7", missing.installUrl)
        assertEquals(RepoAddress("carol", "secret"), missing.address)
        assertEquals("Add this repository to PocketIDE's GitHub App, then try again.", missing.message)
        failsWith<ProjectException> { runBlocking { projects.import("https://gitlab.com/a/b", "") } }

        env.gitHub.offline = true
        val offline = failsWith<ProjectException> { runBlocking { projects.import("bob/tool", "") } }
        assertEquals("Could not reach GitHub. Check the connection and try again.", offline.message)
    }

    @Test
    fun `the owner's own repositories are theirs, and the owner can say otherwise`() = runBlocking<Unit> {
        val projects = registry()
        env.gitHub.reachable["alice/site"] = repoInfo("alice", "site")
        val site = projects.import("alice/site", "")
        assertEquals(ProjectTrust.YOURS, projects.trustOf(site.id))

        projects.setTrust(site.id, ProjectTrust.SOMEONE_ELSES)
        val again = registry()
        again.adopt(emptyList())
        assertEquals("the owner's answer is kept", ProjectTrust.SOMEONE_ELSES, again.trustOf(site.id))
        assertEquals("it travels with the project", ProjectTrust.SOMEONE_ELSES.name, again.all.value.single().trust)
    }

    @Test
    fun `a fork under the owner's own account is someone else's code`() = runBlocking<Unit> {
        val projects = registry()
        env.gitHub.reachable["alice/forked"] = repoInfo("alice", "forked").copy(fork = true)
        val fork = projects.import("alice/forked", "")
        assertEquals(ProjectTrust.SOMEONE_ELSES, projects.trustOf(fork.id))
        assertEquals(ProjectTrust.SOMEONE_ELSES.name, fork.trust)
    }

    @Test
    fun `the answer from another phone wins over the automatic one and survives an older index`() = runBlocking<Unit> {
        val projects = registry()
        val incoming = Project("alice/app", "alice", "app", addedAt = 5, lastActivityAt = 50, trust = ProjectTrust.SOMEONE_ELSES.name)
        projects.adopt(listOf(incoming))
        assertEquals(ProjectTrust.SOMEONE_ELSES, projects.trustOf("alice/app"))
        assertEquals(ProjectTrust.SOMEONE_ELSES, projects.trust.value["alice/app"])

        projects.adopt(listOf(incoming.copy(trust = null)))
        assertEquals(ProjectTrust.SOMEONE_ELSES, projects.trustOf("alice/app"))
    }

    @Test
    fun `the first open clones, a big clone asks the data rules first, later opens fetch`() = runBlocking<Unit> {
        val projects = registry()
        env.gitHub.reachable["bob/big"] = repoInfo("bob", "big", sizeKb = 60 * 1024)
        val project = projects.import("bob/big", "")

        env.dataBudget.decision = Decision.no("This project is big, so it downloads on Wi-Fi.")
        val waiting = failsWith<ProjectException> { runBlocking { projects.ensureCloned(project.id) } }
        assertEquals("This project is big, so it downloads on Wi-Fi.", waiting.message)
        assertEquals(Triple(60L * 1024 * 1024, "clone", true), env.dataBudget.asked.single())
        assertTrue(env.git.cloned.isEmpty())

        env.dataBudget.decision = Decision.YES
        projects.ensureCloned(project.id)
        assertEquals(listOf("https://github.com/bob/big.git"), env.git.cloned)
        assertTrue(File(dirs.bareRepo(project.id), "HEAD").isFile)
        assertTrue(projects.all.value.single().cloned)
        assertEquals(listOf(60L * 1024 * 1024), env.dataBudget.recorded)

        projects.ensureCloned(project.id)
        assertEquals(1, env.git.cloned.size)
        assertEquals(listOf(dirs.bareRepo(project.id)), env.git.fetched)
    }

    @Test
    fun `a big clone on mobile data asks with its size and goes once the owner agrees`() = runBlocking<Unit> {
        val projects = registry()
        env.gitHub.reachable["bob/big"] = repoInfo("bob", "big", sizeKb = 60 * 1024)
        val project = projects.import("bob/big", "")
        env.dataBudget.decision = Decision.no(MeteredDataBudget.WAITS_FOR_WIFI)

        val ask = failsWith<NeedsMobileData> { runBlocking { projects.ensureCloned(project.id) } }

        assertEquals("clone", ask.kind)
        assertEquals(60L * 1024 * 1024, ask.bytes)
        assertEquals("This download is about 60 MB, so it waits for Wi-Fi.", ask.message)
        assertTrue(env.git.cloned.isEmpty())

        env.dataBudget.allowOnce(ask.kind, ask.bytes)
        projects.ensureCloned(project.id)
        assertEquals(listOf("https://github.com/bob/big.git"), env.git.cloned)
        assertEquals(listOf(60L * 1024 * 1024), env.dataBudget.recorded)
    }

    @Test
    fun `a small clone is not big, and an interrupted clone leaves nothing that looks finished`() = runBlocking<Unit> {
        val projects = registry()
        env.gitHub.reachable["bob/small"] = repoInfo("bob", "small", sizeKb = 50 * 1024)
        val project = projects.import("bob/small", "")
        env.git.failClone = true

        failsWith<Exception> { runBlocking { projects.ensureCloned(project.id) } }

        assertFalse(env.dataBudget.asked.single().third)
        assertFalse(dirs.bareRepo(project.id).exists())
        assertTrue(dirs.repos.listFiles().orEmpty().isEmpty())
        assertFalse(projects.all.value.single().cloned)
    }

    @Test
    fun `remove refuses while work is not on github, and never touches the repository there`() = runBlocking<Unit> {
        val projects = registry()
        env.gitHub.reachable["bob/tool"] = repoInfo("bob", "tool")
        val project = projects.import("bob/tool", "")
        projects.ensureCloned(project.id)
        env.work.unsavedReason = "A session of this project has changes that are not committed."

        val refused = failsWith<ProjectException> { runBlocking { projects.remove(project.id) } }
        assertEquals("A session of this project has changes that are not committed.", refused.message)
        assertTrue(dirs.bareRepo(project.id).exists())

        env.work.unsavedReason = null
        projects.remove(project.id)
        assertEquals(listOf(project.id), env.work.released)
        assertFalse(dirs.bareRepo(project.id).exists())
        assertTrue(projects.all.value.isEmpty())
        assertTrue("GitHub keeps it", env.gitHub.reachable.containsKey("bob/tool"))
    }

    @Test
    fun `projects are kept across restarts and touched only forward, never past the clock`() = runBlocking<Unit> {
        val projects = registry()
        env.gitHub.reachable["bob/tool"] = repoInfo("bob", "tool")
        val project = projects.import("bob/tool", "")

        now += 3_600_000
        projects.touched(project.id, now)
        projects.touched(project.id, now - 3_599_000)
        projects.touched(project.id, now + 400L * 24 * 3_600_000)
        val until = System.currentTimeMillis() + 5_000
        while (projects.all.value.single().lastActivityAt != now && System.currentTimeMillis() < until) Thread.sleep(10)
        Thread.sleep(100)

        val again = registry()
        again.adopt(emptyList())
        assertEquals("a time from the future counts as now", now, again.all.value.single().lastActivityAt)
        assertEquals("bob", again.all.value.single().owner)
    }

    @Test
    fun `a stored list that no longer parses is set aside, never overwritten`() = runBlocking<Unit> {
        File(dirs.vault, "projects.json").apply { parentFile?.mkdirs(); writeText("{not json") }
        val projects = registry()
        projects.adopt(emptyList())
        assertTrue(projects.all.value.isEmpty())
        assertTrue(dirs.vault.listFiles()!!.any { it.name.startsWith("projects.json.unreadable-") })
    }

    @Test
    fun `adopt takes projects from the vault index and keeps the newest activity`() = runBlocking<Unit> {
        val projects = registry()
        val incoming = Project("dave/lib", "dave", "lib", addedAt = 5, lastActivityAt = 50, cloned = true)
        projects.adopt(listOf(incoming))
        val adopted = projects.all.value.single()
        assertFalse("a clone is this phone's own fact", adopted.cloned)
        assertEquals(ProjectTrust.SOMEONE_ELSES, projects.trustOf("dave/lib"))
        projects.adopt(listOf(incoming.copy(lastActivityAt = 10)))
        assertEquals(50, projects.all.value.single().lastActivityAt)
    }
}
