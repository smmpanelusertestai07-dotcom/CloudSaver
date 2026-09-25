package com.pocketide.rooms

import com.pocketide.bridge.PhoneGuestTools
import com.pocketide.core.AppDirs
import com.pocketide.linux.LinuxCommand
import com.pocketide.linux.ProotCommand
import com.pocketide.linux.ProotHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoomLayoutTest {
    private val dirs = AppDirs(File("/data/user/0/com.pocketide/files"), File("/data/user/0/com.pocketide/cache"))
    private val tokenLike = Regex("(?i)token|secret|passw|credential|api_?key")

    @Test fun `a room binds its own folders and the shared repositories, nothing else`() {
        val binds = RoomLayout.binds(dirs, "claude")
        assertEquals(
            listOf(
                "${dirs.base}/rooms/claude/home" to "/root",
                "${dirs.base}/rooms/claude/tmp" to "/tmp",
                "${dirs.base}/rooms/claude/shm" to "/dev/shm",
                "${dirs.base}/bridge/claude" to "/run/pocketide",
                "${dirs.base}/repos" to "/repos",
                "${dirs.base}/work/claude" to "/work",
            ),
            binds.map { it.hostPath to it.guestPath },
        )
        for (other in listOf("codex", "antigravity")) {
            assertTrue(binds.none { "/$other" in it.hostPath })
        }
        assertTrue(binds.none { it.readOnly })
        // Nothing of the app's own storage beyond these: no vault, no queue, no secure store.
        val allowed = setOf(
            dirs.roomHome("claude"), dirs.roomTmp("claude"), RoomLayout.shm(dirs, "claude"), dirs.roomBridge("claude"), dirs.repos, dirs.roomWork("claude"),
        )
        assertEquals(allowed.map { it.absolutePath }.toSet(), binds.map { it.hostPath }.toSet())
    }

    @Test fun `each room has a writable shared-memory folder of its own, created with the room`() {
        val shm = RoomLayout.binds(dirs, "claude").single { it.guestPath == "/dev/shm" }
        assertFalse(shm.readOnly)
        assertTrue(File(shm.hostPath) in RoomLayout.hostFolders(dirs, "claude"))
        assertTrue(RoomLayout.binds(dirs, "codex").none { it.hostPath == shm.hostPath })
        val argv = ProotCommand.build(
            ProotHost(File("/lib"), File("/tmp")), File("/rootfs"), emptyMap(), "UTC",
            LinuxCommand(listOf("python3"), binds = RoomLayout.binds(dirs, "claude")), sharedMemory = File("/rootfs/tmp"),
        ).argv
        // The room's own folder, never the computer's shared /tmp that set-up uses.
        assertTrue(argv.none { it.endsWith(":/dev/shm") && it != "${shm.hostPath}:/dev/shm" })
    }

    @Test fun `every engine command uses exactly the room's binds`() {
        val profile = RoomProfiles.of("codex", null)!!
        val worktree = AppDirs.guestWorktree("octo/app", "s1")
        val commands = listOf(
            RoomEngines.codeServer(dirs, profile, worktree, 40001, emptyMap()),
            RoomEngines.hub(dirs, RoomProfiles.of("antigravity", null)!!, worktree, 40002, emptyMap(), token = "e".repeat(64)),
            RoomEngines.terminal(dirs, "codex", worktree, 40003, emptyMap()),
            RoomEngines.setUpOnly(dirs, "codex", emptyMap()),
        )
        assertEquals(RoomLayout.binds(dirs, "codex"), commands[0].binds)
        assertEquals(RoomLayout.binds(dirs, "antigravity"), commands[1].binds)
        assertEquals(RoomLayout.binds(dirs, "codex"), commands[2].binds)
        assertEquals(RoomLayout.binds(dirs, "codex"), commands[3].binds)
    }

    @Test fun `the environment carries Variables and the room's facts, never a token`() {
        val dropped = mutableListOf<String>()
        val env = RoomLayout.environment(
            agentId = "claude",
            variables = mapOf(
                "API_BASE_URL" to "https://staging.example",
                "GITHUB_TOKEN" to "ghp_x",
                "GH_TOKEN" to "x",
                "PATH" to "/evil",
                "HOME" to "/tmp",
                "LD_PRELOAD" to "/tmp/x.so",
                "POCKETIDE_ROOM" to "codex",
                "HASHED_PASSWORD" to "x",
                "VSCODE_OPTIONS" to "--auth none",
            ),
            engine = mapOf("NODE_OPTIONS" to "--max-old-space-size=512"),
        ) { dropped += it }
        assertEquals("claude", env["POCKETIDE_ROOM"])
        assertEquals("/work", env["POCKETIDE_MEDIA_ROOT"])
        assertEquals(PhoneGuestTools.XDG_OPEN, env["BROWSER"])
        assertTrue("the phone's xdg-open comes first", ProotCommand.GUEST_PATH.startsWith(PhoneGuestTools.BIN_DIR + ":"))
        assertEquals("https://staging.example", env["API_BASE_URL"])
        assertEquals("--max-old-space-size=512", env["NODE_OPTIONS"])
        assertEquals(
            setOf("GITHUB_TOKEN", "GH_TOKEN", "PATH", "HOME", "LD_PRELOAD", "POCKETIDE_ROOM", "HASHED_PASSWORD", "VSCODE_OPTIONS"),
            dropped.toSet(),
        )
        assertTrue(env.keys.none { tokenLike.containsMatchIn(it) })
    }

    @Test fun `every program in the Antigravity room has agy's self-updater off, and no Variable turns it on`() {
        val env = RoomLayout.environment("antigravity", mapOf("AGY_CLI_DISABLE_AUTO_UPDATE" to "false"), emptyMap())
        assertEquals("true", env["AGY_CLI_DISABLE_AUTO_UPDATE"])
        val terminal = RoomEngines.terminal(dirs, "antigravity", "/work/octo__app/a1", 40003, env)
        assertEquals("true", terminal.env["AGY_CLI_DISABLE_AUTO_UPDATE"])
        assertNull(RoomLayout.environment("claude", emptyMap(), emptyMap())["AGY_CLI_DISABLE_AUTO_UPDATE"])
    }

    @Test fun `no engine command carries a secret in its arguments or environment`() {
        val secret = "f".repeat(64)
        val hubToken = "e".repeat(64)
        val profile = RoomProfiles.of("claude", null)!!
        val worktree = AppDirs.guestWorktree("octo/app", "s1")
        val environment = RoomLayout.environment("claude", emptyMap(), mapOf("GIT_AUTHOR_NAME" to "Octo"))
        val commands: List<LinuxCommand> = listOf(
            RoomEngines.codeServer(dirs, profile, worktree, 40001, environment),
            RoomEngines.hub(dirs, RoomProfiles.of("antigravity", null)!!, worktree, 40002, environment, token = hubToken),
            RoomEngines.terminal(dirs, "claude", worktree, 40003, environment),
        )
        val hash = RoomEngines.sha256Hex(secret)
        for (command in commands) {
            val everything = command.argv + command.env.keys + command.env.values
            assertTrue(everything.none { secret in it || hash in it })
            assertTrue(command.env.keys.none { tokenLike.containsMatchIn(it) })
        }
        // The one exception: agy takes its token only as an argument, and nowhere else.
        val hub = commands[1]
        assertEquals(listOf("--csrf_token=$hubToken"), (hub.argv + hub.env.keys + hub.env.values).filter { hubToken in it })
        // The secret reaches code-server only through its config file, and the WebView never holds it.
        assertEquals("hashed-password: \"$hash\"\n", RoomEngines.codeServerConfig(secret))
        assertEquals("code-server-session=$hash", RoomEngines.sessionCookie(secret))
    }

    @Test fun `code-server listens on loopback with a password and no proxy route`() {
        val command = RoomEngines.codeServer(dirs, RoomProfiles.of("claude", null)!!, "/work/octo__app/s1", 40001, emptyMap())
        val argv = command.argv
        assertEquals(listOf(RoomLayout.PYTHON, RoomLayout.ROOM_LAUNCHER, "claude", "--", RoomEngines.CODE_SERVER), argv.take(5))
        assertEquals("127.0.0.1:40001", argv[argv.indexOf("--bind-addr") + 1])
        assertEquals("password", argv[argv.indexOf("--auth") + 1])
        assertEquals("/run/pocketide/.code-server-40001.secret", argv[argv.indexOf("--config") + 1])
        assertTrue("--disable-proxy" in argv && "--disable-telemetry" in argv && "--disable-update-check" in argv)
        assertTrue("--disable-workspace-trust" in argv)
        assertEquals("/work/octo__app/s1", argv.last())
        assertEquals("/work/octo__app/s1", command.workDir)
        assertEquals("claude-vscode.primaryEditor.open", command.env["POCKETIDE_OPEN_COMMAND"])
        assertEquals("editor", command.env["POCKETIDE_OPEN_PLACE"])
        // A template with {{port}} where a port number goes stops code-server 4.138's workbench on every connect.
        assertFalse("VSCODE_PROXY_URI" in command.env)
        val inherited = RoomEngines.codeServer(dirs, RoomProfiles.of("claude", null)!!, "/work/octo__app/s1", 40001, mapOf("VSCODE_PROXY_URI" to "x"))
        assertFalse("VSCODE_PROXY_URI" in inherited.env)
    }

    @Test fun `the hub runs as its extension starts it, without its self-updater`() {
        val token = "e".repeat(64)
        val command = RoomEngines.hub(dirs, RoomProfiles.of("antigravity", null)!!, "/work/octo__app/s1", 40002, emptyMap(), token)
        assertTrue(
            command.argv.containsAll(
                listOf(RoomEngines.AGY, "--hub", "--hub-port=40002", "--app_data_dir=antigravity", "--csrf_token=$token", "--add-dir=/work/octo__app/s1"),
            ),
        )
        assertEquals("1", command.env["AGY_ENABLE_HUB"])
        assertEquals("true", command.env["AGY_CLI_DISABLE_AUTO_UPDATE"])
    }

    @Test fun `guest paths map to the room's own places only`() {
        assertEquals(RoomPath(dirs.roomWork("codex"), "octo__app/s1/shot.png"), RoomLayout.hostPath(dirs, "codex", "/work/octo__app/s1/shot.png"))
        assertEquals(RoomPath(dirs.roomTmp("codex"), "a.webm"), RoomLayout.hostPath(dirs, "codex", "/tmp/./a.webm"))
        assertEquals(RoomPath(dirs.roomHome("codex"), "x.png"), RoomLayout.hostPath(dirs, "codex", "/root/x.png"))
        for (outside in listOf("/etc/passwd", "/repos/a.git/config", "/work/../rooms/claude/home/x", "/tmp", "relative.png", "/run/pocketide/phone.sock")) {
            assertNull(outside, RoomLayout.hostPath(dirs, "codex", outside))
        }
        assertEquals(".claude/.credentials.json", RoomLayout.homeRelative("/root/.claude/.credentials.json"))
        assertNull(RoomLayout.homeRelative("/rootx/y"))
    }

    @Test fun `profiles for the official agents and a discovered one`() {
        val claude = RoomProfiles.of("claude", null)!!
        assertEquals(Engine.CODE_SERVER, claude.engine)
        assertEquals("anthropic.claude-code", claude.extensionId)
        assertEquals(listOf(".claude/CLAUDE.md"), claude.instructionFiles)
        val codex = RoomProfiles.of("codex", null)!!
        assertEquals("chatgpt.openSidebar", codex.openCommand)
        assertEquals(listOf(".codex/AGENTS.md"), codex.instructionFiles)
        val antigravity = RoomProfiles.of("antigravity", null)!!
        assertEquals(Engine.AGY_HUB, antigravity.engine)
        assertEquals(listOf(".gemini/GEMINI.md"), antigravity.instructionFiles)
        assertNull(RoomProfiles.of("kilocode.kilo-code", null))
        assertFalse(RoomProfiles.isAgentId(".."))
        assertFalse(RoomProfiles.isAgentId("a/b"))
    }
}
