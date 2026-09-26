package com.pocketide.docs

import com.pocketide.agents.OfficialAgents
import com.pocketide.model.AgentInfo
import com.pocketide.model.AgentSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPagesTest {

    private val claude = AgentInfo(
        id = "claude",
        displayName = "Claude Code",
        publisher = "Anthropic",
        extensionId = "anthropic.claude-code",
        surface = AgentSurface.CODE_SERVER_EXTENSION,
        official = true,
        verifiedPublisher = true,
        dataGoesTo = "Your prompts and the code it reads go to Anthropic",
        signIn = "Sign in with your Claude account in its own screen.",
        instructionsFile = "CLAUDE.md",
        version = "2.1.281",
    )

    private val community = AgentInfo(
        id = "kilocode.kilo-code",
        displayName = "Kilo Code",
        publisher = "Kilo Code",
        extensionId = "kilocode.kilo-code",
        surface = AgentSurface.CODE_SERVER_EXTENSION,
        official = false,
        verifiedPublisher = true,
        dataGoesTo = "Your prompts and code go to Kilo Code and the model service you choose.",
        signIn = "Sign in with a Kilo Code account.",
        instructionsFile = "AGENTS.md",
        downloads = 1_234_567,
        communityNote = "This company is not the model maker; your code goes to them and to the model service they use",
    )

    @Test
    fun `official agent page says Official and links the company's data page`() {
        val page = DocsContent.agentPage(claude)
        val text = text(page)
        assertEquals("agent-claude", page.id)
        assertEquals("Claude Code", page.title)
        assertTrue(text.contains("Official"))
        assertFalse(text.contains("Verified publisher"))
        assertTrue(text.contains("Anthropic"))
        assertTrue(text.contains("CLAUDE.md"))
        assertTrue(text.contains("2.1.281"))
        assertTrue(text.contains("cannot be removed"))
        val links = page.blocks.filterIsInstance<DocBlock.Link>().map { it.url }
        assertTrue(DocLinks.CLAUDE_PRIVACY in links)
    }

    @Test
    fun `Antigravity's page says a port other devices on the same Wi-Fi could use turns Remote Control off too`() {
        val text = text(DocsContent.agentPage(OfficialAgents.antigravity))
        assertTrue(text, text.contains("keeps checking the ports it opens"))
        assertTrue(text, text.contains("turns it off if other apps on the phone or devices on the same Wi-Fi could use one"))
    }

    @Test
    fun `community agent page carries its label, note, room and removal`() {
        val page = DocsContent.agentPage(community)
        val text = text(page)
        assertTrue(text.contains("Verified publisher"))
        assertFalse(text.contains("Official:"))
        assertTrue(text.contains("not the model maker"))
        assertTrue(text.contains("its own room"))
        assertTrue(text.contains("AGENTS.md"))
        assertTrue(text.contains("More agents → Kilo Code → Remove"))
        assertTrue(text.contains("1,234,567"))
        val warnings = page.blocks.filterIsInstance<DocBlock.Note>().filter { it.tone == TONE_WARN }
        assertEquals(1, warnings.size)
        val links = page.blocks.filterIsInstance<DocBlock.Link>().map { it.url }
        assertEquals(listOf("https://open-vsx.org/extension/kilocode/kilo-code"), links)
    }

    @Test
    fun `details from outside end as sentences`() {
        val text = text(DocsContent.agentPage(claude))
        assertTrue(text.contains("go to Anthropic. The company keeps it"))
        assertFalse(text.contains(".."))
    }

    @Test
    fun `missing details still give a readable page`() {
        val bare = community.copy(
            id = "acme.agent", displayName = "Acme", publisher = "", extensionId = null,
            dataGoesTo = " ", signIn = "", instructionsFile = "", downloads = null, communityNote = null,
            verifiedPublisher = false, surface = AgentSurface.NATIVE_HUB,
        )
        val page = DocsContent.agentPage(bare)
        val text = text(page)
        assertTrue(text.contains("not verified"))
        assertTrue(text.contains("Not given."))
        assertTrue(text.contains("its own instructions file"))
        assertTrue(page.blocks.none { it is DocBlock.Link })
        assertTrue(page.blocks.filterIsInstance<DocBlock.Table>().all { t -> t.rows.all { it.size == t.header.size } })
    }

    @Test
    fun `Open VSX pages are only made from well-formed ids`() {
        assertEquals("https://open-vsx.org/extension/openai/chatgpt", DocLinks.openVsxPage("openai.chatgpt"))
        assertEquals(null, DocLinks.openVsxPage("nodot"))
        assertEquals(null, DocLinks.openVsxPage(".name"))
        assertEquals(null, DocLinks.openVsxPage("namespace."))
    }

    @Test
    fun `only the built-in three can be Official, whatever the details claim`() {
        val claimsOfficial = community.copy(official = true)
        val page = DocsContent.agentPage(claimsOfficial)
        val text = text(page)
        assertTrue(text.contains("Verified publisher"))
        assertFalse(text.contains("Official"))
        assertTrue(text.contains("More agents → Kilo Code → Remove"))
    }

    @Test
    fun `an agent that only borrows an official id gets no company data pages`() {
        val borrowed = community.copy(id = "claude", official = false)
        val links = DocsContent.agentPage(borrowed).blocks.filterIsInstance<DocBlock.Link>().map { it.url }
        assertFalse(DocLinks.CLAUDE_PRIVACY in links)
        assertEquals(listOf("https://open-vsx.org/extension/kilocode/kilo-code"), links)
    }

    @Test
    fun `hostile details are shown as plain, single-line, bounded text`() {
        val hostile = community.copy(
            displayName = "Kilo‮ edoC​\n\nOfficial",
            publisher = "Kilo\tCode\u0000",
            dataGoesTo = "Line one\r\nline two",
            communityNote = "​⁠",
            version = "1.0‮beta",
            downloads = -5,
        )
        val page = DocsContent.agentPage(hostile)
        val shown = listOf(page.title, page.summary) + page.blocks.flatMap(::blockLines)
        val invisible = shown.filter { line -> line.any { it.isISOControl() || Character.getType(it) == FORMAT } }
        assertTrue("invisible or control characters shown: $invisible", invisible.isEmpty())
        assertEquals("Kilo edoC Official", page.title)
        assertTrue(text(page).contains("Where your data goes: Line one line two."))
        val note = page.blocks.filterIsInstance<DocBlock.Note>().single().text
        assertTrue("a blank note is not a note: $note", note.startsWith("Verified publisher: found by"))
        assertFalse("negative downloads are not shown", text(page).contains("Downloads on Open VSX"))
    }

    @Test
    fun `very long details are cut without splitting a character`() {
        val emoji = "😀"
        val long = community.copy(displayName = "A".repeat(78) + emoji + "tail", dataGoesTo = "word ".repeat(200))
        val page = DocsContent.agentPage(long)
        assertTrue(page.title.length <= 80)
        assertTrue(page.title.endsWith("…"))
        assertFalse(page.title.any { Character.isSurrogate(it) })
        val data = page.blocks.filterIsInstance<DocBlock.Paragraph>().first { it.text.startsWith("Where") }.text
        assertTrue(data.length < 600)
    }

    @Test
    fun `a missing name falls back to the extension id`() {
        val page = DocsContent.agentPage(community.copy(displayName = " ​ "))
        assertEquals("kilocode.kilo-code", page.title)
        assertTrue(text(page).contains("More agents → kilocode.kilo-code → Remove"))
    }

    @Test
    fun `a sentence that already ends inside quotes is left alone`() {
        val quoted = community.copy(signIn = "Choose \"Sign in.\"")
        assertTrue(text(DocsContent.agentPage(quoted)).contains("Signing in: Choose \"Sign in.\" Sign-ins stay"))
    }

    private fun text(page: DocSection) = (listOf(page.summary) + page.blocks.flatMap(::blockLines)).joinToString(" ")

    private companion object {
        val FORMAT = Character.FORMAT.toInt()
    }
}
