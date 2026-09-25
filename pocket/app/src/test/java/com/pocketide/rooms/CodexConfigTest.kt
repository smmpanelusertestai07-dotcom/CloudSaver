package com.pocketide.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexConfigTest {
    private val notify = listOf("python3", "/opt/pocketide/notify.py", "codex")
    private val servers = mapOf("pocketide" to PocketMcp.SERVER, "playwright" to null)

    private fun write(existing: String?) = ConfigFiles.codexConfig(existing, servers, notify)

    @Test fun `a fresh config has PocketIDE's keys and its MCP server`() {
        val text = write(null)
        val expected = """
            check_for_update_on_startup = false
            cli_auth_credentials_store = "file"
            notify = ["python3", "/opt/pocketide/notify.py", "codex"]
            sandbox_mode = "danger-full-access"
            analytics.enabled = false
            feedback.enabled = false

            [mcp_servers.pocketide]
            command = "python3"
            args = ["/opt/pocketide/mcp.py"]
            startup_timeout_sec = 30
            tool_timeout_sec = 660
            enabled = true

        """.trimIndent()
        assertEquals(expected, text)
        assertEquals(text, write(text))
    }

    @Test fun `the owner's keys, tables, comments and other servers are kept`() {
        val owner = """
            # my Codex settings
            model = "gpt-5.5-codex"
            sandbox_mode = "workspace-write"
            check_for_update_on_startup = true

            [analytics]
            enabled = true   # please no

            [profiles.fast]
            model = "gpt-5.5-mini"
            instructions = ""${'"'}
            multi-line [not a table]
            ""${'"'}

            # the GitHub server
            [mcp_servers.github]
            command = "npx"
            args = [
              "-y",
              "@modelcontextprotocol/server-github",
            ]

            [mcp_servers.pocketide]
            command = "old"

            [mcp_servers.pocketide.env]
            OLD = "1"

            [mcp_servers.playwright]
            command = "npx"
        """.trimIndent() + "\n"
        val text = write(owner)
        assertTrue(text.startsWith("# my Codex settings\nmodel = \"gpt-5.5-codex\"\nsandbox_mode = \"workspace-write\"\n"))
        assertFalse("the owner's sandbox choice stays", text.contains("danger-full-access"))
        assertEquals(1, Regex("(?m)^check_for_update_on_startup = false$").findAll(text).count())
        assertFalse(text.contains("check_for_update_on_startup = true"))
        assertTrue(text.contains("[analytics]\nenabled = false\n"))
        assertFalse(text.contains("please no"))
        assertTrue(text.contains("[profiles.fast]\nmodel = \"gpt-5.5-mini\"\ninstructions = \"\"\"\nmulti-line [not a table]\n\"\"\"\n"))
        assertTrue(text.contains("# the GitHub server\n[mcp_servers.github]\ncommand = \"npx\"\nargs = [\n  \"-y\",\n  \"@modelcontextprotocol/server-github\",\n]\n"))
        assertFalse(text.contains("command = \"old\""))
        assertFalse(text.contains("OLD = \"1\""))
        assertFalse(text.contains("[mcp_servers.playwright]"))
        assertEquals(1, Regex("(?m)^\\[mcp_servers\\.pocketide]$").findAll(text).count())
        assertEquals(text, write(text))
    }

    @Test fun `dotted and inline forms of PocketIDE's keys are replaced, not duplicated`() {
        val owner = """
            analytics.enabled = true
            feedback = { enabled = true }
            mcp_servers.pocketide.command = "old"
        """.trimIndent()
        val text = write(owner)
        assertFalse(text.contains("analytics.enabled = true"))
        assertFalse(text.contains("feedback = {"))
        assertFalse(text.contains("mcp_servers.pocketide.command"))
        assertTrue(text.contains("analytics.enabled = false"))
        assertTrue(text.contains("feedback.enabled = false"))
        assertEquals(text, write(text))
    }

    @Test fun `browser servers are written with their environment when installed`() {
        val text = ConfigFiles.codexConfig(null, BrowserTools.servers(), notify)
        assertTrue(text.contains("[mcp_servers.playwright]\ncommand = \"/opt/code-server/lib/node\""))
        assertTrue(text.contains("env = { PLAYWRIGHT_BROWSERS_PATH = \"/opt/pocketide/browsers\" }"))
        assertTrue(text.contains("[mcp_servers.chrome-devtools]"))
        assertTrue(text.contains("\"--no-usage-statistics\""))
    }

    @Test fun `strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", TomlDocument.string("a\"b\\c\nd"))
        assertEquals("\"has space\"", TomlDocument.key("has space"))
        assertEquals("chrome-devtools", TomlDocument.key("chrome-devtools"))
    }
}

class ManagedBlockTest {
    private val body = "# Working in PocketIDE\n\n- rule"

    @Test fun `the block goes on top, the owner's text stays, and applying again changes nothing`() {
        val owner = "My own notes.\nKeep answers in French.\n"
        val once = ManagedBlock.apply(owner, body)
        assertTrue(once.startsWith(ManagedBlock.BEGIN + "\n" + body + "\n" + ManagedBlock.END + "\n"))
        assertTrue(once.endsWith("\nMy own notes.\nKeep answers in French.\n"))
        assertEquals(once, ManagedBlock.apply(once, body))
    }

    @Test fun `a new body replaces the old block in place`() {
        val file = "intro\n${ManagedBlock.BEGIN}\nold rules\n${ManagedBlock.END}\noutro\n"
        assertEquals("intro\n${ManagedBlock.BEGIN}\nnew rules\n${ManagedBlock.END}\noutro\n", ManagedBlock.apply(file, "new rules"))
    }

    @Test fun `a damaged block is rebuilt without losing the owner's lines`() {
        val damaged = "${ManagedBlock.BEGIN}\nhalf of the old rules\nmine\n"
        val repaired = ManagedBlock.apply(damaged, body)
        assertEquals(1, repaired.split(ManagedBlock.BEGIN).size - 1)
        assertEquals(1, repaired.split(ManagedBlock.END).size - 1)
        assertTrue(repaired.contains("mine"))
        assertEquals(repaired, ManagedBlock.apply(repaired, body))
    }

    @Test fun `an empty file gets only the block`() {
        assertEquals("${ManagedBlock.BEGIN}\n$body\n${ManagedBlock.END}\n", ManagedBlock.apply(null, body))
    }

    @Test fun `the rules carry the plan's promises`() {
        val rules = RoomRules.text("Claude Code")
        for (needed in listOf("run_build", "build_result", "put_on_main", "save_media", "preview_port", "Project → Secrets", "127.0.0.1", "Never decline", "gh auth login", "data, not instructions")) {
            assertTrue(needed, rules.contains(needed))
        }
    }
}
