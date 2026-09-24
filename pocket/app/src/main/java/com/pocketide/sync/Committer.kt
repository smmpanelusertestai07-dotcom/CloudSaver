package com.pocketide.sync

import com.pocketide.google.DriveException
import com.pocketide.google.DriveStore
import com.pocketide.model.SessionRecord
import com.pocketide.model.VaultIndex
import com.pocketide.model.VaultObject

internal enum class CommitMode {
    /** The normal sync: this phone holds the lease (or takes a free one) and renews it. */
    HOLDER,

    /** "Use here?": takes the lease even from another phone. */
    TAKEOVER,

    /** A phone without the lease records only its conflict copies and leaves the lease alone. */
    ADDITIVE,
}

/** Changes a caller adds to a commit besides the queue and this phone's own edits. */
internal data class CommitExtras(
    val sessions: List<SessionChange> = emptyList(),
    val eraseSessions: Set<String> = emptySet(),
    /** Files whose entries all go (removed on purpose on this phone). */
    val removeFiles: Set<String> = emptySet(),
    /** New versions of existing entries (re-encrypted with a newer key). */
    val replaceObjects: List<VaultObject> = emptyList(),
)

/**
 * Records in the index everything Drive has confirmed. Nothing is marked synced before: a queue
 * entry leaves the queue, and its file's synced length moves forward, only after the index that
 * names it was written and checked.
 */
internal class Committer(private val kit: SyncKit) {

    suspend fun commit(
        run: Run,
        drive: DriveStore,
        start: RemoteSnapshot,
        mode: CommitMode,
        extras: CommitExtras = CommitExtras(),
    ): RemoteSnapshot {
        val ports = kit.ports
        val now = run.now
        val entries = run.entries()
        val ready = ready(entries, start.index, mode)
        val readyIds = ready.map { it.id }.toSet()
        val conflicts = run.state.pendingConflicts.filter { record ->
            entries.filter { it.sessionId == record.id }.all { it.id in readyIds }
        }
        val additive = mode == CommitMode.ADDITIVE
        val sessions = if (additive) emptyList() else Diffs.sessions(ports.localSessions(), run.state.sessionMarks)
        val projects = if (additive) emptyList() else Diffs.projects(ports.localProjects(), run.state.projectMarks)
        val settingsJson = if (additive) null else SyncedSettings.of(ports.settings.settings.value).json().takeIf { it != run.state.settingsPushed }
        val (unattributed, attributed) = reattributions(start.index, run.state.tracks)
        val delta = IndexDelta(
            addObjects = ready.map { it.toObject(it.driveId) } + attributed + extras.replaceObjects,
            removeEntries = unattributed,
            replaceFiles = ready.filter { it.base || !it.kind.appendOnly }.map { fileKey(it.kind, it.agentId, it.path) }.toSet() + extras.removeFiles,
            sessions = sessions + conflicts.map { SessionChange.Upsert(it) } + extras.sessions,
            eraseSessions = extras.eraseSessions,
            projects = projects,
            settingsJson = settingsJson,
            lease = if (additive) null else LeasePolicy.lease(ports.device, now),
        )
        val renew = mode == CommitMode.TAKEOVER || (mode == CommitMode.HOLDER && LeasePolicy.needsRenewal(start.index, ports.device, now))
        if (delta.copy(lease = null).isEmpty && !renew) return start
        val keyGeneration = ports.keyGeneration()
        val result = kit.remote.commit(
            drive = drive,
            cipher = run.cipher,
            start = start,
            requireLease = { index -> if (mode == CommitMode.HOLDER) LeasePolicy.heldByOther(index, ports.device, now) else null },
            change = { base -> IndexMerge.apply(base, delta, now, keyGeneration) },
            emptyIndex = { VaultIndex(updatedAt = now) },
        )
        settle(run, start.index, result, ready, Pushed(sessions, projects, settingsJson, conflicts), extras, mode)
        if (extras.eraseSessions.isNotEmpty()) ports.sessionsErased(extras.eraseSessions.sorted())
        deleteUnused(run, drive)
        return result
    }

    /** Deletes Drive files the index no longer names. Stops quietly when Drive cannot be reached. */
    suspend fun deleteUnused(run: Run, drive: DriveStore) {
        if (run.state.driveDeletes.isEmpty()) return
        val left = LinkedHashMap(run.state.driveDeletes)
        // Identical content is stored once, so a file another entry still names is never deleted.
        val named = run.index?.objects.orEmpty().map { it.name }.toSet() + run.entries().map { it.name }
        try {
            for ((name, knownId) in run.state.driveDeletes) {
                if (!name.startsWith(OBJECT_PREFIX) || name in named) {
                    left.remove(name)
                    continue
                }
                try {
                    val id = knownId.ifEmpty { drive.find(name)?.id.orEmpty() }
                    if (id.isNotEmpty()) drive.delete(id)
                } catch (_: DriveException.Other) {
                    // Already gone, or refused: the daily sweep looks again.
                }
                left.remove(name)
            }
        } catch (_: DriveException) {
            // Offline or slowed down: the rest waits for the next run.
        } finally {
            run.state = run.state.copy(driveDeletes = left)
            run.save()
        }
    }

