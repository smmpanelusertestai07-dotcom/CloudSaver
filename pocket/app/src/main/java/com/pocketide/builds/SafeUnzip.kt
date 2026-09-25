package com.pocketide.builds

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A zip that must not be unpacked: an entry escapes the folder, or it is too big. */
class UnsafeZipException(message: String) : IOException(message)

/**
 * Unpacks an Actions artifact. A workflow's output is untrusted: every entry must stay inside
 * [dest] (no "..", no absolute names), and the bytes actually written are counted against the
 * caps (the sizes a zip declares can lie).
 */
class SafeUnzip(
    private val maxEntries: Int = 2_000,
    private val maxEntryBytes: Long = 500L * 1024 * 1024,
    private val maxTotalBytes: Long = 1024L * 1024 * 1024,
) {
    /** Returns the files written, in the zip's order. */
    fun unzip(zip: InputStream, dest: File): List<File> {
        if (!dest.isDirectory && !dest.mkdirs()) throw IOException("Could not create ${dest.name}.")
        val root = dest.canonicalFile
        val written = ArrayList<File>()
        var total = 0L
        var entries = 0
        ZipInputStream(zip).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (++entries > maxEntries) throw UnsafeZipException("The results have more than $maxEntries files.")
                val target = target(root, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                var size = 0L
                target.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        total += n
                        if (size > maxEntryBytes) throw UnsafeZipException("${target.name} is larger than the ${maxEntryBytes / MB} MB a result may be.")
                        if (total > maxTotalBytes) throw UnsafeZipException("The results are larger than ${maxTotalBytes / MB} MB unpacked.")
                        out.write(buffer, 0, n)
                    }
                }
                written += target
            }
        }
        return written
    }

    private fun target(root: File, name: String): File {
        val clean = name.replace('\\', '/')
        if (clean.isEmpty() || clean.startsWith("/") || DRIVE.containsMatchIn(clean) || clean.split('/').any { it == ".." }) {
            throw UnsafeZipException("The results contain a file outside their folder: $name")
        }
        val target = File(root, clean).canonicalFile
        if (!target.toPath().startsWith(root.toPath()) || target == root) {
            throw UnsafeZipException("The results contain a file outside their folder: $name")
        }
        return target
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val MB = 1024 * 1024
        val DRIVE = Regex("^[A-Za-z]:")
    }
}
