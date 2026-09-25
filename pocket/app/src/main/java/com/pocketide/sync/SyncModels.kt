package com.pocketide.sync

import com.pocketide.model.ObjectKind
import com.pocketide.model.SessionRecord
import com.pocketide.model.VaultObject
import kotlinx.serialization.Serializable

/** Where a tracked file lives: the room's home (/root) or the room's work folder (session media). */
internal enum class Root { HOME, WORK }

internal val ObjectKind.root: Root get() = if (this == ObjectKind.MEDIA) Root.WORK else Root.HOME

/** Transcripts are synced as append-only pieces; everything else as whole, content-addressed files. */
internal val ObjectKind.appendOnly: Boolean get() = this == ObjectKind.CHAT_PIECE

/** One logical file: the same key on every phone, so chains and tracks line up. */
internal fun fileKey(kind: ObjectKind, agentId: String?, path: String): String =
    if (kind == ObjectKind.SECRETS) SECRETS_KEY else "${kind.root}:${agentId.orEmpty()}:$path"

internal val VaultObject.fileKey: String get() = fileKey(kind, agentId, path)

/** Identity of one index entry. The same Drive file may back several entries (identical content). */
internal val VaultObject.entryKey: String
    get() = "$name|${agentId.orEmpty()}|$kind|$path|${sessionId.orEmpty()}"

internal const val SECRETS_KEY = "secrets"
internal const val SECRETS_PATH = "secrets"

/** The last remote index this phone saw or wrote. */
@Serializable
internal data class RemoteMark(
    val id: String,
    val md5: String? = null,
    val modifiedTime: String? = null,
    val revision: Long,
    val bytes: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * What Drive holds of one local file: the committed length and the SHA-256 of that prefix (for
 * transcripts), or of the whole content (for other files), and the objects that make it up.
 */
@Serializable
internal data class FileTrack(
    val kind: ObjectKind,
    val agentId: String? = null,
    val path: String,
    val sessionId: String? = null,
    val syncedLength: Long = 0,
    val prefixSha256: String? = null,
    val objects: List<String> = emptyList(),
    /** Size and modification time when last looked at, to skip unchanged files cheaply. */
    val size: Long = -1,
    val modifiedAt: Long = -1,
    /** False once the phone copy was cleaned (Drive keeps it; opening the session brings it back). */
    val onPhone: Boolean = true,
    /** Newest createdAt given to this file's objects, so a clock change cannot reorder pieces. */
    val lastCreatedAt: Long = 0,
    /** The working directory a Codex rollout names in its first line, once read. */
    val headCwd: String? = null,
    /** When the file was first seen missing; a removal is acted on only after a grace period. */
    val missingSince: Long = -1,
    /** Size and time of the local version already kept as a conflict copy, so it is copied once. */
    val preservedSize: Long = -1,
    val preservedModifiedAt: Long = -1,
    /**
     * Drive has a newer version that is written here only once the file's room stops (its agent
     * may hold the file open). Meanwhile nothing of the file is queued, so no piece is sent
     * against the version Drive already replaced.
     */
    val waitsForRoom: Boolean = false,
)

/** The last version of a session record this phone pushed, to push only real changes. */
@Serializable
internal data class SessionMark(val hash: String, val deletedAt: Long? = null)

@Serializable
internal data class WaitingMark(val since: Long, val googleFull: Boolean)

/** A 7-day notice before a chat moves to Recently deleted by a retention rule. */
@Serializable
internal data class RetentionNotice(val rule: String, val noticeAt: Long, val dueAt: Long)

@Serializable
internal data class RestoreJob(val choice: RestoreChoice, val startedAt: Long)

@Serializable
internal enum class MoveStage { COPYING, REKEY, VERIFIED, ERASING, DONE }

@Serializable
internal data class MoveJob(
    val from: String,
    val to: String,
    val stage: MoveStage = MoveStage.COPYING,
    /** Object name → its Drive id in the new account. */
    val copied: Map<String, String> = emptyMap(),
    val indexId: String? = null,
)

/** Everything the engine remembers between runs. Sealed with the vault key in `dirs.vault`. */
@Serializable
internal data class SyncState(
    val account: String? = null,
    val remote: RemoteMark? = null,
    /** The index revision this phone's files match; another phone's newer write means reconciling. */
    val alignedRevision: Long = -1,
    val tracks: Map<String, FileTrack> = emptyMap(),
    val sessionMarks: Map<String, SessionMark> = emptyMap(),
    val projectMarks: Map<String, String> = emptyMap(),
    val settingsPushed: String? = null,
    val heldLease: Boolean = false,
    val waiting: WaitingMark? = null,
    val notices: Map<String, RetentionNotice> = emptyMap(),
    val computerNoticeDue: Long? = null,
    val eraseQueue: Set<String> = emptySet(),
    /** Sessions erased from Drive (session id → when): never uploaded again. */
    val erased: Map<String, Long> = emptyMap(),
    /** Drive files no index entry needs any more, still to delete: name → Drive id ("" when unknown). */
    val driveDeletes: Map<String, String> = emptyMap(),
    /** Conflict-copy sessions waiting to be recorded in the index with their files. */
    val pendingConflicts: List<SessionRecord> = emptyList(),
    val restore: RestoreJob? = null,
    val move: MoveJob? = null,
    val lastSyncAt: Long = 0,
    val lastMaintenanceAt: Long = 0,
    /** When Drive last confirmed new data of each session, for its "Backed up" chip. */
    val backedUpAt: Map<String, Long> = emptyMap(),
    /** The day-before notice for the computer's removal was posted. */
    val computerDayBeforeSent: Boolean = false,
    /** When each kind of notification was last posted, so each is shown at most once a day. */
    val alerts: Map<String, Long> = emptyMap(),
)

/**
 * One encrypted blob waiting in `dirs.queue` for Drive, with everything needed to record it in
 * the index once Drive confirms it. An entry without [blob] reuses a Drive file that holds the same
 * content ([name] is that file's name).
 */
@Serializable
internal data class QueueEntry(
    /** The entry's own id in the queue folder. */
    val id: String,
    /** The Drive file name. */
    val name: String,
    val kind: ObjectKind,
    val agentId: String? = null,
    val sessionId: String? = null,
    val path: String,
    val offset: Long = 0,
    val length: Long,
    val sha256: String,
    val storedBytes: Long = 0,
    val keyGeneration: Int,
    val createdAt: Long,
    /** A transcript piece starting at 0 that replaces every older piece of its file. */
    val base: Boolean = false,
    /** SHA-256 of the file's bytes [0, offset + length), for transcripts. */
    val prefixSha256: String? = null,
    val video: Boolean = false,
    val blob: Boolean = true,
    /** An upload was started, so after a crash Drive is asked whether the file already arrived. */
    val attempted: Boolean = false,
    val driveId: String? = null,
    val trackKey: String,
    val fileSize: Long = -1,
    val fileModifiedAt: Long = -1,
    /** Part of a conflict copy: uploaded and recorded even while another phone holds the lease. */
    val conflict: Boolean = false,
    /** For a conflict copy's session record: the session it copies. */
    val conflictOf: String? = null,
) {
    fun toObject(driveId: String?, storedBytes: Long = this.storedBytes): VaultObject = VaultObject(
        name = name,
        driveId = driveId,
        kind = kind,
        sessionId = sessionId,
        agentId = agentId,
        path = path,
        offset = offset,
        length = length,
        storedBytes = storedBytes,
        sha256 = sha256,
        createdAt = createdAt,
        keyGeneration = keyGeneration,
    )
}
