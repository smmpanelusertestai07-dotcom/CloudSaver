package com.pocketide.sessions

import com.pocketide.core.Redact
import com.pocketide.git.Finding
import com.pocketide.git.FindingKind
import com.pocketide.git.HoldKind
import com.pocketide.git.Verdict
import com.pocketide.sessions.transcripts.oneLine

/** The check-post's findings in plain words, for the owner and for the agent that must fix them. */
internal object CheckPostWords {
    private const val SHOWN = 3

    fun blocked(verdict: Verdict): String {
        // Build outputs cannot be approved: like findings, they must leave the commits.
        val remove = verdict.findings.map(::describe) +
            verdict.holds.filter { it.kind == HoldKind.BUILD_OUTPUT }.map { "${it.path} is a build output (builds are kept in Media)" }
        val approve = verdict.holds.filter { it.kind == HoldKind.WORKFLOW_CHANGE }.map { it.path }.distinct()
        if (remove.isEmpty() && approve.isEmpty()) return "The check-post stopped the push."
        val sentences = mutableListOf<String>()
        if (remove.isNotEmpty()) {
            sentences += "The check-post stopped the push: ${listed(remove)}. Ask the agent to take them out of the commits, then try again."
        }
        if (approve.isNotEmpty()) {
            val lead = if (remove.isEmpty()) "The check-post holds the push" else "Also"
            sentences += "$lead: changed GitHub Actions code in ${listed(approve)} waits for your approval. " +
                "Read the change and approve it, then try again."
        }
        return sentences.joinToString(" ")
    }

    private fun listed(items: List<String>): String {
        val more = items.size - SHOWN
        return items.take(SHOWN).joinToString("; ") + if (more > 0) "; and $more more" else ""
    }

    private fun describe(finding: Finding): String {
        val what = when (finding.kind) {
            FindingKind.SECRET -> "a secret in ${finding.path}"
            FindingKind.AI_DATA -> "AI data in ${finding.path}"
            FindingKind.TOO_LARGE -> "${finding.path} is over 100 MB"
            FindingKind.VARIABLE_OR_SECRET_VALUE -> "the value of one of your Variables or Secrets in ${finding.path}"
        }
        // The detail names the kind of secret; it is cleaned in case it quotes the value itself.
        val detail = oneLine(Redact.text(finding.detail), DETAIL_CHARS)
        return if (detail.isBlank()) what else "$what ($detail)"
    }

    private const val DETAIL_CHARS = 80
}
