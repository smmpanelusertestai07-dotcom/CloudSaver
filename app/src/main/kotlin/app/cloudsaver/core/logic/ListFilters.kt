package app.cloudsaver.core.logic

/**
 * The filtering every list screen shares, as plain data and pure functions.
 *
 * The rules live here rather than in the screens because five screens each
 * deciding for themselves what "over 100 MB" means is five chances to disagree,
 * and because a pure function is the only version of this that can be tested
 * without an emulator.
 */
object ListFilters {

    /** Photos, videos, or both. Present on every list. */
    enum class Type { ALL, PHOTOS, VIDEOS }

    /** Size bands, chosen to match how people talk about file sizes. */
    enum class Size(val minBytes: Long) {
        ANY(0L),
        OVER_10MB(10L * 1_000_000),
        OVER_100MB(100L * 1_000_000),
        OVER_1GB(1_000L * 1_000_000)
    }

    /**
     * One row, seen only as the fields filtering needs.
     *
     * A view rather than the database entity, so Reclaim's items and the
     * duplicate entries - which are not the same type - can go through exactly
     * the same code.
     */
    data class Candidate(
        val id: Long,
        val name: String,
        val album: String?,
        val sizeBytes: Long,
        val isVideo: Boolean
    )

    /** Everything the shared chips can be set to at once. */
    data class State(
        val type: Type = Type.ALL,
        val size: Size = Size.ANY,
        val album: String? = null,
        val query: String = ""
    ) {
        /** True while nothing has been narrowed, so chips read as untouched. */
        val isDefault: Boolean
            get() = type == Type.ALL && size == Size.ANY && album == null
    }

    fun matchesType(candidate: Candidate, type: Type): Boolean = when (type) {
        Type.ALL -> true
        Type.PHOTOS -> !candidate.isVideo
        Type.VIDEOS -> candidate.isVideo
    }

    fun matchesSize(candidate: Candidate, size: Size): Boolean =
        candidate.sizeBytes >= size.minBytes

    fun matchesAlbum(candidate: Candidate, album: String?): Boolean =
        album == null || candidate.album == album

    /**
     * Search matches the file name or the album, case-insensitively.
     *
     * Both, because people look for "beach" meaning the folder just as often
     * as they mean the file, and a search that silently only covers one of
     * them looks broken rather than narrow.
     */
    fun matchesQuery(candidate: Candidate, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return candidate.name.contains(q, ignoreCase = true) ||
            candidate.album?.contains(q, ignoreCase = true) == true
    }

    fun matches(candidate: Candidate, state: State): Boolean =
        matchesType(candidate, state.type) &&
            matchesSize(candidate, state.size) &&
            matchesAlbum(candidate, state.album) &&
            matchesQuery(candidate, state.query)

    /** Albums present in a list, with counts, most files first. */
    fun albumCounts(candidates: List<Candidate>): List<Pair<String, Int>> =
        candidates.mapNotNull { it.album }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
}
