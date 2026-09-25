package com.pocketide.sessions.transcripts

import com.pocketide.sessions.TranscriptEntry
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.NOTE
import java.io.File
import java.io.FileInputStream

/**
 * The read-only view of a session: its conversation files read line by line (streamed, never
 * loaded whole) and merged in time order, keeping the newest [MAX_ENTRIES] entries.
 */
internal object TranscriptView {
    const val MAX_ENTRIES = 5_000

    const val COMPRESSED =
        "Codex compressed an older part of this chat to save space. Continue the chat in Codex to read that part."

    fun read(format: TranscriptFormat, conversations: List<File>): List<TranscriptEntry> {
        val entries = conversations.flatMap { file ->
            if (file.name.endsWith(CodexFormat.COMPRESSED_SUFFIX)) {
                listOf(TranscriptEntry(NOTE, COMPRESSED, file.lastModified()))
            } else {
                readFile(format, file)
            }
        }
        // Several files (after a /clear, a resumed thread) read as one chat, in time order.
        val ordered = if (conversations.size > 1) entries.sortedBy { it.at ?: Long.MIN_VALUE } else entries
        return ordered.takeLast(MAX_ENTRIES)
    }

    private fun readFile(format: TranscriptFormat, file: File): List<TranscriptEntry> {
        val newest = ArrayDeque<TranscriptEntry>()
        var lastAt: Long? = null
        FileInputStream(file).use { stream ->
            JsonLineReader(stream).use { reader ->
                while (true) {
                    val line = reader.next(partialLast = true) ?: break
                    val json = parseObject(line) ?: continue
                    for (entry in format.entries(json)) {
                        // A line without a time keeps its place after the line before it.
                        val placed = if (entry.at == null) entry.copy(at = lastAt) else entry
                        lastAt = placed.at
                        newest.addLast(placed)
                        if (newest.size > MAX_ENTRIES) newest.removeFirst()
                    }
                }
            }
        }
        return newest.toList()
    }
}
