package com.pocketide.rooms

import com.pocketide.builds.BuildTemplate
import com.pocketide.core.AppDirs
import com.pocketide.github.PullRequest
import com.pocketide.github.WorkflowRun
import com.pocketide.media.MediaItem
import com.pocketide.media.MediaKind
import com.pocketide.model.Decision
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.sessions.PutOnMainResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files

class McpToolsTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var dirs: AppDirs
    private lateinit var ports: FakePorts
    private lateinit var tools: McpTools
    private val session = SessionRecord(
        id = "s1", agentId = "claude", projectId = "octo/app", title = "Login fix", branch = "pocket/claude/2026-09-24-login-fix",
        startedAt = 0, lastActivityAt = 0, deviceId = "phone",
    )
    private val other = session.copy(id = "s2", branch = "pocket/claude/2026-09-24-other")

    @Before fun setUp() {
        dirs = AppDirs(temp.newFolder("files"), temp.newFolder("cache"))
        ports = FakePorts(listOf(session, other, session.copy(id = "c1", agentId = "codex")))
        tools = McpTools(dirs, ports)
    }

    private fun call(tool: String, cwd: String? = "/work/octo__app/s1", args: JsonObject = JsonObject(emptyMap())): String = runBlocking {
        val request = buildJsonObject {
            put("tool", tool)
            put("args", args)
            if (cwd != null) put("cwd", cwd)
        }
        tools.call("claude", request).jsonObject["text"]!!.jsonPrimitive.content
    }

    private fun failure(block: () -> Unit): String = try {
        block()
        throw AssertionError("expected a refusal")
    } catch (refused: IllegalArgumentException) {
        refused.message.orEmpty()
    } catch (refused: IllegalStateException) {
        refused.message.orEmpty()
    }

    @Test fun `the session comes from the worktree, else from the room, and only the agent's own`() {
        assertEquals("s1", tools.session("claude", "/work/octo__app/s1/src").id)
        assertEquals("s2", tools.session("claude", "/work/octo__app/s2").id)
        ports.current = "s2"
        assertEquals("s2", tools.session("claude", "/root").id)
        assertEquals("s2", tools.session("claude", null).id)
        ports.current = null
        assertTrue(failure { tools.session("claude", "/work/octo__app/c1") }.contains("not in a PocketIDE session"))
        assertTrue(failure { tools.session("claude", "/work/other__repo/s1") }.contains("not in a PocketIDE session"))
    }

    @Test fun `phone status reads the snapshot and says what is allowed`() {
        ports.snapshot = PhoneSnapshot.UNKNOWN.copy(at = 1, batteryPercent = 18, charging = false, totalRamBytes = 4_000_000_000, availRamBytes = 900_000_000, metered = true)
        ports.guard = Guard.NO_NEW_HEAVY
        val text = call("phone_status")
        assertTrue(text, text.contains("Battery 18 %."))
        assertTrue(text, text.contains("On mobile data"))
        assertTrue(text, text.contains("run_build"))
    }

    @Test fun `run_build pushes first, then starts the chosen template on the session branch`() {
        ports.templates = listOf(BuildTemplate("android-release", "Android release", "", "android.yml", "ubuntu-latest"))
        assertTrue(failure { call("run_build", args = buildJsonObject { put("template", "ios") }) }.contains("android-release (Android release)"))
        val text = call("run_build", args = buildJsonObject { put("template", "android-release") })
        assertEquals(listOf("autosave s1", "run octo/app android-release pocket/claude/2026-09-24-login-fix"), ports.calls)
        assertTrue(text.contains("run 77"))
        ports.autosaveProblem = "The check-post found a secret."
        assertTrue(failure { call("run_build", args = buildJsonObject { put("template", "android-release") }) }.contains("check-post"))
    }

    @Test fun `build_result reports progress and collects outputs when done`() {
        ports.runs = listOf(WorkflowRun(77, "Android", "b", "in_progress", null, "", "", "https://github.com/octo/app/actions/runs/77"))
        assertTrue(call("build_result", args = buildJsonObject { put("run_id", 77) }).contains("still in progress"))
        ports.runs = listOf(WorkflowRun(77, "Android", "b", "completed", "failure", "", "", "https://github.com/octo/app/actions/runs/77"))
        val text = call("build_result", args = buildJsonObject { put("run_id", 77) })
        assertTrue(text, text.contains("finished: failure") && text.contains("3 files were saved"))
        assertTrue(failure { call("build_result", args = buildJsonObject { put("run_id", 5) }) }.contains("not among"))
    }

    @Test fun `put_on_main says what happened in words the agent can act on`() {
        ports.putOnMain = PutOnMainResult.Conflicts(listOf("a.kt", "b.kt"))
        assertTrue(call("put_on_main").contains("a.kt, b.kt"))
        ports.putOnMain = PutOnMainResult.Blocked("a secret is in b.kt")
        assertTrue(failure { call("put_on_main") }.contains("a secret is in b.kt"))
        ports.putOnMain = PutOnMainResult.Merged
        assertTrue(call("put_on_main").startsWith("Done"))
    }

    @Test fun `open_pr pushes and opens a pull request to the default branch`() {
        val text = call("open_pr", args = buildJsonObject { put("title", "Fix login"); put("body", "Why") })
        assertEquals(listOf("autosave s1", "pr octo/app pocket/claude/2026-09-24-login-fix Fix login"), ports.calls)
        assertTrue(text.contains("#12"))
        assertTrue(failure { call("open_pr", args = buildJsonObject { put("title", " ") }) }.contains("title"))
    }

    @Test fun `save_media takes real files from the room and refuses sign-ins, links and other places`() {
        val worktree = dirs.worktree("claude", "octo/app", "s1").apply { mkdirs() }
        File(worktree, "shot.png").writeBytes(byteArrayOf(1, 2, 3))
        val text = call("save_media", args = buildJsonObject { put("path", "/work/octo__app/s1/shot.png"); put("title", "Home screen") })
        assertTrue(text.contains("Home screen.png"))
        assertEquals(File(worktree, "shot.png"), ports.media.single().first)

        dirs.roomHome("claude").mkdirs()
        File(dirs.roomHome("claude"), ".claude").mkdirs()
        File(dirs.roomHome("claude"), ".claude/.credentials.json").writeText("{}")
        File(worktree, ".env").writeText("KEY=1")
        val outside = temp.newFile("vault.key")
        Files.createSymbolicLink(File(worktree, "link.png").toPath(), outside.toPath())
        for (path in listOf("/root/.claude/.credentials.json", "/root/.claude.json", "/work/octo__app/s1/.env")) {
            assertTrue(path, failure { call("save_media", args = buildJsonObject { put("path", path) }) }.contains("sign-in or key"))
        }
        assertTrue(failure { call("save_media", args = buildJsonObject { put("path", "/work/octo__app/s1/link.png") }) }.contains("link"))
        assertTrue(failure { call("save_media", args = buildJsonObject { put("path", "/etc/passwd") }) }.contains("Only files"))
        assertTrue(failure { call("save_media", args = buildJsonObject { put("path", "/work/../rooms/codex/home/x.png") }) }.contains("Only files"))
        assertEquals(1, ports.media.size)
    }

    @Test fun `preview_port needs a listener and is never one of PocketIDE's own ports`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val text = call("preview_port", args = buildJsonObject { put("port", port) })
            assertTrue(text.contains("Preview"))
            assertEquals(mapOf("s1" to port), ports.announced)
            ports.own = setOf(port)
            assertTrue(failure { call("preview_port", args = buildJsonObject { put("port", port) }) }.contains("PocketIDE's own"))
        }
        assertTrue(failure { call("preview_port", args = buildJsonObject { put("port", 80) }) }.contains("1024"))
    }

    @Test fun `a dev server open to the Wi-Fi gets a warning`() {
        val lines = listOf(
            "   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1 1",
            "   1: 00000000:1F91 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1 1",
            "   2: 00000000:1F92 0100007F:C000 01 00000000:00000000 00:00000000 00000000  1000        0 1 1",
            "   0: 00000000000000000000000000000000:1F93 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 1 1",
        )
        assertEquals(false, ListenerTable.openToNetwork(lines, 8080))
        assertEquals(true, ListenerTable.openToNetwork(lines, 8081))
        assertEquals(false, ListenerTable.openToNetwork(lines, 8082))
        assertEquals(true, ListenerTable.openToNetwork(lines, 8083))
    }

    @Test fun `media names are plain and keep the extension`() {
        assertEquals("Home screen.png", McpTools.mediaName("Home screen", "shot.png"))
        assertEquals("shot.png", McpTools.mediaName(null, "shot.png"))
        assertEquals("a_b_c.webm", McpTools.mediaName("a/b\\c", "x.webm"))
        assertEquals("report.html", McpTools.mediaName("report.html", "r.html"))
    }

    @Test fun `unknown tools are refused`() {
        assertTrue(failure { call("format_phone") }.contains("no tool"))
    }

    private class FakePorts(private val all: List<SessionRecord>) : McpPorts {
        var current: String? = null
        var snapshot = PhoneSnapshot.UNKNOWN
        var guard = Guard.OK
        var templates = emptyList<BuildTemplate>()
        var runs = emptyList<WorkflowRun>()
        var putOnMain: PutOnMainResult = PutOnMainResult.Merged
        var autosaveProblem: String? = null
        var own = emptySet<Int>()
        val calls = mutableListOf<String>()
        val media = mutableListOf<Pair<File, String>>()
        val announced = mutableMapOf<String, Int>()

        override fun sessions(agentId: String) = all.filter { it.agentId == agentId }
        override fun currentSession(agentId: String) = current
        override fun project(projectId: String) = Project(id = projectId, owner = "octo", repo = "app", addedAt = 0, lastActivityAt = 0)
        override fun phone() = snapshot
        override fun guard() = guard
        override fun maxAgents() = 1
        override fun heavyWork(what: String) = Decision.YES
        override suspend fun autosave(sessionId: String): String? {
            calls += "autosave $sessionId"
            return autosaveProblem
        }
        override suspend fun putOnMain(sessionId: String) = putOnMain
        override fun templates() = templates
        override suspend fun runBuild(projectId: String, templateId: String, ref: String): Long {
            calls += "run $projectId $templateId $ref"
            return 77
        }
        override suspend fun recentRuns(projectId: String) = runs
        override suspend fun collect(projectId: String, sessionId: String, runId: Long) = 3
        override suspend fun openPullRequest(project: Project, head: String, title: String, body: String): PullRequest {
            calls += "pr ${project.id} $head $title"
            return PullRequest(12, "https://github.com/octo/app/pull/12", "open", merged = false, mergeable = null)
        }
        override suspend fun addMedia(sessionId: String, file: File, name: String): MediaItem {
            media += file to name
            return MediaItem(sessionId, file, name, MediaKind.IMAGE, file.length(), 0, onPhone = true, backedUp = false, source = "agent")
        }
        override fun announcePort(sessionId: String, port: Int) {
            announced[sessionId] = port
        }
        override fun ownPorts() = own
        override suspend fun browser(agentId: String) = "installing"
        override fun listeners() = emptyList<String>()
    }
}
