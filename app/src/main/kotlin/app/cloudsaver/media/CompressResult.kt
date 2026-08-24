package app.cloudsaver.media

import java.io.File

/**
 * Outcome of compressing (or as-is copying) one media item into a temp file.
 *
 * [srcPixels] and [outPixels] are what the encoder actually read and actually
 * wrote, so the detail-kept figure the app shows is a measurement rather than
 * a claim about the preset. Both are zero when the pixel count is unknown -
 * an as-is copy of a format the app does not decode, for instance - and every
 * reader treats zero as "no figure to show" instead of as 0%.
 */
data class CompressResult(
    val file: File,
    val bytes: Long,
    val asIs: Boolean,
    val reason: String,
    val ext: String,
    val srcPixels: Long = 0,
    val outPixels: Long = 0
)
