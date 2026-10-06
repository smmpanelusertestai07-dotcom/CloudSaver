package app.entesaver.core.logic

/**
 * How photos and how videos are optimised - two separate settings, because
 * they are two separate trade-offs.
 *
 * A photo's choice is how many pixels to keep and how hard to compress them;
 * a video's adds the codec, the frame rate, the sound and HDR. One preset for
 * both forced a person who wanted sharp photos and small videos to pick one.
 * Each kind now has three presets and a Custom choice that opens every knob.
 * Custom values are kept while a preset is chosen, so switching back to
 * Custom finds them where they were left.
 */

enum class PhotoPreset { BEST, BALANCED, SMALLEST, CUSTOM }

/**
 * The file a photo is written as. AUTO is HEIC on a phone with a working
 * hardware HEIC encoder - about half the size of JPEG at the same look - and
 * JPEG everywhere else.
 */
enum class PhotoFormat { AUTO, HEIC, JPEG, WEBP }

enum class VideoPreset { BEST, BALANCED, SMALLEST, CUSTOM }

/** AUTO is HEVC on a phone that encodes it in hardware, H.264 otherwise. */
enum class VideoCodecChoice { AUTO, H264, HEVC }

/** Bits spent per pixel: the size-against-look dial for video. */
enum class VideoQuality { HIGH, STANDARD, SMALL }

enum class HdrPolicy { KEEP_WHEN_POSSIBLE, TO_SDR }

/** A photo setting with every value filled in. [maxMp] 0 keeps every pixel. */
data class PhotoSpec(val format: PhotoFormat, val maxMp: Int, val quality: Int) {
    val maxPixels: Long get() = if (maxMp <= 0) Long.MAX_VALUE else maxMp * 1_000_000L
}

/** A video setting with every value filled in. 0 keeps the source's own. */
data class VideoSpec(
    val codec: VideoCodecChoice,
    val longSide: Int,
    val fpsCap: Int,
    val quality: VideoQuality,
    val audioKbps: Int,
    val hdr: HdrPolicy
) {
    val longSideLimit: Int get() = if (longSide <= 0) Int.MAX_VALUE else longSide
}

data class PhotoSettings(
    val preset: PhotoPreset = PhotoPreset.BALANCED,
    val format: PhotoFormat = PhotoFormat.AUTO,
    val maxMp: Int = 16,
    val quality: Int = 82
) {
    fun spec(): PhotoSpec = when (preset) {
        PhotoPreset.BEST -> PhotoSpec(PhotoFormat.AUTO, 24, 85)
        PhotoPreset.BALANCED -> PhotoSpec(PhotoFormat.AUTO, 16, 82)
        PhotoPreset.SMALLEST -> PhotoSpec(PhotoFormat.AUTO, 8, 75)
        PhotoPreset.CUSTOM -> PhotoSpec(format, maxMp, quality)
    }
}

data class VideoSettings(
    val preset: VideoPreset = VideoPreset.BALANCED,
    val codec: VideoCodecChoice = VideoCodecChoice.AUTO,
    val longSide: Int = 1920,
    val fpsCap: Int = 30,
    val quality: VideoQuality = VideoQuality.STANDARD,
    val audioKbps: Int = 128,
    val hdr: HdrPolicy = HdrPolicy.KEEP_WHEN_POSSIBLE
) {
    fun spec(): VideoSpec = when (preset) {
        VideoPreset.BEST -> VideoSpec(VideoCodecChoice.AUTO, 2560, 30, VideoQuality.HIGH, 128, HdrPolicy.KEEP_WHEN_POSSIBLE)
        VideoPreset.BALANCED -> VideoSpec(VideoCodecChoice.AUTO, 1920, 30, VideoQuality.STANDARD, 128, HdrPolicy.KEEP_WHEN_POSSIBLE)
        VideoPreset.SMALLEST -> VideoSpec(VideoCodecChoice.AUTO, 1280, 30, VideoQuality.SMALL, 96, HdrPolicy.KEEP_WHEN_POSSIBLE)
        VideoPreset.CUSTOM -> VideoSpec(codec, longSide, fpsCap, quality, audioKbps, hdr)
    }
}

object MediaSettings {

    /** What Custom offers, so the screen and an imported backup agree. */
    val PHOTO_MP_CHOICES = listOf(0, 24, 16, 12, 8)
    val PHOTO_QUALITY_CHOICES = listOf(90, 85, 82, 75)
    val VIDEO_LONG_SIDE_CHOICES = listOf(0, 3840, 2560, 1920, 1280)

    /** 1080p's long side: what every phone's hardware encoder takes. */
    const val FULL_HD = 1920
    val VIDEO_FPS_CHOICES = listOf(0, 30)
    val AUDIO_KBPS_CHOICES = listOf(128, 96, 64)

