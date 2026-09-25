package com.pocketide.sync

import com.pocketide.google.DriveStore
import com.pocketide.model.ObjectKind
import com.pocketide.model.SessionRecord
import com.pocketide.model.VaultIndex
import com.pocketide.model.VaultObject

/**
 * What a new phone downloads now and what waits in Drive (§6.9): the index, memory, settings,
 * Secrets, room-level agent state and the last 30 days of chats now; older chats when opened.
 */
internal object RestorePlanner {
    const val RECENT_DAYS = 30

    data class Selection(val now: List<List<VaultObject>>, val sessionsNow: List<SessionRecord>, val sessionsLater: List<SessionRecord>)

    fun select(index: VaultIndex, now: Long, videosNow: Boolean): Selection {
        val cutoff = now - Durations.days(RECENT_DAYS)
        val live = index.sessions.filter { it.deletedAt == null }
        val recent = live.filter { it.lastActivityAt >= cutoff }.sortedByDescending { it.lastActivityAt }
        val order = recent.mapIndexed { i, s -> s.id to i }.toMap()
        val chains = Chains.files(index.objects).values.filter { it.isNotEmpty() }
        val chosen = chains.filter { chain ->
            val o = chain.first()
            when {
                o.kind == ObjectKind.MEMORY || o.kind == ObjectKind.SETTINGS || o.kind == ObjectKind.SECRETS -> true
                o.sessionId == null -> !o.path.startsWith(Conflicts.FOLDER)
                o.sessionId in order -> videosNow || !(o.kind == ObjectKind.MEDIA && TrackRules.isVideo(o.path))
                else -> false
            }
        }
        val sorted = chosen.sortedWith(compareBy({ rank(it.first(), order) }, { it.first().path }))
        return Selection(sorted, recent, live.filter { it.id !in order })
    }

    fun plan(index: VaultIndex, indexBytes: Long, now: Long, onWifi: Boolean, freeBytes: Long, videosNow: Boolean): RestorePlan {
        val selection = select(index, now, videosNow)
        val total = Chains.storedBytes(index.objects) + indexBytes
        val downloadNow = Chains.storedBytes(selection.now.flatten()) + indexBytes
        return RestorePlan(
            driveTotalBytes = total,
            downloadNowBytes = downloadNow,
            laterBytes = (total - downloadNow).coerceAtLeast(0),
            sessionsNow = selection.sessionsNow.size,
            sessionsLater = selection.sessionsLater.size,
            onWifi = onWifi,
            freeBytes = freeBytes,
        )
    }

    /** Memory, settings, Secrets, then the rest of the room, then chats from the newest (§6.9). */
    private fun rank(o: VaultObject, order: Map<String, Int>): Int = when {
        o.kind == ObjectKind.MEMORY -> 0
        o.kind == ObjectKind.SETTINGS -> 1
        o.kind == ObjectKind.SECRETS -> 2
        o.sessionId == null -> 3
        else -> 4 + (order[o.sessionId] ?: order.size)
    }
}

internal class Restorer(private val kit: SyncKit, private val pass: SyncPass, private val reconciler: Reconciler) {
    private val ports get() = kit.ports

    suspend fun plan(run: Run): RestorePlan {
        val snapshot = kit.remote.fetch(run.drive(), run.cipher, run.state.remote, run.index)
        run.keepIndex(snapshot)
        run.save()
        val metered = ports.network.metered()
        val videosNow = !metered || ports.settings.settings.value.videosOnMobileData
        return RestorePlanner.plan(
            index = snapshot.index ?: VaultIndex(updatedAt = run.now),
            indexBytes = snapshot.mark?.bytes ?: 0,
            now = run.now,
            onWifi = !metered,
            freeBytes = ports.dirs.base.usableSpace,
            videosNow = videosNow,
        )
    }