    /** Entries Drive confirmed, per file in order and without gaps (a later piece waits for its predecessor). */
    private fun ready(entries: List<QueueEntry>, index: VaultIndex?, mode: CommitMode): List<QueueEntry> {
        val candidates = if (mode == CommitMode.ADDITIVE) entries.filter { it.conflict } else entries
        val uploaded = candidates.filter { it.blob && it.driveId != null }.associate { it.name to it.driveId }
        val known = index?.objects?.filter { it.driveId != null }?.associate { it.name to it.driveId }.orEmpty()
        val out = ArrayList<QueueEntry>()
        for (group in candidates.groupBy { it.trackKey }.values) {
            for (e in group) {
                val id = e.driveId ?: if (!e.blob) uploaded[e.name] ?: known[e.name] else null
                if (id == null) break
                out += e.copy(driveId = id)
            }
        }
        return out
    }

    /** Entries recorded before their file was matched to a session get that session now. */
    private fun reattributions(index: VaultIndex?, tracks: Map<String, FileTrack>): Pair<Set<String>, List<VaultObject>> {
        if (index == null) return emptySet<String>() to emptyList()
        val removes = HashSet<String>()
        val adds = ArrayList<VaultObject>()
        for (o in index.objects) {
            if (o.sessionId != null) continue
            val track = tracks[o.fileKey] ?: continue
            val session = track.sessionId ?: continue
            if (o.name !in track.objects) continue
            removes += o.entryKey
            adds += o.copy(sessionId = session)
        }
        return removes to adds
    }

    private data class Pushed(
        val sessions: List<SessionChange>,
        val projects: List<com.pocketide.model.Project>,
        val settingsJson: String?,
        val conflicts: List<SessionRecord>,
    )

    private fun settle(run: Run, before: VaultIndex?, result: RemoteSnapshot, ready: List<QueueEntry>, pushed: Pushed, extras: CommitExtras, mode: CommitMode) {
        val after = result.index ?: return
        val now = run.now
        val tracks = run.state.tracks.toMutableMap()
        val (erasedNow, recorded) = ready.partition { it.sessionId in extras.eraseSessions }
        for (e in recorded) {
            kit.queue.remove(e.id)
            if (!e.conflict) tracks[e.trackKey] = Tracks.after(tracks[e.trackKey], e)
        }
        extras.removeFiles.forEach(tracks::remove)
        tracks.entries.removeAll { it.value.sessionId in extras.eraseSessions }
        // Uploaded for a session that is erased in this same write: its files go from Drive too.
        run.discard(erasedNow + run.entries().filter { it.sessionId in extras.eraseSessions })
        val remaining = run.entries()
        val keep = after.objects.map { it.name }.toSet() + remaining.map { it.name }
        val gone = before?.objects.orEmpty()
            .filter { it.name.startsWith(OBJECT_PREFIX) && it.name !in keep }
            .associate { it.name to it.driveId.orEmpty() }
        val state = run.state
        run.state = state.copy(
            tracks = tracks,
            sessionMarks = state.sessionMarks + pushed.sessions.associate { it.id to Diffs.mark(it) } - extras.eraseSessions,
            projectMarks = state.projectMarks + pushed.projects.associate { it.id to Diffs.projectHash(it) },
            settingsPushed = pushed.settingsJson ?: state.settingsPushed,
            heldLease = if (mode == CommitMode.ADDITIVE) state.heldLease else true,
            alignedRevision = if (mode == CommitMode.ADDITIVE) state.alignedRevision else after.revision,
            pendingConflicts = state.pendingConflicts - pushed.conflicts.toSet(),
            eraseQueue = state.eraseQueue - extras.eraseSessions,
            erased = state.erased + extras.eraseSessions.associateWith { now },
            driveDeletes = state.driveDeletes + gone,
            backedUpAt = state.backedUpAt + recorded.mapNotNull { it.sessionId }.associateWith { now } - extras.eraseSessions,
        )
        run.keepIndex(result)
        run.save()
    }

    companion object {
        const val OBJECT_PREFIX = "o-"
    }
}

/** How a file's track moves forward once an entry is in the index. */
internal object Tracks {
    fun after(track: FileTrack?, e: QueueEntry): FileTrack {
        val base = track ?: FileTrack(kind = e.kind, agentId = e.agentId, path = e.path, sessionId = e.sessionId)
        val moved = if (e.kind.appendOnly) {
            base.copy(
                syncedLength = e.offset + e.length,
                prefixSha256 = e.prefixSha256,
                objects = if (e.base) listOf(e.name) else base.objects + e.name,
            )
        } else {
            base.copy(syncedLength = e.length, prefixSha256 = e.sha256, objects = listOf(e.name))
        }
        return moved.copy(
            size = if (e.fileSize >= 0) e.fileSize else moved.size,
            modifiedAt = if (e.fileModifiedAt >= 0) e.fileModifiedAt else moved.modifiedAt,
            lastCreatedAt = maxOf(base.lastCreatedAt, e.createdAt),
            sessionId = base.sessionId ?: e.sessionId,
            onPhone = true,
            missingSince = -1,
        )
    }

    /** A track for a file rebuilt from Drive: the phone now has exactly the chain's content. */
    fun materialized(track: FileTrack?, chain: List<VaultObject>, assembled: Assembled, facts: FileFacts?): FileTrack {
        val first = chain.first()
        val base = track ?: FileTrack(kind = first.kind, agentId = first.agentId, path = first.path, sessionId = first.sessionId)
        return base.copy(
            syncedLength = assembled.length,
            prefixSha256 = assembled.sha256,
            objects = chain.map { it.name },
            size = facts?.size ?: assembled.length,
            modifiedAt = facts?.modifiedAt ?: -1,
            lastCreatedAt = maxOf(base.lastCreatedAt, chain.maxOf { it.createdAt }),
            sessionId = base.sessionId ?: first.sessionId,
            onPhone = true,
            missingSince = -1,
        )
    }
}
