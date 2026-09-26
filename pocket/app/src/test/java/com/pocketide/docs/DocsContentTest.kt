package com.pocketide.docs

import com.pocketide.agents.Agent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocsContentTest {
    private fun textOf(section: DocSection): String = (listOf(section.title, section.summary) + section.blocks.map(::blockText)).joinToString(" ")

    private fun blockText(block: DocBlock): String = when (block) {
        is DocBlock.Paragraph -> block.text
        is DocBlock.Bullets -> block.items.joinToString(" ")
        is DocBlock.Steps -> block.items.joinToString(" ")
        is DocBlock.Table -> (block.header + block.rows.flatten()).joinToString(" ")
        is DocBlock.Note -> block.text
        is DocBlock.Link -> "${block.label} ${block.url}"
    }

    private val everything: String by lazy {
        (
            DocsContent.sections.map(::textOf) + DocsContent.faq.map { it.question + " " + it.answer.joinToString(" ", transform = ::blockText) } +
                DocsContent.glossary.map { it.term + " " + it.meaning }
            ).joinToString("\n")
    }

    @Test
    fun `page ids are unique and every id a screen opens exists`() {
        val ids = DocsContent.sections.map { it.id }
        assertEquals(ids.distinct(), ids)
        listOf(
            DocsContent.TERMS_ID, DocsContent.PRIVACY_ID, DocsContent.NOTICES_ID,
            DocsContent.OWNER_SET_UP_ID, DocsContent.YOUR_DATA_ID, DocsContent.USAGE_ID,
        ).forEach { assertTrue(it, DocsContent.section(it) != null) }
    }

    @Test
    fun `every question belongs to a page that exists`() {
        val orphans = DocsContent.faq.filter { it.sectionId != null && DocsContent.section(it.sectionId) == null }.map { it.id }
        assertTrue("questions pointing nowhere: $orphans", orphans.isEmpty())
        assertEquals(DocsContent.faq.map { it.id }.distinct(), DocsContent.faq.map { it.id })
    }

    @Test
    fun `the guide stays short`() {
        val words = DocsContent.guide.sumOf { textOf(it).split(Regex("\\s+")).size }
        assertTrue("the guide is $words words; keep it under 2,200", words < 2_200)
    }

    @Test
    fun `nothing of the phone computer or Drive design is left`() {
        val gone = listOf("proot", "Google Drive", "vault", "keyring", "Recently deleted", "rootfs", "code-server")
        val found = gone.filter { everything.contains(it, ignoreCase = true) }
        assertTrue("old design words in the docs: $found", found.isEmpty())
    }

    @Test
    fun `facts that change carry the day they were checked`() {
        assertTrue(everything.contains(DocsContent.CHECKED_ON) || everything.contains(com.pocketide.usage.Allowance.CHECKED_ON))
        assertTrue(DocsContent.section(DocsContent.USAGE_ID)!!.let(::textOf).contains("as of"))
    }

    @Test
    fun `links are https, and no placeholder is left`() {
        val links = DocsContent.sections.flatMap { it.blocks }.filterIsInstance<DocBlock.Link>().map { it.url }
        assertTrue(links.isNotEmpty())
        assertTrue(links.filterNot { it.startsWith("https://") }.toString(), links.all { it.startsWith("https://") })
        assertTrue(listOf("TODO", "FIXME", "lorem").none { everything.contains(it, ignoreCase = true) })
    }

    @Test
    fun `every agent is named with its maker, sign-in and chats folder`() {
        val page = textOf(DocsContent.section("agents")!!)
        Agent.entries.forEach { agent ->
            assertTrue(agent.name, page.contains(agent.displayName) && page.contains(agent.maker) && page.contains(agent.chatsFolder))
        }
    }

    @Test
    fun `search finds pages by every word`() {
        assertTrue(DocsContent.search("free hours").any { it.id == DocsContent.USAGE_ID })
        assertTrue(DocsContent.search("zzzz").isEmpty())
        assertTrue(DocsContent.search("  ").isEmpty())
    }

    @Test
    fun `the tagline is the one PocketIDE has always had`() {
        assertEquals("Agentic development on your phone", DocsContent.TAGLINE)
    }
}
