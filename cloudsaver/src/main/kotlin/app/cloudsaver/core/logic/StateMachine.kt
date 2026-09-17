package app.cloudsaver.core.logic

/**
 * Pipeline state machine:
 * NEW -> STAGED -> RELEASED -> GONE(CONFIRMED | APP_DELETED | USER_DELETED) -> DONE
 * plus SKIP(reason), FREED (original deleted via tool), UNKNOWN (recovered without evidence).
 */
object StateMachine {

    /** Strongest evidence wins: CONFIRMED > VERIFIED > AGED > NONE. */
    fun strongest(a: Evidence, b: Evidence): Evidence = if (a.ordinal >= b.ordinal) a else b

    /**
     * Every move the pipeline is allowed to make.
     *
     * This table is the written-down specification, not a gate the engines
     * pass through: nothing calls [isAllowed] at runtime, and each engine
     * moves its own rows directly. It is here, and tested, so that the
     * intended shape of the pipeline is stated in one readable place - but a
     * passing test of it says the specification is self-consistent, not that
     * the app is incapable of an illegal transition.
     */
    private val allowed: Map<ItemState, Set<ItemState>> = mapOf(
        ItemState.NEW to setOf(ItemState.STAGED, ItemState.SKIP, ItemState.DONE),
        ItemState.STAGED to setOf(ItemState.RELEASED, ItemState.NEW, ItemState.SKIP, ItemState.DONE),
        ItemState.RELEASED to setOf(ItemState.GONE, ItemState.NEW, ItemState.DONE),
        ItemState.GONE to setOf(
            ItemState.DONE, ItemState.NEW, ItemState.FREED, ItemState.FREED_KEPT
        ),
        ItemState.DONE to setOf(ItemState.FREED, ItemState.FREED_KEPT, ItemState.NEW),
        ItemState.SKIP to setOf(ItemState.NEW),
        ItemState.FREED to emptySet(),
        ItemState.FREED_KEPT to emptySet(),
        ItemState.UNKNOWN to setOf(ItemState.NEW)
    )

    /** Whether a move is one the specification above permits. */
    fun isAllowed(from: ItemState, to: ItemState): Boolean =
        to in (allowed[from] ?: emptySet())

    // What a vanished released copy means is decided by
    // EvidenceRules.onCopyMissing, which is what MaintainEngine calls. An
    // older second version of that decision used to live here, uncalled.

    /**
     * Snapshot import mapping (fresh install / clear-data recovery):
     * - FREED stays FREED
     * - RELEASED/GONE/DONE with NO evidence -> UNKNOWN (never freed, never reprocessed
     *   unless the user enables "Reprocess unknown items")
     * - RELEASED/GONE/DONE with evidence -> DONE, evidence kept
     * - SKIP stays SKIP; NEW/STAGED -> NEW (stage files do not survive reinstall)
     */
    fun importedState(state: ItemState, evidence: Evidence): Pair<ItemState, Evidence> = when (state) {
        // Both reclaimed states are terminal on import: the original is gone
        // from the phone, so there is nothing left to reprocess either way.
        ItemState.FREED, ItemState.FREED_KEPT -> state to evidence
        ItemState.UNKNOWN -> ItemState.UNKNOWN to evidence
        ItemState.RELEASED, ItemState.GONE, ItemState.DONE ->
            if (evidence == Evidence.NONE) ItemState.UNKNOWN to Evidence.NONE
            else ItemState.DONE to evidence
        ItemState.SKIP -> ItemState.SKIP to Evidence.NONE
        ItemState.NEW, ItemState.STAGED -> ItemState.NEW to Evidence.NONE
    }
}
