package com.pocketide.sessions.transcripts

import com.pocketide.core.AppDirs
import com.pocketide.model.SessionRecord
import com.pocketide.projects.JsonFile
import com.pocketide.projects.SafeFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Finds each session's transcripts in its agent's room and keeps what was read of every file
 * between app starts: the next scan reads only the lines appended since, so a transcript of
 * hundreds of megabytes costs one full read, ever. A file that shrank or starts differently was
 * rewritten and is read again from the start.
 */
internal class TranscriptIndex(
    private val dirs: AppDirs,
    private val store: JsonFile<List<FileFacts>>,
    private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private var facts: MutableMap<String, FileFacts>? = null

    /** The transcripts of [sessions] on this phone, by session id; sessions without any are absent. */
    suspend fun scan(sessions: List<SessionRecord>): Map<String, SessionTranscript> = lock.withLock {
        withContext(io) {
            val known = loaded()
            val before = HashMap(known)
            val found = HashMap<String, SessionTranscript>()
            val scanned = HashSet<String>()
            for ((agentId, own) in sessions.groupBy { it.agentId }) {
                val format = TranscriptFormat.of(agentId) ?: continue
                found += format.locate(dirs.roomHome(agentId), own, Source(format, known))
                scanned += format.id
            }
            // Facts of vanished files go, but only for formats scanned now: a compressed Codex
            // thread takes over its plain file's facts during its own format's scan.
            known.values.removeAll { it.format in scanned && !SafeFiles.isFile(File(it.path)) }
            if (known != before) save(known)
            found
        }
    }

    /** Forgets what was read of these files (their session's phone copy was deleted). */
    suspend fun forget(files: Collection<File>) = lock.withLock {
        withContext(io) {
            val known = loaded()
            val paths = files.map { it.absolutePath }.toSet()
            if (known.keys.removeAll { it in paths || paths.any { root -> it.startsWith("$root/") } }) save(known)
        }
    }

    private fun loaded(): MutableMap<String, FileFacts> {
        facts?.let { return it }
        val read = try {
            store.read().orEmpty()
        } catch (unreadable: IOException) {
            emptyList()
        }
        return read.associateByTo(HashMap()) { it.path }.also { facts = it }
    }

    private fun save(known: Map<String, FileFacts>) {
        try {
            store.write(known.values.sortedBy { it.path })
        } catch (full: IOException) {
            // Only a cache: the next scan reads the files again.
        }
    }

    private class Source(private val format: TranscriptFormat, private val known: MutableMap<String, FileFacts>) : FactsSource {

        override fun read(file: File): FileFacts {
            val path = file.absolutePath
            val length = file.length()
            val old = known[path]
            val current = old?.takeIf { it.format == format.id && it.head.isNotEmpty() && it.offset <= length && headOf(file, it.head.length / 2) == it.head }
            if (current != null && current.offset == length) return current
            val start = current ?: FileFacts(path, format.id)
            val updated = try {
                readFrom(file, start)
            } catch (unreadable: IOException) {
                return old ?: start
            }
            known[path] = updated
            return updated
        }

        override fun carried(file: File, plain: File): FileFacts? {
            val path = file.absolutePath
            known[path]?.let { return it }
            val moved = known[plain.absolutePath]?.copy(path = path, head = "") ?: return null
            known[path] = moved
            return moved
        }

        private fun readFrom(file: File, start: FileFacts): FileFacts {
            val tally = Tally(start)
            val consumed = FileInputStream(file).use { stream ->
                stream.channel.position(start.offset)
                JsonLineReader(stream).use { reader ->
                    while (true) {
                        val line = reader.next() ?: break
                        format.count(line, parseObject(line), tally)
                    }
                    reader.consumed
                }
            }
            val head = start.head.ifEmpty { headOf(file, HEAD_BYTES) }
            return tally.facts(start, start.offset + consumed, head)
        }

        private fun headOf(file: File, bytes: Int): String {
            if (bytes <= 0) return ""
            val buffer = ByteArray(bytes)
            val read = FileInputStream(file).use { it.read(buffer) }
            if (read <= 0) return ""
            return buffer.copyOf(read).joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        }
    }

    private companion object {
        const val HEAD_BYTES = 64
    }
}
