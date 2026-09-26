package com.pocketide.docs

import android.content.Context
import java.util.Locale

/**
 * All in-app docs, versioned with the app. Facts that change carry the day they were checked;
 * live values (usage, computers, versions) are shown by the screens, not written here.
 */
object DocsContent {
    const val TAGLINE = "Agentic development on your phone"

    /** Raised when the terms or the privacy policy change in a way the owner should see again. */
    const val TERMS_VERSION = 4

    // Page ids other screens open directly.
    const val TERMS_ID = "terms"
    const val PRIVACY_ID = "privacy"
    const val NOTICES_ID = "notices"
    const val OWNER_SET_UP_ID = "owner-set-up"
    const val YOUR_DATA_ID = "your-data"
    const val USAGE_ID = "usage"

    /** The day the facts, prices and links were checked. */
    const val CHECKED_ON: String = DocLinks.CHECKED_ON

    const val NOTICES_ASSET: String = Legal.NOTICES_ASSET

    /** The guide, in reading order. */
    val guide: List<DocSection> = Guide.all

    /** Terms of use, privacy policy and open-source licences. */
    val legal: List<DocSection> = Legal.all

    /** Every Help page, the owner's set-up included. */
    val sections: List<DocSection> = Guide.all + Guide.ownerOnly + Legal.all

    val faq: List<FaqEntry> = Faq.all

    val glossary: List<GlossaryEntry> = Glossary.all.sortedBy { it.term.lowercase(Locale.ROOT) }

    private val byId: Map<String, DocSection> = sections.associateBy { it.id }

    /** The page with this id, or null (for example a stale link). */
    fun section(id: String): DocSection? = byId[id]

    /** The questions that belong to this page. */
    fun faqFor(sectionId: String): List<FaqEntry> = faq.filter { it.sectionId == sectionId }

    /** Pages whose title, summary or text contains every word of [query]. */
    fun search(query: String): List<DocSection> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return sections.filter { section ->
            val text = (listOf(section.title, section.summary) + section.blocks.map(::textOf)).joinToString(" ").lowercase(Locale.ROOT)
            words.all { it in text }
        }
    }

    /** The full open-source notices from the APK's assets. */
    fun notices(context: Context): String =
        runCatching { context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() } }
            .getOrDefault("The notices file is missing from this build.")

    private fun textOf(block: DocBlock): String = when (block) {
        is DocBlock.Paragraph -> block.text
        is DocBlock.Bullets -> block.items.joinToString(" ")
        is DocBlock.Steps -> block.items.joinToString(" ")
        is DocBlock.Table -> (block.header + block.rows.flatten()).joinToString(" ")
        is DocBlock.Note -> block.text
        is DocBlock.Link -> block.label
    }
}
