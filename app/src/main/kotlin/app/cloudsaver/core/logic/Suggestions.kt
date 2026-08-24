package app.cloudsaver.core.logic

/**
 * Ready-made filters over the reclaim list.
 *
 * Not a second deletion system: each one narrows the same list, nothing is
 * pre-selected, and every safeguard - eligibility, the integrity gate,
 * trash-first, Android's dialog, the large-batch guard - still applies. The
 * point is only that "backed-up videos over 100 MB from last year" is a real
 * thought people have, and building that filter by hand is five taps.
 */
object Suggestions {

    enum class Kind {
        BIG_OLD_VIDEOS, OLD_SCREENSHOTS, OLD_MEDIA, DUPLICATES, CONFIRMED_30_DAYS
    }

    data class Filter(
        val kind: Kind,
        val minBytes: Long = 0,
        val minAgeDays: Int = 0,
        val videosOnly: Boolean = false,
        val screenshotsOnly: Boolean = false
    )

    val ALL: List<Filter> = listOf(
        Filter(Kind.BIG_OLD_VIDEOS, minBytes = 100L * 1_000_000, minAgeDays = 365, videosOnly = true),
        Filter(Kind.OLD_SCREENSHOTS, minAgeDays = 182, screenshotsOnly = true),
        Filter(Kind.OLD_MEDIA, minAgeDays = 730),
        Filter(Kind.DUPLICATES),
        Filter(Kind.CONFIRMED_30_DAYS, minAgeDays = ReclaimRules.MIN_CONFIRM_AGE_DAYS)
    )

    /** Album names Android and its OEMs use for screenshots. */
    private val SCREENSHOT_ALBUMS = setOf("screenshots", "screen shots", "screencapture")

    fun isScreenshot(album: String?, displayName: String): Boolean {
        val bucket = album?.lowercase()
        if (bucket != null && SCREENSHOT_ALBUMS.any { it == bucket }) return true
        return displayName.lowercase().startsWith("screenshot")
    }

    /**
     * Applies a suggestion to candidates that are ALREADY eligible. The
     * caller runs the reclaim gate first; a suggestion can only ever narrow
     * that set, never widen it.
     */
    fun apply(
        eligible: List<ReclaimRules.Candidate>,
        filter: Filter,
        ageDaysOf: (ReclaimRules.Candidate) -> Int,
        nameOf: (ReclaimRules.Candidate) -> String
    ): List<ReclaimRules.Candidate> = eligible.filter { c ->
        if (filter.videosOnly && !c.isVideo) return@filter false
        if (filter.minBytes > 0 && c.sizeBytes < filter.minBytes) return@filter false
        if (filter.minAgeDays > 0 && ageDaysOf(c) < filter.minAgeDays) return@filter false
        if (filter.screenshotsOnly && !isScreenshot(c.album, nameOf(c))) return@filter false
        true
    }
}
