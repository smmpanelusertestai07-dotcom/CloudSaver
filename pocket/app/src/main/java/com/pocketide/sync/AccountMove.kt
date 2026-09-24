package com.pocketide.sync

import com.pocketide.google.DriveAuthResult
import com.pocketide.google.DriveStore
import com.pocketide.model.VaultIndex
import java.io.File
import java.io.FileOutputStream

/**
 * "Move to another Google account" (§5.5). Every vault file is copied one at a time through a
 * temporary file that is deleted before the next, so the phone never needs double space; the
 * files stay encrypted all the way. Then the index is written there, the vault makes a new key
 * with a new Half D in the new account, everything is checked, and the old copy is erased only
 * when the owner agrees. A move that is cut off continues where it stopped.
 */
internal class AccountMove(private val kit: SyncKit) {
    private val ports get() = kit.ports
    private val flows get() = kit.flows

    /** Asks for the new account; Google's own sheet may need approving first. */
    suspend fun start(run: Run) {
        val from = run.state.account ?: ports.account() ?: throw SyncException("Connect Google Drive first.")
        when (val result = ports.authorizeNewAccount()) {
            is DriveAuthResult.Authorized -> {
                val email = result.email ?: throw SyncException("Google did not say which account was chosen. Try again.")
                moveTo(run, from, email)
            }
            is DriveAuthResult.NeedsConsent -> {
                run.state = run.state.copy(move = MoveJob(from = from, to = ""))
                run.save()
                flows.move.value = MoveState.NeedsConsent(result.intent)
            }
            is DriveAuthResult.Failed -> throw SyncException(result.why)
        }
    }

    suspend fun moveTo(run: Run, fallbackFrom: String?, to: String) {
        val job = run.state.move?.takeIf { it.stage != MoveStage.DONE && (it.to.isEmpty() || it.to == to) }?.copy(to = to)
            ?: MoveJob(from = fallbackFrom ?: run.state.account ?: ports.account() ?: throw SyncException("Connect Google Drive first."), to = to)
        if (job.from == job.to) throw SyncException("Choose a different Google account from the one PocketIDE uses now.")
        run.state = run.state.copy(move = job)
        run.save()
        try {
            proceed(run, job)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            flows.move.value = MoveState.Failed(Plain.of(e))
            throw e as? SyncException ?: SyncException(Plain.of(e))
        }
    }

    private suspend fun proceed(run: Run, start: MoveJob) {
        var job = start
        val source = ports.drive(job.from)
        val target = ports.drive(job.to)
        if (job.stage == MoveStage.COPYING) {
            val snapshot = kit.remote.fetch(source, run.cipher, null, null)
            val index = snapshot.index ?: VaultIndex(updatedAt = run.now)
            job = copyAll(run, job, source, target, index)
            val moved = index.copy(
                objects = index.objects.map { it.copy(driveId = job.copied[it.name] ?: it.driveId) },
                lease = LeasePolicy.lease(ports.device, run.now),
                revision = index.revision + 1,
                updatedAt = run.now,
            )
            val existing = target.find(RemoteIndex.NAME)?.id
            val bytes = kit.remote.encode(run.cipher, moved)
            val written = target.uploadBytes(RemoteIndex.NAME, bytes, existing)
            job = job.copy(stage = MoveStage.REKEY, indexId = written.id)
            run.state = run.state.copy(
                move = job,
                account = job.to,
                remote = RemoteMark(written.id, written.md5, written.modifiedTime, moved.revision, bytes.size.toLong(), moved.updatedAt),
                alignedRevision = moved.revision,
                heldLease = true,
            )
            run.index = moved
            kit.repo.saveIndex(run.cipher, moved)
            run.save()
        }
        if (job.stage == MoveStage.REKEY) {
            ports.rekeyForMove()
            verify(run, job, target)
            job = job.copy(stage = MoveStage.VERIFIED)
            run.state = run.state.copy(move = job)
            run.save()
        }
        flows.move.value = MoveState.ReadyToEraseOld(job.from, job.to)
    }

    private suspend fun copyAll(run: Run, start: MoveJob, source: DriveStore, target: DriveStore, index: VaultIndex): MoveJob {
        var job = start
        val files = index.objects.distinctBy { it.name }
        for ((done, o) in files.withIndex()) {
            flows.move.value = MoveState.Copying(job.to, done, files.size)
            if (o.name in job.copied) continue
            val decision = ports.budget.allow(o.storedBytes * 2, MeteredDataBudget.KIND_MOVE, big = true)
            if (!decision.allowed) throw SyncException("${decision.reason}. The move continues on Wi-Fi.")
            val arrived = target.find(o.name)?.takeIf { it.size == o.storedBytes }
            val newId = arrived?.id ?: copyOne(source, target, o.name, o.driveId ?: source.find(o.name)?.id ?: throw SyncException(Materializer.MISSING))
            ports.budget.record(o.storedBytes * 2, MeteredDataBudget.KIND_MOVE)
            job = job.copy(copied = job.copied + (o.name to newId))
            run.state = run.state.copy(move = job)
            run.save()
        }
        flows.move.value = MoveState.Copying(job.to, files.size, files.size)
        return job
    }

    private suspend fun copyOne(source: DriveStore, target: DriveStore, name: String, sourceId: String): String {
        val scratch = kit.queue.scratch()
        try {
            val temp = File(scratch, "copy")
            FileOutputStream(temp).use { source.download(sourceId, it) }
            return target.upload(name, temp).id
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** Every object, the index and the vault's new key half are in the new account. */
    private suspend fun verify(run: Run, job: MoveJob, target: DriveStore) {
        val files = target.list().associateBy { it.name }
        val index = run.index ?: throw SyncException("The copy could not be checked. Try the move again.")
        val missing = index.objects.distinctBy { it.name }.filter { o -> files[o.name]?.let { it.size == o.storedBytes || it.size <= 0 } != true }
        if (missing.isNotEmpty()) throw SyncException("Some files did not arrive in ${job.to}. Try the move again.")
        val readBack = kit.remote.fetch(target, run.cipher, null, null).index
        if (readBack == null || readBack.revision != index.revision) throw SyncException("The copy could not be checked. Try the move again.")
        if (KEY_HALF !in files) throw SyncException("The new account does not have its key half yet. Try the move again.")
    }

    /** Erases every file of the old account's hidden folder, once the owner agreed. */
    suspend fun eraseOld(run: Run) {
        val job = run.state.move?.takeIf { it.stage == MoveStage.VERIFIED || it.stage == MoveStage.ERASING }
            ?: throw SyncException("There is no finished move to clean up.")
        run.state = run.state.copy(move = job.copy(stage = MoveStage.ERASING))
        run.save()
        val source = ports.drive(job.from)
        for (f in source.list()) source.delete(f.id)
        // Kept as done, so a sign-in that still names the old account is not taken for a new vault.
        run.state = run.state.copy(move = job.copy(stage = MoveStage.DONE))
        run.save()
        flows.move.value = MoveState.Done(job.to)
    }

    companion object {
        /** The vault's Half D file: only checked for, never read or changed here. */
        const val KEY_HALF = "keyhalf-d"
    }
}
