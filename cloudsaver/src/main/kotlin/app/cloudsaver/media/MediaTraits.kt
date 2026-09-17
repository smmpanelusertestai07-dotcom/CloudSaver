package app.cloudsaver.media

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.InputStream

/**
 * Real-world file traits that change how an item must be handled:
 *  - HDR video (HLG / PQ / Dolby Vision) needs tone-mapping or HDR-capable output,
 *    never a silent washed-out SDR re-encode.
 *  - Motion photos and multi-picture / depth JPEGs carry an embedded video or
 *    depth map that re-compression would destroy, so they are copied as-is.
 */
object MediaTraits {

    enum class Hdr { NONE, HLG, PQ, DOLBY_VISION }

    /** Reads the video track's colour info; NONE when the file is plain SDR. */
    fun hdrOf(context: Context, uri: Uri): Hdr {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                if (mime.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true)) {
                    return Hdr.DOLBY_VISION
                }
                if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    return when (format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)) {
                        MediaFormat.COLOR_TRANSFER_HLG -> Hdr.HLG
                        MediaFormat.COLOR_TRANSFER_ST2084 -> Hdr.PQ
                        else -> Hdr.NONE
                    }
                }
                return Hdr.NONE
            }
            Hdr.NONE
        } catch (e: Exception) {
            Hdr.NONE
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** True when this device has an encoder that can write 10-bit HDR HEVC. */
    fun deviceSupportsHdrHevcEncode(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
                runCatching {
                    info.getCapabilitiesForType("video/hevc").profileLevels.any { pl ->
                        pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    }
                }.getOrDefault(false)
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Why a photo must be copied byte-for-byte, or null when it may be compressed.
     *
     * This runs on every single photo, so what it allocates matters more than
     * what it does. It used to take a fixed 512 KB buffer whatever the photo's
     * size, copy it to trim it, and then turn the whole thing into a 512
     * thousand character String - about a megabyte and a half of rubbish per
     * item, which on a phone holding tens of thousands of photos is what made
     * a run stutter and the heap thrash. The markers are plain ASCII, so they
     * are matched against the file's own bytes instead, and only as many bytes
     * as the file actually has are ever held.
     */
    fun embeddedPayloadReason(context: Context, uri: Uri): String? {
        val head = readChunk(context, uri, MAX_SCAN) ?: return null
        return when {
            MOTION_MARKER_BYTES.any { containsBytes(head, it) } -> "motion_photo"
            DEPTH_MARKER_BYTES.any { containsBytes(head, it) } -> "depth_photo"
            hasMpfSegment(head) -> "multi_picture"
            else -> null
        }
    }

    /** Google/Samsung motion photo (a still with an embedded MP4). */
    private val MOTION_MARKERS = listOf(
        "MotionPhoto",
        "MicroVideo",
        "MotionPhotoVersion",
        "MotionPhotoPresentationTimestampUs",
        "GCamera:MicroVideoOffset"
    )

    /** The same markers as bytes, so nothing has to be decoded to look. */
    private val MOTION_MARKER_BYTES = MOTION_MARKERS.map { it.toByteArray(Charsets.ISO_8859_1) }

    /** Portrait / depth data that only survives inside the original file. */
    private val DEPTH_MARKERS = listOf(
        "GDepth:Data",
        "GImage:Data",
        "Container:Directory",
        "http://ns.google.com/photos/1.0/depthmap/"
    )

    private val DEPTH_MARKER_BYTES = DEPTH_MARKERS.map { it.toByteArray(Charsets.ISO_8859_1) }

    private const val MAX_SCAN = 512 * 1024

    /**
     * How much is read before the buffer is grown.
     *
     * Motion-photo and depth XMP sits in the APP segments right behind the
     * start of the file, so most photos are answered by the first chunk and
     * never pay for the rest. Only a file that keeps going gets a bigger
     * buffer, and even then never more than [MAX_SCAN].
     */
    private const val SCAN_CHUNK = 64 * 1024

    /** The APP2 payload tag that marks a multi-picture JPEG: "MPF" and a NUL. */
    private val MPF_TAG = byteArrayOf(
        'M'.code.toByte(), 'P'.code.toByte(), 'F'.code.toByte(), 0
    )

    /**
     * Walks the JPEG marker chain looking for an APP2 "MPF" segment
     * (multi-picture: HDR pairs, bokeh source images).
     *
     * Not every marker carries a two-byte length, and assuming they all do is
     * how this walk used to lose its place. The restart markers 0xD0-0xD7 and
     * the TEM marker 0x01 stand entirely alone, and a file is allowed to pad
     * with any run of 0xFF bytes before the marker itself. Reading the two
     * bytes after one of those as a length gives a number out of the picture
     * data, and the walk then jumps to an arbitrary offset and either gives up
     * on the spot or wanders about reading noise. What a person would have
     * seen is a multi-picture photo - a phone's own HDR or portrait shot -
     * being re-compressed like an ordinary JPEG, throwing away the second
     * image it carries. Both shapes are now handled where they occur.
     */
    private fun hasMpfSegment(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if ((bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return false
        var i = 2
        while (i + 1 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) return false
            // Fill bytes: any run of 0xFF before the marker byte is padding.
            var m = i + 1
            while (m < bytes.size && (bytes[m].toInt() and 0xFF) == 0xFF) m++
            if (m >= bytes.size) return false
            val marker = bytes[m].toInt() and 0xFF
            // Start of scan / end of image: no more metadata segments follow.
            if (marker == 0xDA || marker == 0xD9) return false
            if (marker == 0x01 || (marker in 0xD0..0xD7)) {
                // Stands alone: no length, no payload, the next marker follows.
                i = m + 1
                continue
            }
            // Two length bytes must both be present before they can be read.
            if (m + 2 >= bytes.size) return false
            val length = ((bytes[m + 1].toInt() and 0xFF) shl 8) or (bytes[m + 2].toInt() and 0xFF)
            if (length < 2) return false
            if (marker == 0xE2 && m + 3 + MPF_TAG.size <= bytes.size) {
                if (regionMatches(bytes, m + 3, MPF_TAG)) return true
            }
            i = m + 1 + length
        }
        return false
    }

    /** True when [needle] appears anywhere in [haystack]. */
    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        val last = haystack.size - needle.size
        for (i in 0..last) {
            if (haystack[i] == needle[0] && regionMatches(haystack, i, needle)) return true
        }
        return false
    }

    /** True when [needle] sits at [at] in [bytes]; the caller checks the bounds. */
    private fun regionMatches(bytes: ByteArray, at: Int, needle: ByteArray): Boolean {
        for (j in needle.indices) {
            if (bytes[at + j] != needle[j]) return false
        }
        return true
    }

    private fun readChunk(context: Context, uri: Uri, max: Int): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { input -> readUpTo(input, max) }
    } catch (e: Exception) {
        null
    }

    /**
     * Reads at most [max] bytes, holding only as many as the file has.
     *
     * The old version took the full [max] up front - half a megabyte for a
     * thumbnail-sized photo - and then copied the whole thing again to trim
     * it. It starts small now and only grows when the file keeps going, so a
     * small photo costs a small buffer and nothing is copied twice.
     */
    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        var buffer = ByteArray(minOf(max, SCAN_CHUNK))
        var read = 0
        while (read < max) {
            if (read == buffer.size) {
                buffer = buffer.copyOf(minOf(max, buffer.size * 2))
            }
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }
}
