package com.pocketide.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RenameDetector
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.EmptyProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * [GitGate] on JGit. The bare repos live where every room in Linux can write them, so each step
 * checks the repo ([BareRepos]), replaces its config, opens it with a file system that never runs
 * hooks ([GuardedFs]) and talks only to the remote on the gate's own record ([RemoteStates]),
 * given as an explicit URL. One step at a time per repo; all of it on [io] and cancellable.
 */
internal class JGitGate(
    reposRoot: File,
    stateDir: File,
    private val remotes: RemotePolicy = GitHubRemotes,
    private val scanner: CheckPost = CheckPost(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Per connect and per read, not for the whole transfer. */
    private val timeoutSeconds: Int = TIMEOUT_SECONDS,
) : GitGate {
    private val states = RemoteStates(stateDir)
    private val repos = BareRepos(reposRoot, states)
    private val locks = ConcurrentHashMap<File, Mutex>()

    init {
        HermeticJGit.install()
    }

    override suspend fun clone(cloneUrl: String, bareRepo: File, token: String) = step {
        val remote = remotes.allowed(cloneUrl)
        val target = repos.placed(bareRepo)
        val job = coroutineContext.job
        lockFor(target).withLock {
            when {
                target.isEmptyDirectory() || !target.exists() -> cloneInto(target, remote, cloneUrl, token, job)
                target.isRealDirectory() -> refetch(target, cloneUrl, token, job)
                else -> throw GitGateException(GitMessages.ANOTHER_COPY)
            }
        }
    }

    override suspend fun fetch(bareRepo: File, token: String) = step {
        val job = coroutineContext.job
        useRepo(bareRepo) { fetchInto(it, token, job) }
    }

    override suspend fun checkPost(bareRepo: File, branch: String, knownValues: List<String>): Verdict = step {
        val ref = branchRef(branch)
        val job = coroutineContext.job
        useRepo(bareRepo) { gateRepo ->
            val tip = gateRepo.repo.exactRef(ref)?.objectId ?: throw GitGateException(GitMessages.missingBranch(branch))
            val state = gateRepo.recorded()
            scanner.check(gateRepo.repo, tip, state.onGitHub(), knownValues, state.approvedWorkflows) { job.ensureActive() }
        }
    }

    override suspend fun push(bareRepo: File, branch: String, token: String, knownValues: List<String>): PushResult =
        pushStep {
            val ref = branchRef(branch)
            val job = coroutineContext.job
            useRepo(bareRepo) { pushBranch(it, ref, token, knownValues, job) }
        }

    override suspend fun stats(bareRepo: File, branch: String, base: String): Pair<Int, Int> = step {
        val ref = branchRef(branch)
        useRepo(bareRepo) { statsOf(it.repo, ref, base) }
    }

    override suspend fun deleteRemoteBranch(bareRepo: File, branch: String, token: String): PushResult = pushStep {
        val ref = branchRef(branch)
        if (!ref.startsWith(SESSION_BRANCHES)) return@pushStep PushResult.Failed(GitMessages.ONLY_SESSION_BRANCHES)
        val job = coroutineContext.job
        useRepo(bareRepo) { deleteBranch(it, ref, token, job) }
    }

    override suspend fun approveWorkflowChange(bareRepo: File, approvalKey: String) = step {
        if (!WorkflowChanges.isApprovalKey(approvalKey)) throw GitGateException(GitMessages.NOT_AN_APPROVAL)
        val placed = repos.placed(bareRepo)
        lockFor(placed).withLock {
            val gitDir = repos.existing(placed)
            val state = states.read(gitDir)
                ?: RemoteState(remotes.remoteFor(gitDir) ?: throw GitGateException(GitMessages.UNKNOWN_REMOTE))
            val approved = (state.approvedWorkflows - approvalKey + approvalKey).takeLast(MAX_APPROVALS)
            states.write(gitDir, state.copy(approvedWorkflows = approved))
        }
    }

    override suspend fun <T> withRepository(bareRepo: File, block: (Repository) -> T): T =
        withContext(io) { useRepo(bareRepo) { block(it.repo) } }

    /**
     * Builds the clone out of Linux's sight and moves it into place only when complete. Under the
     * projects module's temporary name the finished repo may exist as well, and shares the
     * record: that record is replaced only once the new clone is in place, keeping the owner's
     * approvals for the same repository.
     */
    private fun cloneInto(target: File, remote: URIish, url: String, token: String, job: Job) {
        if (target.exists()) Files.delete(target.toPath())
        val staging = states.staging(target)
        deleteTree(staging)
        try {
            val state = repos.create(staging, target, url).use { repo ->
                val result = withTransport(repo, remote, token) { it.fetch(Progress(job), TRACKING_SPECS) }
                val branch = defaultBranch(result, previous = null) ?: DEFAULT_BRANCH
                startDefaultBranch(repo, branch)
                val approved = states.read(target)?.takeIf { it.url.equals(url, ignoreCase = true) }?.approvedWorkflows
                RemoteState(url, branch, refsOf(result), approved.orEmpty())
            }
            target.parentFile?.mkdirs()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            states.write(target, state)
        } finally {
            // Nothing is left there once the move is done.
            deleteTree(staging)
        }
    }

    /** A clone asked for again: the repo is already on the phone, so it is brought up to date. */
    private fun refetch(target: File, url: String, token: String, job: Job) {
        val gitDir = repos.existing(target)
        val recorded = states.read(gitDir)
        val known = recorded?.url ?: remotes.remoteFor(gitDir)
        if (known == null || !known.equals(url, ignoreCase = true)) throw GitGateException(GitMessages.ANOTHER_COPY)
        if (recorded == null) states.write(gitDir, RemoteState(url))
        useLocked(gitDir) { fetchInto(it, token, job) }
    }

    private fun fetchInto(gateRepo: GateRepo, token: String, job: Job) {
        val result = withTransport(gateRepo.repo, gateRepo.remote, token) { transport ->
            transport.isRemoveDeletedRefs = true
            transport.fetch(Progress(job), TRACKING_SPECS)
        }
        val branch = defaultBranch(result, gateRepo.state?.defaultBranch)
        states.write(gateRepo.gitDir, gateRepo.recorded().copy(defaultBranch = branch, refs = refsOf(result)))
        branch?.let { fastForward(gateRepo, it) }
    }

    private fun pushBranch(
        gateRepo: GateRepo,
        ref: String,
        token: String,
        knownValues: List<String>,
        job: Job,
    ): PushResult {
        val repo = gateRepo.repo
        // This exact commit is checked and pushed, even if Linux moves the branch meanwhile.
        val tip = repo.exactRef(ref)?.objectId
            ?: return PushResult.Failed(GitMessages.missingBranch(ref.removePrefix(Constants.R_HEADS)))
        return withTransport(repo, gateRepo.remote, token) { transport ->
            // What GitHub has right now; commits it has are not checked again.
            val onGitHub = transport.openFetch().use { connection -> idsOf(connection.refs) }
            val approved = gateRepo.recorded().approvedWorkflows
            val verdict = scanner.check(repo, tip, onGitHub.values, knownValues, approved) { job.ensureActive() }
            if (!verdict.ok) return PushResult.Blocked(verdict)
            val update = RemoteRefUpdate(repo, null as String?, tip, ref, false, trackingRef(ref), null)
            val result = transport.push(Progress(job), listOf(update))
            val outcome = outcomeOf(result.getRemoteUpdate(ref) ?: update, result.messages)
            if (outcome == PushResult.Pushed) {
                val refs = onGitHub.mapValues { it.value.name } + (ref to tip.name)
                states.write(gateRepo.gitDir, gateRepo.recorded().copy(refs = refs))
            }
            outcome
        }
    }

    private fun deleteBranch(gateRepo: GateRepo, ref: String, token: String, job: Job): PushResult {
        val update = RemoteRefUpdate(gateRepo.repo, null as String?, ref, false, trackingRef(ref), null)
        val result = withTransport(gateRepo.repo, gateRepo.remote, token) { it.push(Progress(job), listOf(update)) }
        val done = result.getRemoteUpdate(ref) ?: update
        if (done.status != RemoteRefUpdate.Status.OK && done.status != RemoteRefUpdate.Status.NON_EXISTING) {
            return outcomeOf(done, result.messages)
        }
        deleteRef(gateRepo.repo, trackingRef(ref))
        gateRepo.state?.let { states.write(gateRepo.gitDir, it.copy(refs = it.refs - ref)) }
        return PushResult.Pushed
    }

    private fun outcomeOf(update: RemoteRefUpdate, remoteSaid: String?): PushResult = when (update.status) {
        RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> PushResult.Pushed
        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
            PushResult.Rejected(GitMessages.NEWER_ON_GITHUB)
        RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
            PushResult.Failed(GitMessages.refusedBecause(update.message.orEmpty(), remoteSaid.orEmpty()))
        RemoteRefUpdate.Status.REJECTED_NODELETE -> PushResult.Failed(GitMessages.DELETE_REFUSED)
        else -> PushResult.Failed(GitMessages.NOT_CONFIRMED)
    }

    /** The branch GitHub's HEAD points at: from the advertised symref, else the branch at the same commit. */
    private fun defaultBranch(result: FetchResult, previous: String?): String? {
        val head = result.getAdvertisedRef(Constants.HEAD) ?: return previous
        if (head.isSymbolic) {
            val target = head.target.name
            return if (target.startsWith(Constants.R_HEADS)) target.removePrefix(Constants.R_HEADS) else previous
        }
        val id = head.objectId ?: return previous
        val same = result.advertisedRefs
            .filter { it.name.startsWith(Constants.R_HEADS) && it.objectId == id }
            .map { it.name.removePrefix(Constants.R_HEADS) }
        return listOfNotNull(previous, DEFAULT_BRANCH, "master").firstOrNull(same::contains)
            ?: same.minOrNull()
            ?: previous
    }

    private fun startDefaultBranch(repo: Repository, branch: String) {
        val head = repo.updateRef(Constants.HEAD)
        head.disableRefLog()
        requireUpdated(head.link(Constants.R_HEADS + branch))
        // An empty repository on GitHub has no commit to start the branch from.
        val tip = repo.exactRef(TRACKING_PREFIX + branch)?.objectId ?: return
        val local = repo.updateRef(Constants.R_HEADS + branch)
        local.setNewObjectId(tip)
        local.setExpectedOldObjectId(ObjectId.zeroId())
        requireUpdated(local.update())
    }

    /** Moves the local default branch up to GitHub's, only forward: local work is never rewritten. */
    private fun fastForward(gateRepo: GateRepo, branch: String) {
        val repo = gateRepo.repo
        val name = Constants.R_HEADS + branch
        val remoteTip = repo.exactRef(TRACKING_PREFIX + branch)?.objectId ?: return
        val localTip = repo.exactRef(name)?.objectId
        if (localTip == remoteTip || isCheckedOut(gateRepo.gitDir, name)) return
        RevWalk(repo).use { walk ->
            val remoteCommit = commitOrNull(walk, remoteTip) ?: return
            if (localTip != null) {
                val localCommit = commitOrNull(walk, localTip) ?: return
                if (!walk.isMergedInto(localCommit, remoteCommit)) return
            }
            val update = repo.updateRef(name)
            update.setNewObjectId(remoteTip)
            // If Linux moves the branch in the meantime, this fails and the branch stays as Linux left it.
            update.setExpectedOldObjectId(localTip ?: ObjectId.zeroId())
            update.update(walk)
        }
    }

    private fun statsOf(repo: Repository, ref: String, base: String): Pair<Int, Int> {
        val tip = repo.exactRef(ref)?.objectId ?: return 0 to 0
        val baseTip = baseOf(repo, base)
        RevWalk(repo).use { walk ->
            val tipCommit = walk.parseCommit(tip)
            val baseCommit = baseTip?.let(walk::parseCommit)
            walk.markStart(tipCommit)
            baseCommit?.let(walk::markUninteresting)
            val commits = walk.count()
            val from = baseCommit?.let { mergeBase(repo, tipCommit, it) ?: it }
            return commits to filesChanged(repo, from?.tree, tipCommit.tree)
        }
    }

    private fun baseOf(repo: Repository, base: String): ObjectId? {
        val names = when {
            base.startsWith(Constants.R_REFS) -> listOf(base)
            else -> listOf(Constants.R_HEADS + base, TRACKING_PREFIX + base)
        }
        return names.firstNotNullOfOrNull { repo.exactRef(it)?.objectId }
    }

    private fun mergeBase(repo: Repository, a: RevCommit, b: RevCommit): RevCommit? = RevWalk(repo).use { walk ->
        walk.revFilter = RevFilter.MERGE_BASE
        walk.markStart(walk.parseCommit(a))
        walk.markStart(walk.parseCommit(b))
        walk.next()
    }

    /** Files changed from [from] (nothing, when null) to [to]; a rename counts once. */
    private fun filesChanged(repo: Repository, from: RevTree?, to: RevTree): Int = TreeWalk(repo).use { tree ->
        tree.isRecursive = true
        tree.filter = TreeFilter.ANY_DIFF
        if (from == null) tree.addTree(EmptyTreeIterator()) else tree.addTree(from)
        tree.addTree(to)
        val renames = RenameDetector(repo)
        renames.addAll(DiffEntry.scan(tree))
        renames.compute().size
    }

    /** One step on [io]; any failure becomes a [GitGateException] with a plain sentence. */
    private suspend fun <T> step(block: suspend CoroutineScope.() -> T): T = withContext(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitGateException) {
            throw e
        } catch (e: Exception) {
            // JGit reports a cancelled transfer as its own error.
            ensureActive()
            throw GitGateException(plainReason(e), e)
        }
    }

    private suspend fun pushStep(block: suspend CoroutineScope.() -> PushResult): PushResult = try {
        step(block)
    } catch (e: GitGateException) {
        PushResult.Failed(e.message)
    }

    /** A bare repo open for one step, with the gate's record of it. */
    private class GateRepo(
        val gitDir: File,
        val repo: Repository,
        val url: String,
        val remote: URIish,
        val state: RemoteState?,
    ) {
        /** The gate's record, or a new one for a repo it has none of. */
        fun recorded(): RemoteState = state?.copy(url = url) ?: RemoteState(url)
    }

    private suspend fun <T> useRepo(bareRepo: File, block: (GateRepo) -> T): T {
        val placed = repos.placed(bareRepo)
        // Checked once the lock is held, right before use: a room can change the repo meanwhile.
        return lockFor(placed).withLock { useLocked(repos.existing(placed), block) }
    }

    /** Opens [gitDir] with its config replaced; the caller holds its lock. */
    private fun <T> useLocked(gitDir: File, block: (GateRepo) -> T): T {
        val state = states.read(gitDir)
        val url = state?.url ?: remotes.remoteFor(gitDir) ?: throw GitGateException(GitMessages.UNKNOWN_REMOTE)
        val remote = remotes.allowed(url)
        val repo = try {
            repos.resetConfig(gitDir, url)
            repos.open(gitDir)
        } catch (e: RepositoryNotFoundException) {
            throw GitGateException(GitMessages.NOT_ON_PHONE, e)
        } catch (e: IOException) {
            throw GitGateException(plainReason(e), e)
        }
        return repo.use { block(GateRepo(gitDir, it, url, remote, state)) }
    }

    private fun lockFor(gitDir: File): Mutex = locks.getOrPut(repos.identity(gitDir)) { Mutex() }

    /** A transport to [remote] only, with the token in memory for this one use. */
    private inline fun <T> withTransport(repo: Repository, remote: URIish, token: String, block: (Transport) -> T): T {
        val credentials = TokenCredentials(token, remotes)
        try {
            return Transport.open(repo, remote).use { transport ->
                transport.credentialsProvider = credentials
                transport.timeout = timeoutSeconds
                transport.tagOpt = TagOpt.AUTO_FOLLOW
                block(transport)
            }
        } finally {
            credentials.clear()
        }
    }

    private companion object {
        const val DEFAULT_BRANCH = "main"
        val TRACKING_SPECS = listOf(RefSpec(TRACKING_SPEC))
        const val SESSION_BRANCHES = "${Constants.R_HEADS}pocket/"
        const val TIMEOUT_SECONDS = 60

        fun trackingRef(ref: String) = TRACKING_PREFIX + ref.removePrefix(Constants.R_HEADS)

        fun refsOf(result: FetchResult): Map<String, String> = idsOf(result.advertisedRefs).mapValues { it.value.name }

        fun idsOf(refs: Collection<Ref>): Map<String, ObjectId> =
            refs.filterNot(Ref::isSymbolic).mapNotNull { ref -> ref.objectId?.let { ref.name to it } }.toMap()

        fun commitOrNull(walk: RevWalk, id: ObjectId): RevCommit? = try {
            walk.parseCommit(id)
        } catch (e: MissingObjectException) {
            null
        } catch (e: IncorrectObjectTypeException) {
            null
        }

        /** Whether a worktree has [refName] checked out; git never moves such a branch under it. */
        fun isCheckedOut(gitDir: File, refName: String): Boolean =
            File(gitDir, "worktrees").listFiles().orEmpty().any { worktree ->
                File(worktree, Constants.HEAD).readSmallText(MAX_HEAD_BYTES)?.trim() == "ref: $refName"
            }

        fun deleteRef(repo: Repository, name: String) {
            if (repo.exactRef(name) == null) return
            val update = repo.updateRef(name)
            update.isForceUpdate = true
            update.delete()
        }

        fun requireUpdated(result: RefUpdate.Result) {
            val fine = result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED ||
                result == RefUpdate.Result.FAST_FORWARD || result == RefUpdate.Result.NO_CHANGE
            if (!fine) throw IOException("ref update: $result")
        }

        const val MAX_HEAD_BYTES = 4096L

        /** Older approvals are dropped; their content is long replaced on any active branch. */
        const val MAX_APPROVALS = 500
    }
}

/** Lets JGit notice a cancelled coroutine; it asks between the steps of a fetch or push. */
private class Progress(private val job: Job) : EmptyProgressMonitor() {
    override fun isCancelled(): Boolean = !job.isActive
}

/** The full ref of a branch; a [GitGateException] when git would not accept the name. */
internal fun branchRef(branch: String): String {
    val ref = if (branch.startsWith(Constants.R_HEADS)) branch else Constants.R_HEADS + branch
    if (ref == Constants.R_HEADS || !Repository.isValidRefName(ref)) {
        throw GitGateException(GitMessages.invalidBranch(branch))
    }
    return ref
}
