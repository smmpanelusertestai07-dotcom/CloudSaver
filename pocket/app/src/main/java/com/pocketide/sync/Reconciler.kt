package com.pocketide.sync

import com.pocketide.google.DriveStore
import com.pocketide.model.ObjectKind
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.model.VaultIndex
import java.io.File

/**
 * Conflict copies (§5.4): whatever this phone has that Drive's newest version does not is saved as
 * a separate "conflict copy" session with a new id, as Dropbox and Drive do. Nothing is overwritten
 * before it was copied.
 */
internal class Conflicts(private val kit: SyncKit) {

    /** One set of conflict copies: one new session per original session. */
    inner class Batch(private val run: Run, private val book: SessionBook) {
        private val maker = run.maker()
        private val ids = HashMap<String, String>()
        private val records = LinkedHashMap<String, SessionRecord>()

        /** Copies [c] into its conflict copy; false when the file could not be read. */
        fun copy(c: Candidate): Boolean {
            val original = c.sessionId
            val conflictId = ids.getOrPut(original ?: "room:${c.agentId}") {
                "${original ?: "room-${c.agentId}"}-conflict-${Codec.token(6)}"
            }
            val path = conflictPath(c, original, conflictId)
            val entry = maker.copyOf(c, if (original != null) conflictId else null, path, original) ?: return false
            if (original != null) {
                val r = records.getOrPut(conflictId) { record(book[original], original, conflictId, c) }
                val ownRef = book[original]?.agentSessionRef
                val isMainTranscript = ownRef != null && c.path.contains(ownRef)
                if (c.kind == ObjectKind.CHAT_PIECE && (r.agentSessionRef == null || isMainTranscript)) {
                    records[conflictId] = r.copy(agentSessionRef = entry.path)
                }
            }
            return true
        }

        /** The new conflict sessions, to be recorded with their files. */
        fun finish() {
            if (records.isEmpty()) return
            run.state = run.state.copy(pendingConflicts = run.state.pendingConflicts + records.values)
        }
    }

    /** This phone lost the lease: its unsynced data becomes conflict copies, then it stops syncing. */
    fun preservePending(run: Run, book: SessionBook) {
        val entries = run.entries().filter { !it.conflict }
        if (entries.isEmpty()) return
        val batch = Batch(run, book)
        val tracks = run.state.tracks.toMutableMap()
        val copied = ArrayList<QueueEntry>()
        for ((key, group) in entries.groupBy { it.trackKey }) {
            val first = group.first()
            if (first.kind == ObjectKind.SECRETS) {
                copied += group
                continue
            }
            val file = kit.scanner.locate(first.kind, first.agentId, first.path) ?: continue
            val facts = factsOf(file) ?: continue
            val agent = first.agentId ?: continue
            if (!batch.copy(Candidate(first.kind, agent, first.path, file, facts, first.sessionId, first.video))) continue
            copied += group
            tracks[key]?.let { tracks[key] = it.copy(preservedSize = facts.size, preservedModifiedAt = facts.modifiedAt) }
        }
        batch.finish()
        run.state = run.state.copy(tracks = tracks)
        run.discard(copied)
        run.save()
    }

    private fun record(original: SessionRecord?, originalId: String, conflictId: String, c: Candidate): SessionRecord {
        val now = kit.ports.clock.now()
        val device = kit.ports.device
        val base = original ?: SessionRecord(
            id = originalId, agentId = c.agentId, projectId = "", title = "Chat", branch = "",
            startedAt = now, lastActivityAt = now, deviceId = device.id,
        )
        return base.copy(
            id = conflictId,
            title = "${base.title} (conflict copy)",
            status = SessionStatus.CONFLICT_COPY,
            conflictOf = originalId,
            deletedAt = null,
            lastActivityAt = now,
            deviceId = device.id,
            backUp = true,
            pendingBytes = 0,
            pendingVideos = 0,
            agentSessionRef = null,
        )
    }

    companion object {
        const val FOLDER = ".pocketide/conflicts"

        /** Media stays in a media folder of its own; other files go under the room's conflict folder. */
        fun conflictPath(c: Candidate, original: String?, conflictId: String): String {
            if (c.kind == ObjectKind.MEDIA && original != null) {
                val parts = c.path.split('/').toMutableList()
                if (parts.size > 3) {
                    parts[2] = conflictId
                    return parts.joinToString("/")
                }
            }
            return "$FOLDER/$conflictId/${c.path}"
        }
    }
}

/**
 * After another phone wrote the index, brings this phone's files in line with it: a transcript the
 * other phone continued is extended from the new pieces; a file that went another way is replaced
 * by Drive's version, after this phone's own version was kept as a conflict copy.
 */
internal class Reconciler(private val kit: SyncKit, private val conflicts: Conflicts) {

