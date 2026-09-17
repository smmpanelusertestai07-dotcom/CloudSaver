package app.cloudsaver.core.logic

/**
 * How the app knows what it claims about one file.
 *
 * Every list and every details sheet shows this, in the same words, from the
 * same function. An app whose whole safety argument is "we only remove what we
 * can prove reached your cloud" has to be able to say, per file, what the
 * proof actually was - otherwise the claim is just a slogan.
 */
object ProofLine {

    enum class Kind {
        /** The cloud app itself removed the copy after uploading it. */
        CLOUD_REMOVED_COPY,

        /** The cloud app sent about this file's worth of data just after it went out. */
        UPLOAD_SIZE_MATCHED,

        /** A byte-identical file is still on the phone. */
        IDENTICAL_COPY_KEPT,

        /** The copy has been out there a while, with no per-file evidence. */
        TIME_ONLY,

        /** Nothing yet. */
        WAITING
    }

    /**
     * [isDuplicateExtra] wins over everything: an identical twin on the phone
     * is stronger than any upload evidence, and it is also the only proof that
     * does not depend on the cloud app behaving.
     */
    fun forItem(evidence: Evidence, isDuplicateExtra: Boolean): Kind = when {
        isDuplicateExtra -> Kind.IDENTICAL_COPY_KEPT
        evidence == Evidence.CONFIRMED_EXACT -> Kind.CLOUD_REMOVED_COPY
        evidence == Evidence.CONFIRMED_PACED -> Kind.UPLOAD_SIZE_MATCHED
        evidence == Evidence.VERIFIED -> Kind.UPLOAD_SIZE_MATCHED
        evidence == Evidence.AGED -> Kind.TIME_ONLY
        else -> Kind.WAITING
    }

    /**
     * Whether an item may be included in a destructive action.
     *
     * Time alone is not proof of anything, so an AGED item is never swept into
     * a bulk removal - it can only go through a flow that says so explicitly.
     */
    fun allowsRemoval(kind: Kind): Boolean = when (kind) {
        Kind.CLOUD_REMOVED_COPY, Kind.UPLOAD_SIZE_MATCHED, Kind.IDENTICAL_COPY_KEPT -> true
        Kind.TIME_ONLY, Kind.WAITING -> false
    }

    /** A count of each proof kind, for the sentence above a confirmation. */
    fun tally(kinds: List<Kind>): Map<Kind, Int> =
        kinds.groupingBy { it }.eachCount()

    /**
     * Splits a selection into what may go and what may not.
     *
     * Run again immediately before acting, never only when the list was built:
     * a copy can stop qualifying between the tap and the confirmation, and the
     * result names anything that dropped out.
     */
    fun partition(
        items: List<Pair<Long, Kind>>
    ): Pair<List<Long>, List<Long>> {
        val allowed = items.filter { allowsRemoval(it.second) }.map { it.first }
        val refused = items.filterNot { allowsRemoval(it.second) }.map { it.first }
        return allowed to refused
    }
}
