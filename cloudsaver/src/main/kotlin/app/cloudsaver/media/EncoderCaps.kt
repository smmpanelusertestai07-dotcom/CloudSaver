package app.cloudsaver.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import app.cloudsaver.core.logic.MediaSettings

/**
 * What this phone's own encoder chips can do.
 *
 * Only hardware encoders count. A software encoder can produce the same file,
 * but on a budget phone it takes many times longer and runs the phone hot,
 * and this app's first promise is that nobody notices it working.
 */
object EncoderCaps {

    const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    const val MIME_HEIC = MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC

    private val codecs: List<MediaCodecInfo> by lazy {
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList())
    }

    /** Hardware encoders for [mime], best first as the phone lists them. */
    fun hardwareEncoders(mime: String): List<MediaCodecInfo> = codecs.filter { info ->
        info.isEncoder &&
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
            runCatching { info.isHardwareAccelerated && !info.isSoftwareOnly }.getOrDefault(false)
    }

    /**
     * True when a hardware HEVC encoder takes a video of this size and frame
     * rate. Either orientation is accepted: encoders often state limits for
     * landscape only and rotate a portrait frame themselves.
     */
    fun hevcFits(width: Int, height: Int, fps: Float): Boolean =
        hardwareEncoders(MIME_HEVC).any { info ->
            runCatching {
                val video = info.getCapabilitiesForType(MIME_HEVC).videoCapabilities
                val rate = fps.toDouble().coerceAtLeast(1.0)
                video.areSizeAndRateSupported(width, height, rate) ||
                    video.areSizeAndRateSupported(height, width, rate)
            }.getOrDefault(false)
        }

    /**
     * True when some hardware video encoder - H.264 or HEVC - takes this size
     * and frame rate. A budget phone's chip often stops at 1080p; asking it
     * for more ends in a software encode or a failed one.
     */
    /**
     * The longest side this phone makes a video at when [requestedLongSide]
     * is asked for (0 = keep the source's, up to 2160p), or 0 when the phone
     * sets no limit of its own. The compressor's own rule: above 1080p only
     * where a hardware encoder takes the size, and never on the smallest
     * phones ([smallestPhone]).
     */
    fun phoneLongSideCap(requestedLongSide: Int, smallestPhone: Boolean): Int {
        val asked = if (requestedLongSide <= 0) 3840 else requestedLongSide
        return when {
            asked <= MediaSettings.FULL_HD -> 0
            smallestPhone -> MediaSettings.FULL_HD
            anyHardwareFits(asked, asked * 9 / 16, 30f) -> 0
            else -> MediaSettings.FULL_HD
        }
    }

    fun anyHardwareFits(width: Int, height: Int, fps: Float): Boolean =
        listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MIME_HEVC).any { mime ->
            hardwareEncoders(mime).any { info ->
                runCatching {
                    val video = info.getCapabilitiesForType(mime).videoCapabilities
                    val rate = fps.toDouble().coerceAtLeast(1.0)
                    video.areSizeAndRateSupported(width, height, rate) ||
                        video.areSizeAndRateSupported(height, width, rate)
                }.getOrDefault(false)
            }
        }

    /** A hardware encoder HEIC can be written with: a HEIC one, or HEVC. */
    fun hasHardwareHeic(): Boolean =
        hardwareEncoders(MIME_HEIC).isNotEmpty() || hardwareEncoders(MIME_HEVC).isNotEmpty()
}
