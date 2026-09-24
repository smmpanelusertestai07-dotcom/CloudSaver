package com.pocketide.sessions

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.core.Ist
import com.pocketide.git.PushResult
import com.pocketide.github.NotConnectedException
import com.pocketide.media.MediaItem
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.projects.BareRefs
import com.pocketide.projects.BareRefs.Companion.HEADS
import com.pocketide.projects.BareRefs.Companion.ORIGIN
import com.pocketide.projects.JsonFile
import com.pocketide.projects.JsonState
import com.pocketide.projects.ProjectWork
import com.pocketide.projects.SafeFiles
import com.pocketide.rooms.RoomState
import com.pocketide.sessions.Sessions.Companion.ERASE_NOW
import com.pocketide.sessions.transcripts.AntigravityFormat
import com.pocketide.sessions.transcripts.FileFacts
import com.pocketide.sessions.transcripts.SessionTranscript
import com.pocketide.sessions.transcripts.TranscriptFormat
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.NOTE
import com.pocketide.sessions.transcripts.TranscriptIndex
import com.pocketide.sessions.transcripts.TranscriptView
import com.pocketide.sessions.transcripts.oneLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The chat sessions on this phone, kept in `vault/sessions.json` (the sync engine carries them to
 * the encrypted index in Drive). A session is a branch and a worktree in its agent's room, plus
 * the agent's own transcript, which is read here but never written.
 */
