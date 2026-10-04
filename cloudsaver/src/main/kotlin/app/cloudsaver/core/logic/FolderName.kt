package app.cloudsaver.core.logic

/**
 * The rules for a folder name the person types in.
 *
 * Every copy lands in Pictures/<name>, because Ente has to be told about the
 * folder and Pictures is where it lists them. The name has to survive being
 * a folder on every phone (FAT32 SD cards included), must not be hidden from
 * the gallery - Ente would never see it - and must not be one the app already
 * uses for something else.
 */
object FolderName {

    const val PREFIX = "Pictures/"
    const val MAX_LENGTH = 40

    enum class Problem { EMPTY, TOO_LONG, BAD_CHARACTER, HIDDEN, RESERVED, SAME_AS_OTHER }

    private val ALLOWED = Regex("""[\p{L}\p{M}\p{N} ._()\-]+""")

    /** The name with spaces trimmed, as it would be stored. */
    fun clean(name: String): String = name.trim().replace(Regex("""\s+"""), " ")

    /**
     * What is wrong with [name], or null when it can be used. [other] is the
     * other kind's folder when photos and videos go to separate ones: two
     * kinds in one folder is what the single-folder layout is for.
     */
    fun problem(name: String, other: String? = null): Problem? {
        val n = clean(name)
        return when {
            n.isEmpty() -> Problem.EMPTY
            n.length > MAX_LENGTH -> Problem.TOO_LONG
            !ALLOWED.matches(n) -> Problem.BAD_CHARACTER
            n.startsWith(".") -> Problem.HIDDEN
            isReserved(n) -> Problem.RESERVED
            other != null && OutputRoots.same(pathOf(n), other) -> Problem.SAME_AS_OTHER
            else -> null
        }
    }

    fun pathOf(name: String): String = PREFIX + clean(name)

    /** The name part of a path this setting produced, for the edit box. */
    fun nameOf(path: String): String =
        OutputRoots.normalize(path).removePrefix(PREFIX).takeIf { '/' !in it } ?: ""

    /**
     * True for a value that may sit in the folder options: empty (the
     * default), a name the setting could have produced, or a folder an
     * earlier version used, which existing installs stay on until they move.
     */
    fun isStorable(path: String): Boolean {
        if (path.isEmpty()) return true
        val p = OutputRoots.normalize(path)
        if (LEGACY_PINNED.any { OutputRoots.same(it, p) }) return true
        if (!p.startsWith(PREFIX)) return false
        val name = p.removePrefix(PREFIX)
        return '/' !in name && problem(name) == null
    }

    private fun isReserved(name: String): Boolean {
        val reserved = listOf(
            OutputRoots.normalize(Defaults.KEPT_DIR).removePrefix(PREFIX),
            "Screenshots", "Camera"
        )
        return reserved.any { it.equals(name, ignoreCase = true) }
    }

    private val LEGACY_PINNED = listOf(
        Defaults.LEGACY_OUTPUT_DIR, Defaults.LEGACY_OUTPUT_DIR_PHOTOS, Defaults.LEGACY_OUTPUT_DIR_VIDEOS
    )
}
