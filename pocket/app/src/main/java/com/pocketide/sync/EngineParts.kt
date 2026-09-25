package com.pocketide.sync

import com.pocketide.core.AppJson
import com.pocketide.core.Settings
import com.pocketide.google.DriveException
import com.pocketide.google.DriveStore
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.model.VaultIndex
import com.pocketide.sessions.Sessions
import com.pocketide.vault.VaultCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import java.io.IOException

/** The engine's collaborators, built once. */
internal class SyncKit(val ports: SyncPorts) {
    val repo = SyncRepository(ports.dirs)
    val queue = UploadQueue(ports.dirs.queue)
    val remote = RemoteIndex(ports.budget)
    val scanner = Scanner(ports.dirs)
    val materializer = Materializer(queue, ports.budget)
    val prefixes = PrefixMemory()
    val flows = SyncFlows()
}

/** What the screens observe. */
internal class SyncFlows {
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val waiting = MutableStateFlow<List<PendingUpload>>(emptyList())
    val leaseHolder = MutableStateFlow<String?>(null)
    val driveSessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val driveProjects = MutableStateFlow<List<Project>>(emptyList())
    val storage = MutableStateFlow(StorageSummary())
    val move = MutableStateFlow<MoveState>(MoveState.Idle)
    val backups = MutableStateFlow<Map<String, SessionBackup>>(emptyMap())
    val computerRemovalAt = MutableStateFlow<Long?>(null)
    val backgroundLimit = MutableStateFlow<String?>(null)
}

/**
 * One run of the engine, always under the engine's lock: the cipher, the state and the last
 * known index are loaded at the start and saved as the run goes.
 */
internal class Run(val kit: SyncKit, val cipher: VaultCipher) {
    val ports get() = kit.ports
    var state: SyncState = kit.repo.loadState(cipher)
    var index: VaultIndex? = kit.repo.loadIndex(cipher)

    val now: Long get() = ports.clock.now()

    fun save() = kit.repo.saveState(cipher, state)

    fun keepIndex(snapshot: RemoteSnapshot) {
        val index = snapshot.index ?: return
        this.index = index
        state = state.copy(remote = snapshot.mark)
        kit.repo.saveIndex(cipher, index)
        publishIndex()
    }

    fun publishIndex() {
        val index = index ?: return
        kit.flows.driveSessions.value = index.sessions
        kit.flows.driveProjects.value = index.projects
    }

    fun entries(): List<QueueEntry> = kit.queue.entries(cipher)

    fun maker() = PieceMaker(kit.queue, cipher, ports.keyGeneration(), ports.clock, kit.prefixes)

    /** The account whose index this phone holds; a different sign-in starts over with that account. */
    fun account(): String? {
        val current = ports.account()
        val known = state.account
        val move = state.move
        return when {
            known == null -> current.also { if (it != null) state = state.copy(account = it) }
            current == null || current == known -> known
            // While a move waits for its new account, Google's sheet already made that account the one in use.
            move != null && (current == move.from || current == move.to || move.to.isEmpty()) -> known
            else -> {
                startOverWith(current)
                current
            }
        }
    }

    fun drive(): DriveStore {
        val account = account()
        return if (account != null) ports.drive(account) else ports.drive()
    }

    /**
     * Signed in to another account (not by a move): its vault is a different one. Queued entries
     * were made against the old vault (pieces continuing its chains, files reused from it, uploads
     * with its ids), so they go and the next scan sends the phone's files to the new vault whole.
     * Conflict copies are complete on their own and exist nowhere else: they are sent there.
     */
    private fun startOverWith(account: String) {
        val (copies, rest) = entries().partition { it.conflict }
        rest.forEach { kit.queue.remove(it.id) }
        copies.forEach { kit.queue.update(cipher, it.copy(driveId = null, attempted = false)) }
        state = SyncState(account = account, erased = state.erased, alerts = state.alerts, pendingConflicts = state.pendingConflicts)
        index = null
        kit.flows.driveSessions.value = emptyList()
        kit.flows.driveProjects.value = emptyList()
    }

    /**
     * Drops queued entries; anything already in Drive but never recorded is deleted later. An entry
     * that reuses a dropped blob's content (identical files are stored once) could then never be
     * recorded, so it goes too and its file is read again at the next scan. Returns those entries.
     */
    fun discard(entries: Collection<QueueEntry>): List<QueueEntry> {
        if (entries.isEmpty()) return emptyList()
        val dropped = entries.map { it.id }.toSet()
        val blobs = entries.filter { it.blob }.map { it.name }.toSet()
        val reusing = if (blobs.isEmpty()) emptyList() else entries().filter { !it.blob && it.driveId == null && it.name in blobs && it.id !in dropped }
        val uploaded = unrecorded(entries)
        (entries + reusing).forEach { kit.queue.remove(it.id) }
        if (uploaded.isNotEmpty()) state = state.copy(driveDeletes = state.driveDeletes + uploaded)
        return reusing
    }

    /** Drive files [entries] uploaded (name → id), to delete once the entries leave the queue unrecorded. */
    fun unrecorded(entries: Collection<QueueEntry>): Map<String, String> =
        entries.filter { it.blob && it.driveId != null }.associate { it.name to it.driveId.orEmpty() }
}

/** Every session this phone knows: its own records first, then the index's. */
internal class SessionBook(local: List<SessionRecord>, drive: List<SessionRecord>, private val erased: Set<String>) {
    private val byId: Map<String, SessionRecord> = drive.associateBy { it.id } + local.associateBy { it.id }
    val all: Collection<SessionRecord> get() = byId.values
    val matcher = SessionMatcher(byId.values)

