package com.pocketide.sync

import com.pocketide.vault.VaultCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Hashing, compression and naming shared by the whole sync module. */
internal object Codec {
    private val random = SecureRandom()
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4])
            append(HEX[v and 0x0f])
        }
    }

    fun sha256(bytes: ByteArray): String = hex(newDigest().digest(bytes))

    /** The hex digest so far, without ending [digest]. */
    fun peek(digest: MessageDigest): String = hex((digest.clone() as MessageDigest).digest())

    /** An opaque Drive name: "o-" and 130 random bits. Names reveal nothing about the content. */
    fun objectName(): String = "o-" + token(26)

    fun token(length: Int): String = buildString(length) {
        repeat(length) { append(BASE32[random.nextInt(BASE32.length)]) }
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 3 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    /** Small records (the index, local state): gzip, then the vault's age encryption. */
    fun seal(cipher: VaultCipher, plain: ByteArray): ByteArray = cipher.encryptBytes(gzip(plain))

    fun open(cipher: VaultCipher, sealed: ByteArray): ByteArray = gunzip(cipher.decryptBytes(sealed))

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * Gzip as a stream to read from, so a file can be compressed and handed to the cipher's
 * `encrypt(InputStream, OutputStream)` in one pass, without a helper thread or a plaintext copy on
 * disk. Output is a standard gzip member (RFC 1952).
 */
internal class GzipCompressingInputStream(source: InputStream, level: Int = Deflater.DEFAULT_COMPRESSION) : InputStream() {
    private val crc = CRC32()
    private var total = 0L
    private val counted = object : FilterInputStream(source) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                crc.update(b)
                total++
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                crc.update(b, off, n)
                total += n
            }
            return n
        }
    }
    private val deflater = Deflater(level, true)
    private val body = DeflaterInputStream(counted, deflater, BUFFER)
    private var stage = Stage.HEADER
    private var chunk: ByteArray = HEADER
    private var pos = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            when (stage) {
                Stage.HEADER, Stage.TRAILER -> {
                    if (pos < chunk.size) {
                        val n = minOf(len, chunk.size - pos)
                        System.arraycopy(chunk, pos, b, off, n)
                        pos += n
                        return n
                    }
                    stage = if (stage == Stage.HEADER) Stage.BODY else Stage.DONE
                }
                Stage.BODY -> {
                    val n = body.read(b, off, len)
                    if (n > 0) return n
                    chunk = trailer()
                    pos = 0
                    stage = Stage.TRAILER
                }
                Stage.DONE -> return -1
            }
        }
    }

    override fun close() {
        try {
            body.close()
        } finally {
            deflater.end()
        }
    }

    private fun trailer(): ByteArray {
        val c = crc.value
        val size = total and 0xffffffffL
        return ByteArray(8) { i -> ((if (i < 4) c ushr (8 * i) else size ushr (8 * (i - 4))) and 0xff).toByte() }
    }

    private enum class Stage { HEADER, BODY, TRAILER, DONE }

    private companion object {
        const val BUFFER = 64 * 1024
        val HEADER = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xff.toByte())
    }
}

/** Reads at most [limit] bytes of [source] and counts what passed through. */
internal class BoundedInputStream(source: InputStream, private val limit: Long) : FilterInputStream(source) {
    var count = 0L
        private set

    override fun read(): Int {
        if (count >= limit) return -1
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (count >= limit) return -1
        val n = super.read(b, off, minOf(len.toLong(), limit - count).toInt())
        if (n > 0) count += n
        return n
    }

    override fun skip(n: Long): Long = 0
}

/** Reads exactly [length] bytes, discarding them; false when the stream ended first. */
internal fun InputStream.consume(length: Long): Boolean {
    val buffer = ByteArray(64 * 1024)
    var left = length
    while (left > 0) {
        val n = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (n < 0) return false
        left -= n
    }
    return true
}

/** Size and time of a regular file, without following symbolic links (an agent may plant one). */
internal data class FileFacts(val size: Long, val modifiedAt: Long)

internal fun factsOf(file: File): FileFacts? = try {
    val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (attrs.isRegularFile) FileFacts(attrs.size(), attrs.lastModifiedTime().toMillis()) else null
} catch (_: IOException) {
    null
}

internal fun isRealDirectory(file: File): Boolean = try {
    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory
} catch (_: IOException) {
    false
}

/**
 * Resolves [relative] under [base] and refuses anything that could leave it: absolute paths,
 * "..", odd characters, or a symbolic link on the way that points elsewhere.
 */
internal fun safeChild(base: File, relative: String): File? {
    if (relative.isEmpty() || relative.startsWith("/") || relative.contains('\\') || relative.contains('\u0000')) return null
    val parts = relative.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
    val root = runCatching { base.canonicalFile }.getOrNull() ?: return null
    val candidate = File(root, relative)
    val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
    return if (canonical.path.startsWith(root.path + File.separator)) candidate else null
}

internal fun isSafeName(value: String?): Boolean = value != null && value.matches(Regex("[A-Za-z0-9._-]{1,128}")) && value != "." && value != ".."

/** Writes through a temporary file, syncs it to disk and renames it over [target]. */
internal object AtomicFiles {
    fun write(target: File, bytes: ByteArray) = write(target) { it.write(bytes) }

    fun write(target: File, body: (OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        FileOutputStream(temp).use { out ->
            body(out)
            out.flush()
            out.fd.sync()
        }
        moveOver(temp, target)
    }

    fun moveOver(source: File, target: File) {
        if (source.renameTo(target)) return
        target.delete()
        if (!source.renameTo(target)) throw IOException("Could not save ${target.name}")
    }
}
