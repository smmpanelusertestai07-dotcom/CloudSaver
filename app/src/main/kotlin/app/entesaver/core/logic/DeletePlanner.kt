package app.entesaver.core.logic

/**
 * Lazy delete of released copies. Copies are kept as long as space allows; when
 * (stage + output) exceeds MAX_EXTRA or free space drops under MIN_FREE, delete
 * oldest-first among CONFIRMED, then VERIFIED (age >= KEEP_MIN_DAYS), then AGED
 * (age >= AGED_DAYS). The newest remaining file of every output folder is the
 * anchor and is never deleted (folder never empty, no dummy files).
 *
 * "Folder" is the folder on the phone ([Copy.place]), not the kind of copy:
 * after the person picks a new folder, an old one and the new one hold the
 * same kind, and the anchor has to sit in the new one. A copy in a folder no
 * longer in use is never an anchor ([Copy.anchorable]) - that folder is
 * meant to run empty.
 */
object DeletePlanner {

    data class Copy(
        val id: Long,
        val bytes: Long,
        val evidence: Evidence,
        val ageDays: Int,
        val folder: OutFolder,
        val captureAt: Long,
        /** The folder on the phone it waits in; the kind of copy when unknown. */
        val place: String = folder.name,
        val anchorable: Boolean = true
    )

    data class Plan(val ids: List<Long>, val agedUsed: Boolean, val freedBytes: Long)

    /** The per-folder anchor ids: newest captureAt (ties: highest id). */
    fun anchors(copies: List<Copy>): Set<Long> =
        copies.filter { it.anchorable }.groupBy { it.place }
            .mapNotNull { (_, list) ->
                list.maxWithOrNull(compareBy({ it.captureAt }, { it.id }))?.id
            }
            .toSet()

    fun plan(
        copies: List<Copy>,
        bytesToFree: Long,
        keepMinDays: Int = Defaults.KEEP_MIN_DAYS,
        agedDays: Int = Defaults.AGED_DAYS
    ): Plan {
        if (bytesToFree <= 0 || copies.isEmpty()) return Plan(emptyList(), false, 0)
        val anchorIds = anchors(copies)
        fun eligible(c: Copy) = c.id !in anchorIds

        val confirmed = copies
            .filter { eligible(it) && it.evidence.isPerFile }
            .sortedWith(compareBy({ it.captureAt }, { it.id }))
        val verified = copies
            .filter { eligible(it) && it.evidence == Evidence.VERIFIED && it.ageDays >= keepMinDays }
            .sortedWith(compareBy({ it.captureAt }, { it.id }))
        val aged = copies
            .filter { eligible(it) && it.evidence == Evidence.AGED && it.ageDays >= agedDays }
            .sortedWith(compareBy({ it.captureAt }, { it.id }))

        val ids = mutableListOf<Long>()
        var freed = 0L
        var agedUsed = false
        for (c in confirmed + verified + aged) {
            if (freed >= bytesToFree) break
            ids += c.id
            freed += c.bytes
            if (c.evidence == Evidence.AGED) agedUsed = true
        }
        return Plan(ids, agedUsed, freed)
    }

    /**
     * SAFETY PAUSE: stop all copy deletion when the cloud app is missing, or when
     * the cloud app sent (almost) nothing for 3 days while copies wait for upload.
     * txLast3Days == null means "cannot measure" (no Usage Access) - do not pause.
     */
    fun safetyPause(cloudInstalled: Boolean, txLast3Days: Long?, waitingCopies: Int): Boolean {
        if (!cloudInstalled) return true
        if (waitingCopies <= 0) return false
        return txLast3Days != null && txLast3Days < Defaults.SAFETY_TX_MIN_BYTES
    }
}
