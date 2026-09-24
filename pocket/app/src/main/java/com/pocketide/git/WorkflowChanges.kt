package com.pocketide.git

import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.ObjectId
import java.io.ByteArrayOutputStream

/**
 * GitHub Actions code, which runs with the project's Secrets: what counts as it, how a change to
 * it is put to the owner, and what the owner's approval covers (this content at this path).
 */
internal object WorkflowChanges {
    private val roots = listOf(".github/workflows/", ".github/actions/")

    private val secretName = Regex("""\bsecrets\s*(?:\.\s*([A-Za-z_][A-Za-z0-9_-]*)|\[\s*['"]([^'"]+)['"]\s*])""")
    private val allSecrets = Regex("""(?i)toJSON\(\s*secrets\s*\)|^\s*secrets\s*:\s*inherit\b""", RegexOption.MULTILINE)
    private val triggerKey = Regex("""^(?:on|"on"|'on')\s*:(.*)$""")

    /** Enough for any real workflow; a longer diff is cut. */
    private const val MAX_DIFF_CHARS = 64 * 1024

    fun applies(path: String): Boolean = roots.any(path::startsWith)

    fun approvalKey(path: String, content: ObjectId): String = "${content.name}:$path"

    fun isApprovalKey(key: String): Boolean {
        val id = key.substringBefore(':')
        return ObjectId.isId(id) && applies(key.substringAfter(':', ""))
    }

    /** Plain sentences on what the change does that matters for Secrets and runs. */
    fun describe(path: String, before: String?, after: String): String {
        val old = before.orEmpty()
        val sentences = mutableListOf(
            if (before == null) "Adds GitHub Actions code in $path." else "Changes GitHub Actions code in $path.",
        )
        val newSecrets = secretNames(after) - secretNames(old)
        if (newSecrets.isNotEmpty()) sentences += "It newly uses the Secrets ${newSecrets.joinToString(", ")}."
        if (allSecrets.containsMatchIn(after) && !allSecrets.containsMatchIn(old)) {
            sentences += "It hands all of the project's Secrets to its steps."
        }
        if (triggerBlock(after) != triggerBlock(old)) {
            val events = events(after)
            sentences += if (events.isEmpty()) "It changes when it runs." else "It runs on: ${events.joinToString()}."
        }
        sentences += "Read the change and approve it before it goes to GitHub."
        return sentences.joinToString(" ")
    }

    /** A unified diff of [path] from [before] (none for a new file) to [after], cut when long. */
    fun diff(path: String, before: ByteArray?, after: ByteArray): String {
        val old = RawText(before ?: ByteArray(0))
        val new = RawText(after)
        val out = ByteArrayOutputStream()
        out.write("--- ${if (before == null) "/dev/null" else "a/$path"}\n+++ b/$path\n".toByteArray())
        DiffFormatter(out).use { formatter ->
            formatter.format(HistogramDiff().diff(RawTextComparator.DEFAULT, old, new), old, new)
        }
        val text = out.toString(Charsets.UTF_8.name())
        if (text.length <= MAX_DIFF_CHARS) return text
        return text.take(MAX_DIFF_CHARS) + "\n(The rest of the change is not shown.)\n"
    }

    /** The names of the Secrets [text] reads, as `secrets.NAME` or `secrets['NAME']`. */
    fun secretNames(text: String): Set<String> = secretName.findAll(text)
        .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .filter(String::isNotEmpty)
        .toSortedSet()

    /** The workflow's top-level `on:` block, normalised, or null when it has none. */
    fun triggerBlock(text: String): String? {
        val lines = text.lines()
        val start = lines.indexOfFirst { triggerKey.matches(it.trimEnd()) }
        if (start < 0) return null
        val block = mutableListOf(lines[start].trimEnd())
        for (line in lines.drop(start + 1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (!line.first().isWhitespace()) break
            block += line.trimEnd()
        }
        return block.joinToString("\n")
    }

    /** The event names in the `on:` block: `on: push`, `on: [push, pull_request]` or one key per line. */
    fun events(text: String): List<String> {
        val block = triggerBlock(text)?.lines() ?: return emptyList()
        val inline = triggerKey.matchEntire(block.first())?.groupValues?.get(1).orEmpty().substringBefore('#').trim()
        if (inline.isNotEmpty()) {
            return inline.trim('[', ']').split(',').map { it.trim().trim('"', '\'') }.filter(String::isNotEmpty)
        }
        val children = block.drop(1)
        val indent = children.minOfOrNull { line -> line.indexOfFirst { !it.isWhitespace() } } ?: return emptyList()
        return children
            .filter { line -> line.indexOfFirst { !it.isWhitespace() } == indent }
            .map { it.trim().removePrefix("-").trim().substringBefore(':').trim('"', '\'') }
            .filter(String::isNotEmpty)
    }
}
