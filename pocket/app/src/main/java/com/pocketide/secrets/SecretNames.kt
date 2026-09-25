package com.pocketide.secrets

import java.util.Locale

/**
 * Names of Variables and Secrets. They become environment names inside Linux (which takes
 * `[A-Z_][A-Z0-9_]*` only) and GitHub Actions secret names (letters, digits and `_`, not starting
 * with a digit, a Secret not with `GITHUB_`, compared without case), so they are kept in upper case.
 */
object SecretNames {
    const val MAX_LENGTH = 100
    private val SHAPE = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Set by the computer for every room: a Variable must not replace them. */
    private val RESERVED = setOf(
        "HOME", "PATH", "LANG", "LC_ALL", "TERM", "TZ", "TMPDIR", "PWD", "SHELL", "USER", "LOGNAME",
        "LD_PRELOAD", "LD_LIBRARY_PATH",
    )

    /** The stored form of [name]: trimmed and upper case. */
    fun normalize(name: String): String = name.trim().uppercase(Locale.ROOT)

    /** Why [name] cannot be used for [kind], in one sentence; null when it is fine. */
    fun problem(name: String, kind: SecretKind): String? {
        val n = name.trim()
        val upper = n.uppercase(Locale.ROOT)
        return when {
            n.isEmpty() -> "Enter a name."
            n.length > MAX_LENGTH -> "Keep the name under $MAX_LENGTH characters."
            !SHAPE.matches(n) -> "Use letters, digits and _ only, and do not start with a digit."
            kind == SecretKind.SECRET && upper.startsWith("GITHUB_") -> "GitHub does not allow Secret names that start with GITHUB_."
            kind == SecretKind.VARIABLE && upper in RESERVED -> "The computer sets $upper itself. Choose another name."
            else -> null
        }
    }

    /** Throws with [problem]'s sentence when [name] cannot be used. */
    fun require(name: String, kind: SecretKind) {
        problem(name, kind)?.let { throw IllegalArgumentException(it) }
    }
}
