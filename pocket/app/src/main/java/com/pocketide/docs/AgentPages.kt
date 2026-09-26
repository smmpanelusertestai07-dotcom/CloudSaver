package com.pocketide.docs

import com.pocketide.model.AgentInfo
import com.pocketide.model.AgentSurface
import java.util.Locale

/** Writes an agent's help page from its own details, so a newly added agent is documented by itself. */
internal object AgentPages {

    private const val OPEN_VSX_NOTE =
        "Open VSX confirms who owns a publisher name. It cannot confirm who makes the model."

    private const val REMOTE_CONTROL =
        "Remote Control (needs a phone test): while this version's own screen stays closed in PocketIDE, the Antigravity " +
            "room offers Google's own Remote Control. It runs in the room, and you use Antigravity on Google's Remote " +
            "Control page in your browser, signed in with the same Google Account; the page can go on your home screen " +
            "and send notifications. PocketIDE turns it off again if it opens a port other apps on the phone could use. " +
            "Stopping the room stops it."

    private const val SIGN_IN_STAYS = "Sign-ins stay only on this phone, so on a new phone you sign in again."

    /** Longest name or version shown; publishers write these, and a page must stay readable. */
    private const val MAX_NAME = 80

    /** Longest sentence taken from an agent's details. */
    private const val MAX_SENTENCE = 400

    /** Control, format (bidi overrides, zero-width) and other invisible characters that could disguise text. */
    private val INVISIBLE = Regex("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}\\p{Zl}\\p{Zp}]")
    private val SPACES = Regex("\\s+")