    operator fun get(id: String?): SessionRecord? = id?.let { byId[it] }

    /**
     * Room-level files always; a session's files unless "not backed up" or erased. A chat in
     * Recently deleted still uploads what was waiting, so Restore brings back all of it.
     */
    fun uploadable(id: String?): Boolean {
        if (id == null) return true
        if (id in erased) return false
        val s = byId[id] ?: return true
        return s.backUp && s.deletedAt != Sessions.ERASE_NOW
    }

    fun alive(id: String?): Boolean = id != null && byId[id]?.let { it.deletedAt == null } == true

    /** Sessions the owner deleted forever: erased from Drive at this sync, not at the daily job. */
    fun erasingNow(): Set<String> = byId.values.filter { it.deletedAt == Sessions.ERASE_NOW }.map { it.id }.toSet()
}

/** The settings that travel in the index (the ones marked "Synced" plus the automatic trim rule). */
@Serializable
internal data class SyncedSettings(
    val phoneLimitGb: Int,
    val driveLimitGb: Int,
    val onlyOfficialAgents: Boolean,
    val keepChatsMonths: Int,
    val phoneChatDays: Int,
    val phoneMediaDays: Int,
    val cacheDays: Int,
    val computerUnusedDays: Int,
    val autoTrimOldChats: Boolean,
) {
    fun applyTo(s: Settings) = s.copy(
        phoneLimitGb = phoneLimitGb,
        driveLimitGb = driveLimitGb,
        onlyOfficialAgents = onlyOfficialAgents,
        keepChatsMonths = keepChatsMonths,
        phoneChatDays = phoneChatDays,
        phoneMediaDays = phoneMediaDays,
        cacheDays = cacheDays,
        computerUnusedDays = computerUnusedDays,
        autoTrimOldChats = autoTrimOldChats,
    )

    fun json(): String = AppJson.encodeToString(serializer(), this)

    companion object {
        fun of(s: Settings) = SyncedSettings(
            s.phoneLimitGb, s.driveLimitGb, s.onlyOfficialAgents, s.keepChatsMonths, s.phoneChatDays,
            s.phoneMediaDays, s.cacheDays, s.computerUnusedDays, s.autoTrimOldChats,
        )

        fun parse(json: String): SyncedSettings? = runCatching { AppJson.decodeFromString(serializer(), json) }.getOrNull()
    }
}

/** Only real changes are pushed: each record is compared with the version this phone last sent. */
internal object Diffs {
    fun sanitized(r: SessionRecord) = r.copy(pendingBytes = 0, pendingVideos = 0)

    fun hash(r: SessionRecord): String =
        Codec.sha256(AppJson.encodeToString(SessionRecord.serializer(), sanitized(r)).toByteArray(Charsets.UTF_8))

    fun sessions(local: List<SessionRecord>, marks: Map<String, SessionMark>): List<SessionChange> =
        local.filter { it.backUp }.mapNotNull { r ->
            val record = sanitized(r)
            val mark = marks[r.id]
            val deletedAt = r.deletedAt
            when {
                mark != null && mark.hash == hash(record) -> null
                mark?.deletedAt != null && deletedAt == null -> SessionChange.Restore(record)
                deletedAt != null && mark?.deletedAt == null -> SessionChange.Delete(record, deletedAt)
                else -> SessionChange.Upsert(record)
            }
        }

    fun mark(change: SessionChange) = SessionMark(hash(change.record), change.record.deletedAt)

    fun projectHash(p: Project): String =
        Codec.sha256(AppJson.encodeToString(Project.serializer(), p.copy(cloned = false)).toByteArray(Charsets.UTF_8))

    fun projects(local: List<Project>, marks: Map<String, String>): List<Project> =
        local.filter { marks[it.id] != projectHash(it) }.map { it.copy(cloned = false) }
}

/** The sentences the owner sees. Plain, short, and each says what happens next. */
internal object Plain {
    const val OFFLINE = "Offline. New chats are kept on this phone and sync when it is back online."
    const val REVOKED = "Google Drive access was removed. Reconnect Google Drive to keep syncing."
    const val BUSY = "Google Drive asked PocketIDE to slow down. Sync tries again soon."
    const val FAILED = "Sync could not finish. It tries again soon."
    const val KEY_NOT_READY = "Your chats' key is not ready on this phone yet."
    const val MOVING = "Moving your data to another Google account."
    const val GOOGLE_FULL = "Google storage is full. New chats are waiting safely on the phone."
    const val SHARE_FULL = "PocketIDE's space is full. New chats are waiting safely on the phone."
    const val SYNCING = "Syncing chats"
    const val RESTORING = "Restoring your data"
    const val RESTORE_WAITS = "Restore continues on Wi-Fi."
    const val BACKGROUND_OFF =
        "Background use is turned off for PocketIDE, so chats back up only while the app is open. Turn it on in Android's settings for PocketIDE."
    const val BACKGROUND_RESTRICTED =
        "Android limits PocketIDE's background work because the app was not opened for a while, so backup and clean-up run about once a day. Opening PocketIDE lifts the limit."

    fun of(e: Throwable): String = when (e) {
        is SyncException -> e.message ?: FAILED
        is DriveException.StorageFull -> GOOGLE_FULL
        is DriveException.Offline -> OFFLINE
        is DriveException.Revoked -> REVOKED
        is DriveException.RateLimited -> BUSY
        is IOException -> OFFLINE
        else -> FAILED
    }
}

internal object Durations {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR

    fun days(n: Int): Long = n * DAY
}
