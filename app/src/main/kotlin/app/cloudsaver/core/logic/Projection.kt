package app.cloudsaver.core.logic

/**
 * "About X could be saved" - computed in exactly one place.
 *
 * Every screen used to work this out for itself, and each one fell back
 * differently. Where a measured ratio did not exist yet the arithmetic
 * multiplied by zero, so 249 MB of video was reported as "about 0 MB could be
 * saved" - a number that is not cautious, just wrong.
 *
 * The rule here: use this phone's measured ratio when there is one, otherwise
 * the typical ratio for that media type, and always say which was used.
 */
object Projection {

    /**
     * Typical fractions of the original that survive optimising, on the
     * default preset. Derived from [CapacityMath]'s cold-start figures, so the
     * calculator and the rest of the app start from the same assumption.
     */
    val TYPICAL_PHOTO_RATIO: Double = CapacityMath.DEF_PHOTO_OUT_MB / CapacityMath.DEF_PHOTO_ORIG_MB
    val TYPICAL_VIDEO_RATIO: Double =
        CapacityMath.DEF_VIDEO_OUT_MB_PER_MIN_H264 / CapacityMath.DEF_VIDEO_ORIG_MB_PER_MIN

    /** How a figure was arrived at, so the screen can label it honestly. */
    enum class Basis { MEASURED, PARTLY_MEASURED, TYPICAL }

    data class Estimate(val savedBytes: Long, val basis: Basis) {
        val isEstimate: Boolean get() = basis != Basis.MEASURED
    }

    /** The surviving fraction for one media type: measured, else typical. */
    fun ratioFor(isVideo: Boolean, measured: Double): Double = when {
        measured > 0.0 -> measured
        isVideo -> TYPICAL_VIDEO_RATIO
        else -> TYPICAL_PHOTO_RATIO
    }

    /** What optimising one file of [sizeBytes] would save. Never negative. */
    fun forItem(sizeBytes: Long, isVideo: Boolean, measured: Double): Long {
        if (sizeBytes <= 0) return 0
        val kept = ratioFor(isVideo, measured).coerceIn(CapacityMath.CLAMP_MIN, CapacityMath.CLAMP_MAX)
        return (sizeBytes * (1 - kept)).toLong().coerceAtLeast(0)
    }

    /**
     * What optimising a mixed pile would save.
     *
     * Photos and videos compress very differently, so they are projected
     * separately and added - averaging the two ratios and applying it to the
     * total was the other way this number went wrong.
     */
    fun forQueue(
        photoBytes: Long,
        videoBytes: Long,
        measuredPhotoRatio: Double,
        measuredVideoRatio: Double
    ): Estimate {
        val saved = forItem(photoBytes, isVideo = false, measured = measuredPhotoRatio) +
            forItem(videoBytes, isVideo = true, measured = measuredVideoRatio)
        // Only the types that are actually present get a say in the label: a
        // queue of photos alone is fully measured once photos are measured.
        val photoKnown = measuredPhotoRatio > 0.0 || photoBytes <= 0
        val videoKnown = measuredVideoRatio > 0.0 || videoBytes <= 0
        val basis = when {
            photoKnown && videoKnown -> Basis.MEASURED
            photoKnown || videoKnown -> Basis.PARTLY_MEASURED
            else -> Basis.TYPICAL
        }
        return Estimate(saved, basis)
    }
}
