package com.pocketide.ui.manage

import com.pocketide.docs.DocBlock
import com.pocketide.docs.DocSection
import com.pocketide.docs.FaqEntry
import com.pocketide.docs.GlossaryEntry
import java.util.Locale

/** Plain text of the docs, for search and snippets. */
object DocText {
    fun of(block: DocBlock): String = when (block) {
        is DocBlock.Paragraph -> block.text
        is DocBlock.Bullets -> block.items.joinToString("\n")
        is DocBlock.Steps -> block.items.joinToString("\n")
        is DocBlock.Table -> (listOf(block.header) + block.rows).joinToString("\n") { it.joinToString(" · ") }
        is DocBlock.Note -> block.text
        is DocBlock.Link -> block.label
    }

    fun of(blocks: List<DocBlock>): String = blocks.joinToString("\n") { of(it) }

    fun of(section: DocSection): String = listOf(section.title, section.summary, of(section.blocks))
        .filter { it.isNotBlank() }
        .joinToString("\n")

    fun of(entry: FaqEntry): String = entry.question + "\n" + of(entry.answer)
}

/** One search result on the Help screen. */
sealed interface HelpHit {
    val snippet: String

    data class Section(val section: DocSection, override val snippet: String) : HelpHit
    data class Faq(val entry: FaqEntry, override val snippet: String) : HelpHit
    data class Term(val entry: GlossaryEntry, override val snippet: String) : HelpHit
}

/**
 * Every word of the query must appear somewhere in an entry (any order, any case). Entries whose
 * title holds every word come first, then sections, questions and terms in document order.
 */
object HelpSearch {
    private const val SNIPPET_RADIUS = 70

    fun words(query: String): List<String> =
        query.lowercase(Locale.ROOT).split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun search(
        query: String,
        sections: List<DocSection>,
        faq: List<FaqEntry>,
        glossary: List<GlossaryEntry>,
    ): List<HelpHit> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val ranked = mutableListOf<Pair<Boolean, HelpHit>>()
        for (section in sections) {
            val text = DocText.of(section)
            if (containsAll(text, words)) {
                ranked += containsAll(section.title, words) to HelpHit.Section(section, snippet(bodyOf(section), words))
            }
        }
        for (entry in faq) {
            if (containsAll(DocText.of(entry), words)) {
                ranked += containsAll(entry.question, words) to HelpHit.Faq(entry, snippet(DocText.of(entry.answer), words))
            }
        }
        for (entry in glossary) {
            if (containsAll(entry.term + "\n" + entry.meaning, words)) {
                ranked += containsAll(entry.term, words) to HelpHit.Term(entry, snippet(entry.meaning, words))
            }
        }
        // sortedBy is stable, so document order survives inside each group.
        return ranked.sortedBy { (titleMatch, _) -> if (titleMatch) 0 else 1 }.map { it.second }
    }

    fun containsAll(text: String, words: List<String>): Boolean {
        val haystack = text.lowercase(Locale.ROOT)
        return words.all { haystack.contains(it) }
    }

    /** A short piece of [text] around the first matching word, on one line, with "…" where cut. */
    fun snippet(text: String, words: List<String>, radius: Int = SNIPPET_RADIUS): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return ""
        val lower = flat.lowercase(Locale.ROOT)
        val hit = words.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (hit - radius).coerceAtLeast(0).let { wordStart(flat, it) }
        val end = (hit + radius).coerceAtMost(flat.length).let { wordEnd(flat, it) }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        return prefix + flat.substring(start, end).trim() + suffix
    }

    private fun bodyOf(section: DocSection): String =
        listOf(section.summary, DocText.of(section.blocks)).filter { it.isNotBlank() }.joinToString("\n")

    private fun wordStart(text: String, index: Int): Int {
        if (index == 0) return 0
        val space = text.indexOf(' ', index)
        return if (space in index until index + 15) space + 1 else index
    }

    private fun wordEnd(text: String, index: Int): Int {
        if (index >= text.length) return text.length
        val space = text.lastIndexOf(' ', index)
        return if (space > index - 15 && space > 0) space else index
    }
}
