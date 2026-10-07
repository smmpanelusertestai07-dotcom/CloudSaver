package app.entesaver.core.logic

/**
 * What a restore does with each row of a history file.
 *
 * A restore picked by hand usually comes after this install's first scan, so
 * most of the history's photos are already here as NEW rows: the scan made
 * them from the files, and the history knows what happened to them before.
 * Merging the two has to end where restoring into an empty table would have
 * ended. Giving a NEW row the history's evidence and nothing else left it in
 * the queue claiming proof about a copy it did not have: it was optimised
 * again, the new copy went up as a second one, and because it already
 * "had" evidence nothing ever watched it or put it in the ledger.
 */
object ImportMerge {

    /** The parts of a row already in the table that the merge looks at. */
    data class Local(
        val state: ItemState,
        val evidence: Evidence,
        val outputSha256: String?,
        val neverOptimise: Boolean
    )

    /** What to change on a row already in the table. */
    data class Plan(
        /** The row made no copy of its own; it takes the history's record whole. */
        val takeOver: Boolean = false,
        /** Same copy, and the history knows more about it. */
        val raiseEvidence: Boolean = false,
        /** Still waiting, and the person had said never optimise it: park it. */
        val exclude: Boolean = false,
        /** The history carries a "never optimise" this row does not. */
        val addNeverFlag: Boolean = false
    ) {
        val changes: Boolean get() = takeOver || raiseEvidence || exclude || addNeverFlag
    }

    /** The skip reason "never optimise" parks a row under. */
    const val USER_EXCLUDED = "user_excluded"

    private const val DUPLICATE = "duplicate"

    private val WAITING = setOf(ItemState.NEW, ItemState.STAGED)

    /** History states that say a copy was made and what became of it. */
    private val WITH_HISTORY = setOf(
        ItemState.DONE, ItemState.UNKNOWN, ItemState.FREED, ItemState.FREED_KEPT
    )

    /**
     * The plan for [local], already in the table, given [item] from the
     * history after the import mapping.
     *
     * Evidence describes one copy, so it only moves onto a row holding that
     * very copy (same hash). A row with a different copy of its own keeps
     * what it has, and the normal watch decides about that copy.
     */
    fun plan(local: Local, item: SnapshotCodec.SnapItem): Plan {
        val waiting = local.state in WAITING
        val history = item.state in WITH_HISTORY &&
            (item.evidence != Evidence.NONE || item.outputSha256 != null)
        val takeOver = waiting && history
        val addNeverFlag = item.neverOptimise && !local.neverOptimise
        val raiseEvidence = !takeOver &&
            item.evidence.ordinal > local.evidence.ordinal &&
            local.outputSha256 != null &&
            local.outputSha256 == item.outputSha256
        return Plan(
            takeOver = takeOver,
            raiseEvidence = raiseEvidence,
            // The flag alone excludes nothing: the queue reads the state.
            exclude = addNeverFlag && waiting && !takeOver,
            addNeverFlag = addNeverFlag
        )
    }

    /**
     * The state a taken-over row lands in. A reclaimed original that the
     * scan has just found again is back on the phone, so it is DONE like
     * any other the cloud already had - or UNKNOWN when nothing proved
     * that, as the import mapping does for DONE.
     */
    fun takenOverState(item: SnapshotCodec.SnapItem): ItemState = when (item.state) {
        ItemState.FREED, ItemState.FREED_KEPT ->
            if (item.evidence == Evidence.NONE) ItemState.UNKNOWN else ItemState.DONE
        else -> item.state
    }

    /**
     * The row to insert for [item] when the table has none, or null when it
     * is better left to the scan.
     *
     * A row still waiting in the queue holds nothing a scan cannot rebuild,
     * and restored without its file's location it could never be read: a
     * photo deleted since, or one whose date a phone move changed, became a
     * permanent "could not be read" problem. A duplicate from a file that
     * predates its link to the original likewise only ever showed as a
     * problem; the scan finds it again and marks it properly.
     *
     * A waiting row the person had excluded comes back excluded, so the
     * flag and the state say the same thing.
     */
    fun forInsert(item: SnapshotCodec.SnapItem): SnapshotCodec.SnapItem? {
        // A copy staged by the old install went with its app data; only a
        // released one reached the cloud, and the ledger remembers that.
        if (item.state == ItemState.NEW && !item.neverOptimise && item.keptUri == null) {
            return null
        }
        if (item.state == ItemState.SKIP && item.skipReason == DUPLICATE &&
            item.duplicateOf == null
        ) {
            return null
        }
        if (item.state == ItemState.NEW && item.neverOptimise) {
            return item.copy(state = ItemState.SKIP, skipReason = USER_EXCLUDED)
        }
        return item
    }
}
