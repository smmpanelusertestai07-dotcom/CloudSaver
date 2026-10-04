package app.cloudsaver.media

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * An EXIF block for a HEIC file: the shooting date, camera and place.
 *
 * Android's EXIF writer cannot edit a HEIC file, but the HEIC writer takes a
 * ready-made EXIF block. The surest way to make one is the way every JPEG
 * already gets one: the tags are written into a tiny JPEG by the same library
 * that writes them for JPEG copies, and its EXIF segment is lifted out whole.
 */
object ExifBlock {

    /** "Exif" and two NULs: the start of every EXIF block. */
    private val EXIF_ID = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

    /** The block for [tags], with orientation normal, or null if none could be made. */
    fun build(tags: Map<String, String>, tempDir: File): ByteArray? {
        val carrier = File(tempDir, "exif_${System.nanoTime()}.jpg")
        return try {
            val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                FileOutputStream(carrier).use { pixel.compress(Bitmap.CompressFormat.JPEG, 50, it) }
            } finally {
                pixel.recycle()
            }
            val exif = ExifInterface(carrier.absolutePath)
            for ((tag, value) in tags) exif.setAttribute(tag, value)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
            payloadOf(carrier.readBytes())
        } catch (e: Exception) {
            null
        } finally {
            carrier.delete()
        }
    }

    /**
     * The APP1 segment's payload that starts with "Exif\0\0", or null.
     *
     * Walks the JPEG markers from the start of the file and stops at the
     * picture data: an EXIF block is always among the first segments.
     */
    fun payloadOf(jpeg: ByteArray): ByteArray? {
        if (jpeg.size < 4 || u(jpeg[0]) != 0xFF || u(jpeg[1]) != 0xD8) return null
        var i = 2
        while (i + 3 < jpeg.size) {
            if (u(jpeg[i]) != 0xFF) return null
            val marker = u(jpeg[i + 1])
            if (marker == 0xDA || marker == 0xD9) return null
            val length = (u(jpeg[i + 2]) shl 8) or u(jpeg[i + 3])
            if (length < 2) return null
            val start = i + 4
            val end = i + 2 + length
            if (end > jpeg.size) return null
            if (marker == 0xE1 && end - start >= EXIF_ID.size &&
                EXIF_ID.indices.all { jpeg[start + it] == EXIF_ID[it] }
            ) {
                return jpeg.copyOfRange(start, end)
            }
            i = end
        }
        return null
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF
}
