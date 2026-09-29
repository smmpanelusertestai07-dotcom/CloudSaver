package com.pocketide.docs

import com.pocketide.agents.Agent
import com.pocketide.linux.LinuxPins
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
            DocsContent.TERMS_ID, DocsContent.PRIVACY_ID, DocsContent.NOTICES_ID, DocsContent.YOUR_DATA_ID,
            DocsContent.COMPUTER_ID, DocsContent.KEYS_ID, DocsContent.BACKGROUND_ID, DocsContent.TROUBLE_ID,
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
    fun `nothing of the cloud computer or Drive design is left`() {
        // The one answer for owners of an earlier version names what that version made.
        val current = everything.replace(DocsContent.faq.single { it.id == "old-version" }.answer.joinToString(" ", transform = ::blockText), "")
        val gone = listOf("Codespace", "cloud computer", "GitHub App", "free hours", "core-hour", "Google Drive", "vault", "Recently deleted")
        val found = gone.filter { current.contains(it, ignoreCase = true) }
        assertTrue("old design words in the docs: $found", found.isEmpty())
    }

    @Test
    fun `the versions the docs name are the ones the app installs`() {
        val computer = textOf(DocsContent.section(DocsContent.COMPUTER_ID)!!)
        assertTrue(computer.contains(LinuxPins.UBUNTU_VERSION))
        assertTrue(computer.contains(LinuxPins.CODE_SERVER_VERSION))
    }

    @Test
    fun `links are https or lead somewhere in the app that exists, and no placeholder is left`() {
        val links = DocsContent.sections.flatMap { it.blocks }.filterIsInstance<DocBlock.Link>().map { it.url }
        assertTrue(links.isNotEmpty())
        val broken = links.filterNot { url ->
            url.startsWith("https://") ||
                (url.startsWith(DocsContent.HELP_SCHEME) && DocsContent.section(url.removePrefix(DocsContent.HELP_SCHEME)) != null) ||
                (url.startsWith(DocsContent.APP_SCHEME) && AppPlace.of(url.removePrefix(DocsContent.APP_SCHEME)) != null)
        }
        assertTrue(broken.toString(), broken.isEmpty())
        assertTrue("an in-app link", links.any { it.startsWith(DocsContent.APP_SCHEME) })
        assertTrue(listOf("TODO", "FIXME", "lorem").none { everything.contains(it, ignoreCase = true) })
    }

    @Test
    fun `search finds settings and answers too, not only pages`() {
        assertTrue(DocsContent.searchPlaces("app lock").any { it == AppPlace.SETTINGS })
        assertTrue(DocsContent.searchPlaces("api key").any { it == AppPlace.KEYS })
        assertTrue(DocsContent.searchPlaces("reset").any { it == AppPlace.COMPUTER })
        assertTrue(DocsContent.searchQuestions("github").any { it.id == "to-github" })
        assertTrue(DocsContent.search("child process").any { it.id == DocsContent.BACKGROUND_ID })
    }

    @Test
    fun `every agent is named with its maker, sign-in and chats folder`() {
        val page = textOf(DocsContent.section("agents")!!)
        Agent.entries.forEach { agent ->
            assertTrue(
                agent.name,
                page.contains(agent.displayName) && page.contains(agent.maker) && page.contains(agent.chatsFolder) && page.contains(agent.signInCommand),
            )
        }
    }

    @Test
    fun `search finds pages by every word`() {
        assertTrue(DocsContent.search("reset ubuntu").any { it.id == DocsContent.COMPUTER_ID })
        assertTrue(DocsContent.search("zzzz").isEmpty())
        assertTrue(DocsContent.search("  ").isEmpty())
    }

    @Test
    fun `the tagline is the one PocketIDE has always had`() {
        assertEquals("Agentic development on your phone", DocsContent.TAGLINE)
    }
}
