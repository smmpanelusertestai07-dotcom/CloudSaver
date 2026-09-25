package com.pocketide.sync

import com.pocketide.model.Lease
import com.pocketide.model.ObjectKind
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.model.VaultIndex
import com.pocketide.model.VaultObject

/** What this phone changes in the index in one write. Re-applied as is if another phone wrote first. */
internal data class IndexDelta(
    val addObjects: List<VaultObject> = emptyList(),
    /** Entries (by [entryKey]) that are replaced, superseded or removed. */
    val removeEntries: Set<String> = emptySet(),
    /** Files whose older entries all go (a new base piece, a new version of a whole file). */
    val replaceFiles: Set<String> = emptySet(),
    val sessions: List<SessionChange> = emptyList(),
    val eraseSessions: Set<String> = emptySet(),
    val projects: List<Project> = emptyList(),
    val settingsJson: String? = null,
    val lease: Lease? = null,
) {
    val isEmpty: Boolean
        get() = addObjects.isEmpty() && removeEntries.isEmpty() && replaceFiles.isEmpty() && sessions.isEmpty() &&
            eraseSessions.isEmpty() && projects.isEmpty() && settingsJson == null && lease == null
}

/** A change to one session record, as this phone saw it happen. */
internal sealed interface SessionChange {
    val record: SessionRecord
    val id: String get() = record.id

    data class Upsert(override val record: SessionRecord) : SessionChange

    /** Moved to Recently deleted at [at]. An earlier date already in the index is kept. */
    data class Delete(override val record: SessionRecord, val at: Long) : SessionChange

    /** Restored from Recently deleted: the only change allowed to clear a deletion. */
    data class Restore(override val record: SessionRecord) : SessionChange
}

internal object IndexMerge {

    /** Applies [delta] on top of [base], the newest index read from Drive. */
    fun apply(base: VaultIndex, delta: IndexDelta, now: Long, keyGeneration: Int): VaultIndex {
        val replacedFiles = delta.replaceFiles
        val newKeys = delta.addObjects.map { it.entryKey }.toSet()
        val kept = base.objects.filterNot { o ->
            o.entryKey in delta.removeEntries ||
                o.sessionId in delta.eraseSessions ||
                (o.fileKey in replacedFiles && o.entryKey !in newKeys)
        }
        val added = delta.addObjects.filterNot { it.sessionId in delta.eraseSessions }
        var sessions = base.sessions.filterNot { it.id in delta.eraseSessions }
        for (change in delta.sessions) {
            if (change.id !in delta.eraseSessions) sessions = applySession(sessions, change)
        }
        return base.copy(
            updatedAt = now,
            revision = base.revision + 1,
            keyGeneration = maxOf(base.keyGeneration, keyGeneration),
            lease = delta.lease ?: base.lease,
            objects = unionObjects(kept, added),
            sessions = sessions,
            projects = mergeProjects(base.projects, delta.projects),
            settingsJson = delta.settingsJson ?: base.settingsJson,
        )
    }

    /**
     * The merge rule for two whole indexes: the union of objects; sessions merged by id preferring
     * the newer `lastActivityAt`, where a deletion wins once `deletedAt` is set; projects by id,
     * newer activity first; the lease with the newer heartbeat.
     */
    fun merge(a: VaultIndex, b: VaultIndex): VaultIndex {
        val newer = if (b.updatedAt > a.updatedAt) b else a
        val sessions = (a.sessions + b.sessions).groupBy { it.id }.values.map { it.reduce(::mergeSession) }
        return newer.copy(
            revision = maxOf(a.revision, b.revision),
            keyGeneration = maxOf(a.keyGeneration, b.keyGeneration),
            lease = listOfNotNull(a.lease, b.lease).maxByOrNull { it.heartbeatAt },
            objects = unionObjects(a.objects, b.objects),
            sessions = sessions.sortedBy { it.startedAt },
            projects = mergeProjects(a.projects, b.projects),
            settingsJson = newer.settingsJson ?: a.settingsJson ?: b.settingsJson,
        )
    }

