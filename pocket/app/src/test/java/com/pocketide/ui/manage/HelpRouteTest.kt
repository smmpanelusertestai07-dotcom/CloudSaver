package com.pocketide.ui.manage

import com.pocketide.docs.DocBlock
import com.pocketide.docs.DocSection
import com.pocketide.docs.DocsContent
import com.pocketide.docs.FaqEntry
import com.pocketide.model.AgentInfo
import com.pocketide.model.AgentSurface
import com.pocketide.ui.screens.help.noteLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpRouteTest {
    private val page = DocSection("privacy", "Privacy", "", listOf(DocBlock.Paragraph("x")))
    private val agentPage = DocSection("agent-claude", "Claude Code", "", emptyList())
    private val sameIdQuestion = FaqEntry("privacy", "Is it private?", emptyList(), null)
    private val question = FaqEntry("faq-offline", "Does it work offline?", emptyList(), null)

    private fun resolve(id: String?) = HelpRoute.resolve(id, listOf(page), listOf(agentPage), listOf(sameIdQuestion, question))

    @Test
    fun `blank or missing ids open the index`() {
        assertEquals(HelpRoute.Index, resolve(null))
        assertEquals(HelpRoute.Index, resolve(""))
        assertEquals(HelpRoute.Index, resolve("   "))
    }

    @Test
    fun `deep links reach pages, agent pages, questions and the two lists`() {
        assertEquals(HelpRoute.Page(page), resolve(" privacy "))
        assertEquals(HelpRoute.Page(agentPage), resolve("agent-claude"))
        assertEquals(HelpRoute.Question(question), resolve("faq-offline"))
        assertEquals(HelpRoute.AllQuestions, resolve("faq"))
        assertEquals(HelpRoute.Glossary, resolve("glossary"))
    }

    @Test
    fun `a question never hides a page with the same id`() {
        assertEquals(HelpRoute.Page(page), resolve("privacy"))
    }

    @Test
    fun `stale, hostile or odd ids show page not found`() {
        assertEquals(HelpRoute.Missing, resolve("agent-removed"))
        assertEquals(HelpRoute.Missing, resolve("../../etc/passwd"))
        assertEquals(HelpRoute.Missing, resolve("PRIVACY"))
        assertEquals(HelpRoute.Missing, resolve("x".repeat(10_000)))
    }

    @Test
    fun `the shipped docs have unique ids that the routes can reach`() {
        val sectionIds = DocsContent.sections.map { it.id }
        assertEquals(sectionIds.size, sectionIds.toSet().size)
        val faqIds = DocsContent.faq.map { it.id }
        assertEquals(faqIds.size, faqIds.toSet().size)
        val terms = DocsContent.glossary.map { it.term }
        assertEquals(terms.size, terms.toSet().size)
        val reserved = setOf(HelpRoute.FAQ, HelpRoute.GLOSSARY)
        assertTrue((sectionIds + faqIds).none { it in reserved || it.isBlank() })
        assertTrue(sectionIds.none { it.startsWith("agent-") })
        for (id in faqIds) {
            assertTrue("question $id is hidden by a page", sectionIds.none { it == id })
            assertTrue(HelpRoute.resolve(id, DocsContent.sections, emptyList(), DocsContent.faq) is HelpRoute.Question)
        }
        for (entry in DocsContent.faq) {
            val target = entry.sectionId ?: continue
            assertTrue("question ${entry.id} points to missing page $target", DocsContent.section(target) != null)
        }
    }

    @Test
    fun `agent pages resolve by their own id`() {
        val agent = AgentInfo(
            id = "claude", displayName = "Claude Code", publisher = "Anthropic", extensionId = "anthropic.claude-code",
            surface = AgentSurface.CODE_SERVER_EXTENSION, official = true, verifiedPublisher = true,
            dataGoesTo = "Anthropic.", signIn = "Sign in with Claude.", instructionsFile = "CLAUDE.md",
        )
        val generated = DocsContent.agentPage(agent)
        val route = HelpRoute.resolve(generated.id, DocsContent.sections, listOf(generated), DocsContent.faq)
        assertEquals(HelpRoute.Page(generated), route)
    }

    @Test
    fun `personal links are built only from a real GitHub user name`() {
        assertEquals("https://github.com/octo-cat/pocketide-keyring", PersonalLinks.keyring("octo-cat", "pocketide-keyring"))
        assertNull(PersonalLinks.keyring(null, "pocketide-keyring"))
        assertNull(PersonalLinks.keyring("", "pocketide-keyring"))
        assertNull(PersonalLinks.keyring("evil.com/x", "pocketide-keyring"))
        assertNull(PersonalLinks.keyring("a@b", "pocketide-keyring"))
        assertNull(PersonalLinks.keyring("-starts-with-dash", "pocketide-keyring"))
        assertNull(PersonalLinks.keyring("x".repeat(40), "pocketide-keyring"))
    }

    @Test
    fun `notes carry a word label, not colour alone`() {
        assertEquals("Warning", noteLabel("warn"))
        assertEquals("Warning", noteLabel("WARN"))
        assertEquals("Good to know", noteLabel("tip"))
        assertEquals("Note", noteLabel("info"))
        assertEquals("Note", noteLabel("anything else"))
    }
}
