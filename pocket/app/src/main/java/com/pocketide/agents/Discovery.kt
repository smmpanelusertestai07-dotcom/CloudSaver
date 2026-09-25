package com.pocketide.agents

import com.pocketide.core.Clock
import com.pocketide.model.AgentCandidate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

/** An agent that passed every check, with what its card shows beyond [AgentCandidate]. */
internal data class Found(val candidate: AgentCandidate, val facts: CandidateFacts)

/** Why one search result was or was not offered; kept small so tests can name each rule. */
internal sealed interface Verdict {
    data class Offered(val found: Found) : Verdict
    data class Skipped(val id: String, val rule: Rule) : Verdict
}

/** The checks of plan §7, in the order they run (cheap ones first, from the search row). */
internal enum class Rule {
    ALREADY_KNOWN,
    NOT_VERIFIED,
    TOO_FEW_DOWNLOADS,
    DEPRECATED,
    LOOKALIKE,
    MICROSOFT,
    GONE,
    NO_ARM64_BUILD,
    NOT_AI_OR_CHAT,
    NOT_PUBLISHED,
    NO_AGENT_SCREEN,
    TOO_NEW,
}

/**
 * The weekly search for new agents on Open VSX: the AI and Chat categories, most downloaded
 * first, each result checked against the rules below and offered only when it passes all:
 *
 * - verified publisher (Open VSX no longer exposes `unrelatedPublisher` or `namespaceAccess`,
 *   so `verified` is the only trust signal it gives) and not a look-alike of an official one;
 * - at least 50,000 downloads, first published at least 14 days ago;
 * - a linux-arm64 or universal build, from the per-extension answer (search rows can point at
 *   another platform's file);
 * - AI or Chat among its categories, published (not under review), not deprecated;
 * - its own agent screen (a webview in a view container), read from its package.json;
 * - not official, not already added, and not Microsoft's (Microsoft's extensions may be used
 *   only in Microsoft's own products).
 */
internal class Discovery(private val vsx: OpenVsx, private val clock: Clock) {

    suspend fun run(known: Set<String>): List<Verdict> {
        val seen = mutableSetOf<String>()
        val verdicts = mutableListOf<Verdict>()
        for (category in CATEGORIES) {
            for (entry in popular(category)) {
                if (!seen.add(entry.id)) continue
                currentCoroutineContext().ensureActive()
                verdicts += examine(entry, known)
            }
        }
        return verdicts
    }

    /** Results with enough downloads; pages stop at the first row below the bar (sorted by downloads). */
    private suspend fun popular(category: String): List<SearchEntry> {
        val rows = mutableListOf<SearchEntry>()
        var offset = 0
        repeat(MAX_PAGES) {
            val page = vsx.search(category, offset, PAGE_SIZE)
            rows += page.extensions
            offset += page.extensions.size
            val exhausted = page.extensions.isEmpty() || offset >= page.totalSize
            if (exhausted || page.extensions.last().downloadCount < MIN_DOWNLOADS) return rows
        }
        return rows
    }

    suspend fun examine(entry: SearchEntry, known: Set<String>): Verdict {
        fun skip(rule: Rule) = Verdict.Skipped(entry.id, rule)
        if (entry.id in known || entry.id in OfficialAgents.extensionIds) return skip(Rule.ALREADY_KNOWN)
        if (!entry.verified) return skip(Rule.NOT_VERIFIED)
        if (entry.downloadCount < MIN_DOWNLOADS) return skip(Rule.TOO_FEW_DOWNLOADS)
        if (entry.deprecated) return skip(Rule.DEPRECATED)
        if (Lookalikes.problem(entry.namespace, entry.name) != null) return skip(Rule.LOOKALIKE)
        if (isMicrosoftNamespace(entry.namespace)) return skip(Rule.MICROSOFT)

        val overview = vsx.extension(entry.namespace, entry.name) ?: return skip(Rule.GONE)
        val target = TARGETS.firstOrNull { it in overview.downloads } ?: return skip(Rule.NO_ARM64_BUILD)
        val ext = vsx.latest(entry.namespace, entry.name, target) ?: return skip(Rule.NO_ARM64_BUILD)
        if (!ext.verified) return skip(Rule.NOT_VERIFIED)
        if (ext.downloadCount < MIN_DOWNLOADS) return skip(Rule.TOO_FEW_DOWNLOADS)
        if (ext.deprecated) return skip(Rule.DEPRECATED)
        if (ext.targetPlatform != target) return skip(Rule.NO_ARM64_BUILD)
        if (ext.categories.none { it in CATEGORIES }) return skip(Rule.NOT_AI_OR_CHAT)
        if (isMicrosoftLicensed(ext.license)) return skip(Rule.MICROSOFT)
        val published = ext.downloadable && (ext.reviewStatus == null || ext.reviewStatus == "published")
        if (!published) return skip(Rule.NOT_PUBLISHED)

        val packageJson = vsx.packageJson(ext.files["manifest"])
        val openCommand = AgentScreens.openCommand(packageJson) ?: return skip(Rule.NO_AGENT_SCREEN)

        val firstPublished = vsx.firstPublished(entry.namespace, entry.name) ?: return skip(Rule.TOO_NEW)
        if (clock.now() - firstPublished < MIN_AGE_MS) return skip(Rule.TOO_NEW)

        val now = clock.now()
        val candidate = AgentCandidate(
            extensionId = ext.id,
            displayName = ext.displayName?.takeIf { it.isNotBlank() } ?: ext.name,
            publisher = ext.namespaceDisplayName?.takeIf { it.isNotBlank() } ?: ext.namespace,
            version = ext.version,
            downloads = ext.downloadCount,
            firstPublishedAt = firstPublished,
            categories = ext.categories,
            description = ext.description.orEmpty().take(MAX_DESCRIPTION),
            iconUrl = vsx.fileUrl(ext.files["icon"])?.toString(),
            foundAt = now,
        )
        val facts = CandidateFacts(
            extensionId = ext.id,
            identifier = "${ext.namespace}.${ext.name}",
            target = target,
            license = ext.license?.takeIf { it.isNotBlank() },
            repository = ext.repository?.takeIf { it.startsWith("https://") },
            averageRating = ext.averageRating,
            reviewCount = ext.reviewCount,
            openCommand = openCommand,
            checkedAt = now,
        )
        return Verdict.Offered(Found(candidate, facts))
    }

    companion object {
        const val MIN_DOWNLOADS = 50_000L
        val MIN_AGE_MS: Long = TimeUnit.DAYS.toMillis(14)
        val CATEGORIES = listOf("AI", "Chat")
        /** PocketIDE's Linux is glibc Ubuntu on arm64: never alpine-arm64. */
        val TARGETS = listOf("linux-arm64", ExtensionVersion.UNIVERSAL)
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 4
        private const val MAX_DESCRIPTION = 400

        fun isMicrosoftNamespace(namespace: String): Boolean {
            val ns = namespace.lowercase()
            return ns == "microsoft" || ns == "vscode" || ns.startsWith("ms-")
        }

        /** Licences that name Microsoft limit use to Microsoft's products (the Marketplace terms). */
        fun isMicrosoftLicensed(license: String?): Boolean = license?.contains("microsoft", ignoreCase = true) == true
    }
}
