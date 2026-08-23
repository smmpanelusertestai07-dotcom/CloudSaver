package app.litesaver.core.logic

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Cloud calculator math (13.C). Pure Kotlin, fully unit-tested.
 * GB is decimal (1 GB = 1000 MB = 1e9 bytes), like cloud providers count.
 * Every output is an ESTIMATE and is rounded to 2 significant figures for
 * humans - never show false precision.
 */
object CapacityMath {

    const val GB = 1_000_000_000.0

    /** Measured ratios need at least this many processed items of a type. */
    const val MIN_SAMPLES = 20

    /** Outlier guard for per-item compression ratios. */
    const val CLAMP_MIN = 0.05
    const val CLAMP_MAX = 1.0

    // Cold-start defaults (typical phone media).
    const val DEF_PHOTO_ORIG_MB = 3.5
    const val DEF_PHOTO_OUT_MB = 2.5
    const val DEF_VIDEO_ORIG_MB_PER_MIN = 122.0
    const val DEF_VIDEO_OUT_MB_PER_MIN_H264 = 45.0
    const val DEF_VIDEO_OUT_MB_PER_MIN_HEVC = 28.0

    enum class CalcMode { PHOTOS, VIDEOS, BOTH }

    /** One processed item (as-is copies included - they are real outcomes). */
    data class Sample(val origBytes: Long, val outBytes: Long, val durationMin: Double = 0.0)

    data class Ratios(
        val photoRatio: Double,
        val videoRatio: Double,
        val videoOutMBperMin: Double,
        val avgPhotoOutBytes: Double,
        val photoSamples: Int,
        val videoSamples: Int
    ) {
        val photoMeasured: Boolean get() = photoSamples >= MIN_SAMPLES
        val videoMeasured: Boolean get() = videoSamples >= MIN_SAMPLES
    }

    fun clampRatio(r: Double): Double = when {
        r.isNaN() || r.isInfinite() -> CLAMP_MAX
        else -> r.coerceIn(CLAMP_MIN, CLAMP_MAX)
    }

    /**
     * Byte-weighted ratios from this phone's processed items (last 500 per type,
     * the caller limits). Types with fewer than [MIN_SAMPLES] items fall back to
     * the defaults for the given codec.
     */
    fun ratios(
        photo: List<Sample>,
        video: List<Sample>,
        codec: VideoCodec
    ): Ratios {
        val defVideoOut = if (codec == VideoCodec.H264) {
            DEF_VIDEO_OUT_MB_PER_MIN_H264
        } else {
            DEF_VIDEO_OUT_MB_PER_MIN_HEVC
        }
        val defaults = Ratios(
            photoRatio = DEF_PHOTO_OUT_MB / DEF_PHOTO_ORIG_MB,
            videoRatio = defVideoOut / DEF_VIDEO_ORIG_MB_PER_MIN,
            videoOutMBperMin = defVideoOut,
            avgPhotoOutBytes = DEF_PHOTO_OUT_MB * 1e6,
            photoSamples = photo.size,
            videoSamples = video.size
        )

        var photoRatio = defaults.photoRatio
        var avgPhotoOut = defaults.avgPhotoOutBytes
        if (photo.size >= MIN_SAMPLES) {
            val origSum = photo.sumOf { it.origBytes.toDouble() }
            if (origSum > 0) {
                val weighted = photo.sumOf {
                    it.origBytes.toDouble() * clampRatio(it.outBytes.toDouble() / it.origBytes)
                }
                photoRatio = clampRatio(weighted / origSum)
            }
            avgPhotoOut = photo.sumOf { it.outBytes.toDouble() } / photo.size
            if (avgPhotoOut <= 0) avgPhotoOut = defaults.avgPhotoOutBytes
        }

        var videoRatio = defaults.videoRatio
        var videoOutMBperMin = defaults.videoOutMBperMin
        if (video.size >= MIN_SAMPLES) {
            val origSum = video.sumOf { it.origBytes.toDouble() }
            if (origSum > 0) {
                val weighted = video.sumOf {
                    it.origBytes.toDouble() * clampRatio(it.outBytes.toDouble() / it.origBytes)
                }
                videoRatio = clampRatio(weighted / origSum)
            }
            val withDuration = video.filter { it.durationMin > 0 }
            val minutes = withDuration.sumOf { it.durationMin }
            if (minutes > 0.5) {
                videoOutMBperMin = withDuration.sumOf { it.outBytes.toDouble() } / 1e6 / minutes
            }
        }

        return Ratios(
            photoRatio = photoRatio,
            videoRatio = videoRatio,
            videoOutMBperMin = videoOutMBperMin,
            avgPhotoOutBytes = avgPhotoOut,
            photoSamples = photo.size,
            videoSamples = video.size
        )
    }

