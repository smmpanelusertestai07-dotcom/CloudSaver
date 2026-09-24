package com.pocketide.sync

import com.pocketide.core.AppDirs
import com.pocketide.core.AppJson
import com.pocketide.model.VaultIndex
import com.pocketide.vault.VaultCipher
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * The engine's memory on this phone, sealed with the vault key: its [SyncState] and a copy of the
 * last index seen in Drive, so the app starts offline and knows what Drive holds.
 */
internal class SyncRepository(dirs: AppDirs) {
    private val dir = File(dirs.vault, "sync")
    private val stateFile = File(dir, "state.bin")
    private val indexFile = File(dir, "index.bin")

    fun loadState(cipher: VaultCipher): SyncState = read(stateFile, cipher, SyncState.serializer()) ?: SyncState()

    fun saveState(cipher: VaultCipher, state: SyncState) = write(stateFile, cipher, SyncState.serializer(), state)

    fun loadIndex(cipher: VaultCipher): VaultIndex? = read(indexFile, cipher, VaultIndex.serializer())

    fun saveIndex(cipher: VaultCipher, index: VaultIndex) = write(indexFile, cipher, VaultIndex.serializer(), index)

    fun wipe() {
        dir.deleteRecursively()
    }

    /** A damaged or unreadable copy is treated as missing: Drive and the files on disk rebuild it. */
    private fun <T> read(file: File, cipher: VaultCipher, serializer: KSerializer<T>): T? {
        if (!file.isFile) return null
        return runCatching {
            AppJson.decodeFromString(serializer, Codec.open(cipher, file.readBytes()).toString(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun <T> write(file: File, cipher: VaultCipher, serializer: KSerializer<T>, value: T) {
        val json = AppJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        AtomicFiles.write(file, Codec.seal(cipher, json))
    }
}
