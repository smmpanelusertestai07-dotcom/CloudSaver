package com.pocketide.sync

import com.pocketide.google.DriveException
import com.pocketide.google.DriveStore
import com.pocketide.model.Lease
import com.pocketide.model.ObjectKind
import com.pocketide.model.VaultIndex

internal data class PassOptions(
    /** "Upload now" for these sessions only; their videos may use mobile data. */
    val only: Set<String>? = null,
    /** Called before a large upload, so a worker can move to the foreground. */
    val onLargeUpload: suspend () -> Unit = {},
    /** No new upload starts after this time; the rest waits for the next run. */
    val deadline: Long = Long.MAX_VALUE,
)

internal enum class PassOutcome { DONE, OFFLINE, LOCKED, WAITING, BLOCKED }

/** Why uploads stopped in a run. */
internal data class UploadReport(
    val uploaded: Int = 0,
    val googleFull: Boolean = false,
    val shareFull: Boolean = false,
    val blocked: String? = null,
)

/**
 * One sync: new data is queued first (encrypted, on the phone, even offline), then the lease is
 * checked, another phone's changes are brought in, the queue goes up to Drive, and the index
 * records what Drive confirmed.
 */
internal class SyncPass(
    private val kit: SyncKit,
    private val committer: Committer,
    private val conflicts: Conflicts,
    private val reconciler: Reconciler,
    private val notices: Notices,
) {
    private val ports get() = kit.ports

    suspend fun run(run: Run, opts: PassOptions): PassOutcome {
        kit.queue.recover()
        run.account()
        val book = book(run)
        val online = ports.network.online()
        val drive = run.drive()
        val snapshot = if (online) fetchOrNull(run, drive) else null
        collect(run, book, snapshot?.index ?: run.index)
        queueSecrets(run, snapshot?.index ?: run.index)
        run.save()
        Views.publishWaiting(run, book)
        if (snapshot == null) return offline(run)
        LeasePolicy.heldByOther(snapshot.index, ports.device, run.now)?.let { return locked(run, drive, snapshot, it, book) }
        kit.flows.leaseHolder.value = null
        val remoteIndex = snapshot.index
        if (remoteIndex != null && remoteIndex.revision != run.state.alignedRevision) reconciler.reconcile(run, remoteIndex, drive, book)
        adoptRemote(run, snapshot)
        val report = upload(run, drive, opts, book, onlyConflicts = false)
        val removals = removals(run, book)
        val committed = try {
            committer.commit(run, drive, snapshot, CommitMode.HOLDER, CommitExtras(removeFiles = removals, eraseSessions = run.state.eraseQueue))
        } catch (e: LeaseLostException) {
            return locked(run, drive, e.snapshot, e.holder, book)
        }
        committer.deleteUnused(run, drive)
        return settle(run, book, committed, report)
    }

    /** The newest index, or null when Drive cannot be reached right now. */
    private suspend fun fetchOrNull(run: Run, drive: DriveStore): RemoteSnapshot? = try {
        kit.remote.fetch(drive, run.cipher, run.state.remote, run.index)
    } catch (_: DriveException.Offline) {
        null
    } catch (_: java.io.IOException) {
        null
    }

    fun book(run: Run) = SessionBook(ports.localSessions(), run.index?.sessions.orEmpty(), run.state.erased.keys)

    /** Queues every new byte and changed file, and notes files that disappeared. */
    fun collect(run: Run, book: SessionBook, index: VaultIndex?) {
        val maker = run.maker()
        val entries = run.entries().filter { !it.conflict }
        val queued = entries.groupBy { it.trackKey }.toMutableMap()
        val pendingBySha = entries.filter { it.blob && !it.kind.appendOnly }
            .associate { it.sha256 to SameContent(it.name, null, it.storedBytes, it.keyGeneration) }.toMutableMap()
        val indexBySha = index?.objects.orEmpty().filter { !it.kind.appendOnly && it.kind != ObjectKind.SECRETS && it.driveId != null }
            .associate { it.sha256 to SameContent(it.name, it.driveId, it.storedBytes, it.keyGeneration) }
        val compactAllowed = !ports.network.metered()
        val tracks = run.state.tracks.toMutableMap()
        val seen = HashSet<String>()
        for (c in kit.scanner.scan(book.matcher, tracks)) {
            seen += c.key
            if (!book.uploadable(c.sessionId)) continue
            if (TrackRules.needsQuiet(c.path) && run.now - c.facts.modifiedAt < QUIET_MS) continue
            val waiting = queued[c.key].orEmpty()
            var track = tracks[c.key]
            if (track == null && waiting.isEmpty()) track = reconciler.adopt(index, c)?.also { tracks[c.key] = it }
            if (track != null && track.sessionId == null && c.sessionId != null) track = track.copy(sessionId = c.sessionId)
            val known = Known.of(track, waiting)
            val result = if (c.kind.appendOnly) {
                maker.transcript(c, known, compact = compactAllowed && known.pieces >= COMPACT_AFTER)
            } else {
                maker.whole(c, known) { sha -> pendingBySha[sha] ?: indexBySha[sha] }
            }
            val base = track ?: FileTrack(kind = c.kind, agentId = c.agentId, path = c.path, sessionId = c.sessionId)
            when (result) {
                MakeResult.Unchanged, MakeResult.Changing -> if (track != null) tracks[c.key] = track.copy(missingSince = -1, headCwd = c.headCwd)
                MakeResult.Touched -> tracks[c.key] = if (waiting.isEmpty()) {
                    base.copy(size = c.facts.size, modifiedAt = c.facts.modifiedAt, missingSince = -1)
                } else {
                    base.copy(missingSince = -1)
                }
                is MakeResult.Queued -> {
                    val entry = result.entry
                    if (entry.base || !entry.kind.appendOnly) {
                        run.discard(waiting)
                        queued[c.key] = listOf(entry)
                    }
                    if (entry.blob && !entry.kind.appendOnly) pendingBySha[entry.sha256] = SameContent(entry.name, null, entry.storedBytes, entry.keyGeneration)
                    tracks[c.key] = base.copy(headCwd = c.headCwd, missingSince = -1)
                }
            }
        }
        noteMissing(run, tracks, seen, book)
        run.state = run.state.copy(tracks = tracks)
    }

    /**
     * A file that is gone from the phone: a transcript or a deleted session's file stays in Drive
     * (the phone's own clean-up is never a delete); a memory file, or media of a live session,
     * removed on purpose leaves Drive too, but only after an hour, so a session that is being
     * deleted is never mistaken for removed media.
     */
    private fun noteMissing(run: Run, tracks: MutableMap<String, FileTrack>, seen: Set<String>, book: SessionBook) {
        for ((key, t) in tracks.toMap()) {
            if (key in seen || key == SECRETS_KEY || !t.onPhone) continue
            val file = kit.scanner.locate(t.kind, t.agentId, t.path)
            if (file != null && factsOf(file) != null) continue
            tracks[key] = if (removable(t, book)) {
                t.copy(missingSince = if (t.missingSince < 0) run.now else t.missingSince)
            } else {
                t.copy(onPhone = false, missingSince = -1)
            }
        }
    }

    private fun removable(t: FileTrack, book: SessionBook): Boolean = when {
        t.kind.appendOnly -> false
        t.sessionId == null -> true
        t.kind == ObjectKind.MEDIA || t.kind == ObjectKind.MEMORY -> book.alive(t.sessionId)
        else -> false
    }

    /** Files removed on purpose, past their grace period: they leave the index in this commit. */
    private fun removals(run: Run, book: SessionBook): Set<String> {
        val due = run.state.tracks.filter { (_, t) -> t.missingSince >= 0 && run.now - t.missingSince >= REMOVAL_GRACE_MS && removable(t, book) }.keys
        if (due.isEmpty()) return due
        run.discard(run.entries().filter { it.trackKey in due })
        return due
    }

    private suspend fun queueSecrets(run: Run, index: VaultIndex?) {
        val bytes = runCatching { ports.exportSecrets() }.getOrNull() ?: return
        try {
            val queued = run.entries().filter { it.kind == ObjectKind.SECRETS }
            val known = queued.maxByOrNull { it.createdAt }?.sha256
                ?: run.state.tracks[SECRETS_KEY]?.prefixSha256
                ?: index?.objects?.filter { it.kind == ObjectKind.SECRETS }?.maxByOrNull { it.createdAt }?.sha256
            if (run.maker().secrets(bytes, known) != null) run.discard(queued)
        } finally {
            bytes.fill(0)
        }
    }

    /** Settings another phone changed come here, unless this phone changed them too (then its own win). */
    fun adoptRemote(run: Run, snapshot: RemoteSnapshot) {
        run.keepIndex(snapshot)
        val remoteJson = snapshot.index?.settingsJson ?: return
        val pushed = run.state.settingsPushed
        if (remoteJson == pushed) return
        val local = SyncedSettings.of(ports.settings.settings.value).json()
        if (pushed != null && local != pushed) return
        SyncedSettings.parse(remoteJson)?.let { synced -> ports.settings.update { synced.applyTo(it) } }
        run.state = run.state.copy(settingsPushed = remoteJson)
    }

    /**
     * Uploads what the rules allow now: videos wait for Wi-Fi unless the owner allows mobile data
     * (or tapped "Upload now"); the daily mobile limit and PocketIDE's Drive share are respected.
     */
    suspend fun upload(run: Run, drive: DriveStore, opts: PassOptions, book: SessionBook, onlyConflicts: Boolean): UploadReport {
        val settings = ports.settings.settings.value
        val metered = ports.network.metered()
        val limit = Limits.gb(settings.driveLimitGb)
        var projected = Chains.storedBytes(run.index?.objects.orEmpty())
        var uploaded = 0
        val order = run.entries()
            .filter { it.blob && it.driveId == null && (!onlyConflicts || it.conflict) }
            .sortedWith(compareBy({ !it.conflict }, { it.video }, { it.createdAt }))
        for (e in order) {
            val chosen = opts.only?.contains(e.sessionId) == true
            if (opts.only != null && !chosen && !e.conflict) continue
            if (e.sessionId != null && !e.conflict && !book.uploadable(e.sessionId)) continue
            if (e.video && metered && !settings.videosOnMobileData && !chosen) continue
            if (run.now > opts.deadline) break
            val decision = ports.budget.allow(e.storedBytes, MeteredDataBudget.KIND_SYNC, big = false)
            if (!decision.allowed) return UploadReport(uploaded, blocked = decision.reason)
            if (!e.conflict && projected + e.storedBytes > limit) return UploadReport(uploaded, shareFull = true)
            if (e.storedBytes >= LARGE_UPLOAD) opts.onLargeUpload()
            try {
                uploadOne(run, drive, e)
            } catch (_: DriveException.StorageFull) {
                return UploadReport(uploaded, googleFull = true)
            }
            ports.budget.record(e.storedBytes, MeteredDataBudget.KIND_SYNC)
            projected += e.storedBytes
            uploaded++
        }
        return UploadReport(uploaded)
    }

    /**
     * One upload. It is marked as started first, so after a kill Drive is asked whether the file
     * already arrived (names are random, so a match is ours) instead of sending it twice.
     */
    private suspend fun uploadOne(run: Run, drive: DriveStore, e: QueueEntry) {
        if (e.attempted) {
            val arrived = drive.find(e.name)
            if (arrived != null && (arrived.size == e.storedBytes || arrived.size <= 0)) {
                kit.queue.update(run.cipher, e.copy(driveId = arrived.id))
                return
            }
        } else {
            kit.queue.update(run.cipher, e.copy(attempted = true))
        }
        val file = drive.upload(e.name, kit.queue.blobFile(e.id))
        kit.queue.update(run.cipher, e.copy(attempted = true, driveId = file.id))
    }

    /** Another phone holds the lease: keep what this phone had as conflict copies, then stop. */
    private suspend fun locked(run: Run, drive: DriveStore, snapshot: RemoteSnapshot, holder: Lease, book: SessionBook): PassOutcome {
        kit.flows.leaseHolder.value = holder.deviceName
        run.keepIndex(snapshot)
        if (run.state.heldLease) {
            conflicts.preservePending(run, book)
            run.state = run.state.copy(heldLease = false)
            run.save()
            notices.leaseLost(run, holder.deviceName)
        }
        if (run.entries().any { it.conflict } || run.state.pendingConflicts.isNotEmpty()) {
            upload(run, drive, PassOptions(), book, onlyConflicts = true)
            committer.commit(run, drive, snapshot, CommitMode.ADDITIVE)
        }
        kit.flows.status.value = SyncStatus.Idle
        run.save()
        return PassOutcome.LOCKED
    }

    private fun offline(run: Run): PassOutcome {
        val waiting = run.state.waiting
        kit.flows.status.value = if (waiting != null) Views.waitingStatus(run, waiting) else SyncStatus.Error(Plain.OFFLINE)
        return PassOutcome.OFFLINE
    }

    private fun settle(run: Run, book: SessionBook, snapshot: RemoteSnapshot, report: UploadReport): PassOutcome {
        val now = run.now
        val pending = Views.pendingBytes(run.entries())
        val full = report.googleFull || report.shareFull
        val previous = run.state.waiting
        val waiting = when {
            full -> previous?.takeIf { it.googleFull == report.googleFull } ?: WaitingMark(now, report.googleFull)
            report.uploaded > 0 || pending == 0L -> null
            else -> previous
        }
        run.state = run.state.copy(waiting = waiting, lastSyncAt = now)
        run.save()
        if (full && previous == null) notices.storageFull(run, report.googleFull)
        Views.publishWaiting(run, book)
        kit.flows.storage.value = Views.storage(run, snapshot.index, ports.settings.settings.value, kit.flows.storage.value)
        kit.flows.status.value = when {
            waiting != null -> Views.waitingStatus(run, waiting)
            report.blocked != null && pending > 0 -> SyncStatus.Error("${report.blocked}. New chats sync on Wi-Fi.")
            else -> SyncStatus.UpToDate(now)
        }
        return when {
            waiting != null -> PassOutcome.WAITING
            report.blocked != null -> PassOutcome.BLOCKED
            else -> PassOutcome.DONE
        }
    }

    companion object {
        const val COMPACT_AFTER = 100
        const val LARGE_UPLOAD = 8L * 1024 * 1024
        const val QUIET_MS = 60_000L
        const val REMOVAL_GRACE_MS = Durations.HOUR
    }
}
