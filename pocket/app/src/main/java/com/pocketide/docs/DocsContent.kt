package com.pocketide.docs

import com.pocketide.model.AgentInfo

/** All in-app docs. Replaced by the docs module. */
object DocsContent {
    val sections: List<DocSection> = emptyList()
    val faq: List<FaqEntry> = emptyList()
    val glossary: List<GlossaryEntry> = emptyList()

    /** The help page of one agent, generated from its own details. */
    fun agentPage(agent: AgentInfo): DocSection = DocSection("agent-${agent.id}", agent.displayName, agent.dataGoesTo, emptyList())
}
