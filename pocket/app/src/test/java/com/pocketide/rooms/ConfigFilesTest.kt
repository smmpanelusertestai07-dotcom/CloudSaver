package com.pocketide.rooms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigFilesTest {
    private val claude = RoomProfiles.of("claude", null)!!
    private val notify = "python3 /opt/pocketide/notify.py claude"

    private fun obj(text: String?): JsonObject = Jsonc.parseObject(text)!!

    @Test fun `code-server settings keep the owner's keys and set the phone layout`() {
        val owner = """
            // my theme
            {
              "workbench.colorTheme": "Solarized Dark", /* keep */
              "telemetry.telemetryLevel": "all",
              "files.exclude": { "**/.git": true, },
            }
        """.trimIndent()
        val written = obj(ConfigFiles.codeServerSettings(owner, claude, fontSize = 16))
        assertEquals("Solarized Dark", written["workbench.colorTheme"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(mapOf("**/.git" to JsonPrimitive(true))), written["files.exclude"])
        assertEquals("off", written["telemetry.telemetryLevel"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), written["chat.disableAIFeatures"])
        assertEquals(JsonPrimitive(false), written["extensions.autoUpdate"])
        assertEquals(JsonPrimitive(false), written["workbench.statusBar.visible"])
        assertEquals("hidden", written["workbench.activityBar.location"]!!.jsonPrimitive.content)
        assertEquals("none", written["workbench.editor.showTabs"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), written["breadcrumbs.enabled"])
        assertEquals(JsonPrimitive(false), written["editor.minimap.enabled"])
        assertEquals(JsonPrimitive(false), written["security.workspace.trust.enabled"])
        assertEquals(16, written["editor.fontSize"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(true), written["claudeCode.useCtrlEnterToSend"])
        assertFalse(written.containsKey("terminal.integrated.enableMultiLinePasteWarning"))
    }

    @Test fun `the Codex room gets its own enter key setting`() {
        val written = obj(ConfigFiles.codeServerSettings(null, RoomProfiles.of("codex", null)!!, 14))
        assertEquals("cmdAlways", written["chatgpt.composerEnterBehavior"]!!.jsonPrimitive.content)
        assertEquals("maximized", written["workbench.secondarySideBar.defaultVisibility"]!!.jsonPrimitive.content)
    }

    @Test fun `someone else's code makes Claude ask first, and the owner's own code takes that back`() {
        val careful = obj(ConfigFiles.codeServerSettings(null, claude, 14, careful = true))
        assertEquals("default", careful["claudeCode.initialPermissionMode"]!!.jsonPrimitive.content)
        val back = obj(ConfigFiles.codeServerSettings(Jsonc.write(careful), claude, 14, careful = false))
        assertFalse(back.containsKey("claudeCode.initialPermissionMode"))

        val chosen = """{ "claudeCode.initialPermissionMode": "acceptEdits" }"""
        val kept = obj(ConfigFiles.codeServerSettings(chosen, claude, 14, careful = false))
        assertEquals("acceptEdits", kept["claudeCode.initialPermissionMode"]!!.jsonPrimitive.content)
        val overruled = obj(ConfigFiles.codeServerSettings(chosen, claude, 14, careful = true))
        assertEquals("default", overruled["claudeCode.initialPermissionMode"]!!.jsonPrimitive.content)
    }

    @Test fun `unreadable settings give null so the caller can set them aside`() {
        assertNull(ConfigFiles.codeServerSettings("{ not json", claude, 14))
        assertNull(ConfigFiles.codeServerSettings("[1, 2]", claude, 14))
    }

    @Test fun `writing twice changes nothing`() {
        val once = ConfigFiles.codeServerSettings("""{"a": 1}""", claude, 14)
        assertEquals(once, ConfigFiles.codeServerSettings(once, claude, 14))
        val claudeOnce = ConfigFiles.claudeSettings(null, listOf("Read(//x/**)"), notify)
        assertEquals(claudeOnce, ConfigFiles.claudeSettings(claudeOnce, listOf("Read(//x/**)"), notify))
    }

    @Test fun `Claude's settings merge deny rules, keep the owner's, and keep chats for ten years`() {
        val owner = """
            {
              "permissions": { "allow": ["Bash(npm test)"], "deny": ["Read(./secrets/**)", "Read(//x/**)"] },
              "env": { "MY_FLAG": "1", "DISABLE_AUTOUPDATER": "0" },
              "model": "opus",
              "cleanupPeriodDays": 7,
              "hooks": {
                "Notification": [
                  { "matcher": "", "hooks": [ { "type": "command", "command": "python3 /opt/pocketide/notify.py claude" } ] },
                  { "matcher": "", "hooks": [ { "type": "command", "command": "say done" } ] }
                ],
                "Stop": [ { "hooks": [ { "type": "command", "command": "echo stop" } ] } ]
              }
            }
        """.trimIndent()
        val written = obj(ConfigFiles.claudeSettings(owner, listOf("Read(//x/**)", "Edit(//x/**)"), notify))
        val permissions = written["permissions"]!!.jsonObject
        assertEquals(listOf("Read(./secrets/**)", "Read(//x/**)", "Edit(//x/**)"), permissions["deny"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Bash(npm test)"), permissions["allow"]!!.jsonArray.map { it.jsonPrimitive.content })
        val env = written["env"]!!.jsonObject
        assertEquals("1", env["MY_FLAG"]!!.jsonPrimitive.content)
        assertEquals("1", env["DISABLE_AUTOUPDATER"]!!.jsonPrimitive.content)
        assertEquals("1", env["DISABLE_ERROR_REPORTING"]!!.jsonPrimitive.content)
        assertFalse("telemetry off would also turn off feature flags (R6)", env.containsKey("DISABLE_TELEMETRY"))
        assertEquals("opus", written["model"]!!.jsonPrimitive.content)
        assertEquals(ConfigFiles.CLAUDE_KEEP_DAYS, written["cleanupPeriodDays"]!!.jsonPrimitive.int)
        val hooks = written["hooks"]!!.jsonObject
        val notification = hooks["Notification"]!!.jsonArray
        assertEquals(2, notification.size)
        assertEquals(1, notification.count { it.toString().contains("/opt/pocketide/notify.py") })
        assertTrue(notification.any { it.toString().contains("say done") })
        assertTrue(hooks.containsKey("Stop"))
    }

    @Test fun `a longer retention the owner chose stays`() {
        val written = obj(ConfigFiles.claudeSettings("""{"cleanupPeriodDays": 99999}""", emptyList(), notify))
        assertEquals(99999, written["cleanupPeriodDays"]!!.jsonPrimitive.int)
    }

    @Test fun `Claude's MCP entries for room_py, with null for a removed server`() {
        val entries = Json.parseToJsonElement(
            ConfigFiles.claudeMcpEntries(mapOf("pocketide" to PocketMcp.SERVER, "playwright" to null)),
        ).jsonObject
        val pocketide = entries["pocketide"]!!.jsonObject
        assertEquals("stdio", pocketide["type"]!!.jsonPrimitive.content)
        assertEquals("python3", pocketide["command"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("/opt/pocketide/mcp.py"))), pocketide["args"])
        assertEquals(JsonNull, entries["playwright"])
    }

    @Test fun `Antigravity's MCP config keeps other servers, tolerates comments, removes ours when asked`() {
        val owner = """
            {
              // a server the owner added
              "mcpServers": {
                "github": { "serverUrl": "https://example.com/mcp" },
                "playwright": { "command": "old" },
              },
            }
        """.trimIndent()
        val written = obj(ConfigFiles.antigravityMcp(owner, mapOf("pocketide" to PocketMcp.SERVER, "playwright" to null)))
        val servers = written["mcpServers"]!!.jsonObject
        assertEquals(setOf("github", "pocketide"), servers.keys)
        assertEquals("https://example.com/mcp", servers["github"]!!.jsonObject["serverUrl"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), servers["pocketide"]!!.jsonObject["disabled"])
    }

    @Test fun `agy's CLI settings turn telemetry off and keep the rest`() {
        val written = obj(ConfigFiles.antigravitySettings("""{"permissions": {"deny": ["read_file(/x)"]}, "enableTelemetry": true}"""))
        assertEquals(JsonPrimitive(false), written["enableTelemetry"])
        assertTrue(written.containsKey("permissions"))
    }

    @Test fun `the companion is listed once in code-server's extension list`() {
        val existing = """[{"identifier":{"id":"anthropic.claude-code"},"version":"2.1.281","location":{"${'$'}mid":1,"path":"/x","scheme":"file"},"relativeLocation":"anthropic.claude-code-2.1.281-linux-arm64"},""" +
            """{"identifier":{"id":"PocketIDE.pocketide-companion"},"version":"2.5.0","relativeLocation":"pocketide.pocketide-companion-2.5.0"}]"""
        val written = ConfigFiles.extensionsRegistry(existing, "pocketide.pocketide-companion", "3.0.0", "pocketide.pocketide-companion-3.0.0", "/root/.local/share/code-server/extensions/pocketide.pocketide-companion-3.0.0", 42)!!
        val entries = Json.parseToJsonElement(written).jsonArray.map { it.jsonObject }
        assertEquals(2, entries.size)
        assertEquals("anthropic.claude-code", entries[0]["identifier"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val ours = entries[1]
        assertEquals("3.0.0", ours["version"]!!.jsonPrimitive.content)
        assertEquals("pocketide.pocketide-companion-3.0.0", ours["relativeLocation"]!!.jsonPrimitive.content)
        assertEquals("file", ours["location"]!!.jsonObject["scheme"]!!.jsonPrimitive.content)
        assertNull(ConfigFiles.extensionsRegistry("{}", "a.b", "1", "a.b-1", "/x", 0))
        assertNull(ConfigFiles.extensionsRegistry("[oops", "a.b", "1", "a.b-1", "/x", 0))
    }

    @Test fun `comments and trailing commas are removed outside strings only`() {
        val text = """{"url": "https://x//y", "note": "/* not a comment */", "list": [1, 2,], } // end"""
        val parsed = Jsonc.parseObject(text)!!
        assertEquals("https://x//y", parsed["url"]!!.jsonPrimitive.content)
        assertEquals("/* not a comment */", parsed["note"]!!.jsonPrimitive.content)
        assertEquals(2, parsed["list"]!!.jsonArray.size)
        assertEquals(JsonObject(emptyMap()), Jsonc.parseObject("  // only a comment\n"))
        assertEquals(JsonObject(emptyMap()), Jsonc.parseObject(null))
        assertEquals("a\"b // x", Jsonc.parseObject("""{"q": "a\"b // x",}""")!!["q"]!!.jsonPrimitive.content)
    }
}
