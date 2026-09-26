package com.pocketide.sync

import com.pocketide.core.Settings
import com.pocketide.google.DriveException
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What a background run tells WorkManager. */
internal enum class WorkResult { OK, RETRY }

/**
 * The sync engine. Every operation runs on the IO dispatcher under one lock, so the queue, the
 * state and the index copy have a single writer; screens only observe the flows.
 */
internal class DriveSyncEngine(private val ports: SyncPorts) : SyncEngine {
    private val kit = SyncKit(ports)
    private val lock = Mutex()
    private val again = AtomicBoolean(false)
    private val committer = Committer(kit)
    private val conflicts = Conflicts(kit)
    private val reconciler = Reconciler(kit, conflicts)
    private val notices = Notices(kit)
    private val pass = SyncPass(kit, committer, conflicts, reconciler, notices)
    private val maintenance = Maintenance(kit, committer, pass, notices)
    private val restorer = Restorer(kit, pass, reconciler)
    private val mover = AccountMove(kit)
    private val flows get() = kit.flows

    override val status: StateFlow<SyncStatus> = flows.status
    override val waiting: StateFlow<List<PendingUpload>> = flows.waiting
    override val usage: StateFlow<DataUsage> = ports.budget.usage
    override val leaseHolder: StateFlow<String?> = flows.leaseHolder
    override val driveSessions: StateFlow<List<SessionRecord>> = flows.driveSessions
    override val driveProjects: StateFlow<List<Project>> = flows.driveProjects
    override val storage: StateFlow<StorageSummary> = flows.storage
    override val move: StateFlow<MoveState> = flows.move
    override val backups: StateFlow<Map<String, SessionBackup>> = flows.backups
    override val computerRemovalAt: StateFlow<Long?> = flows.computerRemovalAt
    override val backgroundLimit: StateFlow<String?> = flows.backgroundLimit

    /** Shows what Drive held at the last sync, so the app starts offline with it. */
    suspend fun warmUp() {
        attempt { run ->
            run.publishIndex()
            val index = run.index
            LeasePolicy.heldByOther(index, ports.device, run.now)?.let { flows.leaseHolder.value = it.deviceName }
            run.state.waiting?.let { flows.status.value = Views.waitingStatus(run, it) }
            run.state.move?.let { job -> if (job.stage == MoveStage.VERIFIED) flows.move.value = MoveState.ReadyToEraseOld(job.from, job.to) }
            flows.computerRemovalAt.value = run.state.computerNoticeDue
            flows.storage.value = Views.storage(run, index, ports.settings.settings.value, flows.storage.value)
            Views.publishWaiting(run, pass.book(run))
        }
    }

    override fun requestSync(reason: String) {
        again.set(true)
        ports.scheduler.requestSoon()
    }

    override suspend fun syncNow() {
        attempt { run -> jobsThenPass(run, PassOptions()) }
    }

    override suspend fun uploadNow(sessionIds: List<String>) {
        act { run ->
            requireReady()
            if (!ports.network.online()) throw SyncException(Plain.OFFLINE)
            flows.status.value = SyncStatus.Running(Plain.SYNCING)
            when (pass.run(run, PassOptions(only = sessionIds.toSet()))) {
                PassOutcome.DONE -> Unit
                else -> (flows.status.value as? SyncStatus.Error)?.let { throw SyncException(it.why) }
            }
        }
    }

    override suspend fun queueNow(sessionIds: List<String>): Set<String> = act { run ->
        requireReady()
        pass.queueOnly(run).intersect(sessionIds.toSet())
    }

    override suspend fun restorePlan(): RestorePlan = act { run ->
        if (!ports.network.online()) throw SyncException(Plain.OFFLINE)
        restorer.plan(run)
    }

    override suspend fun restore(choice: RestoreChoice) {
        act { run ->
            if (!ports.network.online()) throw SyncException(Plain.OFFLINE)
            flows.status.value = SyncStatus.Running(Plain.RESTORING)
            val paused = restorer.restore(run, choice)
            flows.status.value = if (paused != null) SyncStatus.Error(paused) else SyncStatus.UpToDate(run.now)
        }
    }

    override suspend fun fetchSession(sessionId: String) {
        act { run -> restorer.fetchSession(run, sessionId) }
    }