internal class SessionManager(
    private val env: SessionEnv,
    private val dirs: AppDirs,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val branchDate: (Long) -> String = Ist::branchDate,
) : Sessions, ProjectWork {

    private val records = JsonState(
        JsonFile(File(dirs.vault, "sessions.json"), ListSerializer(SessionRecord.serializer())),
        emptyList(),
        io,
    )
    private val active = JsonState(
        JsonFile(File(dirs.vault, "active-sessions.json"), MapSerializer(String.serializer(), String.serializer())),
        emptyMap(),
        io,
    )
    private val transcripts = TranscriptIndex(
        dirs,
        JsonFile(File(dirs.vault, "transcripts.json"), ListSerializer(FileFacts.serializer())),
        io,
    )
    private val git = LinuxGit({ env.computer }, dirs, io)
    private val worktrees = Worktrees(git, dirs)
    private val merger = MainMerger(env, git, worktrees, dirs, io)
    private val autosaver = Autosaver(clock, scope) { pushBranch(it) }
    private val projectLocks = ConcurrentHashMap<String, Mutex>()
    private val refreshLock = Mutex()
    private val large = ConcurrentHashMap.newKeySet<String>()

    override val all: StateFlow<List<SessionRecord>> = records.flow

    init {
        scope.launch {
            quietly {
                records.current()
                active.current()
            }
        }
    }

    override suspend fun start(projectId: String, agentId: String, title: String?): SessionRecord {
        env.projects.ensureCloned(projectId)
        val project = project(projectId)
        val id = UUID.randomUUID().toString()
        val now = clock.now()
        val slug = if (project.isPrivate) BranchNames.slug(title) else BranchNames.neutralSlug(id)
        val base = BranchNames.base(agentId, branchDate(now), slug)
        val taken = records.current().filter { it.projectId == projectId }.mapTo(HashSet()) { it.branch }
        var branch: String
        var attempts = 0
        while (true) {
            val refs = BareRefs(dirs.bareRepo(projectId))
            val prefix = BranchNames.prefix(agentId)
            refs.under(HEADS + prefix).mapTo(taken) { it.removePrefix(HEADS) }
            refs.under(ORIGIN + prefix).mapTo(taken) { it.removePrefix(ORIGIN) }
            branch = BranchNames.unique(base, taken)
            val start = refs.firstOf(ORIGIN + project.defaultBranch, HEADS + project.defaultBranch)
            val made = worktrees.create(agentId, projectId, id, branch, start)
            if (made.ok) break
            // Another session may have taken the name a moment ago: the next free one is tried.
            if (!made.says("already exists") || ++attempts >= MAX_NAME_ATTEMPTS) {
                throw SessionException("Could not create the session's folder: ${made.reason()}")
            }
            taken += branch
        }
        withContext(io) { dirs.sessionMedia(agentId, projectId, id).mkdirs() }
        val record = SessionRecord(
            id = id,
            agentId = agentId,
            projectId = projectId,
            title = title?.let(::cleanTitle)?.takeIf { it.isNotEmpty() } ?: DEFAULT_TITLE,
            branch = branch,
            startedAt = now,
            lastActivityAt = now,
            deviceId = env.deviceId,
        )
        records.update { list -> (listOf(record) + list) to Unit }
        setActive(agentId, id)
        env.sync.requestSync("session started")
        return record
    }

    override suspend fun rename(sessionId: String, title: String) {
        val clean = cleanTitle(title)
        if (clean.isEmpty()) throw SessionException("Give the chat a name.")
        change(sessionId) { it.copy(title = clean) }
        env.sync.requestSync("session renamed")
    }

    override suspend fun continueSession(sessionId: String) {
        val session = session(sessionId)
        when (session.status) {
            SessionStatus.DELETED -> throw SessionException("Restore this chat first.")
            SessionStatus.CONFLICT_COPY -> throw SessionException(CONFLICT_COPY_ONLY_READ)
            SessionStatus.OPEN, SessionStatus.ON_MAIN -> Unit
        }
        if (session.backUp && session.transcriptBytes > 0 && transcriptOf(session)?.main.isNullOrEmpty()) {
            fetchQuietly(sessionId)
        }
        env.projects.ensureCloned(session.projectId)
        worktrees.restore(session, project(session.projectId))
        // A merged chat that goes on gets a fresh branch from main under the same name.
        if (session.status == SessionStatus.ON_MAIN) change(sessionId) { it.copy(status = SessionStatus.OPEN) }
        setActive(session.agentId, sessionId)
        env.rooms.open(session.agentId, sessionId)
    }

    override suspend fun delete(sessionId: String) {
        val session = session(sessionId)
        if (session.status == SessionStatus.DELETED) return
        closeRoom(session)
        change(sessionId) { it.copy(status = SessionStatus.DELETED, deletedAt = clock.now()) }
        clearActive(session)
        // The phone copy goes now; Drive keeps the chat for Recently deleted. Bytes still waiting to
        // be uploaded are given a chance first, and stay on the phone if they cannot go now.
        val waiting = session.backUp && session.pendingBytes > 0 && !uploaded(sessionId)
        if (!waiting) removePhoneCopy(session)
        env.sync.requestSync("session deleted")
    }

    override suspend fun restore(sessionId: String) {
        val session = session(sessionId)
        if (session.status != SessionStatus.DELETED) return
        change(sessionId) { it.copy(status = SessionStatus.OPEN, deletedAt = null) }
        if (session.backUp) fetchQuietly(sessionId)
        env.sync.requestSync("session restored")
    }

    override suspend fun deleteForever(sessionId: String) {
        val session = session(sessionId)
        closeRoom(session)
        change(sessionId) { it.copy(status = SessionStatus.DELETED, deletedAt = ERASE_NOW) }
        clearActive(session)
        removePhoneCopy(session)
        // The branch always stays (unmerged code is never deleted automatically); a clean worktree
        // is only a checkout of it, so it goes. One with uncommitted changes stays.
        try {
            if (worktrees.isDirty(session) == false) worktrees.remove(session)
        } catch (unavailable: SessionException) {
            // Kept; removing the project later checks it for unsaved work.
        }
        large.remove(sessionId)
        autosaver.forget(sessionId)
        env.sync.requestSync("session deleted forever")
    }

    override suspend fun putOnMain(sessionId: String): PutOnMainResult {
        val session = try {
            session(sessionId)
        } catch (missing: SessionException) {
            return PutOnMainResult.Failed(missing.message ?: NOT_HERE)
        }
        when (session.status) {
            SessionStatus.ON_MAIN -> return PutOnMainResult.Failed("This chat is already on main.")
            SessionStatus.DELETED -> return PutOnMainResult.Failed("Restore this chat first.")
            SessionStatus.CONFLICT_COPY -> return PutOnMainResult.Failed("A conflict copy cannot be put on main. Use the original chat.")
            SessionStatus.OPEN -> Unit
        }
        return try {
            if (!BareRefs(dirs.bareRepo(session.projectId)).isCloned()) env.projects.ensureCloned(session.projectId)
            val project = project(session.projectId)
            projectLocks.computeIfAbsent(project.id) { Mutex() }.withLock {
                val outcome = merger.run(session(sessionId), project)
                if (outcome.result == PutOnMainResult.Merged) {
                    val now = clock.now()
                    change(sessionId) { it.copy(status = outcome.status, lastActivityAt = maxOf(it.lastActivityAt, now)) }
                    env.projects.touched(project.id, now)
                    env.sync.requestSync("put on main")
                }
                outcome.result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            PutOnMainResult.Failed(failure.message ?: "Could not put this chat on main.")
        }
    }

    override suspend fun changes(sessionId: String): SessionChanges {
        val session = session(sessionId)
        val project = project(session.projectId)
        val refs = BareRefs(dirs.bareRepo(project.id))
        val nothing = SessionChanges(emptyList(), emptyList())
        val branch = refs.firstOf(HEADS + session.branch, ORIGIN + session.branch) ?: return nothing
        val base = refs.firstOf(ORIGIN + project.defaultBranch, HEADS + project.defaultBranch) ?: return nothing
        val bare = AppDirs.guestBareRepo(project.id)
        val log = git.run(null, listOf("-C", bare, "log", "--format=%h %s", "-n", "$MAX_COMMITS_SHOWN", "$base..$branch"), errors = false)
        val diff = git.run(null, listOf("-C", bare, "diff", "--numstat", "--no-renames", "$base...$branch"), errors = false)
        if (!log.ok || !diff.ok) throw SessionException("Could not read this session's changes.")
        return SessionChanges(log.lines.filter { it.isNotBlank() }, diff.lines.mapNotNull(::changedFile))
    }

    override suspend fun transcript(sessionId: String): List<TranscriptEntry> {
        val session = session(sessionId)
        if (session.status == SessionStatus.DELETED) return note("This chat is in Recently deleted. Restore it to read it.")
        val format = TranscriptFormat.of(session.agentId)
            ?: return note("Open this chat in ${env.agentName(session.agentId)} to read it.")
        var files = transcriptOf(session)?.main?.map { it.file }.orEmpty()
        if (files.isEmpty() && session.backUp && (session.transcriptBytes > 0 || session.status == SessionStatus.CONFLICT_COPY)) {
            fetchQuietly(sessionId)
            files = transcriptOf(session)?.main?.map { it.file }.orEmpty()
        }
        if (files.isEmpty()) {
            return note(
                when {
                    format == AntigravityFormat -> "Open this chat in Antigravity to read it."
                    session.transcriptBytes > 0 -> "This chat is in Drive and could not be downloaded now. Try again when you are online."
                    else -> "No messages yet."
                },
            )
        }
        return withContext(io) { TranscriptView.read(format, files) }
    }

    override suspend fun setBackUp(sessionId: String, backUp: Boolean) {
        change(sessionId) { it.copy(backUp = backUp) }
        env.sync.requestSync("backup choice changed")
    }

    override suspend fun removeMedia(sessionId: String) {
        val session = session(sessionId)
        mediaOf(sessionId)?.forEach { env.media.delete(it) }
        withContext(io) {
            SafeFiles.children(dirs.sessionMedia(session.agentId, session.projectId, session.id)).forEach(SafeFiles::delete)
        }
        change(sessionId) { it.copy(mediaBytes = 0, mediaCount = 0, pendingVideos = 0) }
        env.sync.requestSync("media removed")
    }

    override suspend fun autosave(sessionId: String): String? = autosaver.save(sessionId)

    override suspend fun refresh() = refreshLock.withLock {
        val sessions = records.current().filterNot(::isErasing)
        val found = transcripts.scan(sessions)
        val projects = env.projects.all.value.associateBy { it.id }
        val now = clock.now()
        val measured = HashMap<String, SessionRecord>()
        for (session in sessions) {
            try {
                val updated = measure(session, found[session.id], projects[session.projectId], now)
                if (updated != session) measured[session.id] = updated
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unreadable: Exception) {
                // One unreadable session must not stop the others; it is measured again next time.
            }
        }
        if (measured.isEmpty()) return@withLock
        records.update { list -> list.map { current -> measured[current.id]?.let { withMeasures(current, it) } ?: current } to Unit }
        measured.values.groupBy { it.projectId }.forEach { (projectId, list) ->
            env.projects.touched(projectId, list.maxOf { it.lastActivityAt })
        }
    }

    override fun activeSession(agentId: String): String? {
        val sessionId = active.flow.value[agentId] ?: return null
        return sessionId.takeIf { id -> records.flow.value.any { it.id == id && !isErasing(it) } }
    }

    override fun largeTranscript(sessionId: String): Boolean = sessionId in large

    override suspend fun transcriptFiles(sessionId: String): List<File> {
        val session = session(sessionId)
        return withContext(io) { transcriptOf(session)?.files().orEmpty() }
    }

    override suspend fun adopt(records: List<SessionRecord>) {
        this.records.update { list ->
            val byId = LinkedHashMap<String, SessionRecord>()
            list.forEach { byId[it.id] = it }
            for (incoming in records) {
                if (byId[incoming.id]?.let(::isErasing) == true) continue
                byId[incoming.id] = incoming
            }
            byId.values.sortedByDescending { it.startedAt } to Unit
        }
    }

    override suspend fun erased(sessionIds: List<String>) {
        val ids = sessionIds.toSet()
        val gone = records.update { list ->
            val (drop, keep) = list.partition { it.id in ids && it.status == SessionStatus.DELETED }
            keep to drop
        }
        gone.forEach {
            large.remove(it.id)
            autosaver.forget(it.id)
        }
    }

    // ProjectWork: what removing a project must not lose.

    override suspend fun unsaved(projectId: String): String? {
        val ids = records.current().filter { it.projectId == projectId }.mapTo(HashSet()) { it.id }
        if (env.rooms.states.value.values.any { it is RoomState.Running && it.sessionId in ids }) {
            return "An agent is working on this project. Close it first."
        }
        try {
            for ((agentId, folder) in worktreeFolders(projectId)) {
                val out = git.run(agentId, listOf("-C", "${AppDirs.GUEST_WORK}/${AppDirs.projectDirName(projectId)}/${folder.name}", "status", "--porcelain"))
                if (!out.ok) return "Could not check this project's session folders: ${out.reason()}"
                if (out.lines.any(Worktrees::isChange)) {
                    return "A session of this project has changes that are not committed. Ask its agent to commit them, then try again."
                }
            }
        } catch (unavailable: SessionException) {
            return unavailable.message
        }
        val bare = dirs.bareRepo(projectId)
        val refs = BareRefs(bare)
        if (!refs.isCloned()) return null
        for (branch in refs.localBranches()) {
            val verdict = try {
                env.git.checkPost(bare, branch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return "Could not check the branch $branch for work that is not on GitHub yet."
            }
            if (verdict.commitsScanned > 0) {
                return "The branch $branch has commits that are not on GitHub yet. Let its chat save them or put it on main, then try again."
            }
        }
        return null
    }

    override suspend fun release(projectId: String) {
        withContext(io) {
            for (agentDir in SafeFiles.children(dirs.work)) {
                val projectDir = File(agentDir, AppDirs.projectDirName(projectId))
                // Worktrees and temporary merge folders go; the media folder stays with the chats.
                SafeFiles.children(projectDir).filter { it.name != MEDIA_FOLDER }.forEach(SafeFiles::delete)
            }
        }
        val ids = records.current().filter { it.projectId == projectId }.mapTo(HashSet()) { it.id }
        quietly { active.update { map -> map.filterValues { it !in ids } to Unit } }
    }

    // Helpers.

    private suspend fun measure(session: SessionRecord, transcript: SessionTranscript?, project: Project?, now: Long): SessionRecord {
        var updated = session
        if (transcript != null) {
            val bytes = withContext(io) { transcript.bytes() }
            val title = if (session.title == DEFAULT_TITLE) transcript.firstUserText?.let { oneLine(it, TITLE_FROM_CHAT_CHARS) } else null
            updated = updated.copy(
                transcriptBytes = bytes,
                tokensIn = transcript.tokensIn,
                tokensOut = transcript.tokensOut,
                agentSessionRef = transcript.ref ?: session.agentSessionRef,
                lastActivityAt = maxOf(session.lastActivityAt, transcript.newestAt ?: 0),
                title = title ?: session.title,
            )
            if (transcript.currentBytes > LARGE_TRANSCRIPT_BYTES) large += session.id else large -= session.id
        }
        branchStats(session, project)?.let { (commits, files) ->
            // A commit is agent work too; the transcript usually dates it already.
            val activity = if (commits > session.commits) maxOf(updated.lastActivityAt, now) else updated.lastActivityAt
            updated = updated.copy(commits = commits, filesChanged = files, lastActivityAt = activity)
        }
        mediaOf(session.id)?.let { items ->
            updated = updated.copy(mediaBytes = items.sumOf { it.bytes }, mediaCount = items.size)
        }
        return updated
    }

    /** The measured numbers applied to the record as it is now (it may have been renamed meanwhile). */
    private fun withMeasures(current: SessionRecord, measured: SessionRecord) = current.copy(
        transcriptBytes = measured.transcriptBytes,
        tokensIn = measured.tokensIn,
        tokensOut = measured.tokensOut,
        agentSessionRef = measured.agentSessionRef,
        commits = measured.commits,
        filesChanged = measured.filesChanged,
        mediaBytes = measured.mediaBytes,
        mediaCount = measured.mediaCount,
        lastActivityAt = maxOf(current.lastActivityAt, measured.lastActivityAt),
        title = if (current.title == DEFAULT_TITLE) measured.title else current.title,
    )

    private suspend fun branchStats(session: SessionRecord, project: Project?): Pair<Int, Int>? {
        if (project == null || session.status == SessionStatus.ON_MAIN) return null
        val bare = dirs.bareRepo(project.id)
        if (!BareRefs(bare).exists(HEADS + session.branch)) return null
        return try {
            env.git.stats(bare, session.branch, project.defaultBranch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            null
        }
    }

    private suspend fun mediaOf(sessionId: String): List<MediaItem>? = try {
        withTimeoutOrNull(MEDIA_WAIT_MS) { env.media.forSession(sessionId).first() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unreadable: Exception) {
        null
    }

    /** The transcripts of [session]; all of its agent's sessions are needed to tell theirs apart. */
    private suspend fun transcriptOf(session: SessionRecord): SessionTranscript? {
        val others = records.current().filter { it.agentId == session.agentId && it.id != session.id && !isErasing(it) }
        return transcripts.scan(others + session)[session.id]
    }

    private suspend fun removePhoneCopy(session: SessionRecord) {
        val roots = transcriptOf(session)?.roots.orEmpty()
        withContext(io) {
            roots.forEach(SafeFiles::delete)
            SafeFiles.delete(dirs.sessionMedia(session.agentId, session.projectId, session.id))
        }
        transcripts.forget(roots)
        large.remove(session.id)
    }

    private suspend fun uploaded(sessionId: String): Boolean = try {
        env.sync.uploadNow(listOf(sessionId))
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (offline: Exception) {
        false
    }

    private suspend fun pushBranch(sessionId: String): String? {
        val session = records.current().find { it.id == sessionId && !isErasing(it) } ?: return NOT_HERE
        if (session.status == SessionStatus.ON_MAIN || session.status == SessionStatus.CONFLICT_COPY) return null
        val project = env.projects.all.value.find { it.id == session.projectId } ?: return "This chat's project is not on this phone."
        val bare = dirs.bareRepo(project.id)
        if (!BareRefs(bare).exists(HEADS + session.branch)) return null
        // A branch goes to GitHub with its first commit: a session without commits leaves nothing there.
        if (branchStats(session, project)?.first == 0) return null
        val token = try {
            env.gitHubAuth.token()
        } catch (signedOut: NotConnectedException) {
            return "Connect GitHub to save this chat's code."
        }
        val result = try {
            env.git.push(bare, session.branch, token, env.secrets.allValues())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failure.message ?: "Could not reach GitHub. The code is saved on this phone."
        }
        return when (result) {
            PushResult.Pushed -> null
            is PushResult.Blocked -> CheckPostWords.blocked(result.verdict)
            is PushResult.Rejected -> "GitHub did not accept this chat's branch: ${result.why}"
            is PushResult.Failed -> result.why
        }
    }

    private suspend fun closeRoom(session: SessionRecord) {
        val state = env.rooms.states.value[session.agentId]
        if (state is RoomState.Running && state.sessionId == session.id) quietly { env.rooms.stop(session.agentId) }
    }

    private suspend fun setActive(agentId: String, sessionId: String) =
        quietly { active.update { it + (agentId to sessionId) to Unit } }

    private suspend fun clearActive(session: SessionRecord) = quietly {
        active.update { map -> (if (map[session.agentId] == session.id) map - session.agentId else map) to Unit }
    }

    private suspend fun fetchQuietly(sessionId: String) = quietly { env.sync.fetchSession(sessionId) }

    /** Session folders of a project in every room (not the media folder), as (agent, folder). */
    private fun worktreeFolders(projectId: String): List<Pair<String, File>> =
        SafeFiles.children(dirs.work).flatMap { agentDir ->
            SafeFiles.children(File(agentDir, AppDirs.projectDirName(projectId)))
                .filter { !it.name.startsWith(".") && SafeFiles.isFile(File(it, ".git")) }
                .map { agentDir.name to it }
        }

    private suspend fun session(sessionId: String): SessionRecord =
        records.current().find { it.id == sessionId && !isErasing(it) } ?: throw SessionException(NOT_HERE)

    private fun project(projectId: String): Project =
        env.projects.all.value.find { it.id == projectId } ?: throw SessionException("This chat's project is not on this phone.")

    private suspend fun change(sessionId: String, edit: (SessionRecord) -> SessionRecord) {
        records.update { list ->
            if (list.none { it.id == sessionId && !isErasing(it) }) throw SessionException(NOT_HERE)
            list.map { if (it.id == sessionId) edit(it) else it } to Unit
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            // Best effort: nothing the owner has to act on.
        }
    }

    private fun isErasing(session: SessionRecord) =
        session.status == SessionStatus.DELETED && session.deletedAt == ERASE_NOW

    private fun cleanTitle(title: String) = oneLine(title, MAX_TITLE_CHARS)

    private fun changedFile(line: String): ChangedFile? {
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return null
        // Binary files show "-" instead of line counts.
        return ChangedFile(parts[2], parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
    }

    private fun note(text: String) = listOf(TranscriptEntry(NOTE, text, null))

    companion object {
        const val DEFAULT_TITLE = "New session"
        const val LARGE_TRANSCRIPT_BYTES = 10L * 1024 * 1024
        private const val MAX_TITLE_CHARS = 100
        private const val TITLE_FROM_CHAT_CHARS = 60
        private const val MAX_NAME_ATTEMPTS = 3
        private const val MAX_COMMITS_SHOWN = 500
        private const val MEDIA_WAIT_MS = 5_000L
        private const val MEDIA_FOLDER = ".media"
        private const val NOT_HERE = "This chat is not on this phone."
        private const val CONFLICT_COPY_ONLY_READ = "This is a conflict copy. Read it here, or continue the original chat."
    }
}
