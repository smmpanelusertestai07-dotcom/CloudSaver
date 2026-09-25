package com.pocketide.agents

/**
 * Catches identifiers made to pass for an official agent: characters from other alphabets that
 * look like Latin letters, a publisher name one or two letters away from an official one, or an
 * official extension's name under another publisher.
 */
internal object Lookalikes {
    /** Open VSX names are plain ASCII; anything else here can only be imitation. */
    private val PLAIN = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
    private const val NEAR = 2

    private val official = OfficialAgents.extensionIds.map { it.substringBefore('.') to it.substringAfter('.') }

    /** Why [namespace].[name] looks like an official agent, or null when it does not. */
    fun problem(namespace: String, name: String): String? {
        if (!PLAIN.matches(namespace) || !PLAIN.matches(name)) {
            return "Its name uses characters that can imitate other letters."
        }
        val ns = namespace.lowercase()
        val ext = name.lowercase()
        for ((officialNs, officialName) in official) {
            if (ns == officialNs) continue
            if (distance(ns, officialNs) <= NEAR) return "Its publisher name looks like $officialNs, an official publisher."
            if (distance(ext, officialName) <= NEAR) return "It copies the name of $officialNs.$officialName from another publisher."
        }
        return null
    }

    /** Levenshtein distance. */
    fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            previous = current
        }
        return previous[b.length]
    }
}
