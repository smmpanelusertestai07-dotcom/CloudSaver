package com.pocketide.sync

import com.pocketide.core.AgentFiles
import com.pocketide.core.AppDirs
import com.pocketide.rooms.RoomFiles
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels

/**
 * Files that can run code ([AgentFiles.mayCarryCode]). A skill, subagent or command an agent
 * wrote with hooks, tool servers or inline commands would otherwise follow the owner to every
 * phone, and into the room restored after a reinstall, and run there with no one asked. One goes
 * to Drive only when nothing in it can run code ([AgentFiles.codeIn]), when Drive already holds
 * that content, or when the owner kept exactly this version in Your data; until then it waits on
 * the phone ([SyncState.held]). What is queued must be the very content judged: a file changed
 * between the check and the copy is not sent.
 */
internal class CodeGate(held: Map<String, HeldMark>, private val kept: Map<String, String>) {
    private val held = held.toMutableMap()

    sealed interface Verdict {
        /** Not such a file, or unchanged since it was last sent: nothing to judge. */
        data object Free : Verdict

        /** Waits for the owner on this phone, or could not be read whole just now. */
        data object Stays : Verdict

        /** May go, as exactly this content. */
        data class Goes(val sha256: String) : Verdict
    }

    fun judge(c: Candidate, known: Known): Verdict {
        if (!AgentFiles.mayCarryCode(c.path)) return Verdict.Free
        if (waiting(held, c)) return Verdict.Stays
        if (c.facts.size == known.size && c.facts.modifiedAt == known.modifiedAt) return Verdict.Free.also { held.remove(c.key) }
        val read = Reading.of(c) ?: return Verdict.Stays
        val reasons = AgentFiles.codeIn(c.path, read.text)
        val goes = reasons.isEmpty() || kept[c.key] == read.sha256 || known.sha == read.sha256
        if (goes) {
            held.remove(c.key)
        } else {
            held[c.key] = HeldMark(c.agentId, c.path, read.sha256, c.facts.size, c.facts.modifiedAt, reasons, read.shown)
        }
        return if (goes) Verdict.Goes(read.sha256) else Verdict.Stays
    }

    /** [result], unless what it queued is not the content judged: then the file changed between the two reads. */
    fun check(verdict: Verdict, result: MakeResult, run: Run): MakeResult {
        if (verdict !is Verdict.Goes || result !is MakeResult.Queued || result.entry.sha256 == verdict.sha256) return result
        run.discard(listOf(result.entry))
        return MakeResult.Changing
    }

    /** What still waits, of the files [seen] in this scan. */
    fun held(seen: Set<String>): Map<String, HeldMark> = held.filterKeys { it in seen }

    companion object {
        /** The version that waits, still as it was found: it is not read again until it changes. */
        fun waiting(held: Map<String, HeldMark>, c: Candidate): Boolean =
            held[c.key]?.let { it.size == c.facts.size && it.modifiedAt == c.facts.modifiedAt } == true

        fun publish(run: Run) {
            run.kit.flows.held.value = run.state.held.values
                .sortedWith(compareBy({ it.agentId }, { it.path }))
                .map { HeldFile(it.agentId, it.path, it.sha256, it.reasons, it.text) }
        }

        /**
         * Deletes [mark]'s file from its room, never through a link. True once that version is not
         * there any more (it was deleted now, or had gone); false when the file now holds another.
         */
        fun remove(dirs: AppDirs, mark: HeldMark): Boolean {
            val room = RoomFiles(dirs.roomHome(mark.agentId), guardSecrets = true)
            val now = room.open(mark.path)?.use { Channels.newInputStream(it).sha256() }
            if (now == mark.sha256) room.delete(mark.path)
            return now == null || now == mark.sha256
        }

        private fun InputStream.sha256(): String {
            val digest = Codec.newDigest()
            val buffer = ByteArray(BUFFER)
            var n = read(buffer)
            while (n >= 0) {
                digest.update(buffer, 0, n)
                n = read(buffer)
            }
            return Codec.hex(digest.digest())
        }

        private const val BUFFER = 64 * 1024
    }
}

/** One read of a file to judge it: its SHA-256, its whole text when it is short enough, and what the owner is shown. */
private class Reading(val sha256: String, val text: String?, val shown: String) {
    companion object {
        /** Longer files are not read through as text; one that must be counts as code. */
        private const val TEXT_LIMIT = 1 shl 20
        private const val SHOWN_CHARS = 4_000
        private const val BUFFER = 64 * 1024

        /** A file with a zero byte is not text, and is not shown as such. */
        private const val NUL: Byte = 0

        /** Null when the file cannot be read, or is no longer as the scan found it. */
        fun of(c: Candidate): Reading? = try {
            c.readInRoom().use { raw ->
                val input = BoundedInputStream(raw, c.facts.size)
                val digest = Codec.newDigest()
                val head = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                var n = input.read(buffer)
                while (n >= 0) {
                    digest.update(buffer, 0, n)
                    head.write(buffer, 0, minOf(n, maxOf(0, TEXT_LIMIT - head.size())))
                    n = input.read(buffer)
                }
                if (input.count == c.facts.size) read(Codec.hex(digest.digest()), head.toByteArray(), input.count <= TEXT_LIMIT) else null
            }
        } catch (_: IOException) {
            null
        }

        private fun read(sha256: String, head: ByteArray, whole: Boolean): Reading {
            val text = String(head, Charsets.UTF_8)
            val shown = if (head.contains(NUL)) "" else text.take(SHOWN_CHARS)
            return Reading(sha256, text.takeIf { whole }, shown)
        }
    }
}
