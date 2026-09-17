package app.cloudsaver.core.logic

/**
 * Adopting light copies that outlived the database.
 *
 * Uninstalling the app, or clearing its data, wipes Room but leaves the copies
 * in Pictures/CloudSaver exactly where they were. Without this, the next run
 * would compress and upload every one of those files a second time - hours of
 * work, and a duplicate of every photo in the user's cloud.
 *
 * The copy's own filename carries the original's fingerprint, so a copy can be
 * matched back to its original with no stored state at all.
 */
object ReattachRules {

    /**
     * Whether [state] may adopt a copy already sitting in the output folder.
     *
     * Only rows that have no output of their own. A RELEASED row already knows
     * about its copy, and rows further along carry upload evidence that a
     * filename match is not entitled to overwrite.
     */
    fun canAdopt(state: String, hasOutput: Boolean): Boolean = when {
        hasOutput -> false
        else -> state == ItemState.NEW.name || state == ItemState.STAGED.name
    }

    /**
     * The evidence an adopted row is allowed to claim: none.
     *
     * The copy being on disk proves it was made, not that any cloud app ever
     * collected it. Claiming otherwise here would let Reclaim offer to delete
     * an original on the strength of a filename, which is exactly the mistake
     * this whole app is built to avoid.
     */
    val evidence: Evidence = Evidence.NONE

    /** The state an adopted row lands in. */
    val state: ItemState = ItemState.RELEASED
}
