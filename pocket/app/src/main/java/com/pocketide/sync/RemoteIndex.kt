package com.pocketide.sync

import com.pocketide.core.AppJson
import com.pocketide.google.DriveFile
import com.pocketide.google.DriveRevision
import com.pocketide.google.DriveRevisions
import com.pocketide.google.DriveStore
import com.pocketide.model.Lease
import com.pocketide.model.VaultIndex
import com.pocketide.vault.VaultCipher
import java.io.ByteArrayOutputStream

/** The newest index in Drive (null when the vault is still empty) and how we recognise it. */
internal data class RemoteSnapshot(val index: VaultIndex?, val mark: RemoteMark?)

/** Another phone holds the lease; nothing of this phone's change was written. */
internal class LeaseLostException(val holder: Lease, val snapshot: RemoteSnapshot) : Exception("Another phone holds the lease")

/**
 * The encrypted index file "index" in the Drive hidden folder. Drive has no compare-and-swap, so a
 * write is read-modify-write, checked afterwards: the newest index is read just before each write
 * and the change applied to it; then Drive's list of the file's versions must show, just before
 * ours, the version that was read. Otherwise another phone wrote in between and ours replaced it,
 * so ours is made again on top of theirs. Metadata (checksum) is compared first, so an unchanged
 * index is never downloaded. What the index sends and receives counts in [budget] like any other
 * sync transfer, so Settings shows it and the daily mobile limit leaves less for new uploads.
 */
internal class RemoteIndex(private val budget: MeteredDataBudget? = null) {

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
     * Writes [change] applied to the newest index; [start] is only what this phone knew, never
     * trusted to be the newest. [requireLease] refuses to write when another phone holds an
     * unexpired lease (the normal sync); a takeover or a conflict copy passes false. When this
     * phone's write replaced another phone's that took the lease, theirs is put back, without
     * this phone's change, before the lost lease is reported.
     */
    suspend fun commit(
        drive: DriveStore,
        cipher: VaultCipher,
        start: RemoteSnapshot,
        requireLease: (VaultIndex?) -> Lease?,
        change: (VaultIndex) -> VaultIndex,
        emptyIndex: () -> VaultIndex,
    ): RemoteSnapshot {
        var known = start
        var undo: Undo? = null
        repeat(ATTEMPTS) {
            val head = newest(drive, cipher, known)
            val current = head.index
            val earlier = undo
            val base = earlier?.rebased(current) ?: current
            val holder = requireLease(base)
            val next = when {
                holder == null -> change(base ?: emptyIndex())
                // With the lease gone, a write that replaced another phone's is undone: theirs goes back alone.
                earlier != null -> earlier.rebased(current)
                else -> throw LeaseLostException(holder, head)
            }.above(current)
            val bytes = encode(cipher, next)
            val written = drive.uploadBytes(NAME, bytes, head.mark?.id)
            counted(bytes.size)
            val result = RemoteSnapshot(next, mark(written, next, bytes.size.toLong()))
            when (val check = check(drive, cipher, written, bytes, head)) {
                Check.Clean -> return if (holder == null) result else throw LeaseLostException(holder, result)
                is Check.WentOver -> undo = Undo.of(earlier, base, current, check.theirs, ours = next)
                Check.Moved -> Unit
            }
            known = result
        }
        throw SyncException("Another phone keeps changing your data in Drive. Sync tries again soon.")
    }

    /** The newest index, which this app version must be able to read. */
    private suspend fun newest(drive: DriveStore, cipher: VaultCipher, known: RemoteSnapshot): RemoteSnapshot {
        val head = fetch(drive, cipher, known.mark, known.index)
        if ((head.index?.schema ?: SCHEMA) > SCHEMA) throw SyncException(NEWER_APP)
        return head
    }

    /** What Drive's versions of the index say about a write this phone just made. */
    private sealed interface Check {
        /** Made directly on top of the version that was read. */
        data object Clean : Check

        /** Another phone wrote [theirs] between the read and the write, and this write replaced it. */
        data class WentOver(val theirs: VaultIndex) : Check

        /** Not the index other phones read, or not known: made again on top of the newest. */
        data object Moved : Check
    }

    private suspend fun check(drive: DriveStore, cipher: VaultCipher, written: DriveFile, bytes: ByteArray, read: RemoteSnapshot): Check {
        val readMark = read.mark
        val sameFile = readMark != null && written.id == readMark.id
        val revisions = drive.revisions
        val versions = if (sameFile) revisions?.revisionsOf(written.id).orEmpty() else emptyList()
        val before = versions.getOrNull(versions.indexOfLast { it.md5 != null && it.md5 == written.md5 } - 1)
        return when {
            // A new file (the vault was empty, or the file read was deleted): only the oldest index counts.
            readMark == null || !sameFile -> if (drive.find(NAME)?.id == written.id) Check.Clean else Check.Moved
            revisions == null || before == null || readMark.md5 == null -> if (holdsOurs(drive, written, bytes)) Check.Clean else Check.Moved
            before.md5 == readMark.md5 -> Check.Clean
            else -> replacedBy(revisions, cipher, written, before, read.index)
        }
    }

