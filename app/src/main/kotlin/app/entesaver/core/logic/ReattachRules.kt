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
     * How long after a restore its copies are looked for, on every compress
     * run. A copy may be on a card put in later, or one MediaStore has not
     * read yet; until the watch ends each counts as in the folder.
     */
    const val RESTORE_WATCH_MS = 7 * 86_400_000L

    /** Whether a restore made at [restoredAt] is still watched at [now]. */
    fun watching(restoredAt: Long, now: Long): Boolean =
        restoredAt in (now - RESTORE_WATCH_MS + 1)..now

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
     * Whether a restored row an earlier pass settled as DONE takes back the
     * copy found now. Versions before 12.2 did that on any recorded grade,
     * and a card that was out then can bring the very copy back ([sameCopy]).
     * Without per-file proof Ente may yet send it, so it goes back under
     * watch; with it, Ente had it, and nothing is to be watched.
     */
    fun canReadopt(recorded: Evidence, sameCopy: Boolean, fromImport: Boolean): Boolean =
        fromImport && sameCopy && !recorded.isPerFile

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
        if ((state == ItemState.UNKNOWN.name || state == ItemState.DONE.name) && sameCopy) recorded else evidence

    /**
     * Where a restored row goes when its copy is in none of the output
     * folders. With per-file proof from its history file, to DONE: Ente had
     * that very copy, and it has since gone. With a weaker grade, to DONE too
     * once the restore is no longer [watching] - until then the copy may be
     * on a card not in yet, so it stays UNKNOWN and is looked for again.
     * Without any, it stays UNKNOWN.
     */
    fun stateWhenCopyMissing(recorded: Evidence, watching: Boolean): ItemState = when {
        recorded.isPerFile -> ItemState.DONE
        recorded == Evidence.NONE || watching -> ItemState.UNKNOWN
        else -> ItemState.DONE
    }

    /** The state an adopted row lands in. */
    val state: ItemState = ItemState.RELEASED
}
