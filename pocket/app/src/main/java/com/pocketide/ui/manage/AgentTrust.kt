package com.pocketide.ui.manage

import java.util.Locale

/**
 * A last check on the "More agents" card before the owner can tap Add: an extension id that
 * imitates an official publisher is refused, whatever the discovery rules let through.
 */
object AgentTrust {
    /** The Open VSX namespaces of the companies behind the built-in agents. */
    private val officialNamespaces = listOf("anthropic", "openai", "google")
    private val plainId = Regex("[a-z0-9][a-z0-9_-]*\\.[a-z0-9][a-z0-9._-]*")

    /** Why [extensionId] must not be offered, or null when nothing looks wrong. */
    fun problem(extensionId: String): String? {
        val id = extensionId.trim()
        if (id.any { it.code > 0x7F }) return "Its name uses letters that only look like plain ones."
        val lower = id.lowercase(Locale.ROOT)
        if (!plainId.matches(lower)) return "Its name is not a plain Open VSX id."
        val namespace = lower.substringBefore('.')
        if (namespace in officialNamespaces) return null
        val imitated = officialNamespaces.firstOrNull { distance(namespace, it) in 1..2 }
        return imitated?.let { "Its publisher \"$namespace\" is one or two letters away from \"$it\"." }
    }

    /** Edit distance (insert, delete, replace), small strings only. */
    internal fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