    /** Gallery totals for the selected mode's folder rules (bytes, minutes). */
    data class Gallery(
        val photoBytes: Long,
        val videoBytes: Long,
        val videoMinutes: Double,
        val monthlyPhotoBytes: Long,
        val monthlyVideoBytes: Long
    )

    /** Default mix slider position: this phone's actual photo:video byte share. */
    fun defaultMixShare(g: Gallery): Double {
        val total = g.photoBytes + g.videoBytes
        if (total <= 0) return 0.5
        return g.photoBytes.toDouble() / total
    }

    data class Estimate(
        /** "X GB cloud ~ Y GB of originals". */
        val originalsGB: Double,
        /** "~N photos" capacity for the photo share. */
        val photoCount: Long,
        /** "~H hours of video" capacity for the video share. */
        val videoHours: Double,
        /** Predicted compressed size of the current gallery backlog (GB). */
        val backlogGB: Double,
        val fits: Boolean,
        /** "needs C GB more" when the backlog does not fit. */
        val needMoreGB: Double,
        /** "lasts ~M more months"; negative = cannot say (no pace / does not fit). */
        val monthsLeft: Double,
        /** True while defaults are used for a type this mode needs. */
        val typicalEstimate: Boolean,
        /** Number of this phone's files the ratios are based on. */
        val sampleCount: Int
    )

    fun estimate(
        cloudFreeGB: Double,
        mode: CalcMode,
        photoShare: Double,
        g: Gallery,
        r: Ratios
    ): Estimate {
        val x = max(0.0, cloudFreeGB) * GB
        val share = when (mode) {
            CalcMode.PHOTOS -> 1.0
            CalcMode.VIDEOS -> 0.0
            CalcMode.BOTH -> photoShare.coerceIn(0.0, 1.0)
        }
        val xPhoto = x * share
        val xVideo = x * (1 - share)

        val originalsBytes = xPhoto / r.photoRatio + xVideo / r.videoRatio
        val photoCount = if (share > 0 && r.avgPhotoOutBytes > 0) {
            floor(xPhoto / r.avgPhotoOutBytes).toLong()
        } else {
            0L
        }
        val videoHours = if (share < 1 && r.videoOutMBperMin > 0) {
            xVideo / (r.videoOutMBperMin * 1e6 * 60.0)
        } else {
            0.0
        }

        val usePhotos = mode != CalcMode.VIDEOS
        val useVideos = mode != CalcMode.PHOTOS
        val backlogBytes =
            (if (usePhotos) g.photoBytes * r.photoRatio else 0.0) +
                (if (useVideos) g.videoBytes * r.videoRatio else 0.0)
        val fits = backlogBytes <= x
        val needMoreGB = max(0.0, backlogBytes - x) / GB

        val monthlyCompressed =
            (if (usePhotos) g.monthlyPhotoBytes * r.photoRatio else 0.0) +
                (if (useVideos) g.monthlyVideoBytes * r.videoRatio else 0.0)
        val monthsLeft = when {
            !fits -> -1.0
            monthlyCompressed <= 0 -> -1.0
            else -> (x - backlogBytes) / monthlyCompressed
        }

        val typical = (usePhotos && !r.photoMeasured) || (useVideos && !r.videoMeasured)
        val samples = (if (usePhotos) r.photoSamples else 0) + (if (useVideos) r.videoSamples else 0)

        return Estimate(
            originalsGB = originalsBytes / GB,
            photoCount = photoCount,
            videoHours = videoHours,
            backlogGB = backlogBytes / GB,
            fits = fits,
            needMoreGB = needMoreGB,
            monthsLeft = monthsLeft,
            typicalEstimate = typical,
            sampleCount = samples
        )
    }

    /** Round to 2 significant figures - human numbers, no false precision. */
    fun round2sf(v: Double): Double {
        if (v == 0.0 || v.isNaN() || v.isInfinite()) return 0.0
        val magnitude = floor(log10(abs(v)))
        val factor = 10.0.pow(magnitude - 1)
        return (v / factor).roundToLong() * factor
    }
}
