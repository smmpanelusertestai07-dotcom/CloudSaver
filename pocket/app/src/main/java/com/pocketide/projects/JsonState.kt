package com.pocketide.projects

import com.pocketide.core.AppJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One JSON document in the app's private storage, replaced atomically: the new content goes to a
 * temporary file that is flushed to the disk and then renamed over the old one, so a kill or a
 * full disk mid-write leaves the previous version whole.
 */
internal class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {

    /**
     * The stored value, or null when nothing was stored yet. A file that no longer parses is moved
     * aside (never silently overwritten) and reads as empty; a failing disk throws [IOException].
     */
    fun read(): T? {
        if (!file.isFile) return null
        val text = file.readText()
        return try {
            AppJson.decodeFromString(serializer, text)
        } catch (unreadable: IllegalArgumentException) {
            file.renameTo(File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}"))
            null
        }
    }

    fun write(value: T) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("No folder for ${file.name}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create ${dir.name}")
        val temp = File(dir, "${file.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(AppJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * A value kept in a [JsonFile] and observed as a [StateFlow]. Every change runs under one lock
 * and is on the disk before observers see it. The file is read on first use, off the main thread.
 */
internal class JsonState<T>(
    private val file: JsonFile<T>,
    private val empty: T,
    private val io: CoroutineDispatcher,
    private val normalize: (T) -> T = { it },
) {
    private val lock = Mutex()
    private val state = MutableStateFlow(empty)
    private var loaded = false

    val flow: StateFlow<T> = state.asStateFlow()

    /** The current value, loading it first when needed. */
    suspend fun current(): T = lock.withLock {
        ensureLoaded()
        state.value
    }

    /** Applies [change]; the new value is written only when it differs. Returns what [change] returns. */
    suspend fun <R> update(change: (T) -> Pair<T, R>): R = lock.withLock {
        ensureLoaded()
        val (next, result) = change(state.value)
        if (next != state.value) {
            withContext(io) { file.write(next) }
            state.value = next
        }
        result
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        state.value = withContext(io) { normalize(file.read() ?: empty) }
        loaded = true
    }
}
