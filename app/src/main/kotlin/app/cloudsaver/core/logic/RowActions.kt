package app.cloudsaver.core.logic

/**
 * Which actions a file may be offered, decided from its state alone.
 *
 * Every list used to build its own menu, so the same file could be offered
 * "Never optimise this file" on one screen and not on another, and a file with
 * no proof could be offered "Remove from phone" simply because the screen that
 * drew it had not thought about proof. The rule now lives here: one function,
 * no UI, fully testable.
 *
 * Two principles decide everything below:
 *
 *  - **Nothing meaningless is offered.** An action that cannot do anything for
 *    this file is absent, not greyed out. A disabled control invites "why
 *    not?", and the answer belongs in the row, not in a tooltip nobody opens.
 *  - **Removing an original needs proof, always.** The check happens again at
 *    the moment of acting, but a file with no proof is never even offered it.
 */
object RowActions {

    enum class Action {
        /** Open the file in the phone's own viewer. */
        OPEN,

        /** Jump this file to the front of the queue. */
        OPTIMISE_FIRST,

        /** Try a file that previously failed. */
        TRY_AGAIN,

        /** Add to the skip list, or take it off again. */
        NEVER_OPTIMISE,
        ALLOW_AGAIN,

        /** Delete the original, through the one reclaim path. */
        REMOVE_FROM_PHONE,

        /** Duplicates only. */
        REMOVE_EXTRA,
        KEEP_THIS_INSTEAD
    }

    /** A row as the rule needs to see it, whatever table it came from. */
    data class Row(
        val state: ItemState,
        val evidence: Evidence,
        val neverOptimise: Boolean,
        val originalMissing: Boolean
    )

    /**
     * The actions for one ordinary file.
     *
     * Note what is deliberately missing. An optimised copy waiting for the
     * cloud is not offered "Never optimise this file": the work is already
     * done, so the option cannot undo anything, and offering it produced the
     * exact confusion it looks like it would - a phone reporting three
     * optimised files and two in the upload folder, with no way to tell why.
     */
    fun forItem(row: Row): List<Action> = buildList {
        add(Action.OPEN)

        // An original that is no longer in the gallery can only be looked at.
        if (row.originalMissing) return@buildList

        when (row.state) {
            ItemState.NEW -> {
                if (!row.neverOptimise) add(Action.OPTIMISE_FIRST)
                add(if (row.neverOptimise) Action.ALLOW_AGAIN else Action.NEVER_OPTIMISE)
            }

            ItemState.SKIP -> {
                if (!row.neverOptimise) add(Action.TRY_AGAIN)
                add(if (row.neverOptimise) Action.ALLOW_AGAIN else Action.NEVER_OPTIMISE)
            }

            // Optimised and waiting, or optimised and proven. Proof is what
            // separates "you may remove the original" from "not yet".
            ItemState.STAGED, ItemState.RELEASED, ItemState.DONE, ItemState.GONE -> {
                if (row.evidence.isPerFile) add(Action.REMOVE_FROM_PHONE)
            }

            // Already reclaimed, or never understood. Nothing to offer.
            ItemState.FREED, ItemState.FREED_KEPT, ItemState.UNKNOWN -> Unit
        }
    }

    /** The actions for one member of a duplicate group. */
    fun forDuplicate(isKeeper: Boolean): List<Action> =
        if (isKeeper) {
            listOf(Action.OPEN)
        } else {
            listOf(Action.OPEN, Action.REMOVE_EXTRA, Action.KEEP_THIS_INSTEAD)
        }

    /**
     * Splits a mixed selection into what an action can touch and what it
     * must skip (CC6).
     *
     * A bulk "Optimise" over five rows where two are already optimised must
     * act on three, say "3 of 5", and name why two were left - acting on all
     * five would re-process finished work, and silently acting on three
     * reads as the app losing count.
     */
    data class Split(val eligibleIds: List<Long>, val skipped: Int) {
        val eligible: Int get() = eligibleIds.size
    }

    fun splitFor(action: Action, rows: List<Pair<Long, Row>>): Split {
        val eligible = rows.filter { (_, row) -> action in forItem(row) }.map { it.first }
        return Split(eligible, skipped = rows.size - eligible.size)
    }

    /**
     * Optimise-eligibility is the union of "queue it first" and "try again":
     * both end in the same encode, and a bulk bar offering them separately
     * would make the user classify failures the app can already classify.
     */
    fun splitForOptimise(rows: List<Pair<Long, Row>>): Split {
        val eligible = rows.filter { (_, row) ->
            val actions = forItem(row)
            Action.OPTIMISE_FIRST in actions || Action.TRY_AGAIN in actions
        }.map { it.first }
        return Split(eligible, skipped = rows.size - eligible.size)
    }

    /**
     * How many of a selection may actually be removed from the phone.
     *
     * The bottom bar uses this to say "3 of 12 are not backed up yet" instead
     * of failing halfway through, or worse, quietly removing only some.
     */
    fun removableCount(rows: List<Row>): Int =
        rows.count { !it.originalMissing && it.evidence.isPerFile && !it.state.isReclaimed }
}
