package app.cloudsaver.core.logic

/**
 * Whether a released copy is genuinely visible to other apps (CC1.1).
 *
 * The bug this exists to stop: MediaStore's un-pend update was fire-and-
 * forget, so a silent failure left the row at IS_PENDING = 1 - invisible to
 * the gallery and to every cloud app - while the item was still marked
 * RELEASED. The Home tile then counted a file that, as far as the rest of the
 * phone was concerned, did not exist. Nothing in the app could notice,
 * because nothing ever looked again.
 *
 * So the rule is now: re-read the row we just wrote, and only a row that is
 * present, finished and where we meant to put it counts as released.
 */
object ReleaseVerdict {

    enum class Failure {
        /** The row vanished, or the query could not read it back. */
        MISSING,

        /** Still pending: written, but invisible to every other app. */
        STILL_PENDING,

        /** Zero bytes: the copy did not land. */
        EMPTY,

        /** It exists, but not in the folder the cloud app was told to watch. */
        WRONG_FOLDER
    }

    /**
     * [relativePath] and [expectedPath] are compared leniently on the trailing
     * slash only, because MediaStore normalises "Pictures/CloudSaver" and
     * "Pictures/CloudSaver/" to the same place and returns whichever it likes.
     */
    fun check(
        found: Boolean,
        isPending: Boolean,
        sizeBytes: Long,
        relativePath: String?,
        expectedPath: String
    ): Failure? = when {
        !found -> Failure.MISSING
        isPending -> Failure.STILL_PENDING
        sizeBytes <= 0 -> Failure.EMPTY
        !samePath(relativePath, expectedPath) -> Failure.WRONG_FOLDER
        else -> null
    }

    fun samePath(actual: String?, expected: String): Boolean {
        if (actual.isNullOrEmpty()) return false
        return actual.trim('/').equals(expected.trim('/'), ignoreCase = true)
    }

    /** True only when every check passed - the one gate before RELEASED. */
    fun isVisible(failure: Failure?): Boolean = failure == null
}
