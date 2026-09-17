package app.cloudsaver.core.logic

/**
 * Grouping files the app can prove are the same file.
 *
 * Byte-for-byte only. The app does not look at two similar photos and decide
 * which one is better, because that is a judgement about someone's memory
 * rather than a fact about their storage, and a wrong guess deletes the
 * photo they wanted.
 */
object DuplicateRules {

    /** One original, as the grouping needs to see it. */
    data class Entry(
        val id: Long,
        val fingerprint: String,
        val displayName: String,
        val sizeBytes: Long,
        val sha256: String?,
        val capturedAtMs: Long,
        val album: String?,
        val path: String
    )

    data class Group(
        val sha256: String,
        val keeper: Entry,
        /** Identical extras; removing any of these leaves the content intact. */
        val extras: List<Entry>
    ) {
        val reclaimableBytes: Long get() = extras.sumOf { it.sizeBytes }
        val all: List<Entry> get() = listOf(keeper) + extras
    }

    /**
     * Only files whose sizes collide can possibly be identical, so hashing is
     * limited to those. On a phone with 20 000 photos that is the difference
     * between reading a few hundred megabytes and reading all of them.
     */
    fun needsHashing(entries: List<Entry>): List<Entry> {
        val sharedSizes = entries.groupingBy { it.sizeBytes }.eachCount()
            .filterValues { it > 1 }.keys
        return entries.filter { it.sha256 == null && it.sizeBytes in sharedSizes }
    }

    /**
     * Which copy stays.
     *
     * Oldest by capture date, because that is the one the rest were copied
     * from and the one whose album placement is most likely deliberate. Ties
     * go to the album holding more files - a shared album is a better home
     * than a stray download - and then to the shortest path.
     */
    fun chooseKeeper(entries: List<Entry>, albumSizes: Map<String, Int>): Entry =
        entries.minWith(
            compareBy(
                { it.capturedAtMs },
                { -(albumSizes[it.album] ?: 0) },
                { it.path.length },
                { it.id }
            )
        )

    /** Groups of byte-identical files, largest saving first. */
    fun group(entries: List<Entry>, albumSizes: Map<String, Int> = emptyMap()): List<Group> =
        entries.filter { it.sha256 != null }
            .groupBy { it.sha256!! }
            .filterValues { it.size > 1 }
            .map { (sha, members) ->
                val keeper = chooseKeeper(members, albumSizes)
                Group(sha, keeper, members.filter { it.id != keeper.id })
            }
            .sortedByDescending { it.reclaimableBytes }

    /**
     * A duplicate may go whenever an identical copy stays on the phone: the
     * content is provably still there, so no upload evidence is needed. The
     * last remaining copy is an ordinary original again and goes back through
     * the full reclaim gate.
     */
    fun mayRemoveWithoutEvidence(group: Group, alsoRemoving: Set<Long>): Boolean =
        group.all.any { it.id !in alsoRemoving }
}
