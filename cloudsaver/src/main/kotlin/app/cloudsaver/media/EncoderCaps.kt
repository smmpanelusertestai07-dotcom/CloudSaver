package app.cloudsaver.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

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

    /** A hardware encoder HEIC can be written with: a HEIC one, or HEVC. */
    fun hasHardwareHeic(): Boolean =
        hardwareEncoders(MIME_HEIC).isNotEmpty() || hardwareEncoders(MIME_HEVC).isNotEmpty()
}
