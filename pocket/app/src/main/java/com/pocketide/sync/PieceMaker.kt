package com.pocketide.sync

import com.pocketide.core.Clock
import com.pocketide.model.ObjectKind
import com.pocketide.vault.VaultCipher
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.DigestInputStream

/** The newest state of a file this phone knows: what Drive has, plus anything still queued. */
internal data class Known(
    val end: Long,
    val sha: String?,
    val size: Long,
    val modifiedAt: Long,
    val pieces: Int,
    val lastCreatedAt: Long,
) {
    companion object {
        fun of(track: FileTrack?, queued: List<QueueEntry>): Known {
            val last = queued.maxWithOrNull(compareBy({ it.createdAt }, { it.offset }))
            val committedPieces = track?.objects?.size ?: 0
            val lastCreated = maxOf(track?.lastCreatedAt ?: 0, last?.createdAt ?: 0)
            if (last != null) {
                val end = if (last.kind.appendOnly) last.offset + last.length else last.length
                return Known(end, last.prefixSha256 ?: last.sha256, last.fileSize, last.fileModifiedAt, committedPieces + queued.size, lastCreated)
            }
            return Known(
                end = track?.syncedLength ?: 0,
                sha = track?.prefixSha256,
                size = track?.size ?: -1,
                modifiedAt = track?.modifiedAt ?: -1,
                pieces = committedPieces,
                lastCreatedAt = lastCreated,
            )
        }
    }
}

/** A Drive file (or a queued one) that already holds some content. */
internal data class SameContent(val name: String, val driveId: String?, val storedBytes: Long, val keyGeneration: Int)

internal sealed interface MakeResult {
    data object Unchanged : MakeResult

    /** Same content, new size or time on disk: only the track is updated. */
    data object Touched : MakeResult

    data class Queued(val entry: QueueEntry) : MakeResult

    /** The file changed while it was read; the next run tries again. */
    data object Changing : MakeResult
}

/**
 * Turns changed files into queue entries. A transcript's new bytes [end, size) become one piece
 * after the already-synced prefix is checked against its SHA-256 in the same read; when the file
 * shrank or its prefix changed, the whole file becomes a new base piece that supersedes the old
 * ones. Other files are whole, content-addressed objects: identical content is stored once.
 */
