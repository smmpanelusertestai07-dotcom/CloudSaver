package com.pocketide.sync

import com.pocketide.core.AppJson
import com.pocketide.vault.VaultCipher
import java.io.File
import java.io.OutputStream

/**
 * Encrypted pieces waiting for Drive, in `dirs.queue`. Each entry is a blob (`<id>.age`, already
 * compressed and encrypted) plus a sealed description (`<id>.meta`). The blob is written and synced
 * first, the description last, so after a kill or a reboot an entry either exists whole or not at
 * all, and nothing that was queued is lost.
 */
internal class UploadQueue(private val dir: File) {

    /** Removes half-written files and scratch folders left by a kill. */
    fun recover() {
        val files = dir.listFiles() ?: return
        val metas = files.filter { it.name.endsWith(META) }.map { it.name.removeSuffix(META) }.toSet()
        for (f in files) {
            val name = f.name
            when {
                name.endsWith(".part") -> f.delete()
                name.endsWith(BLOB) && name.removeSuffix(BLOB) !in metas -> f.delete()
                name.startsWith(TMP_PREFIX) -> f.deleteRecursively()
            }
        }
    }

    fun entries(cipher: VaultCipher): List<QueueEntry> {
        val files = dir.listFiles { f -> f.name.endsWith(META) } ?: return emptyList()
        return files.mapNotNull { meta ->
            val entry = runCatching {
                AppJson.decodeFromString(QueueEntry.serializer(), Codec.open(cipher, meta.readBytes()).toString(Charsets.UTF_8))
            }.getOrNull()
            if (entry != null && entry.blob && !blobFile(entry.id).isFile) {
                meta.delete()
                null
            } else {
                entry
            }
        }.sortedWith(compareBy({ it.createdAt }, { it.offset }, { it.id }))
    }

    fun blobFile(id: String) = File(dir, id + BLOB)

    /**
     * Writes a blob with [write], which encrypts into the stream and returns the finished entry (its
     * hashes are known only once everything was read), or null when the source changed while it
     * was read. Only then is the entry recorded.
     */
    fun addBlob(cipher: VaultCipher, id: String, write: (OutputStream) -> QueueEntry?): QueueEntry? {
        dir.mkdirs()
        val blob = blobFile(id)
        var written: QueueEntry? = null
        AtomicFiles.write(blob) { out -> written = write(out) }
        val entry = written
        if (entry == null) {
            blob.delete()
            return null
        }
        val stored = entry.copy(id = id, blob = true, storedBytes = blob.length())
        writeMeta(cipher, stored)
        return stored
    }

    /** An entry that reuses a Drive file with the same content: nothing to upload. */
    fun addReference(cipher: VaultCipher, entry: QueueEntry): QueueEntry {
        dir.mkdirs()
        val reference = entry.copy(id = REF_PREFIX + Codec.token(20), blob = false)
        writeMeta(cipher, reference)
        return reference
    }

    fun update(cipher: VaultCipher, entry: QueueEntry) = writeMeta(cipher, entry)

    fun remove(id: String) {
        File(dir, id + META).delete()
        blobFile(id).delete()
    }

    fun clear() {
        dir.deleteRecursively()
    }

    /** A scratch folder for downloads; [recover] removes it if a run is killed. */
    fun scratch(): File = File(dir, TMP_PREFIX + Codec.token(8)).apply { mkdirs() }

    private fun writeMeta(cipher: VaultCipher, entry: QueueEntry) {
        val json = AppJson.encodeToString(QueueEntry.serializer(), entry).toByteArray(Charsets.UTF_8)
        AtomicFiles.write(File(dir, entry.id + META), Codec.seal(cipher, json))
    }

    private companion object {
        const val BLOB = ".age"
        const val META = ".meta"
        const val TMP_PREFIX = "tmp-"
        const val REF_PREFIX = "r-"
    }
}
