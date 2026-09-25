package com.pocketide.sync

import android.app.PendingIntent
import com.pocketide.model.ObjectKind
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val what: String) : SyncStatus
    data class UpToDate(val at: Long) : SyncStatus
    /**
     * New chats wait safely on the phone because Drive has no room; [since] drives the 24-hour lock.
     * [googleStorageFull] is true when the whole Google storage is full (not only PocketIDE's share),
     * and [locks] is true once the wait passed 24 hours or 200 MB, when the app must lock.
     */
    data class Waiting(
        val why: String,
        val since: Long,
        val pendingBytes: Long,
        val googleStorageFull: Boolean = false,
        val locks: Boolean = false,
    ) : SyncStatus
    data class Error(val why: String) : SyncStatus
}

data class PendingUpload(val sessionId: String, val title: String, val bytes: Long, val videos: Int)

/** Where one session's backup stands, for the chip on its row ("Backed up · 2 min ago"). */
enum class BackupState {
    /** Everything this phone has of the session is in Drive. */
    BACKED_UP,

    /** New parts wait for the next sync (or for room in Drive: see [SyncStatus.Waiting]). */
    WAITING,

    /** Only videos wait, for Wi-Fi ("1 video waiting for Wi-Fi"). */
    WAITING_FOR_WIFI,

    /** "Don't back up this chat": kept on this phone only. */
    NOT_BACKED_UP,
}

data class SessionBackup(
    val state: BackupState,
    /** When Drive last confirmed new parts of this session from this phone; null when not known here. */
    val lastBackedUpAt: Long? = null,
    val pendingBytes: Long = 0,
    val videosWaitingForWifi: Int = 0,
)

/** Recently deleted keeps a chat for 30 days, counted from the date stored in Drive (§6.4). */
object RecentlyDeleted {
    const val DAYS = 30

    /** When a chat deleted at [deletedAt] is erased for good; the daily job may run a little later. */
    fun erasesAt(deletedAt: Long): Long = deletedAt + DAYS * 24L * 60 * 60 * 1000
}

/** What a new phone downloads now and what waits in Drive (restore plan, §6.9). */
data class RestorePlan(
    val driveTotalBytes: Long,
    val downloadNowBytes: Long,
    val laterBytes: Long,
    val sessionsNow: Int,
    val sessionsLater: Int,
    val onWifi: Boolean,
    val freeBytes: Long,
)

enum class RestoreChoice { WIFI_ONLY, MOBILE_UP_TO_LIMIT }

/** Monthly data usage by type, metered networks only (Wi-Fi is free). */
data class DataUsage(val todayMeteredBytes: Long, val monthMeteredBytes: Long, val byType: Map<String, Long>)

/** How full PocketIDE's share of the phone is (§6.5): a notice at 80 %, caches cleaned at 90 %. */
enum class PhoneSpace { OK, NEARLY_FULL, FULL }

/** What PocketIDE keeps in Drive and on the phone, for "Your data" and the storage limits. */
data class StorageSummary(
    /** Encrypted bytes in the Drive hidden folder (each stored file counted once). */
    val driveBytes: Long = 0,
    val driveLimitBytes: Long = 0,
    val driveByKind: Map<ObjectKind, Long> = emptyMap(),
    /** Encrypted bytes per session id, for "largest sessions". */
    val driveBySession: Map<String, Long> = emptyMap(),
    /** PocketIDE's share of Drive reached the owner's limit: "PocketIDE's space is full". */
    val driveShareFull: Boolean = false,
    val phoneBytes: Long = 0,
    val phoneLimitBytes: Long = 0,
    val phoneFreeBytes: Long = 0,
    val phone: PhoneSpace = PhoneSpace.OK,
)

/** "Move to another Google account" (§5.5), step by step. */
sealed interface MoveState {
    data object Idle : MoveState

    /**
     * Google's own sheet must be approved first: launch [intent], pass its result to
     * `DriveAuth.completeConsent`, then call [SyncEngine.moveToAccount] with the account it names.
     */
    data class NeedsConsent(val intent: PendingIntent) : MoveState

    data class Copying(val to: String, val done: Int, val total: Int) : MoveState

    /** Everything is in [to] and checked. The copy in [from] stays until the owner agrees to erase it. */
    data class ReadyToEraseOld(val from: String, val to: String) : MoveState

    data class Done(val to: String) : MoveState

    data class Failed(val why: String) : MoveState
}

/** A sync action could not finish. [message] is one plain sentence the owner can act on. */
class SyncException(message: String) : Exception(message)

