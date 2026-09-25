package com.pocketide.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * What a file really is, from its first bytes and never from its name alone (an agent can call
 * anything "photo.png"). Only the formats Android's own decoders render safely become IMAGE or
 * VIDEO; the name counts only to tell an APK from any other zip.
 */
object MediaSniffer {
    /** How many bytes of a file [kindOf] looks at. */
    const val HEAD_BYTES = 4096

    const val IMAGE_LIMIT = 50L * 1024 * 1024
    const val VIDEO_LIMIT = 500L * 1024 * 1024
    const val OTHER_LIMIT = 200L * 1024 * 1024

    private val PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = bytes(0xFF, 0xD8, 0xFF)
    private val EBML = bytes(0x1A, 0x45, 0xDF, 0xA3)
    private val ZIP = bytes(0x50, 0x4B, 0x03, 0x04)

    /** ISO-BMFF brands that are still images or QuickTime, not MP4 video. */
    private val NOT_MP4_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis", "qt  ")

    /** The first [HEAD_BYTES] of [file] (fewer when it is shorter). */
    fun head(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(HEAD_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val n = input.read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        buffer.copyOf(filled)
    }

    fun kindOf(name: String, head: ByteArray): MediaKind = when {
        head.startsWith(PNG) || head.startsWith(JPEG) || isGif(head) || isWebp(head) -> MediaKind.IMAGE
        isMp4(head) || isWebm(head) -> MediaKind.VIDEO
        head.startsWith("%PDF".toByteArray()) -> MediaKind.PDF
        head.startsWith(ZIP) -> if (name.lowercase(Locale.ROOT).endsWith(".apk")) MediaKind.APK else MediaKind.OTHER
        isHtml(head) -> MediaKind.HTML
        isText(head) -> MediaKind.TEXT
        else -> MediaKind.OTHER
    }

    /** The largest file of [kind] PocketIDE keeps. */
    fun limitFor(kind: MediaKind): Long = when (kind) {
        MediaKind.IMAGE -> IMAGE_LIMIT
        MediaKind.VIDEO -> VIDEO_LIMIT
        else -> OTHER_LIMIT
    }

    /** The extension a stored file gets, so every tool that goes by names agrees with its bytes. */
    fun extensionFor(kind: MediaKind, head: ByteArray, name: String): String {
        val given = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (kind) {
            MediaKind.IMAGE -> when {
                head.startsWith(PNG) -> "png"
                head.startsWith(JPEG) -> "jpg"
                isGif(head) -> "gif"
                else -> "webp"
            }
            MediaKind.VIDEO -> if (isWebm(head)) "webm" else "mp4"
            MediaKind.PDF -> "pdf"
            MediaKind.APK -> "apk"
            MediaKind.HTML -> if (given == "htm") "htm" else "html"
            MediaKind.TEXT -> given.takeIf { it.isNotEmpty() && it !in RENDERED } ?: "txt"
            MediaKind.OTHER -> given.takeIf { it.isNotEmpty() && it !in RENDERED } ?: "bin"
        }
    }

    fun isPngOrJpeg(head: ByteArray) = head.startsWith(PNG) || head.startsWith(JPEG)

    /** Extensions that promise a rendered format; a file whose bytes say otherwise must not keep them. */
    private val RENDERED = setOf("png", "jpg", "jpeg", "gif", "webp", "mp4", "webm", "pdf", "apk", "html", "htm", "svg")

    private fun isGif(head: ByteArray) = head.startsWith("GIF87a".toByteArray()) || head.startsWith("GIF89a".toByteArray())

    private fun isWebp(head: ByteArray) =
        head.startsWith("RIFF".toByteArray()) && head.size >= 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP"

    private fun isMp4(head: ByteArray): Boolean {
        if (head.size < 12 || String(head, 4, 4, Charsets.ISO_8859_1) != "ftyp") return false
        return String(head, 8, 4, Charsets.ISO_8859_1).lowercase(Locale.ROOT) !in NOT_MP4_BRANDS
    }

    /** WebM is Matroska with the "webm" document type; plain Matroska is not one of the safe formats. */
    private fun isWebm(head: ByteArray) =
        head.startsWith(EBML) && !String(head, Charsets.ISO_8859_1).contains("matroska")

    private fun isHtml(head: ByteArray): Boolean {
        val text = String(head, Charsets.ISO_8859_1).trimStart('\uFEFF', 'ï', '»', '¿', ' ', '\t', '\r', '\n')
            .lowercase(Locale.ROOT)
        return text.startsWith("<!doctype html") || text.take(HTML_WINDOW).contains("<html")
    }

    /** How far into a file an `<html` tag is looked for (after a comment or an XML line). */
    private const val HTML_WINDOW = 512

    /** Valid UTF-8 without NUL bytes; a character cut off by the end of [head] still counts. */
    private fun isText(head: ByteArray): Boolean {
        if (head.any { it == 0.toByte() }) return false
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        for (cut in 0..3) {
            if (cut > head.size) break
            try {
                decoder.reset().decode(ByteBuffer.wrap(head, 0, head.size - cut))
                return true
            } catch (_: CharacterCodingException) {
                // Try again without the last bytes: they may be half of one character.
            }
        }
        return false
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
