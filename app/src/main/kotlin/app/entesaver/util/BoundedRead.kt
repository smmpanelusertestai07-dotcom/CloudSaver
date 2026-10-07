package app.entesaver.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads a stream whole, but only up to a size.
 *
 * Restore opens whatever file the person picks, and the picker cannot be
 * narrowed to backups. A video chosen by mistake used to be read into memory
 * whole, and on a small phone that is an OutOfMemoryError - an Error, not an
 * Exception, so nothing caught it and the app closed instead of saying the
 * file was not a backup. A provider does not always report a size, so the
 * limit is counted while reading rather than asked for first.
 */
object BoundedRead {

    /** Larger than any history file this app writes, far below a phone's heap. */
    const val MAX_BACKUP_BYTES: Long = 64L * 1024 * 1024

    private const val CHUNK = 64 * 1024

    /** The bytes of [input], or null as soon as it passes [maxBytes]. */
    fun readAtMost(input: InputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
