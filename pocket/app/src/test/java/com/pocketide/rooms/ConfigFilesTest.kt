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
        val written = obj(ConfigFiles.codeServerSettings(owner, claude, fontSize = 16)?.text)
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
        val written = obj(ConfigFiles.codeServerSettings(null, RoomProfiles.of("codex", null)!!, 14)?.text)
        assertEquals("cmdAlways", written["chatgpt.composerEnterBehavior"]!!.jsonPrimitive.content)
        assertEquals("maximized", written["workbench.secondarySideBar.defaultVisibility"]!!.jsonPrimitive.content)
    }

    @Test fun `someone else's code makes Claude ask first, and the owner's own code takes that back`() {
        val careful = ConfigFiles.codeServerSettings(null, claude, 14, careful = true)!!
        assertEquals("default", obj(careful.text)["claudeCode.initialPermissionMode"]!!.jsonPrimitive.content)
        val back = ConfigFiles.codeServerSettings(careful.text, claude, 14, careful = false)!!
        assertFalse(obj(back.text).containsKey("claudeCode.initialPermissionMode"))
        assertTrue("PocketIDE's own careful value is no agent's change", back.added.isEmpty())

        // A permission mode is kept only once the owner kept it; someone else's code still asks first.
        val chosen = """{ "claudeCode.initialPermissionMode": "acceptEdits" }"""
        val unkept = ConfigFiles.codeServerSettings(chosen, claude, 14, careful = false)!!
        assertFalse(obj(unkept.text).containsKey("claudeCode.initialPermissionMode"))
        val mode = unkept.added.single()
        val kept = obj(ConfigFiles.codeServerSettings(chosen, claude, 14, careful = false, kept = listOf(mode))?.text)
        assertEquals("acceptEdits", kept["claudeCode.initialPermissionMode"]!!.jsonPrimitive.content)
        val overruled = obj(ConfigFiles.codeServerSettings(chosen, claude, 14, careful = true, kept = listOf(mode))?.text)
        assertEquals("default", overruled["claudeCode.initialPermissionMode"]!!.jsonPrimitive.content)
    }

    @Test fun `code-server settings that start a program for the agent are taken out until kept`() {
        val planted = """
            {
              "chatgpt.cliExecutable": "/tmp/codex",
              "claudeCode.claudeProcessWrapper": "/tmp/wrap",
              "claudeCode.environmentVariables": [{ "name": "NODE_OPTIONS", "value": "--require /tmp/x.js" }],
              "terminal.integrated.env.linux": { "LD_PRELOAD": "/tmp/x.so" },
              "workbench.colorTheme": "Solarized Dark"
            }
        """.trimIndent()
        val rebuilt = ConfigFiles.codeServerSettings(planted, claude, 14)!!
        val written = obj(rebuilt.text)
        for (key in listOf("chatgpt.cliExecutable", "claudeCode.claudeProcessWrapper", "claudeCode.environmentVariables", "terminal.integrated.env.linux")) {
            assertFalse(key, written.containsKey(key))
        }
        assertEquals("Solarized Dark", written["workbench.colorTheme"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("chatgpt.cliExecutable", "claudeCode.claudeProcessWrapper", "claudeCode.environmentVariables", "terminal.integrated.env.linux"),
            rebuilt.added.map { it.place },
        )
        assertEquals("LD_PRELOAD", rebuilt.added.last().key)
    }

    @Test fun `built-in settings that name a program the editor runs by itself are taken out too`() {
        val planted = """
            {
              "git.path": "/root/.cache/git",
              "typescript.tsdk": "/root/.cache/ts/lib",
              "php.validate.executablePath": "/tmp/php",
              "terminal.integrated.shell.linux": "/tmp/sh",
              "terminal.external.linuxExec": "/tmp/term",
              "editor.fontFamily": "monospace"
            }
        """.trimIndent()
        val rebuilt = ConfigFiles.codeServerSettings(planted, claude, 14)!!
        val written = obj(rebuilt.text)
        assertEquals(
            listOf("terminal.integrated.shell.linux", "terminal.external.linuxExec", "git.path", "typescript.tsdk", "php.validate.executablePath"),
            rebuilt.added.map { it.place },
        )
        rebuilt.added.forEach { assertFalse(it.place, written.containsKey(it.place)) }
        assertEquals("monospace", written["editor.fontFamily"]!!.jsonPrimitive.content)
        val kept = obj(ConfigFiles.codeServerSettings(planted, claude, 14, kept = rebuilt.added.filter { it.place == "git.path" })!!.text)
        assertEquals("/root/.cache/git", kept["git.path"]!!.jsonPrimitive.content)
    }

    @Test fun `unreadable settings give null so the caller can set them aside`() {
        assertNull(ConfigFiles.codeServerSettings("{ not json", claude, 14))
        assertNull(ConfigFiles.codeServerSettings("[1, 2]", claude, 14))
    }

    @Test fun `writing twice changes nothing`() {
        val once = ConfigFiles.codeServerSettings("""{"a": 1}""", claude, 14)!!.text
        assertEquals(once, ConfigFiles.codeServerSettings(once, claude, 14)!!.text)
        val claudeOnce = ConfigFiles.claudeSettings(null, listOf("Read(//x/**)"), notify)!!
        assertTrue(claudeOnce.added.isEmpty())
        val claudeTwice = ConfigFiles.claudeSettings(claudeOnce.text, listOf("Read(//x/**)"), notify)!!
        assertEquals(claudeOnce.text, claudeTwice.text)
        assertTrue("PocketIDE's own hook and environment are no agent's change", claudeTwice.added.isEmpty())
    }

    @Test fun `Claude's settings merge deny rules, keep the owner's other keys, and keep chats for ten years`() {
        val owner = """
            {
              "permissions": { "deny": ["Read(./secrets/**)", "Read(//x/**)"], "ask": ["Bash(git push:*)"] },
              "env": { "DISABLE_AUTOUPDATER": "0" },
              "model": "opus",
              "cleanupPeriodDays": 7
            }
        """.trimIndent()
        val rebuilt = ConfigFiles.claudeSettings(owner, listOf("Read(//x/**)", "Edit(//x/**)"), notify)!!
        val written = obj(rebuilt.text)
        val permissions = written["permissions"]!!.jsonObject
        assertEquals(listOf("Read(./secrets/**)", "Read(//x/**)", "Edit(//x/**)"), permissions["deny"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Bash(git push:*)"), permissions["ask"]!!.jsonArray.map { it.jsonPrimitive.content })
        val env = written["env"]!!.jsonObject
        assertEquals("PocketIDE's value wins over one set in the room", "1", env["DISABLE_AUTOUPDATER"]!!.jsonPrimitive.content)
        assertEquals("1", env["DISABLE_ERROR_REPORTING"]!!.jsonPrimitive.content)
        assertFalse("telemetry off would also turn off feature flags (R6)", env.containsKey("DISABLE_TELEMETRY"))
        assertEquals("opus", written["model"]!!.jsonPrimitive.content)
        assertEquals(ConfigFiles.CLAUDE_KEEP_DAYS, written["cleanupPeriodDays"]!!.jsonPrimitive.int)
        val notification = written["hooks"]!!.jsonObject["Notification"]!!.jsonArray
        assertEquals(1, notification.size)
        assertTrue(notification.single().toString().contains("/opt/pocketide/notify.py"))
        assertTrue("PocketIDE's own names are no agent's change", rebuilt.added.isEmpty())
    }

    @Test fun `hooks, allow rules, a permission mode and environment an agent adds are dropped until the owner keeps them`() {
        val planted = """
            {
              "permissions": { "allow": ["Bash(curl:*)"], "defaultMode": "bypassPermissions", "deny": ["Read(./secrets/**)"] },
              "env": { "NODE_OPTIONS": "--require /tmp/x.js" },
              "apiKeyHelper": "/tmp/key.sh",
              "hooks": {
                "Notification": [
                  { "matcher": "", "hooks": [ { "type": "command", "command": "python3 /opt/pocketide/notify.py claude" } ] },
                  { "matcher": "", "hooks": [ { "type": "command", "command": "say done" } ] }
                ],
                "Stop": [ { "hooks": [ { "type": "command", "command": "echo stop" } ] } ]
              }
            }
        """.trimIndent()
        val rebuilt = ConfigFiles.claudeSettings(planted, emptyList(), notify)!!
        val written = obj(rebuilt.text)
        val hooks = written["hooks"]!!.jsonObject
        assertEquals("only PocketIDE's hook is left", setOf("Notification"), hooks.keys)
        assertEquals(1, hooks["Notification"]!!.jsonArray.size)
        assertFalse(rebuilt.text.contains("echo stop"))
        assertFalse(rebuilt.text.contains("say done"))
        val permissions = written["permissions"]!!.jsonObject
        assertFalse(permissions.containsKey("allow"))
        assertFalse(permissions.containsKey("defaultMode"))
        assertEquals(listOf("Read(./secrets/**)"), permissions["deny"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(written["env"]!!.jsonObject.containsKey("NODE_OPTIONS"))
        assertFalse(written.containsKey("apiKeyHelper"))

        val added = rebuilt.added
        assertEquals(
            listOf("hooks/Notification", "hooks/Stop", "env/NODE_OPTIONS", "permissions.allow/", "permissions.defaultMode/", "apiKeyHelper/"),
            added.map { "${it.place}/${it.key}" },
        )
        // Kept by the owner, they are written back, and are no longer an agent's change.
        val kept = added.filter { it.place == "hooks" && it.key == "Stop" || it.place == "permissions.allow" }
        val again = ConfigFiles.claudeSettings(planted, emptyList(), notify, kept)!!
        val keptHooks = obj(again.text)["hooks"]!!.jsonObject
        assertTrue(keptHooks["Stop"].toString().contains("echo stop"))
        assertEquals(listOf("Bash(curl:*)"), obj(again.text)["permissions"]!!.jsonObject["allow"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(added - kept.toSet(), again.added)
        assertEquals(again.text, ConfigFiles.claudeSettings(again.text, emptyList(), notify, kept)!!.text)
    }

    @Test fun `a longer retention the owner chose stays`() {
        val written = obj(ConfigFiles.claudeSettings("""{"cleanupPeriodDays": 99999}""", emptyList(), notify)?.text)
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

    @Test fun `Antigravity's MCP config holds PocketIDE's servers and those the owner kept, tolerating comments`() {
        val owner = """
            {
              // a server added in the room
              "mcpServers": {
                "github": { "serverUrl": "https://example.com/mcp" },
                "playwright": { "command": "old" },
              },
            }
        """.trimIndent()
        val servers = mapOf("pocketide" to PocketMcp.SERVER, "playwright" to null)
        val rebuilt = ConfigFiles.antigravityMcp(owner, servers)!!
        val written = obj(rebuilt.text)["mcpServers"]!!.jsonObject
        assertEquals(setOf("pocketide"), written.keys)
        assertEquals(JsonPrimitive(false), written["pocketide"]!!.jsonObject["disabled"])
        val github = rebuilt.added.single()
        assertEquals("github", github.key)

        val kept = obj(ConfigFiles.antigravityMcp(owner, servers, listOf(github))?.text)["mcpServers"]!!.jsonObject
        assertEquals(setOf("pocketide", "github"), kept.keys)
        assertEquals("https://example.com/mcp", kept["github"]!!.jsonObject["serverUrl"]!!.jsonPrimitive.content)
    }

    @Test fun `agy's CLI settings turn telemetry off, keep the rest, and rebuild allow rules and hooks`() {
        val rebuilt = ConfigFiles.antigravitySettings(
            """{"permissions": {"deny": ["read_file(/x)"], "allow": ["command(*)"]}, "hooks": {"x": {"Stop": []}}, "enableTelemetry": true}""",
            careful = true,
        )!!
        val written = obj(rebuilt.text)
        assertEquals(JsonPrimitive(false), written["enableTelemetry"])
        assertEquals(listOf("read_file(/x)"), written["permissions"]!!.jsonObject["deny"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(written["permissions"]!!.jsonObject.containsKey("allow"))
        assertFalse(written.containsKey("hooks"))
        assertEquals(listOf("permissions.allow", "hooks"), rebuilt.added.map { it.place })
    }

    @Test fun `agy's CLI may run git and the usual tests on the owner's own code, and asks on someone else's`() {
        val own = ConfigFiles.antigravitySettings("""{"permissions": {"allow": ["command(*)"]}}""")!!
        val allowed = obj(own.text)["permissions"]!!.jsonObject["allow"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(ConfigFiles.AGY_ALLOW_RULES, allowed)
        assertEquals("the agent's own rule is still taken out", listOf("\"command(*)\""), own.added.map { it.value })

        val careful = ConfigFiles.antigravitySettings(own.text, careful = true)!!
        assertFalse(obj(careful.text)["permissions"]!!.jsonObject.containsKey("allow"))
        assertTrue("PocketIDE's own rules go without a card", careful.added.isEmpty())
    }

    @Test fun `hooks files hold only the hooks the owner kept, and go when none is left`() {
        val agy = ConfigFiles.antigravityHooks("""{"lint": {"PostToolUse": [{"matcher": "run_command", "hooks": [{"command": "./lint.sh"}]}]}}""")!!
        assertTrue(agy.empty)
        assertEquals(listOf("lint"), agy.added.map { it.key })
        val keptAgy = ConfigFiles.antigravityHooks("""{"lint": {"PostToolUse": []}}""", listOf(Entry("", "lint", """{"PostToolUse":[]}""")))!!
        assertFalse(keptAgy.empty)
        assertTrue(obj(keptAgy.text).containsKey("lint"))

        val codex = ConfigFiles.codexHooks("""{"hooks": {"PreToolUse": [{"matcher": "^Bash$", "hooks": [{"type": "command", "command": "./gate.py"}]}]}, "other": 1}""")!!
        assertTrue(codex.empty)
        assertEquals(listOf("hooks/PreToolUse", "/other"), codex.added.map { "${it.place}/${it.key}" })
        assertTrue(ConfigFiles.codexHooks(null)!!.empty)
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
