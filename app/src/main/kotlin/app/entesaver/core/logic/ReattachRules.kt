package app.entesaver.core.logic

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
     * Whether restored copies still have to be matched to the folder: not
     * since the last restore ([reattached] false), or a volume is in now
     * ([volumesNow]) that was not when they were ([volumesThen]) - a copy on
     * it was taken as gone then, and may be in the folder after all.
     */
    fun matchPending(reattached: Boolean, volumesThen: Set<String>, volumesNow: Set<String>): Boolean =
        !reattached || !volumesThen.containsAll(volumesNow)

    /**
     * Whether [state] may adopt a copy already sitting in the output folder.
     *
     * Only rows that have no output of their own. A RELEASED row already knows
     * about its copy, and rows further along carry upload evidence that a
     * filename match is not entitled to overwrite.
     *
     * UNKNOWN is a row restored from a history file that is not matched to
     * its copy yet. Its copy still waiting in the folder means it is waiting
     * for Ente exactly like a released one, so it goes back under watch.
     * Left UNKNOWN, nothing would ever look at it again. Its output fields
     * describe the copy the old install made, so they do not count.
     */
    fun canAdopt(state: String, hasOutput: Boolean): Boolean = when (state) {
        ItemState.UNKNOWN.name -> true
        ItemState.NEW.name, ItemState.STAGED.name -> !hasOutput
        else -> false
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

    /**
     * The evidence a row keeps once it adopts a copy. A queued row never had
     * any. A restored row keeps what its history file recorded - the app's
     * own record of what Ente did with that copy - but only when the copy
     * found is that very copy ([sameCopy]: same name and size). Otherwise the
     * record was about some other file, and the filename match adds nothing.
     */
    fun evidenceAfterAdopt(state: String, recorded: Evidence, sameCopy: Boolean): Evidence =
        if (state == ItemState.UNKNOWN.name && sameCopy) recorded else evidence

    /**
     * Where a restored row goes when its copy is in none of the output
     * folders: with evidence from its history file, to DONE (Ente had it,
     * and the copy has since gone); without, it stays UNKNOWN.
     */
    fun stateWhenCopyMissing(recorded: Evidence): ItemState =
        if (recorded == Evidence.NONE) ItemState.UNKNOWN else ItemState.DONE

    /** The state an adopted row lands in. */
    val state: ItemState = ItemState.RELEASED
}
