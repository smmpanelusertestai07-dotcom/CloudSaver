package com.pocketide.sync

import com.pocketide.google.DriveException
import com.pocketide.google.DriveStore
import com.pocketide.model.Lease
import com.pocketide.model.ObjectKind
import com.pocketide.model.VaultIndex
import com.pocketide.model.VaultObject
import com.pocketide.sessions.Sessions
import kotlinx.coroutines.CancellationException

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
        val online = ports.network.online()
        if (online) checkKeyring()
        run.account()
        val book = book(run)
        val drive = run.drive()
        val snapshot = if (online) fetchOrNull(run, drive) else null
        collect(run, book, snapshot?.index ?: run.index)
        queueSecrets(run, snapshot?.index ?: run.index)
        run.save()
        Views.publishWaiting(run, book)
        if (snapshot == null) return offline(run)
        LeasePolicy.heldByOther(snapshot.index, ports.device, run.now)?.let { return locked(run, drive, snapshot, it, book) }
        kit.flows.leaseHolder.value = null
        catchUp(run, drive, snapshot, book)
        val report = upload(run, drive, opts, book, onlyConflicts = false)
        val removals = removals(run, book)
        val erase = run.state.eraseQueue + book.erasingNow()
        val committed = try {
            committer.commit(run, drive, snapshot, CommitMode.HOLDER, CommitExtras(removeFiles = removals, eraseSessions = erase))
        } catch (e: LeaseLostException) {
            return locked(run, drive, e.snapshot, e.holder, book)
        }
        committer.deleteUnused(run, drive)
        return settle(run, book, committed, report)
    }

    /**
     * The keyring's visibility and collaborators are checked on every sync (§5.1), before anything
     * is sealed, so a key the check replaces is not used for this pass's new pieces. Without
     * GitHub the key is kept on this phone only (the vault says so); any other failure is tried
     * again at the next pass. Either way the sync goes on.
     */
    private suspend fun checkKeyring() {
        try {
            ports.checkKeyring()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // See above.
        }
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

    /**
     * Queues every new byte now, online or not, and returns the sessions with bytes Drive has not
     * confirmed yet: those in the queue, and those with a database still too fresh to copy.
     */
    fun queueOnly(run: Run): Set<String> {
        kit.queue.recover()
        val book = book(run)
        val deferred = collect(run, book, run.index)
        run.save()
        Views.publishWaiting(run, book)
        return deferred + run.entries().filter { !it.conflict }.mapNotNull { it.sessionId }
    }

    /**
     * Queues every new byte and changed file, and notes files that disappeared. Returns the
     * sessions whose databases were left for a later run because they changed within [QUIET_MS].
     */
    fun collect(run: Run, book: SessionBook, index: VaultIndex?): Set<String> {
        val maker = run.maker()
        val entries = dropStranded(run, index).filter { !it.conflict }
        val queued = entries.groupBy { it.trackKey }.toMutableMap()
        val pendingBySha = entries.filter { it.blob && !it.kind.appendOnly }
            .associate { it.sha256 to SameContent(it.name, null, it.storedBytes, it.keyGeneration) }.toMutableMap()
        val indexBySha = index?.objects.orEmpty().filter { !it.kind.appendOnly && it.kind != ObjectKind.SECRETS && it.driveId != null }
            .associate { it.sha256 to SameContent(it.name, it.driveId, it.storedBytes, it.keyGeneration) }
        val compactAllowed = !ports.network.metered()
        val tracks = run.state.tracks.toMutableMap()
        val seen = HashSet<String>()
        val deferred = HashSet<String>()
        for (c in kit.scanner.scan(book.matcher, tracks, ports::roomRunning)) {
            seen += c.key
            if (!book.uploadable(c.sessionId)) continue
            if (TrackRules.isDatabase(c.path) && run.now - c.facts.modifiedAt < QUIET_MS) {
                c.sessionId?.let(deferred::add)
                continue
            }
            val waiting = queued[c.key].orEmpty()
            var track = tracks[c.key]
            // Drive's version comes in first; nothing is sent against the phone's copy meanwhile.
            if (track?.behindDrive == true) continue
            if (track == null && waiting.isEmpty()) {
                track = reconciler.adopt(index, c)
                val inDrive = if (track == null && index != null) Chains.chain(index.objects, c.key) else emptyList()
                if (inDrive.isNotEmpty()) {
                    // Drive holds this file and the phone's copy went another way (it was here before
                    // a restore): it must never replace Drive's. Reconcile keeps it as a conflict copy.
                    val session = c.sessionId ?: inDrive.first().sessionId
                    tracks[c.key] = FileTrack(kind = c.kind, agentId = c.agentId, path = c.path, sessionId = session, headCwd = c.headCwd, behindDrive = true)
                    continue
                }
                track?.let { tracks[c.key] = it }
            }
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
                        forget(waiting + run.discard(waiting), queued, pendingBySha)
                        queued[c.key] = listOf(entry)
                    }
                    if (entry.blob && !entry.kind.appendOnly) pendingBySha[entry.sha256] = SameContent(entry.name, null, entry.storedBytes, entry.keyGeneration)
                    tracks[c.key] = base.copy(headCwd = c.headCwd, missingSince = -1)
                }
            }
        }
        noteMissing(run, tracks, seen, book)
        run.state = run.state.copy(tracks = tracks)
        return deferred
    }

    /**
     * The queue, less entries that reuse content no queued blob and no index entry holds any more
     * (its blob left the queue unrecorded, as when its session was erased): such an entry could
     * never be recorded, so it goes, and its file is read and queued again.
     */
    private fun dropStranded(run: Run, index: VaultIndex?): List<QueueEntry> {
        val entries = run.entries()
        val held = entries.filter { it.blob }.map { it.name }.toSet() + index?.objects.orEmpty().map { it.name }
        val stranded = entries.filter { !it.blob && it.driveId == null && it.name !in held }
        run.discard(stranded)
        return entries - stranded.toSet()
    }

    /** Entries that left the queue no longer wait, and content only they held can no longer be reused. */
    private fun forget(gone: List<QueueEntry>, queued: MutableMap<String, List<QueueEntry>>, pendingBySha: MutableMap<String, SameContent>) {
        val ids = gone.map { it.id }.toSet()
        for (key in gone.map { it.trackKey }.toSet()) queued[key] = queued[key].orEmpty().filter { it.id !in ids }
        val blobs = gone.filter { it.blob }.map { it.name }.toSet()
        pendingBySha.values.removeAll { it.name in blobs }
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
            tracks[key] = if (removedOnPurpose(t, book)) {
                t.copy(missingSince = if (t.missingSince < 0) run.now else t.missingSince)
            } else {
                t.copy(onPhone = false, missingSince = -1)
            }
        }
    }

    /**
     * Only a file that could be removed on purpose, from a room that is still there: when the
     * whole room went (its agent was removed), its chats and memory stay in Drive. Nor is Drive's
     * version of a file this phone never had removed because the phone's own copy went.
     */
    private fun removedOnPurpose(t: FileTrack, book: SessionBook): Boolean = when {
        t.kind.appendOnly || t.behindDrive -> false
        !kit.scanner.roomExists(t.kind, t.agentId, t.path) -> false
        t.sessionId == null -> true
        t.kind == ObjectKind.MEDIA || t.kind == ObjectKind.MEMORY -> book.alive(t.sessionId)
        else -> false
    }

    /** Files removed on purpose, past their grace period: they leave the index in this commit. */
    private fun removals(run: Run, book: SessionBook): Set<String> {
        val due = run.state.tracks.filter { (_, t) -> t.missingSince >= 0 && run.now - t.missingSince >= REMOVAL_GRACE_MS && removedOnPurpose(t, book) }.keys
        if (due.isEmpty()) return due
        run.discard(run.entries().filter { it.trackKey in due })
        return due
    }

    private suspend fun queueSecrets(run: Run, index: VaultIndex?) {
        val bytes = localSecrets() ?: return
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

    /**
     * Brings in what another phone wrote since this phone's files last matched Drive: its changes
     * to files first, then its records. Every path that writes the index as the lease holder calls
     * this first, so none of them can record this phone's work over the other phone's unseen.
     */
    suspend fun catchUp(run: Run, drive: DriveStore, snapshot: RemoteSnapshot, book: SessionBook) {
        val index = snapshot.index
        if (index != null && (index.revision != run.state.alignedRevision || run.state.tracks.values.any { it.behindDrive })) {
            reconciler.reconcile(run, index, drive, book)
        }
        adoptRemote(run, drive, snapshot)
    }

    /**
     * Takes what another phone (or a restore) brought into the index: settings, sessions,
     * projects, Variables and Secrets.
     */
    suspend fun adoptRemote(run: Run, drive: DriveStore, snapshot: RemoteSnapshot) {
        run.keepIndex(snapshot)
        val index = snapshot.index ?: return
        adoptSettings(run, index)
        adoptSessions(run, index)
        adoptProjects(run, index)
        adoptSecrets(run, drive, index)
    }

    /**
     * Variables and Secrets that changed in Drive since this phone last synced them come in before
     * this phone's go up; uploading its own whole set instead would drop the other phone's changes.
     * When this phone changed nothing meanwhile, Drive's copy replaces its own; when both changed,
     * they are merged value by value. The result goes up with this pass if it differs from Drive's.
     * When Drive's copy cannot be brought in, this phone's waits too, and the next pass tries again.
     */
    private suspend fun adoptSecrets(run: Run, drive: DriveStore, index: VaultIndex) {
        val remote = index.objects.filter { it.kind == ObjectKind.SECRETS }.maxByOrNull { it.createdAt } ?: return
        val track = run.state.tracks[SECRETS_KEY]
        if (track?.prefixSha256 == remote.sha256) return
        val queued = run.entries().filter { it.kind == ObjectKind.SECRETS }
        val broughtIn = try {
            bringInSecrets(run, drive, remote, track)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        // Queued without Drive's copy: never sent. The merged set is queued again below.
        run.discard(queued)
        if (!broughtIn) return
        val synced = FileTrack(kind = ObjectKind.SECRETS, path = SECRETS_PATH, syncedLength = remote.length, prefixSha256 = remote.sha256, objects = listOf(remote.name))
        run.state = run.state.copy(tracks = run.state.tracks + (SECRETS_KEY to synced))
        queueSecrets(run, index)
        run.save()
    }

    private suspend fun bringInSecrets(run: Run, drive: DriveStore, remote: VaultObject, track: FileTrack?) {
        val local = localSecrets()
        try {
            val localSha = local?.let(Codec::sha256)
            if (localSha == remote.sha256) return
            val bytes = kit.materializer.bytes(drive, run.cipher, remote, MeteredDataBudget.KIND_SYNC)
            try {
                if (local == null || localSha == track?.prefixSha256) ports.importSecrets(bytes) else ports.mergeSecrets(bytes)
            } finally {
                bytes.fill(0)
            }
        } finally {
            local?.fill(0)
        }
    }

    /** This phone's Variables and Secrets, serialized; null when its store is not available. */
    private suspend fun localSecrets(): ByteArray? = try {
        ports.exportSecrets()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Settings another phone changed come here, unless this phone changed them too (then its own win). */
    private fun adoptSettings(run: Run, index: VaultIndex) {
        val remoteJson = index.settingsJson ?: return
        val pushed = run.state.settingsPushed
        if (remoteJson == pushed) return
        val local = SyncedSettings.of(ports.settings.settings.value).json()
        if (pushed != null && local != pushed) return
        SyncedSettings.parse(remoteJson)?.let { synced -> ports.settings.update { synced.applyTo(it) } }
        run.state = run.state.copy(settingsPushed = remoteJson)
    }

    /**
     * A session record that changed in Drive is taken when this phone did not change it since the
     * last sync; when both changed, this phone's push merges them by the index's rule instead.
     * Sessions only in Drive (a new phone, conflict copies) are added, with their "deleted on" date.
     */
    private suspend fun adoptSessions(run: Run, index: VaultIndex) {
        val marks = run.state.sessionMarks
        val erased = run.state.erased
        val local = ports.localSessions().associateBy { it.id }
        val incoming = index.sessions.filter { r ->
            val mark = marks[r.id]?.hash
            val mine = local[r.id]
            r.deletedAt != Sessions.ERASE_NOW && r.id !in erased && mark != Diffs.hash(r) &&
                (mine == null || Diffs.hash(mine) == mark)
        }
        if (incoming.isEmpty()) return
        ports.adoptSessions(incoming)
        run.state = run.state.copy(sessionMarks = marks + incoming.associate { it.id to SessionMark(Diffs.hash(it), it.deletedAt) })
    }

    private suspend fun adoptProjects(run: Run, index: VaultIndex) {
        val marks = run.state.projectMarks
        val incoming = index.projects.filter { marks[it.id] != Diffs.projectHash(it) }
        if (incoming.isEmpty()) return
        ports.adoptProjects(incoming)
        run.state = run.state.copy(projectMarks = marks + incoming.associate { it.id to Diffs.projectHash(it) })
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
