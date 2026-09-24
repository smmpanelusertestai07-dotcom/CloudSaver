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
        // Searched in the text itself: lower-casing can change a string's length ("İ").
        val match = words.map { it to flat.indexOf(it, ignoreCase = true) }.filter { it.second >= 0 }.minByOrNull { it.second }
        val hit = match?.second ?: 0
        val hitEnd = (hit + (match?.first?.length ?: 0)).coerceAtMost(flat.length)
        val reach = radius.coerceAtLeast(0)
        val start = wordStart(flat, (hit - reach).coerceAtLeast(0), hit)
        val end = wordEnd(flat, (hit + reach).coerceIn(hitEnd, flat.length), hitEnd)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        return prefix + flat.substring(start, end).trim() + suffix
    }

    private fun bodyOf(section: DocSection): String =
        listOf(section.summary, DocText.of(section.blocks)).filter { it.isNotBlank() }.joinToString("\n")

    /** Moves [index] forward to the next word, never past [hit]. */
    private fun wordStart(text: String, index: Int, hit: Int): Int {
        if (index == 0) return 0
        val space = text.indexOf(' ', index)
        return if (space >= 0 && space < minOf(index + 15, hit)) space + 1 else index
    }

    /** Moves [index] back to the end of the previous word, never before [matchEnd]. */
    private fun wordEnd(text: String, index: Int, matchEnd: Int): Int {
        if (index >= text.length) return text.length
        val space = text.lastIndexOf(' ', index)
        return if (space > maxOf(index - 15, matchEnd - 1)) space else index
    }
}

/** Where a Help deep link goes. */
sealed interface HelpRoute {
    data object Index : HelpRoute
    data object AllQuestions : HelpRoute
    data object Glossary : HelpRoute
    data class Page(val section: DocSection) : HelpRoute
    data class Question(val entry: FaqEntry) : HelpRoute
    data object Missing : HelpRoute

    companion object {
        const val FAQ = "faq"
        const val GLOSSARY = "glossary"

        /**
         * A page wins over a question with the same id, so a question can never hide a page; a
         * stale or unknown id shows "Page not found" rather than the wrong page.
         */
        fun resolve(id: String?, sections: List<DocSection>, agentPages: List<DocSection>, faq: List<FaqEntry>): HelpRoute {
            val key = id?.trim()?.takeIf { it.isNotEmpty() } ?: return Index
            if (key == FAQ) return AllQuestions
            if (key == GLOSSARY) return Glossary
            (sections.firstOrNull { it.id == key } ?: agentPages.firstOrNull { it.id == key })?.let { return Page(it) }
            faq.firstOrNull { it.id == key }?.let { return Question(it) }
            return Missing
        }
    }
}

/**
 * The groups of the Help index and the list position each starts at, for the chips above it.
 * It mirrors the index exactly: the search field, then Guide (or its empty note), Agents when
 * there are any, More (label and card) and Your pages.
 */
object HelpIndexLayout {
    data class Group(val label: String, val start: Int)

    fun groups(sectionCount: Int, agentCount: Int): List<Group> {
        val out = mutableListOf<Group>()
        var at = 1
        out += Group("Guide", at)
        at += if (sectionCount > 0) 1 + sectionCount else 1
        if (agentCount > 0) {
            out += Group("Agents", at)
            at += 1 + agentCount
        }
        out += Group("More", at)
        at += 2
        out += Group("Your pages", at)
        return out
    }

    /** The group the first visible row belongs to. */
    fun active(groups: List<Group>, firstVisible: Int): Int =
        groups.indexOfLast { it.start <= firstVisible }.coerceAtLeast(0)
}

/** Links to the owner's own pages, built from their account name as plain strings. */
object PersonalLinks {
    private val login = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")

    /** The private keyring repository, or null when [githubLogin] is not a GitHub user name. */
    fun keyring(githubLogin: String?, repo: String): String? =
        githubLogin?.takeIf { login.matches(it) }?.let { "https://github.com/$it/$repo" }
}