    suspend fun reconcile(run: Run, index: VaultIndex, drive: DriveStore, book: SessionBook) {
        val files = Chains.files(index.objects)
        val queued = run.entries().filter { !it.conflict }.groupBy { it.trackKey }
        val known = run.index?.sessions?.map { it.id }?.toSet().orEmpty()
        val erasedElsewhere = known - index.sessions.map { it.id }.toSet()
        val batch = conflicts.Batch(run, book)
        val tracks = run.state.tracks.toMutableMap()
        var erased = run.state.erased
        for ((key, track) in run.state.tracks) {
            if (key == SECRETS_KEY) continue
            if (track.sessionId != null && track.sessionId in erasedElsewhere) {
                tracks.remove(key)
                erased = erased + (track.sessionId to run.now)
                run.discard(queued[key].orEmpty())
                continue
            }
            val chain = files[key].orEmpty()
            if (chain.map { it.name } == track.objects) continue
            val next = align(run, drive, track, chain, queued[key].orEmpty(), batch)
            if (next == null) tracks.remove(key) else tracks[key] = next
        }
        batch.finish()
        run.state = run.state.copy(tracks = tracks, erased = erased, alignedRevision = index.revision)
        run.save()
    }

    /** The track after aligning one file with [chain]; null when the file left Drive and the phone. */
    private suspend fun align(run: Run, drive: DriveStore, track: FileTrack, chain: List<com.pocketide.model.VaultObject>, queued: List<QueueEntry>, batch: Conflicts.Batch): FileTrack? {
        val file = kit.scanner.locate(track.kind, track.agentId, track.path) ?: return track
        val facts = factsOf(file)
        if (facts == null) {
            // Not on the phone: opening the session later brings Drive's version.
            return track.copy(onPhone = false, objects = chain.map { it.name }, syncedLength = Chains.length(chain), prefixSha256 = null)
        }
        val committedHere = facts.size == track.syncedLength && facts.modifiedAt == track.modifiedAt
        if (chain.isEmpty()) {
            run.discard(queued)
            if (committedHere) {
                file.delete()
                return null
            }
            return track.copy(objects = emptyList(), syncedLength = 0, prefixSha256 = null, size = -1, modifiedAt = -1)
        }
        val continues = track.kind.appendOnly && track.objects.isNotEmpty() && chain.map { it.name }.take(track.objects.size) == track.objects
        if (continues && committedHere) {
            val assembled = kit.materializer.assemble(drive, run.cipher, file, chain.drop(track.objects.size), keep = track.syncedLength, keepSha = track.prefixSha256)
            return Tracks.materialized(track, chain, assembled, factsOf(file))
        }
        val alreadyKept = facts.size == track.preservedSize && facts.modifiedAt == track.preservedModifiedAt
        if (!committedHere && !alreadyKept) {
            val agent = track.agentId ?: return track
            if (!batch.copy(Candidate(track.kind, agent, track.path, file, facts, track.sessionId, TrackRules.isVideo(file.name)))) return track
        }
        run.discard(queued)
        val assembled = kit.materializer.assemble(drive, run.cipher, file, chain)
        return Tracks.materialized(track, chain, assembled, factsOf(file))
    }

    /**
     * A file this phone has but does not track yet (a restore, or a lost state) that Drive already
     * holds: adopted when the file starts with exactly the chain's content, so it is not sent twice.
     */
    fun adopt(index: VaultIndex?, c: Candidate): FileTrack? {
        val chain = index?.let { Chains.chain(it.objects, c.key) }.orEmpty()
        if (chain.isEmpty()) return null
        val match = ChainCheck.prefixOf(c.file, chain) ?: return null
        val whole = match.length == c.facts.size
        return FileTrack(
            kind = c.kind, agentId = c.agentId, path = c.path, sessionId = c.sessionId ?: chain.first().sessionId,
            syncedLength = match.length, prefixSha256 = match.sha256, objects = chain.map { it.name },
            size = if (whole) c.facts.size else -1, modifiedAt = if (whole) c.facts.modifiedAt else -1,
            lastCreatedAt = chain.maxOf { it.createdAt }, headCwd = c.headCwd,
        )
    }
}

/** Checks a local file against a chain, piece by piece, by SHA-256. */
internal object ChainCheck {
    fun prefixOf(file: File, chain: List<com.pocketide.model.VaultObject>): Assembled? = try {
        java.io.FileInputStream(file).use { input ->
            val whole = Codec.newDigest()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            for (o in chain) {
                val piece = Codec.newDigest()
                var left = o.length
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) return null
                    piece.update(buffer, 0, n)
                    whole.update(buffer, 0, n)
                    left -= n
                }
                if (Codec.hex(piece.digest()) != o.sha256) return null
                total += o.length
            }
            Assembled(total, Codec.hex(whole.digest()))
        }
    } catch (_: java.io.IOException) {
        null
    }
}
