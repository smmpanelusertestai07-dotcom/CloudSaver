package com.pocketide.sync

import com.pocketide.core.AppJson
import com.pocketide.google.DriveFile
import com.pocketide.google.DriveStore
import com.pocketide.model.Lease
import com.pocketide.model.VaultIndex
import com.pocketide.vault.VaultCipher
import java.io.ByteArrayOutputStream

/** The newest index in Drive (null when the vault is still empty) and how we recognise it. */
internal data class RemoteSnapshot(val index: VaultIndex?, val mark: RemoteMark?)

/** Another phone holds the lease; nothing was written. */
internal class LeaseLostException(val holder: Lease, val snapshot: RemoteSnapshot) : Exception("Another phone holds the lease")

/**
 * The encrypted index file "index" in the Drive hidden folder. Drive has no compare-and-swap, so a
 * write is read-modify-write: read the newest index, apply this phone's change, write, then check
 * that Drive still holds what was written; if another phone wrote in between, do it again on top
 * of theirs. Metadata (checksum) is compared first, so an unchanged index is never downloaded.
 */
internal class RemoteIndex {

    suspend fun fetch(drive: DriveStore, cipher: VaultCipher, known: RemoteMark?, cached: VaultIndex?): RemoteSnapshot {
        val file = drive.find(NAME) ?: return RemoteSnapshot(null, null)
        if (known != null && cached != null && cached.revision == known.revision && sameContent(file, known)) {
            return RemoteSnapshot(cached, known)
        }
        val bytes = download(drive, file.id)
        val index = decode(cipher, bytes)
        return RemoteSnapshot(index, mark(file, index, bytes.size.toLong()))
    }

    /**
     * Writes [change] applied to the newest index. [requireLease] refuses to write when another
     * phone holds an unexpired lease (the normal sync); a takeover or a conflict copy passes false.
     */
    suspend fun commit(
        drive: DriveStore,
        cipher: VaultCipher,
        start: RemoteSnapshot,
        requireLease: (VaultIndex?) -> Lease?,
        change: (VaultIndex) -> VaultIndex,
        emptyIndex: () -> VaultIndex,
    ): RemoteSnapshot {
        var snapshot = start
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) snapshot = fetch(drive, cipher, snapshot.mark, snapshot.index)
            val current = snapshot.index
            if (current != null && current.schema > SCHEMA) throw SyncException(NEWER_APP)
            requireLease(current)?.let { throw LeaseLostException(it, snapshot) }
            val next = change(current ?: emptyIndex())
            val bytes = encode(cipher, next)
            val written = drive.uploadBytes(NAME, bytes, snapshot.mark?.id)
            val mark = mark(written, next, bytes.size.toLong())
            if (stillOurs(drive, cipher, mark)) return RemoteSnapshot(next, mark)
            snapshot = RemoteSnapshot(null, mark)
        }
        throw SyncException("Another phone keeps changing your data in Drive. Sync tries again soon.")
    }

    /** True when Drive still holds exactly the index this phone wrote. */
    private suspend fun stillOurs(drive: DriveStore, cipher: VaultCipher, mark: RemoteMark): Boolean {
        val file = drive.find(NAME) ?: return false
        if (file.id != mark.id) return false
        if (file.md5 != null && mark.md5 != null) return file.md5 == mark.md5
        val index = decode(cipher, download(drive, file.id))
        return index.revision == mark.revision && index.updatedAt == mark.updatedAt
    }

    fun decode(cipher: VaultCipher, bytes: ByteArray): VaultIndex =
        AppJson.decodeFromString(VaultIndex.serializer(), Codec.open(cipher, bytes).toString(Charsets.UTF_8))

    fun encode(cipher: VaultCipher, index: VaultIndex): ByteArray =
        Codec.seal(cipher, AppJson.encodeToString(VaultIndex.serializer(), index).toByteArray(Charsets.UTF_8))

    suspend fun download(drive: DriveStore, id: String): ByteArray {
        val out = ByteArrayOutputStream()
        drive.download(id, out)
        return out.toByteArray()
    }

    private fun sameContent(file: DriveFile, mark: RemoteMark): Boolean = when {
        file.id != mark.id -> false
        file.md5 != null && mark.md5 != null -> file.md5 == mark.md5
        file.modifiedTime != null && mark.modifiedTime != null -> file.modifiedTime == mark.modifiedTime && file.size == mark.bytes
        else -> false
    }

    private fun mark(file: DriveFile, index: VaultIndex, bytes: Long) =
        RemoteMark(file.id, file.md5, file.modifiedTime, index.revision, bytes, index.updatedAt)

    companion object {
        const val NAME = "index"
        const val SCHEMA = 1
        private const val ATTEMPTS = 4
        const val NEWER_APP = "Your data in Drive was saved by a newer PocketIDE. Update the app to keep syncing."
    }
}
