package com.pocketide.sessions

import java.text.Normalizer
import java.util.Locale

/** Session branch names: `pocket/<agent>/<yyyy-mm-dd>-<slug>`, with `-2`, `-3`… when taken. */
internal object BranchNames {
    const val MAX_SLUG = 40
    const val DEFAULT_SLUG = "session"

    /** Lowercase ASCII letters, digits and single hyphens; accents are dropped ("Café" → "cafe"). */
    fun slug(title: String?): String = clean(title.orEmpty()).take(MAX_SLUG).trim('-').ifEmpty { DEFAULT_SLUG }

    /**
     * On a public repository anyone can read branch names, so they must not tell what the chat is
     * about: the slug is the start of the session id instead of the title.
     */
    fun neutralSlug(sessionId: String): String = clean(sessionId).replace("-", "").take(NEUTRAL_CHARS).ifEmpty { DEFAULT_SLUG }

    private const val NEUTRAL_CHARS = 8

    /** The part of the branch that names the agent ("claude"; a discovered "pub.name" becomes "pub-name"). */
    fun agentPart(agentId: String): String = clean(agentId).ifEmpty { "agent" }

    /** All session branches of an agent start with this. */
    fun prefix(agentId: String) = "pocket/${agentPart(agentId)}/"

    /** [date] is the day in the phone's zone, as `Ist.branchDate` gives it. */
    fun base(agentId: String, date: String, slug: String) = "${prefix(agentId)}$date-$slug"

    /** [base] when free, else the first free of `base-2`, `base-3`… */
    fun unique(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private fun clean(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).lowercase(Locale.ROOT)
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> out.append(c)
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                out.isNotEmpty() && out.last() != '-' -> out.append('-')
            }
        }
        return out.toString().trim('-')
    }
}
