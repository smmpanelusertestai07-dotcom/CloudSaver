package app.entesaver.core.logic

/**
 * Small decisions the Free-up screens make, kept here so a test can hold
 * them without a phone.
 *
 * Each one used to be decided inside a screen or the view model, and each
 * was wrong in a way nobody could see from the screen that drew it: an answer
 * to Android's dialog that reached the wrong flow, a box that could tick a
 * group but never untick it, a button that promised a trash it did not use,
 * and a restore offered for files Android had already emptied out.
 */
object FreeUpFlow {

    /**
     * Which flow a system dialog belongs to.
     *
     * Three screens share one view model, and each opens whatever request is
     * pending. The answer has to go to the flow that asked, not to whichever
     * screen happened to be showing when the dialog opened.
     */
    enum class Dialog { RECLAIM, LEGACY, DUPLICATES, RESTORE }

    /** Where an answer is handled. */
    enum class Answer { REMOVAL, RESTORE, NOBODY }

    /**
     * Removal answers all go to one handler, which already tells reclaim,
     * duplicates and the Android 10 path apart. A restore has its own. An
     * answer with no request behind it is only cleared away.
     */
    fun answerGoesTo(kind: Dialog?): Answer = when (kind) {
        Dialog.RECLAIM, Dialog.LEGACY, Dialog.DUPLICATES -> Answer.REMOVAL
        Dialog.RESTORE -> Answer.RESTORE
        null -> Answer.NOBODY
    }

    /**
     * The selection after a group's box is tapped.
     *
     * A ticked group unticks all of its files and leaves every other group
     * alone. Before, the box could only add, so the one way to undo a group
     * was to untick each file by hand or clear the whole selection.
     */
    fun tickGroup(selected: Set<Long>, group: Collection<Long>, tick: Boolean): Set<Long> =
        if (tick) selected + group else selected - group.toSet()

    /** What the main button and the confirm sheet say the batch will do. */
    enum class Wording { REMOVE_COPIES, TRASH, DELETE }

    /**
     * The main button's words.
     *
     * Copies-only deletes the app's own copies outright and never touches an
     * original, so it can say neither "trash" nor anything about originals.
     */
    fun buttonWording(mode: ReclaimRules.Mode, canUndo: Boolean): Wording = when {
        mode == ReclaimRules.Mode.COPIES_ONLY -> Wording.REMOVE_COPIES
        canUndo -> Wording.TRASH
        else -> Wording.DELETE
    }

    /** The confirm sheet's words, by the same rule as the button. */
    fun sheetWording(mode: ReclaimRules.Mode, permanent: Boolean): Wording = when {
        mode == ReclaimRules.Mode.COPIES_ONLY -> Wording.REMOVE_COPIES
        permanent -> Wording.DELETE
        else -> Wording.TRASH
    }

    /** How long Android keeps a trashed file, and so how long history lasts. */
    const val TRASH_KEEP_MS = 30L * 86_400_000L

    /** The last day a batch's files can come back from the trash. */
    fun trashUntil(atMs: Long): Long = atMs + TRASH_KEEP_MS

    /**
     * True once Android has emptied a batch's files out of its trash.
     *
     * A restore offered after that has nothing to bring back, and a "yes"
     * to it would write down originals that no longer exist. At the exact
     * boundary it counts as gone: offering less is the safer mistake.
     */
    fun trashExpired(atMs: Long, now: Long): Boolean = now >= trashUntil(atMs)
}
