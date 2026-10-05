package app.cloudsaver.media

import android.content.Context
import android.os.Build
import app.cloudsaver.core.logic.FormatResolver
import app.cloudsaver.core.logic.MediaSettings
import app.cloudsaver.core.logic.PhotoFormat
import app.cloudsaver.core.logic.PhotoSpec
import app.cloudsaver.core.logic.VideoCodec
import app.cloudsaver.core.logic.VideoCodecResolver
import app.cloudsaver.core.logic.VideoSpec
import app.cloudsaver.data.prefs.Options

/**
 * What the current settings turn into on this phone, before any file is
 * looked at: the format an ordinary photo is written in and the codec an
 * ordinary 16:9 video gets.
 *
 * Settings show it ("16 MP, HEIC, quality 82"), and encode results are
 * grouped under it, so an estimate only ever quotes files that were made the
 * way new ones will be. One photo may still come out differently - an Ultra
 * HDR photo stays JPEG, a clip too large for the HEVC chip goes H.264 - and
 * its own details say so.
 */
object PlannedEncode {

    fun photoFormat(context: Context, spec: PhotoSpec): PhotoFormat =
        when (val d = FormatResolver.resolve(spec.format, HeicSupport.works(context), false, Build.VERSION.SDK_INT)) {
            is FormatResolver.Decision.Encode -> d.format
            is FormatResolver.Decision.AsIs -> PhotoFormat.JPEG
        }

    private fun plannedFps(spec: VideoSpec): Float = if (spec.fpsCap > 0) spec.fpsCap.toFloat() else 30f

    /**
     * The long side this phone holds [spec] to - 1080p's - or 0 when it makes
     * what is asked. "Keep" is planned for a 2160p source, the largest a
     * phone films.
     */
    fun videoHeldTo(spec: VideoSpec, smallestPhone: Boolean): Int {
        val asked = if (spec.longSide <= 0) 3840 else spec.longSide
        val held = EncoderCaps.holdsToFullHd(asked, asked * 9 / 16, plannedFps(spec), smallestPhone)
        return if (held) MediaSettings.FULL_HD else 0
    }

    /** The codec an ordinary clip gets, at the size the phone actually makes it. */
    fun videoCodec(spec: VideoSpec, smallestPhone: Boolean = false): VideoCodec {
        val held = videoHeldTo(spec, smallestPhone)
        val long = when {
            held > 0 -> held
            spec.longSide <= 0 -> 1920
            else -> spec.longSide
        }
        return VideoCodecResolver.resolve(
            spec.codec,
            VideoCodecResolver.Hardware(hevcFits = EncoderCaps.hevcFits(long, long * 9 / 16, plannedFps(spec)))
        )
    }

    fun photoKey(context: Context, options: Options): String {
        val spec = options.photo.spec()
        return MediaSettings.photoKey(photoFormat(context, spec), spec)
    }

    fun videoKey(options: Options): String {
        val spec = options.video.spec()
        return MediaSettings.videoKey(videoCodec(spec), spec)
    }
}
