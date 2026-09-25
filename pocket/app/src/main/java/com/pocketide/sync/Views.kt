package com.pocketide.sync

import com.pocketide.core.Ist
import com.pocketide.core.Settings
import com.pocketide.model.VaultIndex
import com.pocketide.sessions.Sessions
import java.util.Locale

/** What the screens show, computed from the queue and the index. */
internal object Views {

    /** Bytes waiting for Drive (conflict copies are counted apart: they never lock the app). */
    fun pendingBytes(entries: List<QueueEntry>): Long = entries.filter { !it.conflict }.sumOf { it.length }

    /** "Waiting to upload" per session, and each session's backup chip. */
    fun publishWaiting(run: Run, book: SessionBook) {
        val bySession = run.entries()
            .filter { !it.conflict && it.sessionId != null && book.uploadable(it.sessionId) }
            .groupBy { it.sessionId.orEmpty() }
        run.kit.flows.waiting.value = bySession
            .map { (id, list) -> PendingUpload(id, book[id]?.title ?: "Chat", list.sumOf { it.length }, list.count { it.video }) }
            .sortedByDescending { it.bytes }
        val videosWait = run.ports.network.metered() && !run.ports.settings.settings.value.videosOnMobileData
        run.kit.flows.backups.value = book.all
            .filter { it.deletedAt != Sessions.ERASE_NOW }
            .associate { s -> s.id to backup(s.backUp, bySession[s.id].orEmpty(), run.state.backedUpAt[s.id], videosWait) }
    }

    fun backup(backUp: Boolean, pending: List<QueueEntry>, lastAt: Long?, videosWait: Boolean): SessionBackup {
        if (!backUp) return SessionBackup(BackupState.NOT_BACKED_UP)
        val videos = pending.count { it.video }
        val state = when {
            pending.isEmpty() -> BackupState.BACKED_UP
            videosWait && videos == pending.size -> BackupState.WAITING_FOR_WIFI
            else -> BackupState.WAITING
        }
        return SessionBackup(state, lastAt, pending.sumOf { it.length }, if (videosWait) videos else 0)
    }

    fun waitingStatus(run: Run, waiting: WaitingMark): SyncStatus.Waiting {
        val pending = pendingBytes(run.entries())
        return SyncStatus.Waiting(
            why = if (waiting.googleFull) Plain.GOOGLE_FULL else Plain.SHARE_FULL,
            since = waiting.since,
            pendingBytes = pending,
            googleStorageFull = waiting.googleFull,
            locks = Limits.locks(waiting, pending, run.now),
        )
    }

    fun storage(run: Run, index: VaultIndex?, settings: Settings, previous: StorageSummary, measuredAppBytes: Long? = null): StorageSummary {
        val objects = index?.objects.orEmpty()
        val driveBytes = Chains.storedBytes(objects) + (run.state.remote?.bytes ?: 0)
        val driveLimit = Limits.gb(settings.driveLimitGb)
        val snapshot = run.ports.phone()
        val appBytes = measuredAppBytes ?: if (snapshot.at > 0) snapshot.appDataBytes else previous.phoneBytes
        val free = run.ports.dirs.base.usableSpace
        return StorageSummary(
            driveBytes = driveBytes,
            driveLimitBytes = driveLimit,
            driveByKind = objects.distinctBy { it.name }.groupBy { it.kind }.mapValues { (_, list) -> list.sumOf { it.storedBytes } },
            driveBySession = objects.filter { it.sessionId != null }.groupBy { it.sessionId.orEmpty() }
                .mapValues { (_, list) -> Chains.storedBytes(list) },
            driveShareFull = driveBytes >= driveLimit,
            phoneBytes = appBytes,
            phoneLimitBytes = Limits.gb(settings.phoneLimitGb),
            phoneFreeBytes = free,
            phone = Limits.phoneSpace(appBytes, free, settings.phoneLimitGb),
        )
    }
}

/** Sizes as the owner reads them. */
internal object Sizes {
    fun human(bytes: Long): String {
        val b = bytes.coerceAtLeast(0).toDouble()
        return when {
            b >= Limits.GIB -> String.format(Locale.ENGLISH, "%.1f GB", b / Limits.GIB)
            b >= 1 shl 20 -> String.format(Locale.ENGLISH, "%.0f MB", b / (1 shl 20))
            b >= 1 shl 10 -> String.format(Locale.ENGLISH, "%.0f KB", b / (1 shl 10))
            else -> "${b.toLong()} bytes"
        }
    }
}

/** Notifications on the sync channel; each kind at most once a day. */
internal class Notices(private val kit: SyncKit) {

    fun leaseLost(run: Run, deviceName: String) = post(
        run,
        Notice("lease", "PocketIDE is in use on $deviceName", "This phone stopped syncing. What it had not synced yet was kept as conflict copies."),
    )

    fun storageFull(run: Run, googleFull: Boolean) = post(
        run,
        if (googleFull) {
            Notice("google-full", "Google storage is full", "New chats are waiting safely on the phone. See what uses space at one.google.com/storage.")
        } else {
            Notice("share-full", "PocketIDE's space is full", "New chats are waiting safely on the phone. Delete old chats or raise the limit in Settings.")
        },
    )

    fun retention(run: Run, count: Int, due: Long, trim: Boolean) {
        val chats = if (count == 1) "1 chat" else "$count chats"
        val move = if (count == 1) "moves" else "move"
        val text = if (trim) {
            "PocketIDE's space is full, so $chats older than 12 months $move to Recently deleted on ${Ist.date(due)}. Raise the limit in Settings to keep them."
        } else {
            "$chats with no new messages for a long time $move to Recently deleted on ${Ist.date(due)}. Change \"Keep chats\" in Your data to keep them."
        }
        post(run, Notice(if (trim) "trim" else "keep", "Old chats move to Recently deleted", text))
    }

    fun phoneNearlyFull(run: Run, used: Long, limit: Long) = post(
        run,
        Notice("phone-80", "PocketIDE is using most of its space", "${Sizes.human(used)} of ${Sizes.human(limit)} on this phone. Caches are cleaned at 90 %."),
    )

    fun phoneCleaned(run: Run, freed: Long) = post(
        run,
        Notice("phone-90", "Space freed on this phone", "PocketIDE cleaned caches that are rebuilt when needed: ${Sizes.human(freed)}."),
    )

    fun computerNotice(run: Run, days: Int, due: Long) = post(
        run,
        Notice("computer", "The computer will be removed", "No agent has run for $days days. The computer is removed on ${Ist.date(due)} to free space and is rebuilt the next time you use it."),
    )

    fun computerTomorrow(run: Run, due: Long) = post(
        run,
        Notice("computer", "The computer is removed tomorrow", "It goes on ${Ist.date(due)} unless an agent runs before then. Your projects and chats are safe either way."),
        force = true,
    )

    fun driveRevoked(run: Run) = post(
        run,
        Notice("drive", "Reconnect Google Drive", "PocketIDE can no longer reach your Drive, so new chats wait safely on this phone. Open PocketIDE to reconnect."),
    )

    fun computerRemoved(run: Run) = post(
        run,
        Notice("computer", "The computer was removed", "It is rebuilt the next time you use it. Your projects and chats are safe."),
        force = true,
    )

    private fun post(run: Run, notice: Notice, force: Boolean = false) {
        val now = run.now
        val last = run.state.alerts[notice.key]
        if (!force && last != null && now - last < Durations.DAY) return
        kit.ports.notifier.post(notice)
        val recent = run.state.alerts.filterValues { now - it < Durations.days(7) }
        run.state = run.state.copy(alerts = recent + (notice.key to now))
    }
}
