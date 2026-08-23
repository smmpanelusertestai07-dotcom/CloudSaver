package app.cloudsaver.core.logic

/**
 * Pipeline state machine:
 * NEW -> STAGED -> RELEASED -> GONE(CONFIRMED | APP_DELETED | USER_DELETED) -> DONE
 * plus SKIP(reason), FREED (original deleted via tool), UNKNOWN (recovered without evidence).
 */
object StateMachine {

    /** Strongest evidence wins: CONFIRMED > VERIFIED > AGED > NONE. */
    fun strongest(a: Evidence, b: Evidence): Evidence = if (a.ordinal >= b.ordinal) a else b

    private val allowed: Map<ItemState, Set<ItemState>> = mapOf(
        ItemState.NEW to setOf(ItemState.STAGED, ItemState.SKIP, ItemState.DONE),
        ItemState.STAGED to setOf(ItemState.RELEASED, ItemState.NEW, ItemState.SKIP, ItemState.DONE),
        ItemState.RELEASED to setOf(ItemState.GONE, ItemState.NEW, ItemState.DONE),
        ItemState.GONE to setOf(ItemState.DONE, ItemState.NEW, ItemState.FREED),
        ItemState.DONE to setOf(ItemState.FREED, ItemState.NEW),
        ItemState.SKIP to setOf(ItemState.NEW),
        ItemState.FREED to emptySet(),
        ItemState.UNKNOWN to setOf(ItemState.NEW)
    )

    fun isAllowed(from: ItemState, to: ItemState): Boolean =
        to in (allowed[from] ?: emptySet())

    data class CopyGoneDecision(
        val state: ItemState,
        val reason: GoneReason?,
        val evidence: Evidence,
        val backToNew: Boolean
    )

    /**
     * A RELEASED copy disappeared from the output folder.
     * - the app itself deleted it        -> GONE(APP_DELETED), evidence kept
     * - Confirm-uploads flow active      -> GONE(CONFIRMED), evidence = CONFIRMED
     * - no evidence at all               -> self-heal: back to NEW (re-compress, re-release)
     * - some evidence (VERIFIED/AGED)    -> GONE(USER_DELETED), evidence kept
     */
    fun onReleasedCopyMissing(
        appDeleted: Boolean,
        confirmFlowActive: Boolean,
        evidence: Evidence
    ): CopyGoneDecision = when {
        appDeleted ->
            CopyGoneDecision(ItemState.GONE, GoneReason.APP_DELETED, evidence, backToNew = false)
        confirmFlowActive ->
            CopyGoneDecision(ItemState.GONE, GoneReason.CONFIRMED, Evidence.CONFIRMED, backToNew = false)
        evidence == Evidence.NONE ->
            CopyGoneDecision(ItemState.NEW, GoneReason.USER_DELETED, Evidence.NONE, backToNew = true)
        else ->
            CopyGoneDecision(ItemState.GONE, GoneReason.USER_DELETED, evidence, backToNew = false)
    }

    /**
     * Snapshot import mapping (fresh install / clear-data recovery):
     * - FREED stays FREED
     * - RELEASED/GONE/DONE with NO evidence -> UNKNOWN (never freed, never reprocessed
     *   unless the user enables "Reprocess unknown items")
     * - RELEASED/GONE/DONE with evidence -> DONE, evidence kept
     * - SKIP stays SKIP; NEW/STAGED -> NEW (stage files do not survive reinstall)
     */
    fun importedState(state: ItemState, evidence: Evidence): Pair<ItemState, Evidence> = when (state) {
        ItemState.FREED -> ItemState.FREED to evidence
        ItemState.UNKNOWN -> ItemState.UNKNOWN to evidence
        ItemState.RELEASED, ItemState.GONE, ItemState.DONE ->
            if (evidence == Evidence.NONE) ItemState.UNKNOWN to Evidence.NONE
            else ItemState.DONE to evidence
        ItemState.SKIP -> ItemState.SKIP to Evidence.NONE
        ItemState.NEW, ItemState.STAGED -> ItemState.NEW to Evidence.NONE
    }
}
