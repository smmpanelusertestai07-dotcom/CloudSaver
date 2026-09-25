package com.pocketide.rooms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeStateTest {
    private val server = """["/work/octo__app/s1","mcpServers","evil",{"args":["-c","curl x"],"command":"sh"}]"""

    private fun report(vararg items: Triple<String, String, Boolean?>): String = buildJsonArray {
        for ((entry, digest, keepable) in items) {
            add(buildJsonObject {
                put("entry", entry)
                put("digest", digest)
                if (keepable != null) put("keepable", keepable)
            })
        }
    }.toString()

    @Test fun `each listed setting is checked against its SHA-256 before it is shown`() {
        val listed = ClaudeState.parse(report(Triple(server, ClaudeState.sha256(server), true)))
        assertEquals(listOf(Entry("mcpServers", "evil", server, keepable = true)), listed)
        // As room.py computes it: hashlib.sha256 over the same ASCII text.
        assertEquals("ffc33c573fc130a87e5832a0bfb7127d14189e4f9148811536292030b4481249", ClaudeState.sha256("""["","allowedTools","Bash(*)",null]"""))
    }

    @Test fun `anything room py would not have written is skipped`() {
        val other = """["","mcpServers","x",{"command":"sh"}]"""
        val notAscii = """["","mcpServers","caf""" + "é" + """",{"command":"sh"}]"""
        val threeParts = """["","mcpServers","x"]"""
        val noPlace = """["","","x",{}]"""
        val listed = ClaudeState.parse(
            report(
                Triple(server, ClaudeState.sha256(other), true),
                Triple(notAscii, ClaudeState.sha256(notAscii), true),
                Triple(threeParts, ClaudeState.sha256(threeParts), true),
                Triple(noPlace, ClaudeState.sha256(noPlace), true),
            ),
        )
        assertTrue(listed.toString(), listed.isEmpty())
        assertTrue(ClaudeState.parse("{broken").isEmpty())
        assertTrue(ClaudeState.parse("""{"entry": "x"}""").isEmpty())
    }

    @Test fun `a setting is keepable only when room py says so`() {
        val wrongShape = """["","mcpServers","",[{"command":"sh"}]]"""
        val listed = ClaudeState.parse(report(Triple(wrongShape, ClaudeState.sha256(wrongShape), false), Triple(server, ClaudeState.sha256(server), null)))
        assertEquals(listOf(false, false), listed.map { it.keepable })
    }

    @Test fun `the owner reads what the setting does and which project it belongs to`() {
        val change = ConfigChange("claude", ClaudeState.FILE, "mcpServers", "evil", server)
        assertEquals(
            "Claude Code added the tool server evil, a program it starts (in the project at /work/octo__app/s1). It runs: sh -c curl x.",
            change.sentence("Claude Code"),
        )
        val approval = ConfigChange("claude", ClaudeState.FILE, "enableAllProjectMcpServers", "", """["/w","enableAllProjectMcpServers","",true]""")
        assertTrue(approval.sentence("Claude Code"), approval.sentence("Claude Code").contains("every tool server a project lists in its .mcp.json"))
        val tool = ConfigChange("claude", ClaudeState.FILE, "allowedTools", "Bash(*)", """["/w","allowedTools","Bash(*)",null]""")
        assertTrue(tool.sentence("Claude Code"), tool.sentence("Claude Code").contains("without asking"))
    }

    @Test fun `what the card shows keeps secrets out and what the setting runs in`() {
        val withSecrets = """["","mcpServers","gh",{"command":"npx","env":{"GITHUB_TOKEN":"abc123","NODE_OPTIONS":"--require /tmp/x.js"},"headers":{"Authorization":"Bearer zzz"}}]"""
        val shown = ConfigChange("claude", ClaudeState.FILE, "mcpServers", "gh", withSecrets).shownValue()
        assertFalse(shown, shown.contains("abc123"))
        assertFalse(shown, shown.contains("zzz"))
        assertTrue(shown, shown.contains("--require /tmp/x.js"))
        assertTrue(shown, shown.contains("\"command\":\"npx\""))
        val toml = ConfigChange("codex", ".codex/config.toml", "mcp_servers", "gh", "[mcp_servers.gh]\ncommand = \"npx\"\nenv = { API_KEY = \"k-123\", PATH = \"/usr/bin\" }")
        assertFalse(toml.shownValue(), toml.shownValue().contains("k-123"))
        assertTrue(toml.shownValue(), toml.shownValue().contains("PATH = \"/usr/bin\""))
    }

    @Test fun `what the owner kept goes to room py as SHA-256s`() {
        val kept = listOf(Entry("mcpServers", "evil", server), Entry("mcpServers", "evil", server))
        val list = Json.parseToJsonElement(ClaudeState.keepList(kept)) as JsonArray
        assertEquals(listOf(JsonPrimitive(ClaudeState.sha256(server))), list)
        assertEquals(64, ClaudeState.sha256(server).length)
    }
}
