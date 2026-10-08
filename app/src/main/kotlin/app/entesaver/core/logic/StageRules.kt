package app.entesaver.core.logic

/**
 * Whether a waiting file may be made into a light copy right now.
 *
 * Two paths stage files: the scheduled run and the Home trial. Both pick the
 * newest photos first, from a list read before they started, so by the time
 * one of them reaches a file the other may already have staged it. And a
 * file can change while it waits: "Save" in a gallery editor rewrites the
 * bytes under the same MediaStore id, the scanner records the edited photo
 * as a new row, and the old row still points at that id. Encoding the old
 * row then made a second copy of the edited photo, under the old row's name.
 */
object StageRules {

    enum class Verdict {
        /** Still waiting, and the file is the one the row describes. */
        STAGE,

        /** Another path got there first, or the original has gone. */
        TAKEN,

        /** The file behind the address is not this row's file any more. */
        CHANGED
    }

    /**
     * [fileName] and [fileSize] are read from the address just now; null
     * when the read failed. A failed read is not proof of a change, so the
     * encode goes ahead and fails, or not, in the ordinary way.
     */
    fun verdict(
        state: String,
        originalMissing: Boolean,
        rowName: String,
        rowSize: Long,
        fileName: String?,
        fileSize: Long?
    ): Verdict = when {
        state != ItemState.NEW.name || originalMissing -> Verdict.TAKEN
        fileName == null || fileSize == null -> Verdict.STAGE
        OriginalCheck.unchanged(rowName, rowSize, fileName, fileSize) -> Verdict.STAGE
        else -> Verdict.CHANGED
    }

    /**
     * Whether a row read after an encode may still take its result: it is
     * still waiting, still wanted, and still the file that was encoded
     * ([encodedFingerprint]). Anything else settled it while the encode ran.
     */
    fun stillWaiting(
        state: String,
        neverOptimise: Boolean,
        originalMissing: Boolean,
        fingerprint: String,
        encodedFingerprint: String
    ): Boolean = state == ItemState.NEW.name && !neverOptimise && !originalMissing &&
        fingerprint == encodedFingerprint

    /** A row still waiting, as much of it as [replaced] needs. */
    data class Waiting(val id: Long, val fingerprint: String, val contentUri: String)

    /**
     * Waiting rows whose address now holds a different file.
     *
     * [onPhone] is what the scan just found, address to fingerprint. A row
     * whose address is there under another fingerprint was edited in place
     * (or re-stamped by MediaProvider): the scan has a row for the file as it
     * is now, and this one describes bytes that no longer exist. A row whose
     * address the scan did not see is left to the presence check.
     */
    fun replaced(waiting: List<Waiting>, onPhone: Map<String, String>): List<Long> =
        waiting.filter { w ->
            val now = onPhone[w.contentUri]
            now != null && now != w.fingerprint
        }.map { it.id }
}