    /** A frame rate this high or more is slow motion: kept as filmed. */
    const val SLOW_MOTION_FPS = 90f

    /**
     * The frame rate a video is written at: capped only for an ordinary high
     * frame rate (31-89 fps). A slow-motion clip keeps every frame, or its
     * slow motion would be gone.
     */
    fun outputFps(sourceFps: Float, cap: Int): Float? {
        if (cap <= 0 || !sourceFps.isFinite()) return null
        if (sourceFps <= cap + 0.5f || sourceFps >= SLOW_MOTION_FPS) return null
        return cap.toFloat()
    }

    /** The name a short side is known by: 1080p for 1920 on the long side. */
    fun pLabel(longSide: Int): String = when (longSide) {
        3840 -> "2160p"
        2560 -> "1440p"
        1920 -> "1080p"
        1280 -> "720p"
        else -> "${longSide}px"
    }

    /**
     * The key encode results are grouped under, so an estimate only ever
     * quotes files made the same way. Photos: format-megapixels-quality
     * ("jpeg-16-82"). Videos: codec-long side-quality ("h264-1920-std") - the
     * three that decide most of a video's size.
     */
    fun photoKey(format: PhotoFormat, spec: PhotoSpec): String =
        "${format.name.lowercase()}-${spec.maxMp}-${spec.quality}"

    fun videoKey(codec: VideoCodec, spec: VideoSpec): String =
        "${codec.name.lowercase()}-${spec.longSide}-${qualityTag(spec.quality)}"

    /** A photo key read back, for a file's details. */
    data class PhotoKey(val format: PhotoFormat, val maxMp: Int, val quality: Int)

    /** A video key read back, for a file's details. */
    data class VideoKey(val codec: VideoCodec, val longSide: Int, val quality: VideoQuality)

    fun parsePhotoKey(key: String): PhotoKey? {
        val parts = key.split('-')
        if (parts.size != 3) return null
        val format = PhotoFormat.entries.firstOrNull { it.name.equals(parts[0], ignoreCase = true) } ?: return null
        val mp = parts[1].toIntOrNull() ?: return null
        val quality = parts[2].toIntOrNull() ?: return null
        return PhotoKey(format, mp, quality)
    }

    fun parseVideoKey(key: String): VideoKey? {
        val parts = key.split('-')
        if (parts.size != 3) return null
        val codec = VideoCodec.entries.firstOrNull { it.name.equals(parts[0], ignoreCase = true) } ?: return null
        val side = parts[1].toIntOrNull() ?: return null
        val quality = VideoQuality.entries.firstOrNull { qualityTag(it) == parts[2] } ?: return null
        return VideoKey(codec, side, quality)
    }

    fun qualityTag(q: VideoQuality): String = when (q) {
        VideoQuality.HIGH -> "high"
        VideoQuality.STANDARD -> "std"
        VideoQuality.SMALL -> "small"
    }

    /**
     * The settings an earlier version's single preset and codec stand for:
     * the nearest of the new presets, not the same numbers. The old default
     * lands on Balanced, with the same size and quality as before; the old
     * smallest lands on Smallest, which goes a little further than it did;
     * and on a phone that passes the HEIC test, Auto writes HEIC where JPEG
     * was written before - the same photo in fewer bytes. The size estimates
     * learned under the old settings start again under the new ones. An
     * explicit HEVC choice is kept as a Custom video setting with HEVC,
     * because Auto would use H.264 on a phone without a hardware HEVC
     * encoder.
     */
    fun fromLegacy(preset: String?, codec: String?): Pair<PhotoSettings, VideoSettings> {
        val (photoPreset, videoPreset, legacy) = when (preset) {
            "BALANCED" -> Triple(PhotoPreset.BEST, VideoPreset.BEST, VideoPreset.BEST)
            "MAX_SAVER" -> Triple(PhotoPreset.SMALLEST, VideoPreset.SMALLEST, VideoPreset.SMALLEST)
            else -> Triple(PhotoPreset.BALANCED, VideoPreset.BALANCED, VideoPreset.BALANCED)
        }
        val photo = PhotoSettings(preset = photoPreset)
        val video = if (codec == VideoCodec.HEVC.name) {
            val base = VideoSettings(preset = legacy).spec()
            VideoSettings(
                preset = VideoPreset.CUSTOM,
                codec = VideoCodecChoice.HEVC,
                longSide = base.longSide,
                fpsCap = base.fpsCap,
                quality = base.quality,
                audioKbps = base.audioKbps,
                hdr = base.hdr
            )
        } else {
            VideoSettings(preset = videoPreset)
        }
        return photo to video
    }
}
