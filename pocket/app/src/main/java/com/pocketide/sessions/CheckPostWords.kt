package com.pocketide.sessions

import com.pocketide.core.Redact
import com.pocketide.git.Finding
import com.pocketide.git.FindingKind
import com.pocketide.git.Verdict
import com.pocketide.sessions.transcripts.oneLine

/** The check-post's findings in plain words, for the owner and for the agent that must fix them. */
internal object CheckPostWords {
    private const val SHOWN = 3

    fun blocked(verdict: Verdict): String {
        val findings = verdict.findings
        if (findings.isEmpty()) return "The check-post stopped the push."
        val listed = findings.take(SHOWN).joinToString("; ") { describe(it) }
        val more = findings.size - SHOWN
        val tail = if (more > 0) "; and $more more" else ""
        return "The check-post stopped the push: $listed$tail. Ask the agent to take them out of the commits, then try again."
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
