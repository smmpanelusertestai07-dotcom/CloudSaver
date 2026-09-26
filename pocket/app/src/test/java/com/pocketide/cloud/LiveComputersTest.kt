package com.pocketide.cloud

import com.pocketide.core.NewComputerChoices
import com.pocketide.github.AccountUsage
import com.pocketide.github.ApiFixture
import com.pocketide.github.GitHubAccount
import com.pocketide.github.GitHubApi
import com.pocketide.github.GitHubException
import com.pocketide.github.NewFile
import com.pocketide.github.RepoFile
import com.pocketide.github.RepoInfo
import com.pocketide.github.TestClock
import com.pocketide.github.WorkflowRun
import com.pocketide.github.json
import com.pocketide.github.next
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LiveComputersTest {
    private val server = MockWebServer()
    private val gitHub = FakeGitHub()
    private val steps = mutableListOf<OpenStep>()
    private lateinit var computers: LiveComputers

    @Before
    fun setUp() {
        server.start()
        computers = LiveComputers(CodespacesRest(ApiFixture(server).rest), gitHub, TestClock(), pause = {})
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `the computer PocketIDE made for a repository is used again`() = runBlocking {
        server.enqueue(json(page(codespace("other-1", config = null), codespace("ours-1"))))
        server.enqueue(json(codespace("ours-1", state = "Available")))
        val opened = computers.openFor(repo, NewComputerChoices(), addSetUp = false) { steps += it }
        assertEquals("ours-1", opened.name)
        assertEquals(listOf(OpenStep.CHECKING, OpenStep.READY), steps)
        assertTrue("nothing written to the repository", gitHub.commits.isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a repository without the set-up asks first, and writes nothing`() = runBlocking {
        server.enqueue(json(page()))
        try {
            computers.openFor(repo, NewComputerChoices(), addSetUp = false) { steps += it }
            fail("expected the owner to be asked")
        } catch (e: SetUpNeededException) {
            assertEquals(SetUpFiles.MISSING, e.files)
        }
        assertTrue(gitHub.commits.isEmpty())
    }

    @Test
    fun `with the owner's yes, the set-up is committed and a computer made and started`() = runBlocking {
        server.enqueue(json(page()))
        server.enqueue(json(codespace("new-1", state = "Queued"), 201))
        server.enqueue(json(codespace("new-1", state = "Provisioning")))
        server.enqueue(json(codespace("new-1", state = "Available")))
        val opened = computers.openFor(repo, NewComputerChoices(idleMinutes = 999, keepDays = 90), addSetUp = true) { steps += it }
        assertEquals("new-1", opened.name)
        assertEquals(listOf(ComputerConfig.DEVCONTAINER, ComputerConfig.SETTINGS, ComputerConfig.SETUP), gitHub.commits.single().map { it.path })
        assertEquals(listOf(OpenStep.CHECKING, OpenStep.ADDING_SET_UP, OpenStep.CREATING, OpenStep.READY), steps.distinct())
        server.next()
        val create = server.next().body?.utf8().orEmpty()
        assertTrue("idle time kept within GitHub's 240 minutes", create.contains("\"idle_timeout_minutes\":240"))
        assertTrue("kept within GitHub's 30 days", create.contains("\"retention_period_minutes\":43200"))
    }

    @Test
    fun `a stopped computer is started once and followed until ready`() = runBlocking {
        server.enqueue(json(codespace("ours-1", state = "Shutdown")))
        server.enqueue(json(codespace("ours-1", state = "Starting")))
        server.enqueue(json(codespace("ours-1", state = "Starting")))
        server.enqueue(json(codespace("ours-1", state = "Available")))
        computers.startAndWait("ours-1") { steps += it }
        server.next()
        assertEquals("/user/codespaces/ours-1/start", server.next().url.encodedPath)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `used-up hours are said plainly`() = runBlocking {
        server.enqueue(json(page()))
        server.enqueue(json("""{"message":"Payment required"}""", 402))
        try {
            computers.openFor(repo, NewComputerChoices(), addSetUp = true) {}
            fail("expected GitHub's refusal")
        } catch (e: GitHubException) {
            assertEquals(CloudText.OUT_OF_HOURS, e.message)
        }
    }

    @Test
    fun `display names stay within GitHub's limit`() {
        assertEquals(LiveComputers.DISPLAY_NAME_MAX, LiveComputers.displayName("x".repeat(100)).length)
        assertEquals("PocketIDE · demo", LiveComputers.displayName("demo"))
    }

    private val repo = RepoInfo(42, "octo", "demo", isPrivate = true, defaultBranch = "main", htmlUrl = "https://github.com/octo/demo", pushedAt = null)

    private fun page(vararg codespaces: String) = """{"total_count":${codespaces.size},"codespaces":[${codespaces.joinToString(",")}]}"""

    private fun codespace(name: String, state: String = "Shutdown", config: String? = ComputerConfig.DEVCONTAINER) = """{
        "name": "$name", "repository": {"id": 42, "name": "demo", "owner": {"login": "octo"}, "private": true},
        "state": "$state", "web_url": "https://$name.github.dev"${config?.let { ", \"devcontainer_path\": \"$it\"" }.orEmpty()}
    }"""

    /** Only what the computers use: reading and committing the set-up files. */
    private class FakeGitHub : GitHubApi {
        val commits = mutableListOf<List<NewFile>>()

        override suspend fun readFile(owner: String, name: String, path: String, ref: String?): RepoFile? = null

        override suspend fun commitFiles(owner: String, name: String, branch: String, files: List<NewFile>, message: String): String {
            commits += files
            return "c1"
        }

        override suspend fun me(): GitHubAccount = unused()
        override suspend fun repos(): List<RepoInfo> = unused()
        override suspend fun repo(owner: String, name: String): RepoInfo = unused()
        override suspend fun createPrivateRepo(name: String, description: String): RepoInfo = unused()
        override suspend fun runs(owner: String, name: String): List<WorkflowRun> = unused()
        override suspend fun accountUsage(): AccountUsage = unused()
        override suspend fun installedOn(login: String): Boolean = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException()
    }
}