    /** The newer activity wins ([a] on a tie); the earliest deletion date is kept, so the 30-day count never restarts. */
    fun mergeSession(a: SessionRecord, b: SessionRecord): SessionRecord {
        val newer = if (b.lastActivityAt > a.lastActivityAt) b else a
        val deletions = listOfNotNull(a.deletedAt, b.deletedAt)
        if (deletions.isEmpty()) return newer
        return newer.copy(deletedAt = deletions.min(), status = SessionStatus.DELETED)
    }

    /** Union by entry; for the same entry the later object wins (a re-encrypted copy, say). */
    fun unionObjects(first: List<VaultObject>, second: List<VaultObject>): List<VaultObject> {
        val byKey = LinkedHashMap<String, VaultObject>()
        for (o in first) byKey[o.entryKey] = o
        for (o in second) byKey[o.entryKey] = o
        return byKey.values.toList()
    }

    fun mergeProjects(base: List<Project>, changes: List<Project>): List<Project> {
        if (changes.isEmpty()) return base
        val byId = LinkedHashMap<String, Project>()
        for (p in base) byId[p.id] = p
        for (p in changes) {
            val old = byId[p.id]
            byId[p.id] = if (old == null || p.lastActivityAt >= old.lastActivityAt) p else old
        }
        return byId.values.toList()
    }

    private fun applySession(sessions: List<SessionRecord>, change: SessionChange): List<SessionRecord> {
        val old = sessions.firstOrNull { it.id == change.id }
        // This phone's own edit (a rename, say) wins a tie; a record with newer activity wins otherwise.
        val next = when (change) {
            is SessionChange.Upsert -> if (old == null) change.record else mergeSession(change.record, old)
            is SessionChange.Delete -> {
                val merged = if (old == null) change.record else mergeSession(change.record, old)
                merged.copy(deletedAt = listOfNotNull(old?.deletedAt, change.record.deletedAt, change.at).min(), status = SessionStatus.DELETED)
            }
            is SessionChange.Restore -> {
                val newer = if (old != null && old.lastActivityAt > change.record.lastActivityAt) old else change.record
                val status = change.record.status.takeUnless { it == SessionStatus.DELETED } ?: SessionStatus.OPEN
                newer.copy(deletedAt = null, status = status)
            }
        }
        return if (old == null) sessions + next else sessions.map { if (it.id == change.id) next else it }
    }
}

/** The objects that rebuild one logical file, in order. */
internal object Chains {

    /** For transcripts: the newest base piece and the pieces that continue it without a gap. */
    fun chain(objects: List<VaultObject>, key: String): List<VaultObject> {
        val parts = objects.filter { it.fileKey == key }
        if (parts.isEmpty()) return emptyList()
        if (parts.first().kind != ObjectKind.CHAT_PIECE) {
            return listOfNotNull(parts.maxWithOrNull(compareBy<VaultObject>({ it.createdAt }, { it.name })))
        }
        val base = parts.filter { it.offset == 0L }.maxWithOrNull(compareBy({ it.createdAt }, { it.length }, { it.name }))
            ?: return emptyList()
        val chain = mutableListOf(base)
        var end = base.length
        val later = parts.filter { it !== base && it.createdAt >= base.createdAt }
        while (true) {
            val next = later.filter { it.offset == end && it.length > 0 }
                .maxWithOrNull(compareBy({ it.createdAt }, { it.name })) ?: break
            chain += next
            end += next.length
        }
        return chain
    }

    fun length(chain: List<VaultObject>): Long = chain.sumOf { it.length }

    /** Every logical file of the index, by key. */
    fun files(objects: List<VaultObject>): Map<String, List<VaultObject>> =
        objects.map { it.fileKey }.toSet().associateWith { chain(objects, it) }

    /** Encrypted bytes, each Drive file counted once. */
    fun storedBytes(objects: Collection<VaultObject>): Long = objects.distinctBy { it.name }.sumOf { it.storedBytes }
}
