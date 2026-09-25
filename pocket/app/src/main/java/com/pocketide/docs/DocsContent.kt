package com.pocketide.docs

import com.pocketide.model.AgentInfo

/**
 * All in-app docs, versioned with the app. Facts that change carry an "as of" date and a link to
 * their source; live values (usage, sizes, versions) are shown by the screens, not written here.
 */
object DocsContent {
    /** The day the facts, prices and links were checked, and the terms and privacy policy took effect. */
    const val CHECKED_ON: String = DocLinks.CHECKED_ON

    /** The open-source notices text inside the APK's assets. */
    const val NOTICES_ASSET: String = Legal.NOTICES_ASSET

    /** The guide, in reading order (the ~3,000-word body, without terms, privacy policy or notices). */
    val guide: List<DocSection> = GuideStart.all + GuideData.all + GuideSafety.all + GuidePhone.all

    /** Terms of use, privacy policy and the open-source notices page. */
    val legal: List<DocSection> = Legal.all

    /** Every Help section, in reading order. */
    val sections: List<DocSection> = guide + legal

    val faq: List<FaqEntry> = Faq.all

    val glossary: List<GlossaryEntry> = Glossary.all.sortedBy { it.term.lowercase() }

    private val byId: Map<String, DocSection> = sections.associateBy { it.id }

    /** The section with this id, or null (for example a stale deep link). */
    fun section(id: String): DocSection? = byId[id]

    /** The FAQ entries that point to this section. */
    fun faqFor(sectionId: String): List<FaqEntry> = faq.filter { it.sectionId == sectionId }

    /** The help page of one agent, generated from its own details. */
    fun agentPage(agent: AgentInfo): DocSection = AgentPages.pageFor(agent)
}