    /**
     * The version listed just before this phone's write, which the write replaced. Only a write made
     * on top of what was read can have been; an older one listed there (Drive merged or reordered
     * versions) changes nothing.
     */
    private suspend fun replacedBy(revisions: DriveRevisions, cipher: VaultCipher, written: DriveFile, before: DriveRevision, read: VaultIndex?): Check {
        val out = ByteArrayOutputStream()
        revisions.downloadRevision(written.id, before.id, out)
        counted(out.size())
        val theirs = decode(cipher, out.toByteArray())
        return if (read == null || theirs.revision > read.revision) Check.WentOver(theirs) else Check.Clean
    }

    /** True when Drive still holds exactly the index this phone wrote (for a store without versions). */
    private suspend fun holdsOurs(drive: DriveStore, written: DriveFile, bytes: ByteArray): Boolean {
        val file = drive.find(NAME)
        return when {
            file == null || file.id != written.id -> false
            file.md5 != null && written.md5 != null -> file.md5 == written.md5
            else -> download(drive, file.id).contentEquals(bytes)
        }
    }

    fun decode(cipher: VaultCipher, bytes: ByteArray): VaultIndex =
        AppJson.decodeFromString(VaultIndex.serializer(), Codec.open(cipher, bytes).toString(Charsets.UTF_8))

    fun encode(cipher: VaultCipher, index: VaultIndex): ByteArray =
        Codec.seal(cipher, AppJson.encodeToString(VaultIndex.serializer(), index).toByteArray(Charsets.UTF_8))

    suspend fun download(drive: DriveStore, id: String): ByteArray {
        val out = ByteArrayOutputStream()
        drive.download(id, out)
        counted(out.size())
        return out.toByteArray()
    }

    private fun counted(bytes: Int) {
        budget?.record(bytes.toLong(), MeteredDataBudget.KIND_SYNC)
    }

    /** This phone's write [ours] replaced another phone's; [asIf] is the index as if this phone had not written. */
    private class Undo(val asIf: VaultIndex, val ours: VaultIndex) {
        /** [asIf], with whatever was written after [ours], up to [head]. */
        fun rebased(head: VaultIndex?): VaultIndex = if (head == null) asIf else asIf.changedBy(IndexMerge.diff(ours, head), head)

        companion object {
            /**
             * After [ours], made on [base] (the version [read], or what an earlier replaced write was
             * made from), replaced [theirs]: as if this phone had not written, theirs is all there is,
             * or, for a write that was itself made again, [base] with their change to [read].
             */
            fun of(earlier: Undo?, base: VaultIndex?, read: VaultIndex?, theirs: VaultIndex, ours: VaultIndex): Undo {
                if (earlier == null || base == null) return Undo(theirs, ours)
                return Undo(base.changedBy(IndexMerge.diff(read ?: VaultIndex(updatedAt = 0), theirs), theirs), ours)
            }
        }
    }

    companion object {
        const val NAME = "index"
        const val SCHEMA = 1
        private const val ATTEMPTS = 4
        const val NEWER_APP = "Your data in Drive was saved by a newer PocketIDE. Update the app to keep syncing."
    }
}

/** Whether [file] still holds the index [mark] describes: by checksum, or by time and size without one. */
private fun sameContent(file: DriveFile, mark: RemoteMark): Boolean = when {
    file.id != mark.id -> false
    file.md5 != null && mark.md5 != null -> file.md5 == mark.md5
    file.modifiedTime != null && mark.modifiedTime != null -> file.modifiedTime == mark.modifiedTime && file.size == mark.bytes
    else -> false
}

/** How this phone recognises [index], stored in [file], the next time it reads Drive. */
private fun mark(file: DriveFile, index: VaultIndex, bytes: Long) =
    RemoteMark(file.id, file.md5, file.modifiedTime, index.revision, bytes, index.updatedAt)

/** [delta] made on this index, dated and keyed like [from]. */
private fun VaultIndex.changedBy(delta: IndexDelta, from: VaultIndex): VaultIndex = IndexMerge.apply(this, delta, from.updatedAt, from.keyGeneration)

/** A write always gets a revision above the one it replaces, so every phone sees it as new. */
private fun VaultIndex.above(head: VaultIndex?): VaultIndex = if (head != null && revision <= head.revision) copy(revision = head.revision + 1) else this