    /** The built-in three and their companies' data pages. Only these can be Official, whatever a detail claims. */
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
        val details = Details.of(agent)
        val blocks = buildList {
            add(detailsTable(details))
            add(labelNote(details))
            add(p("Where your data goes: ${details.dataGoesTo} ${companyKeepsLine(details)}"))
            addAll(chatsLines(details))
            add(p("Signing in: ${details.signIn} $SIGN_IN_STAYS"))
            add(p(roomLine(details)))
            add(p(instructionsLine(details)))
            add(p(removeLine(details)))
            if (details.official && details.id == "antigravity") {
                add(p(REMOTE_CONTROL))
                add(link("Antigravity Remote Control", DocLinks.ANTIGRAVITY_REMOTE_CONTROL))
            }
            addAll(dataLinks(details))
        }
        return DocSection(
            id = pageId(agent.id),
            title = details.name,
            summary = "${details.name} by ${details.publisher ?: "an unnamed publisher"}: ${details.label}.",
            blocks = blocks,
        )
    }

    fun pageId(agentId: String) = "agent-$agentId"

    /** An agent's details as they may be shown: cleaned, bounded, and with its label settled. */
    private class Details(
        val id: String,
        val name: String,
        val publisher: String?,
        val extensionId: String?,
        val version: String?,
        val downloads: Long?,
        val surface: AgentSurface,
        val official: Boolean,
        val verified: Boolean,
        val dataGoesTo: String,
        val signIn: String,
        val instructionsFile: String?,
        val communityNote: String?,
    ) {
        val label = when {
            official -> "Official"
            verified -> "Verified publisher"
            else -> "Unverified publisher"
        }

        companion object {
            fun of(agent: AgentInfo): Details {
                val extensionId = name(agent.extensionId)
                val official = agent.official && agent.id in officialDataLinks
                return Details(
                    id = agent.id,
                    name = name(agent.displayName) ?: extensionId ?: name(agent.id) ?: "This agent",
                    publisher = name(agent.publisher),
                    extensionId = extensionId,
                    version = name(agent.version),
                    downloads = agent.downloads?.takeIf { it >= 0 },
                    surface = agent.surface,
                    official = official,
                    verified = official || agent.verifiedPublisher,
                    dataGoesTo = sentence(agent.dataGoesTo),
                    signIn = sentence(agent.signIn),
                    instructionsFile = name(agent.instructionsFile),
                    communityNote = agent.communityNote?.takeIf { clean(it).isNotEmpty() }?.let(::sentence),
                )
            }
        }
    }

    private fun detailsTable(agent: Details): DocBlock {
        val rows = buildList {
            add(row("Publisher", agent.publisher ?: "Not given"))
            add(row("Label", agent.label))
            agent.extensionId?.let { add(row("Open VSX id", it)) }
            agent.version?.let { add(row("Version on this phone", it)) }
            agent.downloads?.let { add(row("Downloads on Open VSX", String.format(Locale.ROOT, "%,d", it))) }
            add(row("Shown as", surfaceLine(agent.surface)))
        }
        return DocBlock.Table(listOf("", agent.name), rows)
    }

    private fun surfaceLine(surface: AgentSurface) = when (surface) {
        AgentSurface.CODE_SERVER_EXTENSION -> "The publisher's own extension, full screen"
        AgentSurface.NATIVE_HUB -> "The publisher's own local app, full screen"
    }

    private fun labelNote(agent: Details): DocBlock = when {
        agent.official -> info(
            "Official: one of the three agents built into PocketIDE, published by the company that makes " +
                "its model. It updates itself; each update is checked, tested on this phone and rolled back " +
                "if it fails.",
        )
        agent.verified && agent.communityNote != null -> warn(
            "Verified publisher. ${agent.communityNote} $OPEN_VSX_NOTE",
        )
        agent.verified -> info(
            "Verified publisher: found by the weekly Open VSX search and added by your tap. $OPEN_VSX_NOTE",
        )
        else -> warn(
            "This publisher is not verified on Open VSX. PocketIDE does not offer such agents; remove it " +
                "unless you know exactly who made it.",
        )
    }

    private fun companyKeepsLine(agent: Details) =
        if (agent.official) {
            "The company keeps it under its own policy; see its data page below."
        } else {
            "The publisher and the model service it uses keep it under their own policies."
        }

    /** Where an official agent's chats are saved, and the company pages that show them. */
    private fun chatsLines(agent: Details): List<DocBlock> {
        val home = ChatHomes.of(agent.id)?.takeIf { agent.official } ?: return emptyList()
        return listOfNotNull(p("Where its chats are saved: ${home.kept}"), home.note?.let(::p), home.open) + home.sources
    }

    private fun roomLine(agent: Details) =
        "Room: ${agent.name} runs in its own room, with its own home folder and the working folders of its " +
            "own sessions. Other rooms' folders are not visible from it. It never receives your GitHub token, " +
            "your Drive access or your Secrets; only your Variables are set in its room."

    private fun instructionsLine(agent: Details) =
        "Instructions: it reads ${agent.instructionsFile ?: "its own instructions file"} in its room's home, " +
            "never in your repo. You can read and edit it in Your data; it syncs with your other AI data."

    private fun removeLine(agent: Details) =
        if (agent.official) {
            "Removing: it is built in and cannot be removed. If you stop using it, sign out in its own " +
                "screen. What it already sent stays with its company; see its data page."
        } else {
            "Removing: More agents → ${agent.name} → Remove. Its room and its sign-in on this phone are " +
                "deleted. What it already sent stays with the publisher."
        }

    private fun dataLinks(agent: Details): List<DocBlock> {
        if (agent.official) officialDataLinks[agent.id]?.let { return it }
        val page = agent.extensionId?.let(DocLinks::openVsxPage) ?: return emptyList()
        return listOf(link("${agent.name} on Open VSX", page))
    }

    /** Outside text on one line, without invisible characters that could disguise it. */
    private fun clean(text: String): String = text.replace(INVISIBLE, " ").replace(SPACES, " ").trim()

    private fun name(text: String?): String? =
        text?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { shorten(it, MAX_NAME) }

    /** Agent details come from outside data; make each one a bounded sentence. */
    private fun sentence(text: String): String {
        val cleaned = shorten(clean(text), MAX_SENTENCE)
        return when {
            cleaned.isEmpty() -> "Not given."
            cleaned.last() in SENTENCE_ENDS -> cleaned
            cleaned.length > 1 && cleaned.last() in CLOSERS && cleaned[cleaned.length - 2] in SENTENCE_ENDS -> cleaned
            else -> "$cleaned."
        }
    }

    private fun shorten(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max - 1
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private const val SENTENCE_ENDS = ".!?…"
    private const val CLOSERS = "\"')”’"
}