    override suspend fun takeOver() {
        act { run ->
            if (!ports.network.online()) throw SyncException(Plain.OFFLINE)
            val drive = run.drive()
            val snapshot = kit.remote.fetch(drive, run.cipher, run.state.remote, run.index)
            pass.catchUp(run, drive, snapshot, pass.book(run))
            committer.commit(run, drive, snapshot, CommitMode.TAKEOVER)
            flows.leaseHolder.value = null
            pass.run(run, PassOptions())
        }
    }

    override suspend fun moveToAnotherAccount() {
        act { run -> mover.start(run) }
    }

    override suspend fun moveToAccount(email: String) {
        act { run -> mover.moveTo(run, null, email) }
    }

    override suspend fun eraseOldAccountCopy() {
        act { run -> mover.eraseOld(run) }
    }

    override suspend fun cleanNow(): Long = act { run -> maintenance.cleanNow(run) }

    override suspend fun eraseForever(sessionIds: List<String>) {
        act { run ->
            run.state = run.state.copy(eraseQueue = run.state.eraseQueue + sessionIds)
            run.save()
            if (ports.network.online() && ports.settings.settings.value.onboardingDone) pass.run(run, PassOptions())
        }
    }

    /**
     * "Delete everything": every file in the Drive hidden folder (the vault's too), then the rooms,
     * worktrees, clones, vault, queue, Media records and scheduled tasks on the phone, and the
     * sealed secrets. Drive goes first, so a failure leaves the phone's data in place to try again.
     */
    override suspend fun deleteEverything() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!ports.network.online()) throw SyncException("Connect to the internet to delete your data in Drive.")
            ports.scheduler.cancelAll()
            try {
                ports.stopRooms()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing may be running; the folders go either way.
            }
            val drive = ports.account()?.let { ports.drive(it) } ?: ports.drive()
            try {
                for (f in drive.list()) drive.delete(f.id)
            } catch (e: DriveException) {
                throw SyncException(Plain.of(e))
            }
            val dirs = ports.dirs
            listOf(
                dirs.rooms, dirs.work, dirs.repos, dirs.vault, dirs.queue, dirs.builds, dirs.mediaMeta, dirs.schedules,
                dirs.downloads, dirs.share, dirs.apk,
            )
                .forEach { deleteTree(it) }
            ports.forgetLocal()
            ports.wipeSecureStore()
            ports.forgetVaultKey()
            // Settings are erased too, as the owner was told: no old choice reaches the next vault, and
            // set-up starts over (the extra password belonged to the old key, which is gone).
            ports.settings.update { Settings() }
            flows.status.value = SyncStatus.Idle
            flows.waiting.value = emptyList()
            flows.leaseHolder.value = null
            flows.driveSessions.value = emptyList()
            flows.driveProjects.value = emptyList()
            flows.storage.value = StorageSummary()
            flows.move.value = MoveState.Idle
            flows.backups.value = emptyMap()
            flows.computerRemovalAt.value = null
        }
    }

    override fun schedule() {
        ports.scheduler.schedulePeriodic()
        ports.scheduler.requestSoon()
    }

    /**
     * A background sync; runs again at once when more was requested meanwhile. A [periodic] run
     * stops before the network when there is nothing to do (see [SyncPass.stopIfIdle]).
     */
    suspend fun runScheduled(periodic: Boolean = false, onLargeUpload: suspend () -> Unit): WorkResult {
        var result = WorkResult.OK
        repeat(MAX_ROUNDS) { round ->
            again.set(false)
            val options = PassOptions(onLargeUpload = onLargeUpload, deadline = ports.clock.now() + PASS_BUDGET_MS, quietWhenIdle = periodic && round == 0)
            val outcome = attempt { run -> jobsThenPass(run, options) }
            result = if (outcome == null && flows.status.value is SyncStatus.Error && retryable) WorkResult.RETRY else WorkResult.OK
            if (!again.get() || outcome != PassOutcome.DONE) return result
        }
        return result
    }

    suspend fun runMaintenance(): WorkResult {
        val moved = attempt { run ->
            if (ports.settings.settings.value.onboardingDone) maintenance.run(run) else emptyList()
        }.orEmpty()
        // Outside the lock: deleting a session may ask this engine to upload what it had waiting.
        for (id in moved) {
            try {
                ports.deleteSessionLocally(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The index already says "deleted on"; the Chats list shows it from there.
            }
        }
        return if (retryable && flows.status.value is SyncStatus.Error) WorkResult.RETRY else WorkResult.OK
    }

    /** A cut-off move or restore continues first; then the normal sync. */
    private suspend fun jobsThenPass(run: Run, options: PassOptions): PassOutcome? {
        val job = run.state.move
        if (job != null && job.to.isNotEmpty() && (job.stage == MoveStage.COPYING || job.stage == MoveStage.REKEY)) {
            flows.status.value = SyncStatus.Running(Plain.MOVING)
            mover.moveTo(run, null, job.to)
        }
        run.state.restore?.let { restoreJob ->
            val paused = restorer.restore(run, restoreJob.choice)
            if (paused != null) {
                flows.status.value = SyncStatus.Error(paused)
                return null
            }
        }
        if (!ports.settings.settings.value.onboardingDone) return null
        if (flows.status.value !is SyncStatus.Waiting) flows.status.value = SyncStatus.Running(Plain.SYNCING)
        val outcome = passOrStop(run, options)
        // Kept safely on the phone while offline: it goes up as soon as a network is back.
        if (outcome == PassOutcome.OFFLINE && run.entries().isNotEmpty()) ports.scheduler.requestWhenOnline()
        if (flows.storage.value.phone == PhoneSpace.FULL && run.now - run.state.lastMaintenanceAt > MAINTENANCE_GAP_MS) {
            ports.scheduler.requestMaintenance()
        }
        return outcome
    }

    /** The pass; a periodic run with nothing to do stops before the network instead ([SyncPass.stopIfIdle]). */
    private suspend fun passOrStop(run: Run, options: PassOptions): PassOutcome =
        if (options.quietWhenIdle && pass.stopIfIdle(run)) PassOutcome.DONE else pass.run(run, options)

    private fun requireReady() {
        if (!ports.settings.settings.value.onboardingDone) throw SyncException("Finish setting up PocketIDE first.")
    }

    /** Set by the last failure: whether trying again soon may help. */
    @Volatile
    private var retryable = false

    /** A background run: failures become the status, never an exception. */
    private suspend fun <T> attempt(block: suspend (Run) -> T): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            observe()
            val cipher = ports.cipher()
            if (cipher == null) {
                flows.status.value = SyncStatus.Idle
                return@withLock null
            }
            val run = Run(kit, cipher)
            try {
                ports.loadLocal()
                block(run).also { retryable = false }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(run, e)
                null
            }
        }
    }

    /** An action the owner asked for: failures also come back as a [SyncException] to show. */
    private suspend fun <T> act(block: suspend (Run) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            observe()
            val cipher = ports.cipher() ?: throw SyncException(Plain.KEY_NOT_READY)
            val run = Run(kit, cipher)
            try {
                ports.loadLocal()
                block(run)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(run, e)
                throw e as? SyncException ?: SyncException(Plain.of(e))
            }
        }
    }

    /** What Android allows in the background, and a new day's data counters, as of this run. */
    private fun observe() {
        flows.backgroundLimit.value = ports.backgroundLimit()
        ports.budget.refresh()
    }

    private fun fail(run: Run, e: Exception) {
        retryable = e is DriveException.RateLimited || e is DriveException.Other || e is java.io.IOException
        // In the background Google may need the owner to approve again; only the app can ask (R13).
        if (e is DriveException.Revoked) notices.driveRevoked(run)
        // Even the index could not be written: the wait (and its 24-hour lock) starts now.
        if (e is DriveException.StorageFull && run.state.waiting == null) {
            run.state = run.state.copy(waiting = WaitingMark(run.now, googleFull = true))
            notices.storageFull(run, googleFull = true)
        }
        val waiting = run.state.waiting
        flows.status.value = if (waiting != null && e !is SyncException) Views.waitingStatus(run, waiting) else SyncStatus.Error(Plain.of(e))
        run.state = run.state.copy(lastFailedAt = run.now)
        try {
            run.save()
        } catch (_: java.io.IOException) {
            // The state on disk stays as it was before this run; nothing is lost.
        }
    }

    private companion object {
        const val MAX_ROUNDS = 3
        const val PASS_BUDGET_MS = 8 * Durations.MINUTE
        const val MAINTENANCE_GAP_MS = 6 * Durations.HOUR
    }
}
