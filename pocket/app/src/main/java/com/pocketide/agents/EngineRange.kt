package com.pocketide.agents

/**
 * The npm-style range an extension writes in `engines.vscode` ("^1.94.0", ">=1.80.0", "~1.2",
 * "1.x", "*", "a - b", "r1 || r2"), checked against the VS Code version code-server is built on.
 *
 * Like VS Code's own check, pre-release tags are left out on both sides: "^1.96.2-insider" asks
 * for 1.96.2 or newer within 1.x. Anything that does not parse accepts nothing, so an extension
 * whose range cannot be read is never installed.
 */
class EngineRange private constructor(private val alternatives: List<List<Bound>>) {

    fun accepts(version: SemVer): Boolean {
        val core = version.core()
        return alternatives.any { all -> all.all { it.accepts(core) } }
    }

    private enum class Op { GE, GT, LE, LT, EQ }

    private data class Bound(val op: Op, val version: SemVer) {
        fun accepts(v: SemVer): Boolean {
            val order = v.compareTo(version)
            return when (op) {
                Op.GE -> order >= 0
                Op.GT -> order > 0
                Op.LE -> order <= 0
                Op.LT -> order < 0
                Op.EQ -> order == 0
            }
        }
    }

    /** A version that may leave parts open ("1.2", "1.x", "*"); null parts are wildcards. */
    private data class Partial(val major: Long?, val minor: Long?, val patch: Long?) {
        val complete get() = major != null && minor != null && patch != null

        fun low() = SemVer(major ?: 0, minor ?: 0, patch ?: 0)

        /** The first version above everything this partial names ("1.2" → 1.3.0). */
        fun next(): SemVer = when {
            major == null -> error("A wildcard has no next version")
            minor == null -> SemVer(major + 1, 0, 0)
            else -> SemVer(major, minor + 1, 0)
        }
    }

    companion object {
        private val ANY = listOf<Bound>()
        private val NOTHING = listOf(Bound(Op.LT, SemVer(0, 0, 0)))
        private val COMPARATOR = Regex("(\\^|~>?|>=|<=|>|<|=)?\\s*v?([0-9xX*]+(?:\\.[0-9xX*]+){0,2})(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")
        private val PART = Regex("0|[1-9][0-9]{0,17}")

        /** Null when [text] is not a range. */
        fun parse(text: String): EngineRange? {
            val alternatives = text.split("||").map { alternative ->
                parseAlternative(alternative.trim()) ?: return null
            }
            return EngineRange(alternatives)
        }

        private fun parseAlternative(text: String): List<Bound>? {
            if (text.isEmpty()) return ANY
            val hyphen = text.split(Regex("\\s+-\\s+"))
            if (hyphen.size == 2) return hyphenRange(hyphen[0], hyphen[1])
            if (hyphen.size > 2) return null
            val bounds = mutableListOf<Bound>()
            for (token in joinOperators(text.split(Regex("\\s+")))) {
                bounds += comparator(token) ?: return null
            }
            return bounds
        }

        /** ">= 1.2" is written with a space sometimes; glue a lone operator to what follows it. */
        private fun joinOperators(tokens: List<String>): List<String> {
            val joined = mutableListOf<String>()
            var pending = ""
            for (token in tokens) {
                if (token.isEmpty()) continue
                if (token in setOf("^", "~", "~>", ">=", "<=", ">", "<", "=")) pending += token else {
                    joined += pending + token
                    pending = ""
                }
            }
            if (pending.isNotEmpty()) joined += pending
            return joined
        }

        private fun hyphenRange(from: String, to: String): List<Bound>? {
            val low = partial(from) ?: return null
            val high = partial(to) ?: return null
            val lower = if (low.major == null) ANY else listOf(Bound(Op.GE, low.low()))
            val upper = when {
                high.major == null -> ANY
                high.complete -> listOf(Bound(Op.LE, high.low()))
                else -> listOf(Bound(Op.LT, high.next()))
            }
            return lower + upper
        }

        private fun comparator(token: String): List<Bound>? {
            val match = COMPARATOR.matchEntire(token) ?: return null
            val op = match.groupValues[1]
            val version = partial(match.groupValues[2]) ?: return null
            return when (op) {
                "^" -> caret(version)
                "~", "~>" -> tilde(version)
                ">=" -> if (version.major == null) ANY else listOf(Bound(Op.GE, version.low()))
                ">" -> when {
                    version.major == null -> NOTHING
                    version.complete -> listOf(Bound(Op.GT, version.low()))
                    else -> listOf(Bound(Op.GE, version.next()))
                }
                "<=" -> when {
                    version.major == null -> ANY
                    version.complete -> listOf(Bound(Op.LE, version.low()))
                    else -> listOf(Bound(Op.LT, version.next()))
                }
                "<" -> if (version.major == null) NOTHING else listOf(Bound(Op.LT, version.low()))
                else -> exact(version)
            }
        }

        private fun exact(version: Partial): List<Bound> = when {
            version.major == null -> ANY
            version.complete -> listOf(Bound(Op.EQ, version.low()))
            else -> listOf(Bound(Op.GE, version.low()), Bound(Op.LT, version.next()))
        }

        /** Changes that do not modify the left-most non-zero part. */
        private fun caret(version: Partial): List<Bound> {
            val major = version.major ?: return ANY
            val minor = version.minor
            val patch = version.patch
            val upper = when {
                major > 0 -> SemVer(major + 1, 0, 0)
                minor == null -> SemVer(1, 0, 0)
                minor > 0 -> SemVer(0, minor + 1, 0)
                patch == null -> SemVer(0, 1, 0)
                else -> SemVer(0, 0, patch + 1)
            }
            return listOf(Bound(Op.GE, version.low()), Bound(Op.LT, upper))
        }

        /** Patch-level changes when a minor is given, minor-level changes when not. */
        private fun tilde(version: Partial): List<Bound> {
            val major = version.major ?: return ANY
            val upper = version.minor?.let { SemVer(major, it + 1, 0) } ?: SemVer(major + 1, 0, 0)
            return listOf(Bound(Op.GE, version.low()), Bound(Op.LT, upper))
        }

        private fun partial(text: String): Partial? {
            val parts = text.trim().removePrefix("v").substringBefore('+').substringBefore('-').split('.')
            if (parts.size !in 1..3) return null
            val numbers = parts.map { part ->
                when {
                    part == "x" || part == "X" || part == "*" -> null
                    PART.matches(part) -> part.toLong()
                    else -> return null
                }
            }
            // Once a part is a wildcard, everything after it is too ("1.x.3" means "1.x").
            val firstWild = numbers.indexOfFirst { it == null }.let { if (it < 0) numbers.size else it }
            fun at(i: Int) = if (i < firstWild) numbers.getOrNull(i) else null
            return Partial(at(0), at(1), at(2))
        }
    }
}
