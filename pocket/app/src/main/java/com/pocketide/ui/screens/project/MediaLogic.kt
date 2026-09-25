package com.pocketide.ui.screens.project

import com.pocketide.media.MediaKind
import java.security.MessageDigest

/** Size limits above which a file is not rendered, only listed (§6.12). */
object MediaLimits {
    const val IMAGE_BYTES: Long = 64L * 1024 * 1024
    const val PDF_BYTES: Long = 100L * 1024 * 1024
    const val HTML_BYTES: Long = 2L * 1024 * 1024
    const val TEXT_BYTES: Long = 1L * 1024 * 1024
    /** Longest side a viewer decodes an image or a PDF page at. */
    const val VIEW_SIDE_PX: Int = 2048

    fun renderable(kind: MediaKind, bytes: Long): Boolean = when (kind) {
        MediaKind.IMAGE -> bytes <= IMAGE_BYTES
        MediaKind.PDF -> bytes <= PDF_BYTES
        MediaKind.HTML -> bytes <= HTML_BYTES
        MediaKind.TEXT -> bytes <= TEXT_BYTES
        MediaKind.VIDEO, MediaKind.APK -> true
        MediaKind.OTHER -> false
    }
}

/**
 * The size to decode an image at so its longer side is at most [maxSide]: aspect ratio kept,
 * never enlarged, never zero.
 */
fun boundedSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val longest = maxOf(width, height)
    if (longest <= maxSide) return width to height
    val scale = maxSide.toDouble() / longest
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

/** A signing certificate's SHA-256, as Android and apksigner print it (AB:CD:…). */
fun certificateFingerprint(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it.toInt() and 0xff) }

/**
 * The signers an APK shows before it is installed. Android fills `signingInfo` except on API 29
 * and the first Android 13 release, where only the legacy signature list is set; an empty
 * result means the APK is not signed (or could not be read) and must not be installed.
 */
fun signerFingerprints(contentSigners: List<ByteArray>?, legacySignatures: List<ByteArray>?): List<String> {
    val certificates = contentSigners?.takeIf { it.isNotEmpty() } ?: legacySignatures.orEmpty()
    return certificates.filter { it.isNotEmpty() }.map(::certificateFingerprint).distinct()
}

/** The MIME type the share sheet gets for a file. */
fun shareMime(kind: MediaKind, name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (kind) {
        MediaKind.IMAGE -> when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/*"
        }
        MediaKind.VIDEO -> if (ext == "webm") "video/webm" else "video/mp4"
        MediaKind.PDF -> "application/pdf"
        MediaKind.HTML -> "text/html"
        MediaKind.TEXT -> "text/plain"
        MediaKind.APK -> APK_MIME
        MediaKind.OTHER -> "application/octet-stream"
    }
}

const val APK_MIME = "application/vnd.android.package-archive"

/** "3 files · 12 MB" for a media strip's heading. */
fun mediaSummary(count: Int, bytes: Long): String =
    "${WorkFormat.count(count, "file", "files")} · ${WorkFormat.bytes(bytes)}"
