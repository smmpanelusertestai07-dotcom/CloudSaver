package com.pocketide.sync

import com.pocketide.google.DriveStore
import com.pocketide.model.VaultObject
import com.pocketide.vault.VaultCipher
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** What was written: the file's length and the SHA-256 of its whole content. */
internal data class Assembled(val length: Long, val sha256: String)

/**
 * Brings objects back from Drive: download, decrypt, gunzip, check each piece's SHA-256 and
 * length, and write the file through a temporary file that replaces the target only when
 * everything checked out. Ciphertext and compressed plaintext live only in a scratch folder of the
 * queue for the moment they are needed.
 */
internal class Materializer(
    private val queue: UploadQueue,
    private val budget: MeteredDataBudget,
) {

    /**
     * Writes [target] as its first [keep] bytes (which must hash to [keepSha]) followed by
     * [pieces], in order and without gaps. [keep] = 0 rebuilds the file from Drive alone.
     */
    suspend fun assemble(
        drive: DriveStore,
        cipher: VaultCipher,
        target: File,
        pieces: List<VaultObject>,
        keep: Long = 0,
        keepSha: String? = null,
        kind: String = MeteredDataBudget.KIND_RESTORE,
    ): Assembled {
        var end = keep
        for (p in pieces) {
            if (p.offset != end) throw SyncException(BROKEN)
            end += p.length
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.pocketide-part")
        val whole = Codec.newDigest()
        val scratch = queue.scratch()
        try {
            FileOutputStream(temp).use { raw ->
                val out = DigestOutputStream(raw, whole)
                if (keep > 0) copyPrefix(target, keep, keepSha, out)
                for (p in pieces) fetchInto(drive, cipher, p, scratch, out, kind)
                out.flush()
                raw.fd.sync()
            }
            AtomicFiles.moveOver(temp, target)
            return Assembled(end, Codec.hex(whole.digest()))
        } finally {
            temp.delete()
            scratch.deleteRecursively()
        }
    }

    /** One object's plaintext in memory (small objects only, such as the Secrets). */
    suspend fun bytes(drive: DriveStore, cipher: VaultCipher, o: VaultObject, kind: String): ByteArray {
        val scratch = queue.scratch()
        try {
            val sink = java.io.ByteArrayOutputStream()
            fetchInto(drive, cipher, o, scratch, sink, kind)
            return sink.toByteArray()
        } finally {
            scratch.deleteRecursively()
        }
    }

    private suspend fun fetchInto(drive: DriveStore, cipher: VaultCipher, o: VaultObject, scratch: File, sink: OutputStream, kind: String) {
        val id = o.driveId ?: drive.find(o.name)?.id ?: throw SyncException(MISSING)
        val encrypted = File(scratch, "c")
        val compressed = File(scratch, "g")
        FileOutputStream(encrypted).use { drive.download(id, it) }
        budget.record(encrypted.length(), kind)
        FileInputStream(encrypted).use { input -> FileOutputStream(compressed).use { cipher.decrypt(input, it) } }
        encrypted.delete()
        val piece = Codec.newDigest()
        var length = 0L
        GZIPInputStream(FileInputStream(compressed), BUFFER).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                piece.update(buffer, 0, n)
                sink.write(buffer, 0, n)
                length += n
            }
        }
        compressed.delete()
        if (length != o.length || Codec.hex(piece.digest()) != o.sha256) throw SyncException(BROKEN)
    }

    private fun copyPrefix(source: File, keep: Long, keepSha: String?, out: OutputStream) {
        val check = Codec.newDigest()
        FileInputStream(source).use { input ->
            val bounded = BoundedInputStream(input, keep)
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = bounded.read(buffer)
                if (n < 0) break
                check.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
            if (bounded.count != keep) throw IOException("The file on the phone is shorter than expected")
        }
        if (keepSha != null && hex(check) != keepSha) throw IOException("The file on the phone changed")
    }

    private fun hex(d: MessageDigest) = Codec.hex(d.digest())

    companion object {
        private const val BUFFER = 64 * 1024
        const val BROKEN = "A file in Drive did not match its checksum. It was not written to the phone."
        const val MISSING = "A file is missing from Drive."
    }
}