    /**
     * Downloads the plan's "now" part, resuming where a previous run stopped. Returns null when it
     * is done, or the reason it paused (Wi-Fi only, the daily limit); the sync job continues it.
     */
    suspend fun restore(run: Run, choice: RestoreChoice): String? {
        val job = run.state.restore ?: RestoreJob(choice, run.now)
        run.state = run.state.copy(restore = job)
        run.save()
        val drive = run.drive()
        val snapshot = kit.remote.fetch(drive, run.cipher, run.state.remote, run.index)
        val index = snapshot.index
        if (index == null) {
            run.state = run.state.copy(restore = null)
            run.save()
            return null
        }
        // Settings, sessions, projects, Variables and Secrets come in with the index's records.
        pass.adoptRemote(run, drive, snapshot)
        run.save()
        val metered = ports.network.metered()
        val selection = RestorePlanner.select(index, run.now, videosNow = !metered || ports.settings.settings.value.videosOnMobileData)
        for (chain in selection.now.filter { it.first().kind != ObjectKind.SECRETS }) {
            if (job.choice == RestoreChoice.WIFI_ONLY && ports.network.metered()) return Plain.RESTORE_WAITS
            val decision = ports.budget.allow(Chains.storedBytes(chain), MeteredDataBudget.KIND_RESTORE, big = isBig(chain))
            if (!decision.allowed) return "${decision.reason}. ${Plain.RESTORE_WAITS}"
            materialize(run, drive, chain, replaceUntrackedMemory = true)
        }
        run.state = run.state.copy(restore = null, alignedRevision = index.revision)
        run.save()
        return null
    }

    /** An older session, when the owner opens it. Downloads what the data rules allow now. */
    suspend fun fetchSession(run: Run, sessionId: String) {
        val drive = run.drive()
        val index = if (ports.network.online()) {
            kit.remote.fetch(drive, run.cipher, run.state.remote, run.index).also { run.keepIndex(it) }.index
        } else {
            run.index
        } ?: throw SyncException("This chat is not in Drive yet.")
        val chains = Chains.files(index.objects).values
            .filter { chain -> chain.isNotEmpty() && chain.first().sessionId == sessionId }
            .sortedBy { isBig(it) }
        var waits: String? = null
        for (chain in chains) {
            val decision = ports.budget.allow(Chains.storedBytes(chain), MeteredDataBudget.KIND_RESTORE, big = isBig(chain))
            if (!decision.allowed) {
                waits = decision.reason
                continue
            }
            materialize(run, drive, chain, replaceUntrackedMemory = false)
        }
        run.save()
        waits?.let { throw SyncException("$it. The rest of this chat downloads on Wi-Fi.") }
    }

    /**
     * Writes one file from Drive. A file already on the phone is not overwritten here: it is
     * adopted when it starts with Drive's content; otherwise the next sync keeps it as a conflict
     * copy and brings Drive's version in, so it never replaces Drive's. The one exception is
     * untracked memory during a restore, which the app wrote before Drive's copy came.
     */
    suspend fun materialize(run: Run, drive: DriveStore, chain: List<VaultObject>, replaceUntrackedMemory: Boolean) {
        val first = chain.first()
        val key = first.fileKey
        val place = kit.scanner.roomFile(first.kind, first.agentId, first.path) ?: return
        val target = place.file
        val track = run.state.tracks[key]
        val facts = factsOf(target)
        if (facts != null) {
            if (track != null && track.onPhone && track.objects == chain.map { it.name }) return
            val replace = replaceUntrackedMemory && track == null && first.kind == ObjectKind.MEMORY
            if (!replace) {
                val candidate = Candidate(first.kind, first.agentId.orEmpty(), first.path, target, facts, first.sessionId, TrackRules.isVideo(first.path))
                reconciler.adopt(VaultIndex(updatedAt = run.now, objects = chain), candidate)?.let { adopted ->
                    run.state = run.state.copy(tracks = run.state.tracks + (key to adopted))
                }
                return
            }
        }
        val assembled = kit.materializer.assemble(drive, run.cipher, place, chain) ?: return
        run.state = run.state.copy(tracks = run.state.tracks + (key to Tracks.materialized(track, chain, assembled, factsOf(target))))
        run.save()
    }

    private fun isBig(chain: List<VaultObject>): Boolean {
        val first = chain.first()
        return (first.kind == ObjectKind.MEDIA && TrackRules.isVideo(first.path)) || Chains.storedBytes(chain) >= BIG_DOWNLOAD
    }

    companion object {
        const val BIG_DOWNLOAD = 16L * 1024 * 1024
    }
}
