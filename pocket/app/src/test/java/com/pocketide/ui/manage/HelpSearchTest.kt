package com.pocketide.ui.manage

import com.pocketide.docs.DocBlock
import com.pocketide.docs.DocSection
import com.pocketide.docs.FaqEntry
import com.pocketide.docs.GlossaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpSearchTest {
    private val data = DocSection(
        "data", "Your data", "Where each thing lives.",
        listOf(
            DocBlock.Paragraph("Chats are encrypted in Drive."),
            DocBlock.Table(listOf("Data", "Phone", "Drive"), listOf(listOf("Chats", "30 days", "all"))),
        ),
    )
    private val delete = DocSection(
        "delete", "Deleting", "Recently deleted keeps chats for 30 days.",
        listOf(
            DocBlock.Steps(listOf("Open Chats", "Tap Delete")),
            DocBlock.Note("warn", "Drive's Trash is not used."),
            DocBlock.Link("Drive settings", "https://drive.google.com/drive/settings"),
        ),
    )
    private val faq = listOf(FaqEntry("faq-gone", "When is a deleted chat really gone?", listOf(DocBlock.Paragraph("After 30 days.")), "delete"))
    private val glossary = listOf(GlossaryEntry("Room", "The separate space one agent runs in."))

    @Test
    fun `every block type flattens to its text`() {
        assertEquals("a", DocText.of(DocBlock.Paragraph("a")))
        assertEquals("a\nb", DocText.of(DocBlock.Bullets(listOf("a", "b"))))
        assertEquals("a\nb", DocText.of(DocBlock.Steps(listOf("a", "b"))))
        assertEquals("H1 · H2\nx · y", DocText.of(DocBlock.Table(listOf("H1", "H2"), listOf(listOf("x", "y")))))
        assertEquals("careful", DocText.of(DocBlock.Note("warn", "careful")))
        assertEquals("Label", DocText.of(DocBlock.Link("Label", "https://example.org")))
        assertTrue(DocText.of(data).startsWith("Your data\nWhere each thing lives.\nChats are encrypted"))
    }

    @Test
    fun `blank query finds nothing`() {
        assertTrue(HelpSearch.search("   ", listOf(data), faq, glossary).isEmpty())
    }

    @Test
    fun `every word must match, in any order and case`() {
        val hits = HelpSearch.search("DRIVE encrypted", listOf(data, delete), faq, glossary)
        assertEquals(1, hits.size)
        assertEquals("data", (hits.single() as HelpHit.Section).section.id)
    }

    @Test
    fun `title matches come first, then document order`() {
        val hits = HelpSearch.search("delet", listOf(data, delete), faq, glossary)
        val ids = hits.map {
            when (it) {
                is HelpHit.Section -> it.section.id
                is HelpHit.Faq -> it.entry.id
                is HelpHit.Term -> it.entry.term
            }
        }
        assertEquals(listOf("delete", "faq-gone"), ids)
    }

    @Test
    fun `glossary and table cells are searched`() {
        assertTrue(HelpSearch.search("separate space", emptyList(), emptyList(), glossary).single() is HelpHit.Term)
        assertEquals("data", (HelpSearch.search("30 days all", listOf(data), emptyList(), emptyList()).single() as HelpHit.Section).section.id)
    }

    @Test
    fun `snippet centres on the match and marks cuts`() {
        val text = "word ".repeat(40) + "needle " + "tail ".repeat(40)
        val snippet = HelpSearch.snippet(text, listOf("needle"), radius = 30)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("needle"))
        assertTrue(snippet.length < 80)
        assertEquals("short text", HelpSearch.snippet("short\n  text", listOf("short")))
    }

    @Test
    fun `words are split, lower-cased and unique`() {
        assertEquals(listOf("put", "on", "main"), HelpSearch.words("  Put ON main  put "))
    }
}
