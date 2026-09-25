package com.pocketide.sync

import com.pocketide.google.DriveStore
import com.pocketide.model.VaultObject
import com.pocketide.vault.VaultCipher
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** What was written: the file's length and the SHA-256 of its whole content. */
internal data class Assembled(val length: Long, val sha256: String)

/**
 * A synced file on the phone: [path] under [root], a room's home or work folder. Programs inside
 * Linux can change anything under [root], so nothing written there follows a link.
 */
internal class RoomFile(val root: File, val path: String, val file: File)

/**
 * Brings objects back from Drive: download, decrypt, gunzip, check each piece's SHA-256 and
 * length, and put the file together in a scratch folder of the queue, which only the app can
 * reach. Only when everything checked out is it renamed into the room, through real folders, so a
 * link a program in Linux planted there (at a temporary name, or in place of a folder) can never
 * make the app write anywhere else. Ciphertext and compressed plaintext live in the scratch folder
 * only for the moment they are needed.
 */
internal class Materializer(
    private val queue: UploadQueue,
    private val budget: MeteredDataBudget,
) {

    /**
     * Writes [target] as its first [keep] bytes (which must hash to [keepSha]) followed by
     * [pieces], in order and without gaps. [keep] = 0 rebuilds the file from Drive alone. Returns
     * null, having written nothing, when a folder on the way is a link or a file.
     */
    suspend fun assemble(
        drive: DriveStore,
        cipher: VaultCipher,
        target: RoomFile,
        pieces: List<VaultObject>,
        keep: Long = 0,
        keepSha: String? = null,
        kind: String = MeteredDataBudget.KIND_RESTORE,
    ): Assembled? = build(drive, cipher, target, pieces, keep, keepSha, kind).use { built ->
        built.assembled.takeIf { built.placeAt(target) }
    }

    /** The new content of [target], put together in the scratch folder; see [assemble]. */
    suspend fun build(
        drive: DriveStore,
        cipher: VaultCipher,
        target: RoomFile,
        pieces: List<VaultObject>,
        keep: Long = 0,
        keepSha: String? = null,
        kind: String = MeteredDataBudget.KIND_RESTORE,
    ): Built {
        var end = keep
        for (p in pieces) {
            if (p.offset != end) throw SyncException(BROKEN)
            end += p.length
        }
        val scratch = queue.scratch()
        try {
            val content = File(scratch, "content")
            val whole = Codec.newDigest()
            FileOutputStream(content).use { raw ->
                val out = DigestOutputStream(raw, whole)
                if (keep > 0) copyPrefix(target.file, keep, keepSha, out)
                for (p in pieces) fetchInto(drive, cipher, p, scratch, out, kind)
                out.flush()
                raw.fd.sync()
            }
            return Built(scratch, content, Assembled(end, Codec.hex(whole.digest())))
        } catch (e: Throwable) {
            scratch.deleteRecursively()
            throw e
        }
    }

    /** A file put together in the app's own scratch folder, waiting to be moved into a room. */
    internal class Built(private val scratch: File, private val content: File, val assembled: Assembled) : Closeable {

        /**
         * Renames the content over [target]. Every folder from the room down is a real folder
         * (missing ones are made), and just before the rename the target's folder must still
         * resolve inside the room, so one swapped for a link meanwhile is caught. Renaming
         * replaces a link at the target itself instead of writing through it. False, with
         * nothing moved, when a link or a file is in the way.
         */
        fun placeAt(target: RoomFile): Boolean {
            val names = target.path.split('/')
            val folder = realFolder(target.root, names.dropLast(1)) ?: return false
            if (!resolvesInside(folder, target.root)) return false
            Files.move(content.toPath(), folder.resolve(names.last()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        }

        override fun close() {
            scratch.deleteRecursively()
        }

        private fun realFolder(root: File, names: List<String>): Path? {
            var current = Files.createDirectories(root.toPath())
            for (name in names) {
                val next = current.resolve(name)
                when (attributes(next)?.isDirectory) {
                    true -> Unit
                    false -> return null
                    null -> try {
                        Files.createDirectory(next)
                    } catch (_: FileAlreadyExistsException) {
                        if (attributes(next)?.isDirectory != true) return null
                    }
                }
                current = next
            }
            return current
        }

        private fun resolvesInside(folder: Path, root: File): Boolean = try {
            folder.toRealPath().startsWith(root.toPath().toRealPath())
        } catch (_: IOException) {
            false
        }

        private fun attributes(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            null
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

    /** The phone's own first [keep] bytes; a link at the file is refused rather than followed. */
    private fun copyPrefix(source: File, keep: Long, keepSha: String?, out: OutputStream) {
        val check = Codec.newDigest()
        Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
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