/**
 * Durable, append-only sync of AI data to the Drive hidden folder. New transcript bytes become
 * small compressed, encrypted pieces; images go with the chat; videos wait for Wi-Fi unless the
 * owner allows mobile data; nothing is marked synced until Drive confirms it. Holds the lease
 * (one active phone) and keeps conflict copies.
 */
interface SyncEngine {
    val status: StateFlow<SyncStatus>
    val waiting: StateFlow<List<PendingUpload>>
    val usage: StateFlow<DataUsage>

    /** The other phone's name when it holds the lease (this phone must lock); null when we hold it. */
    val leaseHolder: StateFlow<String?>

    /**
     * Sessions as the encrypted index in Drive knows them: sessions that are only in Drive, the
     * Recently deleted ones with their stored `deletedAt` (so a reinstall continues the count),
     * and conflict copies. A conflict copy's `agentSessionRef` is the path of its transcript,
     * relative to the room's home, once it is on the phone.
     */
    val driveSessions: StateFlow<List<SessionRecord>>

    /** Projects as the index knows them, so a new phone gets its project list back. */
    val driveProjects: StateFlow<List<Project>>

    val storage: StateFlow<StorageSummary>

    val move: StateFlow<MoveState>

    /** Each session's backup state by session id, for its chip and the sync dot on Home. */
    val backups: StateFlow<Map<String, SessionBackup>> get() = NO_BACKUPS

    /**
     * When the unused computer will be removed (its 7-day notice is running), or null. Removal
     * also waits until everything is synced and no agent runs.
     */
    val computerRemovalAt: StateFlow<Long?> get() = NOTHING

    /**
     * A plain sentence when Android holds back PocketIDE's background work (restricted standby
     * bucket, or background use off): backup and the daily clean-up then run late. Null when not.
     */
    val backgroundLimit: StateFlow<String?> get() = NOTHING

    /** Schedules a sync soon (end of a task, or every few minutes while agents run). */
    fun requestSync(reason: String)

    suspend fun syncNow()

    suspend fun uploadNow(sessionIds: List<String>)

    /**
     * Queues every new byte of the chats on this phone (compressed and encrypted, kept safely in
     * the upload queue; works offline) and returns those of [sessionIds] that still have bytes
     * Drive has not confirmed. A deleted chat's phone copy goes only once it is not returned.
     * Throws [SyncException] when nothing could be queued (the key is not ready, say).
     */
    suspend fun queueNow(sessionIds: List<String>): Set<String> = sessionIds.toSet()

    suspend fun restorePlan(): RestorePlan

    suspend fun restore(choice: RestoreChoice)

    /** Downloads an older session when the owner opens it. */
    suspend fun fetchSession(sessionId: String)

    /** "Use here?" on a second phone: takes the lease; the first phone locks at its next check. */
    suspend fun takeOver()

    /** Moves every vault file to another Google account, one file at a time. */
    suspend fun moveToAnotherAccount()

    /** Continues a move to [email] after the owner approved it in Google's sheet. */
    suspend fun moveToAccount(email: String)

    /** Erases the old account's copy once a move is finished and the owner agreed. */
    suspend fun eraseOldAccountCopy()

    /** "Delete forever": erases these sessions' files from Drive now (or at the next connection). */
    suspend fun eraseForever(sessionIds: List<String>)

    /** Erases everything in Drive and on the phone ("Delete everything"). */
    suspend fun deleteEverything()

    /** Starts the periodic sync and the daily maintenance job. */
    fun schedule()
}

private val NO_BACKUPS: StateFlow<Map<String, SessionBackup>> = MutableStateFlow(emptyMap())
private val NOTHING: StateFlow<Nothing?> = MutableStateFlow(null)

/** Metered-only accounting and the daily limit, checked before every big transfer. */
interface DataBudget {
    /** This month's metered usage by type, for Settings → Data. */
    val usage: StateFlow<DataUsage>

    /** May [bytes] of kind [kind] be transferred now? Big items wait for Wi-Fi by default. */
    fun allow(bytes: Long, kind: String, big: Boolean): com.pocketide.model.Decision

    fun record(bytes: Long, kind: String)

    /**
     * The owner saw the size of one big transfer of [kind] and confirmed it on mobile data (set-up
     * on mobile data, §6.7): [allow] lets that kind through today until about [bytes] of it were
     * recorded, without changing the owner's data settings. Kept in memory only.
     */
    fun allowOnce(kind: String, bytes: Long) = Unit
}
