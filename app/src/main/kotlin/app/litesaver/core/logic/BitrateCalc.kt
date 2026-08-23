package app.litesaver.core.logic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Video bitrate policy:
 * target = OUTPUT pixels x fps x bits-per-pixel-per-frame (0.10 H.264 / 0.065 HEVC),
 * clamped to [1 Mbps, 12 Mbps]. ~6 Mbps for 1080p30 H.264, ~4 Mbps HEVC.
 */
object BitrateCalc {

    const val BPP_H264 = 0.10
    const val BPP_HEVC = 0.065
    const val CAP_BPS = 12_000_000
    const val FLOOR_BPS = 1_000_000

    /** Copy as-is when source bitrate <= 1.15 x target (and size/container fit). */
    const val COPY_BITRATE_FACTOR = 1.15

    /** Result rejected when output bitrate > 1.3 x target (encoder ignored settings). */
    const val RESULT_BITRATE_FACTOR = 1.3

    const val DURATION_TOLERANCE_MS = 2_000L

    fun bppFor(codec: VideoCodec): Double = if (codec == VideoCodec.H264) BPP_H264 else BPP_HEVC

    fun targetBps(outWidth: Int, outHeight: Int, fps: Float, codec: VideoCodec): Int {
        val safeFps = if (fps.isFinite() && fps > 1f) fps else 30f
        val raw = outWidth.toDouble() * outHeight.toDouble() * safeFps * bppFor(codec)
        return raw.toLong().coerceIn(FLOOR_BPS.toLong(), CAP_BPS.toLong()).toInt()
    }

    /** Keep aspect ratio, clamp long side, force even dimensions. */
    fun outputDims(srcWidth: Int, srcHeight: Int, longSideLimit: Int): Pair<Int, Int> {
        val w = max(2, srcWidth)
        val h = max(2, srcHeight)
        val longSide = max(w, h)
        if (longSide <= longSideLimit) return even(w) to even(h)
        val scale = longSideLimit.toDouble() / longSide
        return even((w * scale).roundToInt()) to even((h * scale).roundToInt())
    }

    private fun even(v: Int): Int = max(2, v - (v % 2))

    fun shouldCopyAsIs(
        srcLongSide: Int,
        longSideLimit: Int,
        srcBps: Long,
        targetBps: Int,
        containerOk: Boolean
    ): Boolean = containerOk &&
        srcLongSide <= longSideLimit &&
        srcBps > 0 &&
        srcBps <= (targetBps * COPY_BITRATE_FACTOR).toLong()

    /** Mandatory result check: smaller than source, sane bitrate, duration within +/-2 s. */
    fun resultAcceptable(
        srcBytes: Long,
        outBytes: Long,
        outBps: Long,
        targetBps: Int,
        srcDurationMs: Long,
        outDurationMs: Long
    ): Boolean {
        if (outBytes <= 0 || outBytes >= srcBytes) return false
        if (outBps > (targetBps * RESULT_BITRATE_FACTOR).toLong()) return false
        if (srcDurationMs > 0 && abs(srcDurationMs - outDurationMs) > DURATION_TOLERANCE_MS) return false
        return true
    }

    /**
     * Power-of-two BitmapFactory inSampleSize: decode within 2x of the pixel
     * budget (memory safety), the exact downscale then lands on the budget.
     */
    fun sampleSizeFor(width: Int, height: Int, maxPixels: Long): Int {
        var sample = 1
        var pixels = width.toLong() * height.toLong()
        while (pixels > maxPixels * 2) {
            sample *= 2
            pixels /= 4
        }
        return sample
    }
}