internal class PieceMaker(
    private val queue: UploadQueue,
    private val cipher: VaultCipher,
    private val keyGeneration: Int,
    private val clock: Clock,
) {

    fun transcript(c: Candidate, known: Known, compact: Boolean): MakeResult {
        val facts = c.facts
        if (facts.size == 0L && known.end == 0L) return MakeResult.Unchanged
        val end = try {
            uploadEnd(c)
        } catch (_: IOException) {
            return MakeResult.Changing
        }
        if (facts.size == known.size && facts.modifiedAt == known.modifiedAt && known.end >= end) return MakeResult.Unchanged
        if (end < known.end || compact || (known.end > 0 && known.sha == null)) return base(c, known, end)
        return try {
            appendOrRewrite(c, known, end)
        } catch (_: IOException) {
            MakeResult.Changing
        }
    }

    /**
     * Where the bytes to send end: the file's size, except for prompt histories, which go up to
     * their last complete line so a secret still being written is never split across two pieces
     * (each piece is masked on its own).
     */
    private fun uploadEnd(c: Candidate): Long {
        if (!TrackRules.needsSecretScan(c.path)) return c.facts.size
        c.openInRoom().use { f ->
            val tail = ByteArray(TAIL_BUFFER)
            var pos = minOf(c.facts.size, f.size())
            while (pos > 0) {
                val start = maxOf(0L, pos - tail.size)
                val n = (pos - start).toInt()
                val buffer = ByteBuffer.wrap(tail, 0, n)
                f.position(start)
                while (buffer.hasRemaining()) if (f.read(buffer) < 0) throw EOFException("The file shrank while it was read.")
                for (i in n - 1 downTo 0) if (tail[i] == NEWLINE) return start + i + 1
                pos = start
            }
            return 0
        }
    }

    private fun appendOrRewrite(c: Candidate, known: Known, end: Long): MakeResult {
        val prefix = Codec.newDigest()
        val raw = c.readInRoom()
        raw.use {
            val bounded = BoundedInputStream(raw, end)
            val prefixed = DigestInputStream(bounded, prefix)
            if (!prefixed.consume(known.end)) return MakeResult.Changing
            if (known.end > 0 && Codec.peek(prefix) != known.sha) return base(c, known, end)
            if (end == known.end) return MakeResult.Touched
            val id = Codec.objectName()
            val entry = queue.addBlob(cipher, id) { out ->
                val piece = Codec.newDigest()
                val counted = BoundedInputStream(prefixed, Long.MAX_VALUE)
                encrypt(DigestInputStream(masked(counted, c.path), piece), out)
                if (known.end + counted.count != end) {
                    null
                } else {
                    draft(c, known, id, offset = known.end, length = counted.count).copy(
                        sha256 = Codec.hex(piece.digest()),
                        prefixSha256 = Codec.hex(prefix.digest()),
                        base = known.end == 0L,
                    )
                }
            } ?: return MakeResult.Changing
            return MakeResult.Queued(entry)
        }
    }

    /** The whole file (up to [end]) as a new base piece. */
    private fun base(c: Candidate, known: Known, end: Long): MakeResult = try {
        val id = Codec.objectName()
        val entry = queue.addBlob(cipher, id) { out ->
            c.readInRoom().use { raw ->
                val local = Codec.newDigest()
                val sent = Codec.newDigest()
                val bounded = BoundedInputStream(raw, end)
                encrypt(DigestInputStream(masked(DigestInputStream(bounded, local), c.path), sent), out)
                if (bounded.count != end) {
                    null
                } else {
                    // The piece is checked by what was sent; the file on the phone by its own bytes.
                    draft(c, known, id, offset = 0, length = bounded.count)
                        .copy(sha256 = Codec.hex(sent.digest()), prefixSha256 = Codec.hex(local.digest()), base = true)
                }
            }
        }
        if (entry == null) MakeResult.Changing else MakeResult.Queued(entry)
    } catch (_: IOException) {
        MakeResult.Changing
    }

    /**
     * A whole file (memory, agent state, media). It is encrypted while hashed, in one read; when
     * Drive or the queue already holds the same content, the new blob is dropped for a reference.
     */
    fun whole(c: Candidate, known: Known, sameContent: (String) -> SameContent?): MakeResult {
        val facts = c.facts
        if (facts.size == known.size && facts.modifiedAt == known.modifiedAt) return MakeResult.Unchanged
        val id = Codec.objectName()
        val entry = try {
            queue.addBlob(cipher, id) { out ->
                c.readInRoom().use { raw ->
                    val digest = Codec.newDigest()
                    val bounded = BoundedInputStream(raw, facts.size)
                    encrypt(DigestInputStream(bounded, digest), out)
                    if (bounded.count != facts.size) null
                    else draft(c, known, id, offset = 0, length = bounded.count).copy(sha256 = Codec.hex(digest.digest()))
                }
            }
        } catch (_: IOException) {
            null
        } ?: return MakeResult.Changing
        if (entry.sha256 == known.sha) {
            queue.remove(entry.id)
            return MakeResult.Touched
        }
        val same = sameContent(entry.sha256) ?: return MakeResult.Queued(entry)
        queue.remove(entry.id)
        val reference = entry.copy(name = same.name, driveId = same.driveId, storedBytes = same.storedBytes, keyGeneration = same.keyGeneration)
        return MakeResult.Queued(queue.addReference(cipher, reference))
    }

    /** Project Variables and Secrets as one object; the plaintext never touches the disk. */
    fun secrets(bytes: ByteArray, knownSha: String?): QueueEntry? {
        val sha = Codec.sha256(bytes)
        if (sha == knownSha) return null
        val id = Codec.objectName()
        return queue.addBlob(cipher, id) { out ->
            encrypt(ByteArrayInputStream(bytes), out)
            QueueEntry(
                id = id, name = id, kind = ObjectKind.SECRETS, path = SECRETS_PATH, length = bytes.size.toLong(),
                sha256 = sha, keyGeneration = keyGeneration, createdAt = clock.now(), trackKey = SECRETS_KEY,
            )
        }
    }

    /** A whole-file copy of [c] under another session and path (a conflict copy). */
    fun copyOf(c: Candidate, sessionId: String?, path: String, conflictOf: String?): QueueEntry? = try {
        val id = Codec.objectName()
        queue.addBlob(cipher, id) { out ->
            c.readInRoom().use { raw ->
                val digest = Codec.newDigest()
                val bounded = BoundedInputStream(raw, c.facts.size)
                encrypt(DigestInputStream(masked(bounded, c.path), digest), out)
                val sha = Codec.hex(digest.digest())
                QueueEntry(
                    id = id, name = id, kind = c.kind, agentId = c.agentId, sessionId = sessionId, path = path,
                    length = bounded.count, sha256 = sha, keyGeneration = keyGeneration, createdAt = clock.now(),
                    base = c.kind.appendOnly, prefixSha256 = sha.takeIf { c.kind.appendOnly }, video = c.video,
                    trackKey = fileKey(c.kind, c.agentId, path), conflict = true, conflictOf = conflictOf,
                )
            }
        }
    } catch (_: IOException) {
        null
    }

    private fun draft(c: Candidate, known: Known, id: String, offset: Long, length: Long) = QueueEntry(
        id = id,
        name = id,
        kind = c.kind,
        agentId = c.agentId,
        sessionId = c.sessionId,
        path = c.path,
        offset = offset,
        length = length,
        sha256 = "",
        keyGeneration = keyGeneration,
        createdAt = maxOf(clock.now(), known.lastCreatedAt + 1),
        video = c.video,
        trackKey = c.key,
        fileSize = c.facts.size,
        fileModifiedAt = c.facts.modifiedAt,
    )

    private fun masked(input: InputStream, path: String): InputStream =
        if (TrackRules.needsSecretScan(path)) SecretMaskingInputStream(input) else input

    /** gzip, then age: what every object in Drive is. */
    private fun encrypt(plain: InputStream, out: OutputStream) {
        GzipCompressingInputStream(plain).use { cipher.encrypt(it, out) }
    }

    private companion object {
        const val TAIL_BUFFER = 8 * 1024
        const val NEWLINE = '\n'.code.toByte()
    }
}
