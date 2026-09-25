package com.pocketide.rooms

import com.pocketide.core.AppDirs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The APK's assets, read from the source tree. */
internal class FolderRoomAssets(private val root: File = File("src/main/assets")) : RoomAssets {
    override fun files(folder: String): List<String> {
        val base = File(root, folder)
        return base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).invariantSeparatorsPath }.sorted().toList()
    }

    override fun read(path: String): ByteArray = File(root, path).takeIf { it.isFile }?.readBytes() ?: throw IOException("No asset $path")
}

class RoomConfiguratorTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var dirs: AppDirs
    private lateinit var configurator: RoomConfigurator
    private val log = mutableListOf<String>()

    @Before fun setUp() {
        dirs = AppDirs(temp.newFolder("files"), temp.newFolder("cache"))
        File(dirs.rootfs, "opt").mkdirs()
        configurator = RoomConfigurator(dirs, FolderRoomAssets(), { 1_000L }) { agent, line -> log += "$agent: $line" }
    }

    private fun home(agent: String, path: String) = File(dirs.roomHome(agent), path)

    @Test fun `the tools land in the computer's opt folder, the shim executable`() {
        configurator.installTools()
        val tools = File(dirs.rootfs, "opt/pocketide")
        for (name in listOf("mcp.py", "term.py", "room.py", "notify.py", "bin/xdg-open", "web/terminal/index.html", "web/terminal/xterm.js", "browser-setup/install.py", "browser-setup/package-lock.json")) {
            assertTrue(name, File(tools, name).isFile)
        }
        assertTrue(File(tools, "bin/xdg-open").canExecute())
        assertFalse(configurator.browserInstalled())
        File(dirs.rootfs, "opt/pocketide/browser").mkdirs()
        File(dirs.rootfs, "opt/pocketide/browser/installed.json").writeText("{}")
        assertTrue(configurator.browserInstalled())
    }

    @Test fun `a Claude room gets its rules, settings, deny rules and companion`() {
        home("claude", ".claude").mkdirs()
        home("claude", ".claude/CLAUDE.md").writeText("Answer in French.\n")
        home("claude", ".claude/.credentials.json").writeText("{\"token\":\"x\"}")
        home("claude", ".claude/.credentials.json").setReadable(true, false)
        configurator.configure(RoomProfiles.of("claude", null)!!, listOf("codex", "antigravity"), 15)

        val rules = home("claude", ".claude/CLAUDE.md").readText()
        assertTrue(rules.startsWith(ManagedBlock.BEGIN))
        assertTrue(rules.contains("You are Claude Code"))
        assertTrue(rules.endsWith("Answer in French.\n"))

        val settings = Json.parseToJsonElement(home("claude", ".claude/settings.json").readText()).jsonObject
        val deny = settings["permissions"]!!.jsonObject["deny"]!!.jsonArray.map { it.jsonPrimitive.content }
        for (other in listOf("codex", "antigravity")) {
            assertTrue(deny.contains("Read(/${dirs.roomHome(other).absolutePath}/**)"))
            assertTrue(deny.contains("Edit(/${dirs.roomWork(other).absolutePath}/**)"))
        }
        assertTrue(deny.none { it.contains(dirs.roomHome("claude").absolutePath) })
        assertTrue(deny.contains("Read(//proc/*/root/**)"))
        assertEquals(3650, settings["cleanupPeriodDays"]!!.jsonPrimitive.content.toInt())

        val codeServer = Json.parseToJsonElement(home("claude", RoomConfigurator.CODE_SERVER_SETTINGS).readText()).jsonObject
        assertEquals("15", codeServer["editor.fontSize"]!!.jsonPrimitive.content)
        assertTrue(home("claude", "${RoomConfigurator.EXTENSIONS}/pocketide.pocketide-companion-3.0.0/package.json").isFile)

        // The sign-in file is left as it was, only made owner-only.
        assertEquals("{\"token\":\"x\"}", home("claude", ".claude/.credentials.json").readText())
        assertEquals(2, Files.getPosixFilePermissions(home("claude", ".claude/.credentials.json").toPath()).size)
        assertEquals(3, Files.getPosixFilePermissions(dirs.roomHome("claude").toPath()).size)
    }

    @Test fun `configuring twice changes nothing`() {
        val profile = RoomProfiles.of("claude", null)!!
        configurator.configure(profile, listOf("codex"), 14)
        val snapshot = dirs.roomHome("claude").walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }
        configurator.configure(profile, listOf("codex"), 14)
        assertEquals(snapshot, dirs.roomHome("claude").walkTopDown().filter { it.isFile }.associate { it.path to it.readText() })
    }

    @Test fun `a Codex room gets its config, and the override file when the owner has one`() {
        home("codex", ".codex").mkdirs()
        home("codex", ".codex/AGENTS.override.md").writeText("Owner's override.\n")
        home("codex", ".codex/config.toml").writeText("model = \"gpt-5.5-codex\"\n\n[mcp_servers.github]\ncommand = \"npx\"\n")
        configurator.configure(RoomProfiles.of("codex", null)!!, listOf("claude"), 14)
        val config = home("codex", ".codex/config.toml").readText()
        assertTrue(config.startsWith("model = \"gpt-5.5-codex\"\n"))
        assertTrue(config.contains("[mcp_servers.github]"))
        assertTrue(config.contains("[mcp_servers.pocketide]\ncommand = \"python3\"\nargs = [\"/opt/pocketide/mcp.py\"]"))
        assertTrue(config.contains("notify = [\"python3\", \"/opt/pocketide/notify.py\", \"codex\"]"))
        assertFalse("no browser servers before the browser is installed", config.contains("playwright"))
        assertTrue(home("codex", ".codex/AGENTS.md").readText().contains(ManagedBlock.BEGIN))
        val override = home("codex", ".codex/AGENTS.override.md").readText()
        assertTrue(override.contains(ManagedBlock.BEGIN) && override.contains("Owner's override."))
    }

    @Test fun `browser servers are registered once the browser is installed`() {
        File(dirs.rootfs, "opt/pocketide/browser").mkdirs()
        File(dirs.rootfs, "opt/pocketide/browser/installed.json").writeText("{}")
        configurator.configure(RoomProfiles.of("antigravity", null)!!, emptyList(), 14)
        val mcp = Json.parseToJsonElement(home("antigravity", ".gemini/config/mcp_config.json").readText()).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("pocketide", "playwright", "chrome-devtools"), mcp.keys)
        val settings = Json.parseToJsonElement(home("antigravity", ".gemini/antigravity-cli/settings.json").readText()).jsonObject
        assertEquals("false", settings["enableTelemetry"]!!.jsonPrimitive.content)
        assertTrue(home("antigravity", ".gemini/GEMINI.md").readText().contains("You are Antigravity"))
        assertFalse("the hub room has no code-server", home("antigravity", RoomConfigurator.USER_DATA).exists())
    }

    @Test fun `a broken settings file is kept aside and written fresh`() {
        home("claude", ".claude").mkdirs()
        home("claude", ".claude/settings.json").writeText("{ this is not json")
        configurator.configure(RoomProfiles.of("claude", null)!!, emptyList(), 14)
        assertEquals("{ this is not json", home("claude", ".claude/settings.json.pocketide-broken").readText())
        assertTrue(home("claude", ".claude/settings.json").readText().contains("cleanupPeriodDays"))
        assertTrue(log.any { it.contains("could not be read") })
    }

    @Test fun `the companion joins code-server's extension list when there is one`() {
        val extensions = home("codex", RoomConfigurator.EXTENSIONS)
        extensions.mkdirs()
        File(extensions, "extensions.json").writeText("""[{"identifier":{"id":"openai.chatgpt"},"version":"26.908.40401","relativeLocation":"openai.chatgpt-26.908.40401-linux-arm64"}]""")
        configurator.configure(RoomProfiles.of("codex", null)!!, emptyList(), 14)
        val listed = Json.parseToJsonElement(File(extensions, "extensions.json").readText()).jsonArray
        assertEquals(listOf("openai.chatgpt", "pocketide.pocketide-companion"), listed.map { it.jsonObject["identifier"]!!.jsonObject["id"]!!.jsonPrimitive.content })
    }
}
