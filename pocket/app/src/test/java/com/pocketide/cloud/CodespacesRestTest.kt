package com.pocketide.cloud

import com.pocketide.core.AppJson
import com.pocketide.github.ApiFixture
import com.pocketide.github.GitHubException
import com.pocketide.github.json
import com.pocketide.github.next
import com.pocketide.github.status
import com.pocketide.github.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodespacesRestTest {
    private val server = MockWebServer()
    private lateinit var api: CodespacesRest

    @Before
    fun setUp() {
        server.start()
        api = CodespacesRest(ApiFixture(server).rest)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `a codespace reads as a computer with its machine, times and git state`() = runBlocking {
        server.enqueue(json("""{"total_count":1,"codespaces":[${codespace()}]}"""))
        val computer = api.list().single()
        assertEquals("fuzzy-space-guide-1234", computer.name)
        assertEquals("PocketIDE · demo", computer.displayName)
        assertEquals(RepoRef(42, "octo", "demo", true), computer.repo)
        assertEquals(ComputerState.STOPPED, computer.state)
        assertEquals(2, computer.machine?.cpus)
        assertEquals(30, computer.idleMinutes)
        assertEquals(43_200, computer.keepMinutes)
        assertEquals(java.time.Instant.parse("2026-10-20T10:00:00Z").toEpochMilli(), computer.deletesAtMs)
        assertTrue(computer.setUpByPocketIde)
        assertFalse(computer.git!!.safeToDelete)
        assertEquals("/user/codespaces", server.next().url.encodedPath)
    }

    @Test
    fun `a new computer asks for PocketIDE's set-up, the owner's choices and this repository only`() = runBlocking {
        server.enqueue(json(codespace(state = "Queued"), 201))
        val made = api.create(
            NewCodespace(42, "main", "PocketIDE · demo", ComputerConfig.DEVCONTAINER, machine = null, idleMinutes = 30, keepMinutes = 43_200),
        )
        assertEquals(ComputerState.CREATING, made.state)
        val sent = AppJson.parseToJsonElement(server.next().text()).jsonObject
        assertEquals(42L, sent["repository_id"]!!.jsonPrimitive.long)
        assertEquals(ComputerConfig.DEVCONTAINER, sent["devcontainer_path"]!!.jsonPrimitive.content)
        assertEquals(30, sent["idle_timeout_minutes"]!!.jsonPrimitive.int)
        assertEquals(43_200, sent["retention_period_minutes"]!!.jsonPrimitive.int)
        assertTrue(sent["multi_repo_permissions_opt_out"]!!.jsonPrimitive.boolean)
        assertNull("no machine means GitHub's smallest", sent["machine"])
    }

    @Test
    fun `start, stop and delete name the codespace in the path`() = runBlocking {
        server.enqueue(json(codespace(state = "Starting")))
        server.enqueue(json(codespace(state = "ShuttingDown")))
        server.enqueue(status(202))
        assertEquals(ComputerState.STARTING, api.start("fuzzy-space-guide-1234").state)
        assertEquals(ComputerState.STOPPING, api.stop("fuzzy-space-guide-1234").state)
        api.delete("fuzzy-space-guide-1234")
        val start = server.next()
        assertEquals("POST", start.method)
        assertEquals("/user/codespaces/fuzzy-space-guide-1234/start", start.url.encodedPath)
        assertEquals("/user/codespaces/fuzzy-space-guide-1234/stop", server.next().url.encodedPath)
        val delete = server.next()
        assertEquals("DELETE", delete.method)
        assertEquals("/user/codespaces/fuzzy-space-guide-1234", delete.url.encodedPath)
    }

    @Test
    fun `a name that could change the path is refused before any call`() {
        runCatching { runBlocking { api.delete("../repos/octo/demo") } }
        runCatching { runBlocking { api.get("a/b") } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a missing codespace is null`() = runBlocking {
        server.enqueue(json("""{"message":"Not Found"}""", 404))
        assertNull(api.get("gone-1"))
    }

    @Test
    fun `GitHub's codespace errors become sentences the owner can act on`() {
        assertEquals(CloudText.OUT_OF_HOURS, CloudErrors.of(GitHubException("x", 402)).message)
        assertEquals(CloudText.NOT_ALLOWED, CloudErrors.of(GitHubException("x", 403)).message)
        assertEquals(CloudText.BUSY, CloudErrors.of(GitHubException("x", 409)).message)
        assertEquals(CloudText.BAD_CHOICE, CloudErrors.of(GitHubException("x", 422)).message)
    }

    @Test
    fun `every GitHub state has a group`() {
        val states = listOf(
            "Unknown", "Created", "Queued", "Provisioning", "Available", "Awaiting", "Unavailable", "Deleted", "Moved",
            "Shutdown", "Archived", "Starting", "ShuttingDown", "Failed", "Exporting", "Updating", "Rebuilding",
        )
        assertEquals(ComputerState.UNKNOWN, stateOf("Unknown"))
        assertTrue(states.drop(1).none { stateOf(it) == ComputerState.UNKNOWN })
    }

    private fun codespace(state: String = "Shutdown") = """{
        "id": 1, "name": "fuzzy-space-guide-1234", "display_name": "PocketIDE · demo",
        "repository": {"id": 42, "name": "demo", "full_name": "octo/demo", "owner": {"login": "octo"}, "private": true},
        "machine": {"name": "basicLinux32gb", "display_name": "2 cores, 8 GB RAM, 32 GB storage", "cpus": 2,
                    "memory_in_bytes": 8589934592, "storage_in_bytes": 34359738368},
        "state": "$state", "web_url": "https://fuzzy-space-guide-1234.github.dev", "location": "SoutheastAsia",
        "idle_timeout_minutes": 30, "retention_period_minutes": 43200, "retention_expires_at": "2026-10-20T10:00:00Z",
        "created_at": "2026-09-20T10:00:00Z", "last_used_at": "2026-09-26T09:00:00Z",
        "git_status": {"ahead": 1, "behind": 0, "has_unpushed_changes": true, "has_uncommitted_changes": false, "ref": "main"},
        "devcontainer_path": ".devcontainer/pocketide/devcontainer.json"
    }"""
}
