package com.pocketide.docs

import com.pocketide.model.AgentInfo
import com.pocketide.model.AgentSurface
import java.util.Locale

/** Writes an agent's help page from its own details, so a newly added agent is documented by itself. */
internal object AgentPages {

    private const val OPEN_VSX_NOTE =
        "Open VSX confirms who owns a publisher name. It cannot confirm who makes the model."

    private const val SIGN_IN_STAYS = "Sign-ins stay only on this phone, so on a new phone you sign in again."

    private val officialDataLinks = mapOf(
        "claude" to listOf(
            link("Claude privacy settings", DocLinks.CLAUDE_PRIVACY),
            link("Claude Code data usage", DocLinks.CLAUDE_DATA_USAGE),
        ),
        "codex" to listOf(
            link("ChatGPT data controls", DocLinks.CHATGPT_DATA_CONTROLS),
            link("OpenAI data controls FAQ", DocLinks.OPENAI_DATA_FAQ),
        ),
        "antigravity" to listOf(
            link("Antigravity settings", DocLinks.ANTIGRAVITY_SETTINGS),
            link("Antigravity terms", DocLinks.ANTIGRAVITY_TERMS),
        ),
    )

    fun pageFor(agent: AgentInfo): DocSection {
        val blocks = buildList {
            add(detailsTable(agent))
            add(labelNote(agent))
            add(p("Where your data goes: ${sentence(agent.dataGoesTo)} ${companyKeepsLine(agent)}"))
            add(p("Signing in: ${sentence(agent.signIn)} $SIGN_IN_STAYS"))
            add(p(roomLine(agent)))
            add(p(instructionsLine(agent)))
            add(p(removeLine(agent)))
            addAll(dataLinks(agent))
        }
        return DocSection(
            id = pageId(agent.id),
            title = agent.displayName,
            summary = "${agent.displayName} by ${agent.publisher.ifBlank { "an unnamed publisher" }}: " +
                "${label(agent)}.",
            blocks = blocks,
        )
    }

    fun pageId(agentId: String) = "agent-$agentId"

    private fun label(agent: AgentInfo) = when {
        agent.official -> "Official"
        agent.verifiedPublisher -> "Verified publisher"
        else -> "Unverified publisher"
    }

    private fun detailsTable(agent: AgentInfo): DocBlock {
        val rows = buildList {
            add(row("Publisher", agent.publisher.ifBlank { "Not given" }))
            add(row("Label", label(agent)))
            agent.extensionId?.let { add(row("Open VSX id", it)) }
            agent.version?.let { add(row("Version on this phone", it)) }
            agent.downloads?.let { add(row("Downloads on Open VSX", String.format(Locale.ROOT, "%,d", it))) }
            add(row("Shown as", surfaceLine(agent.surface)))
        }
        return DocBlock.Table(listOf("", agent.displayName), rows)
    }

    private fun surfaceLine(surface: AgentSurface) = when (surface) {
        AgentSurface.CODE_SERVER_EXTENSION -> "The publisher's own extension, full screen"
        AgentSurface.NATIVE_HUB -> "The publisher's own local app, full screen"
    }

    private fun labelNote(agent: AgentInfo): DocBlock = when {
        agent.official -> info(
            "Official: one of the three agents built into PocketIDE, published by the company that makes " +
                "its model. It updates itself; each update is checked, tested on this phone and rolled back " +
                "if it fails.",
        )
        agent.communityNote != null -> warn(
            "Verified publisher. ${sentence(agent.communityNote)} $OPEN_VSX_NOTE",
        )
        agent.verifiedPublisher -> info(
            "Verified publisher: found by the weekly Open VSX search and added by your tap. $OPEN_VSX_NOTE",
        )
        else -> warn(
            "This publisher is not verified on Open VSX. PocketIDE does not offer such agents; remove it " +
                "unless you know exactly who made it.",
        )
    }

    private fun companyKeepsLine(agent: AgentInfo) =
        if (agent.official) {
            "The company keeps it under its own policy; see its data page below."
        } else {
            "The publisher and the model service it uses keep it under their own policies."
        }

    private fun roomLine(agent: AgentInfo) =
        "Room: ${agent.displayName} runs in its own room, with its own home folder and the working " +
            "folders of its own sessions. Other rooms' folders are not visible from it. It never receives " +
            "your GitHub token, your Drive access or your Secrets; only your Variables are set in its room."

    private fun instructionsLine(agent: AgentInfo) =
        "Instructions: it reads ${agent.instructionsFile.ifBlank { "its own instructions file" }} in its " +
            "room's home, never in your repo. You can read and edit it in Your data; it syncs with your other " +
            "AI data."

    private fun removeLine(agent: AgentInfo) =
        if (agent.official) {
            "Removing: it is built in and cannot be removed. If you stop using it, sign out in its own " +
                "screen. What it already sent stays with its company; see its data page."
        } else {
            "Removing: More agents → ${agent.displayName} → Remove. Its room and its sign-in on this phone " +
                "are deleted. What it already sent stays with the publisher."
        }

    private fun dataLinks(agent: AgentInfo): List<DocBlock> {
        officialDataLinks[agent.id]?.let { return it }
        val page = agent.extensionId?.let(DocLinks::openVsxPage) ?: return emptyList()
        return listOf(link("${agent.displayName} on Open VSX", page))
    }

    /** Agent details come from outside data; make each one end as a sentence. */
    private fun sentence(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> "Not given."
            trimmed.last() in ".!?" -> trimmed
            else -> "$trimmed."
        }
    }
}
