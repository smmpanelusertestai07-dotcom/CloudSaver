package com.pocketide.sync

import com.pocketide.core.AppDirs
import com.pocketide.core.Settings
import com.pocketide.google.DriveException
import com.pocketide.google.DriveStore
import com.pocketide.model.ObjectKind
import com.pocketide.model.VaultIndex
import com.pocketide.model.VaultObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant

/**
 * The daily job (§6.4–§6.8). It runs even when the app is not opened; "activity" means agent
 * work or a commit, never merely opening the app.
 */
internal class Maintenance(
    private val kit: SyncKit,
    private val committer: Committer,
    private val pass: SyncPass,
    private val notices: Notices,
) {
    private val ports get() = kit.ports
    private val cleaner = LocalCleaner(ports.dirs)

    suspend fun run(run: Run) {
        val settings = ports.settings.settings.value
        val book = pass.book(run)
        cleanPhone(run, settings, book, force = false)
        computer(run, settings, book)
        run.save()
        if (!ports.network.online()) return
        val drive = run.drive()
        val snapshot = kit.remote.fetch(drive, run.cipher, run.state.remote, run.index)
        val index = snapshot.index
        if (index == null || LeasePolicy.heldByOther(index, ports.device, run.now) != null) {
            run.keepIndex(snapshot)
            run.save()
            return
        }
        val extras = retention(run, index, settings, book)
        var latest = committer.commit(run, drive, snapshot, CommitMode.HOLDER, extras)
        phoneCopies(run, latest.index ?: index, settings, book)
        latest = reencrypt(run, drive, latest)
        sweep(run, drive, latest.index ?: index)
        committer.deleteUnused(run, drive)
        run.state = run.state.copy(lastMaintenanceAt = run.now)
        run.save()
    }

    /** Temp files, logs, old build outputs and idle caches; at 90 % of the phone limit, more. */
    fun cleanPhone(run: Run, settings: Settings, book: SessionBook, force: Boolean) {
        val now = run.now
        val active = ports.activeSessionIds()
        cleaner.oldFiles(now, Durations.days(TEMP_DAYS), ports.roomsRunning())
        cleaner.builds()
        cleaner.caches(now, settings.cacheDays, activity(book), active, force = false)
        val appBytes = measuredAppBytes()
        val free = ports.dirs.base.usableSpace
        val limit = Limits.gb(settings.phoneLimitGb)
        when (Limits.phoneSpace(appBytes, free, settings.phoneLimitGb)) {
            PhoneSpace.FULL -> {
                val freed = cleaner.caches(now, settings.cacheDays, activity(book), active, force = true) +
                    cleaner.oldFiles(now, Durations.DAY, ports.roomsRunning())
                if (freed > 0) notices.phoneCleaned(run, freed)
            }
            PhoneSpace.NEARLY_FULL -> if (!force) notices.phoneNearlyFull(run, appBytes, limit)
            PhoneSpace.OK -> Unit
        }
        kit.flows.storage.value = Views.storage(run, run.index, settings, kit.flows.storage.value, measuredAppBytes())
    }

    /** A project's last agent work or commit, by its folder name on disk. */
    private fun activity(book: SessionBook): (String) -> Long? {
        val byDir = HashMap<String, Long>()
        for (p in ports.localProjects()) byDir.merge(AppDirs.projectDirName(p.id), p.lastActivityAt, ::maxOf)
        for (s in book.all) byDir.merge(AppDirs.projectDirName(s.projectId), s.lastActivityAt, ::maxOf)
        return { dir -> byDir[dir] }
    }

    private fun measuredAppBytes(): Long {
        val snapshot = ports.phone()
        if (snapshot.at > 0 && snapshot.appDataBytes > 0) return snapshot.appDataBytes
        return sizeOf(ports.dirs.base) + sizeOf(ports.dirs.cacheBase)
    }

    /**
     * The whole computer after [Settings.computerUnusedDays] without agent activity: a 7-day
     * notice first, then removed only when everything is synced and nothing runs. Only the
     * rootfs goes; it is rebuilt on the next use.
     */
    private fun computer(run: Run, settings: Settings, book: SessionBook) {
        val days = settings.computerUnusedDays
        val rootfs = ports.dirs.rootfs
        val lastWork = (book.all.map { it.lastActivityAt } + ports.localProjects().map { it.lastActivityAt }).maxOrNull() ?: 0L
        val lastActivity = maxOf(lastWork, if (lastWork == 0L) rootfs.lastModified() else 0L)
        val unused = days > 0 && isRealDirectory(rootfs) && run.now - lastActivity >= Durations.days(days)
        val due = run.state.computerNoticeDue
        when {
            !unused -> run.state = run.state.copy(computerNoticeDue = null)
            due == null -> {
                val at = run.now + Durations.days(Retention.NOTICE_DAYS)
                run.state = run.state.copy(computerNoticeDue = at)
                notices.computerNotice(run, days, at)
            }
            run.now >= due && everythingSynced(run) && !ports.roomsRunning() && ports.computerIdle() -> {
                deleteTree(rootfs)
                run.state = run.state.copy(computerNoticeDue = null)
                notices.computerRemoved(run)
            }
        }
    }

    private fun everythingSynced(run: Run): Boolean =
        run.entries().isEmpty() && run.state.waiting == null && run.state.pendingConflicts.isEmpty()

    /**
     * Recently deleted for 30 days is erased (counted from the date in Drive, so a reinstall does
     * not restart it), "Delete forever" is erased now, and the retention rules move old chats to
     * Recently deleted after a 7-day notice.
     */
    private suspend fun retention(run: Run, index: VaultIndex, settings: Settings, book: SessionBook): CommitExtras {
        val now = run.now
        val erase = Retention.dueForErase(index, now) + run.state.eraseQueue
        val inDrive = index.sessions.map { s -> book[s.id]?.takeIf { it.lastActivityAt > s.lastActivityAt } ?: s }
        val keep = Retention.plan(inDrive, run.state.notices, Retention.RULE_KEEP, settings.keepChatsMonths, now)
        val shareFull = Chains.storedBytes(index.objects) >= Limits.gb(settings.driveLimitGb)
        val trimMonths = if (shareFull && settings.autoTrimOldChats) Retention.TRIM_MONTHS else 0
        val trim = Retention.plan(inDrive, keep.notices, Retention.RULE_TRIM, trimMonths, now)
        run.state = run.state.copy(notices = trim.notices)
        val due = now + Durations.days(Retention.NOTICE_DAYS)
        if (keep.noticed.isNotEmpty()) notices.retention(run, keep.noticed.size, due, trim = false)
        if (trim.noticed.isNotEmpty()) notices.retention(run, trim.noticed.size, due, trim = true)
        val moves = (keep.moveNow + trim.moveNow).distinct().filter { it !in erase }
        val changes = moves.mapNotNull { id -> inDrive.firstOrNull { it.id == id }?.let { SessionChange.Delete(Diffs.sanitized(it), now) } }
        for (id in moves) {
            try {
                ports.deleteSessionLocally(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // The index already says "deleted on"; the phone copy follows when the Chats list adopts it.
            }
        }
        return CommitExtras(sessions = changes, eraseSessions = erase)
    }

    /**
     * Phone copies of chats and media older than the owner's choice are deleted, but only once
     * Drive holds exactly what the phone has; opening the session brings them back.
     */
    private fun phoneCopies(run: Run, index: VaultIndex, settings: Settings, book: SessionBook) {
        val active = ports.activeSessionIds()
        val tracks = run.state.tracks.toMutableMap()
        for ((key, t) in run.state.tracks) {
            val session = book[t.sessionId] ?: continue
            if (!t.onPhone || t.sessionId in active || !session.backUp || t.objects.isEmpty()) continue
            val days = if (t.kind == ObjectKind.MEDIA) settings.phoneMediaDays else settings.phoneChatDays
            if (days < 0) continue
            val file = kit.scanner.locate(t.kind, t.agentId, t.path) ?: continue
            val facts = factsOf(file) ?: continue
            val lastUse = if (t.kind == ObjectKind.MEDIA) maxOf(session.lastActivityAt, facts.modifiedAt) else session.lastActivityAt
            if (run.now - lastUse < Durations.days(days)) continue
            val inDrive = Chains.chain(index.objects, key).map { it.name } == t.objects
            val unchanged = facts.size == t.syncedLength && facts.modifiedAt == t.modifiedAt
            if (inDrive && unchanged && file.delete()) tracks[key] = t.copy(onPhone = false)
        }
        run.state = run.state.copy(tracks = tracks)
    }

    /** After a re-key, older objects are downloaded, decrypted, encrypted again and replaced, on Wi-Fi. */
    private suspend fun reencrypt(run: Run, drive: DriveStore, snapshot: RemoteSnapshot): RemoteSnapshot {
        val index = snapshot.index ?: return snapshot
        val current = ports.keyGeneration()
        val stale = index.objects.filter { it.keyGeneration < current && it.driveId != null }.distinctBy { it.name }
        if (stale.isEmpty()) return snapshot
        val replaced = ArrayList<VaultObject>()
        var spent = 0L
        for (o in stale) {
            if (ports.network.metered() || spent >= REENCRYPT_PER_RUN) break
            val stored = reencryptOne(run, drive, o)
            spent += o.storedBytes
            replaced += index.objects.filter { it.name == o.name }.map { it.copy(keyGeneration = current, storedBytes = stored) }
        }
        if (replaced.isEmpty()) return snapshot
        return committer.commit(run, drive, snapshot, CommitMode.HOLDER, CommitExtras(replaceObjects = replaced))
    }

    private suspend fun reencryptOne(run: Run, drive: DriveStore, o: VaultObject): Long {
        val scratch = kit.queue.scratch()
        try {
            val old = File(scratch, "old")
            val plain = File(scratch, "plain")
            val fresh = File(scratch, "new")
            FileOutputStream(old).use { drive.download(o.driveId.orEmpty(), it) }
            FileInputStream(old).use { input -> FileOutputStream(plain).use { run.cipher.decrypt(input, it) } }
            FileInputStream(plain).use { input -> FileOutputStream(fresh).use { run.cipher.encrypt(input, it) } }
            plain.delete()
            drive.upload(o.name, fresh, o.driveId)
            ports.budget.record(old.length() + fresh.length(), MeteredDataBudget.KIND_REENCRYPT)
            return fresh.length()
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * Drive files named like ours that no index entry names (an upload whose record was lost, a
     * failed delete) are removed after a day; the vault's own files are never touched.
     */
    private suspend fun sweep(run: Run, drive: DriveStore, index: VaultIndex) {
        val files = drive.list()
        val referenced = index.objects.map { it.name }.toSet() + run.entries().map { it.name } + run.state.driveDeletes.keys
        val now = run.now
        for (f in files) {
            if (!f.name.startsWith(Committer.OBJECT_PREFIX) || f.name in referenced) continue
            val modified = f.modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: continue
            if (now - modified < ORPHAN_GRACE_MS) continue
            try {
                drive.delete(f.id)
            } catch (_: DriveException.Other) {
                // Checked again tomorrow.
            }
        }
        mergeDuplicateIndexes(run, drive, files.filter { it.name == RemoteIndex.NAME }.map { it.id })
    }

    /** Two phones creating the vault at once can leave two index files: fold the extra one in. */
    private suspend fun mergeDuplicateIndexes(run: Run, drive: DriveStore, ids: List<String>) {
        val keep = run.state.remote?.id ?: return
        for (id in ids.filter { it != keep }) {
            val other = try {
                kit.remote.decode(run.cipher, kit.remote.download(drive, id))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (other != null) {
                val now = run.now
                val merged = kit.remote.commit(
                    drive = drive,
                    cipher = run.cipher,
                    start = RemoteSnapshot(run.index, run.state.remote),
                    requireLease = { null },
                    change = { base -> IndexMerge.merge(base, other).copy(revision = base.revision + 1, updatedAt = now, lease = base.lease) },
                    emptyIndex = { VaultIndex(updatedAt = now) },
                )
                run.keepIndex(merged)
                run.save()
            }
            drive.delete(id)
        }
    }

    companion object {
        const val TEMP_DAYS = 7
        const val ORPHAN_GRACE_MS = Durations.DAY
        const val REENCRYPT_PER_RUN = 200L * 1024 * 1024
    }
}
