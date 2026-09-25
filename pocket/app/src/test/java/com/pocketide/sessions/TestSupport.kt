package com.pocketide.sessions

import com.pocketide.core.AppDirs
import com.pocketide.git.GitGate
import com.pocketide.git.PushResult
import com.pocketide.git.Verdict
import com.pocketide.github.DeviceCode
import com.pocketide.github.DevicePoll
import com.pocketide.github.GitHubAccount
import com.pocketide.github.GitHubAuth
import com.pocketide.linux.Computer
import com.pocketide.linux.ComputerInfo
import com.pocketide.linux.ComputerState
import com.pocketide.linux.LinuxCommand
import com.pocketide.media.MediaItem
import com.pocketide.media.MediaKind
import com.pocketide.media.MediaLibrary
import com.pocketide.model.LinkHealth
import com.pocketide.model.Project
import com.pocketide.projects.Projects
import com.pocketide.rooms.RoomState
import com.pocketide.rooms.Rooms
import com.pocketide.rooms.TerminalHandle
import com.pocketide.secrets.ProjectSecrets
import com.pocketide.secrets.ProjectValue
import com.pocketide.secrets.SecretKind
import com.pocketide.sync.DataUsage
import com.pocketide.sync.PendingUpload
import com.pocketide.sync.RestoreChoice
import com.pocketide.sync.RestorePlan
import com.pocketide.sync.SyncEngine
import com.pocketide.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

const val SESSION_A = "5f0c2a4e-8d1b-4c3a-9e2f-7b6d5c4a3b21"
const val SESSION_B = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
const val PROJECT_ID = "alice/demo"

/** The git arguments of a command, after the fixed safety options. */
fun gitArgs(command: LinuxCommand): List<String> {
    val args = command.argv.drop(1).toMutableList()
    while (args.size >= 2 && args[0] == "-c") repeat(2) { args.removeAt(0) }
    return args
}

/** The host folder a guest path of [command] lives in, through the command's binds. */
fun hostPath(command: LinuxCommand, guest: String): File {
    val bind = command.binds.first { guest == it.guestPath || guest.startsWith(it.guestPath + "/") }
    return File(bind.hostPath + guest.removePrefix(bind.guestPath))
}

/** A computer whose answers are scripted per command; it records what ran. */
class ScriptedComputer(private val answer: (LinuxCommand) -> Pair<Int, List<String>>) : Computer {
    val commands = mutableListOf<LinuxCommand>()
    val ran: List<List<String>> get() = commands.map(::gitArgs)

    override val state = MutableStateFlow<ComputerState>(ComputerState.Ready)
    override suspend fun install() = Unit
    override suspend fun reset() = Unit
    override fun start(command: LinuxCommand): Process = throw UnsupportedOperationException("Sessions only run commands")
    override suspend fun run(command: LinuxCommand, onLine: (String) -> Unit): Int {
        commands += command
        val (code, lines) = answer(command)
        lines.forEach(onLine)
        return code
    }
    override fun stop(process: Process) = Unit
    override suspend fun info() = ComputerInfo("", 0, "", "", null, null, null, 0, null)
    override fun sizeBytes(): Long = 0
}

/**
 * Runs the real git of this machine for each command, with the command's guest paths (/repos,
 * /work) mapped to their bound host folders: the same commands the phone runs inside Linux.
 */
class HostGitComputer(private val scratch: File) : Computer {
    val commands = mutableListOf<LinuxCommand>()

    override val state = MutableStateFlow<ComputerState>(ComputerState.Ready)
    override suspend fun install() = Unit
    override suspend fun reset() = Unit
    override fun start(command: LinuxCommand): Process = throw UnsupportedOperationException("Sessions only run commands")
    override suspend fun run(command: LinuxCommand, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        commands += command
        val argv = command.argv.map { translate(it, command) }
        val builder = ProcessBuilder(argv).directory(scratch)
        builder.environment().clear()
        builder.environment()["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
        builder.environment()["HOME"] = scratch.absolutePath
        builder.environment().putAll(command.env)
        if (command.mergeErrors) builder.redirectErrorStream(true) else builder.redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
        val process = builder.start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        process.waitFor(60, TimeUnit.SECONDS)
        process.exitValue()
    }
    override fun stop(process: Process) = Unit
    override suspend fun info() = ComputerInfo("", 0, "", "", null, null, null, 0, null)
    override fun sizeBytes(): Long = 0

    private fun translate(arg: String, command: LinuxCommand): String {
        for (bind in command.binds) {
            if (arg == bind.guestPath) return bind.hostPath
            if (arg.startsWith(bind.guestPath + "/")) return bind.hostPath + arg.removePrefix(bind.guestPath)
        }
        return arg
    }

    companion object {
        val available: Boolean by lazy {
            try {
                ProcessBuilder("git", "--version").start().waitFor() == 0
            } catch (missing: java.io.IOException) {
                false
            }
        }
    }
}

/** Runs host git and returns its trimmed output; fails the test on an error. */
fun hostGit(dir: File, vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgSign=false") + args)
        .directory(dir).redirectErrorStream(true)
        .also { it.environment()["GIT_CONFIG_NOSYSTEM"] = "1"; it.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null" }
        .start()
    val out = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
    return out.trim()
}

/** A git gate whose results are set by the test; it records what was asked. */
open class FakeGitGate : GitGate {
    val fetched = mutableListOf<File>()
    val pushed = mutableListOf<String>()
    var pushResult: PushResult = PushResult.Pushed
    var statsResult: Pair<Int, Int> = 1 to 1
    var unpushedCommits = 0

    override suspend fun clone(cloneUrl: String, bareRepo: File, token: String): Unit =
        throw UnsupportedOperationException("Not cloned in these tests")
    override suspend fun fetch(bareRepo: File, token: String) {
        fetched += bareRepo
    }
    override suspend fun checkPost(bareRepo: File, branch: String, knownValues: List<String>) = Verdict(true, emptyList(), unpushedCommits)
    override suspend fun push(bareRepo: File, branch: String, token: String, knownValues: List<String>): PushResult {
        pushed += branch
        return pushResult
    }
    override suspend fun stats(bareRepo: File, branch: String, base: String): Pair<Int, Int> = statsResult
    val deletedRemote = mutableListOf<String>()
    override suspend fun deleteRemoteBranch(bareRepo: File, branch: String, token: String): PushResult {
        deletedRemote += branch
        return PushResult.Pushed
    }
    override suspend fun <T> withRepository(bareRepo: File, block: (org.eclipse.jgit.lib.Repository) -> T): T =
        org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(bareRepo).setBare().build().use(block)
}

/** A git gate that really fetches from and pushes to a local "GitHub" repository with host git. */
class LocalRemoteGitGate(private val origin: File) : FakeGitGate() {
    var blockNextPush: Verdict? = null

    override suspend fun clone(cloneUrl: String, bareRepo: File, token: String) {
        hostGit(checkNotNull(bareRepo.parentFile), "clone", "--quiet", "--bare", origin.absolutePath, bareRepo.absolutePath)
        hostGit(bareRepo, "config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*")
        hostGit(bareRepo, "fetch", "--quiet", "origin")
    }

    override suspend fun fetch(bareRepo: File, token: String) {
        fetched += bareRepo
        hostGit(bareRepo, "fetch", "--quiet", "--prune", "origin")
    }

    override suspend fun checkPost(bareRepo: File, branch: String, knownValues: List<String>): Verdict {
        val count = hostGit(bareRepo, "rev-list", "--count", "refs/heads/$branch", "--not", "--remotes=origin").toInt()
        return Verdict(true, emptyList(), count)
    }

    override suspend fun push(bareRepo: File, branch: String, token: String, knownValues: List<String>): PushResult {
        pushed += branch
        blockNextPush?.let {
            blockNextPush = null
            return PushResult.Blocked(it)
        }
        hostGit(bareRepo, "push", "--quiet", "origin", "refs/heads/$branch:refs/heads/$branch")
        // As the real gate does: the remote-tracking ref follows a successful push.
        hostGit(bareRepo, "update-ref", "refs/remotes/origin/$branch", "refs/heads/$branch")
        return PushResult.Pushed
    }

    override suspend fun deleteRemoteBranch(bareRepo: File, branch: String, token: String): PushResult {
        deletedRemote += branch
        hostGit(bareRepo, "push", "--quiet", "origin", "--delete", branch)
        hostGit(bareRepo, "update-ref", "-d", "refs/remotes/origin/$branch")
        return PushResult.Pushed
    }

    override suspend fun stats(bareRepo: File, branch: String, base: String): Pair<Int, Int> {
        val commits = hostGit(bareRepo, "rev-list", "--count", "refs/remotes/origin/$base..refs/heads/$branch").toInt()
        val files = hostGit(bareRepo, "diff", "--name-only", "refs/remotes/origin/$base...refs/heads/$branch").lines().count { it.isNotBlank() }
        return commits to files
    }
}

class FakeProjects(initial: List<Project>, private val onEnsureCloned: suspend (String) -> Unit = {}) : Projects {
    private val flow = MutableStateFlow(initial)
    val touchedAt = mutableMapOf<String, Long>()

    override val all: StateFlow<List<Project>> = flow
    override suspend fun create(name: String, description: String): Project = throw UnsupportedOperationException()
    override suspend fun import(owner: String, repo: String): Project = throw UnsupportedOperationException()
    override suspend fun ensureCloned(projectId: String) = onEnsureCloned(projectId)
    override suspend fun fetch(projectId: String) = Unit
    override suspend fun remove(projectId: String) = Unit
    override fun touched(projectId: String) = Unit
    override fun touched(projectId: String, at: Long) {
        touchedAt[projectId] = at
    }
}

class FakeGitHubAuth(signedIn: Boolean = true) : GitHubAuth {
    override val account = MutableStateFlow(if (signedIn) GitHubAccount("alice", 42, "Alice Example", null) else null)
    override val configured = true
    override suspend fun startDeviceFlow(): DeviceCode = throw UnsupportedOperationException()
    override suspend fun poll(code: DeviceCode): DevicePoll = throw UnsupportedOperationException()
    override suspend fun token(): String = "test-token"
    override suspend fun health() = LinkHealth.OK
    override fun installUrl() = "https://github.com/apps/pocketide/installations/new"
    override suspend fun signOut() = Unit
}

class FakeSecrets : ProjectSecrets {
    override val values: StateFlow<List<ProjectValue>> = MutableStateFlow(emptyList())
    override suspend fun set(projectId: String?, name: String, kind: SecretKind, value: CharArray) = Unit
    override suspend fun reveal(projectId: String?, name: String): CharArray? = null
    override suspend fun remove(projectId: String?, name: String) = Unit
    override suspend fun variablesFor(projectId: String): Map<String, String> = emptyMap()
    override suspend fun allValues(): List<String> = listOf("the-deploy-secret")
    override suspend fun pushToGitHub(projectId: String, name: String) = Unit
    override suspend fun exportBlob(): ByteArray = ByteArray(0)
    override suspend fun importBlob(bytes: ByteArray) = Unit
}

class FakeRooms : Rooms {
    override val states = MutableStateFlow<Map<String, RoomState>>(emptyMap())
    override val previewPorts: StateFlow<Map<String, List<Int>>> = MutableStateFlow(emptyMap())
    val opened = mutableListOf<Pair<String, String>>()
    val stopped = mutableListOf<String>()

    override suspend fun open(agentId: String, sessionId: String): RoomState {
        opened += agentId to sessionId
        return RoomState.Running("http://127.0.0.1:1/", sessionId, 0).also { states.value = states.value + (agentId to it) }
    }
    override suspend fun stop(agentId: String) {
        stopped += agentId
        states.value = states.value - agentId
    }
    override suspend fun stopAll() = Unit
    override suspend fun terminal(sessionId: String): TerminalHandle = throw UnsupportedOperationException()
    override suspend fun configure(agentId: String) = Unit
    override suspend fun delete(agentId: String) = Unit
}

class FakeSync(private val onFetch: (String) -> Unit = {}) : SyncEngine {
    val requests = mutableListOf<String>()
    val fetched = mutableListOf<String>()
    val erasedForever = mutableListOf<String>()
    var uploadFails = false
    var eraseFails = false
    var queueFails = false
    /** Sessions with bytes Drive has not confirmed; an upload that does not fail takes them there. */
    val notInDrive: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** What each [queueNow] was asked for, in order. */
    val queueRequests: MutableList<List<String>> = java.util.Collections.synchronizedList(mutableListOf())

    override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)
    override val waiting: StateFlow<List<PendingUpload>> = MutableStateFlow(emptyList())
    override val usage: StateFlow<DataUsage> = MutableStateFlow(DataUsage(0, 0, emptyMap()))
    override val leaseHolder: StateFlow<String?> = MutableStateFlow(null)
    override val driveSessions: StateFlow<List<com.pocketide.model.SessionRecord>> = MutableStateFlow(emptyList())
    override val driveProjects: StateFlow<List<Project>> = MutableStateFlow(emptyList())
    override val storage: StateFlow<com.pocketide.sync.StorageSummary> = MutableStateFlow(com.pocketide.sync.StorageSummary())
    override val move: StateFlow<com.pocketide.sync.MoveState> = MutableStateFlow(com.pocketide.sync.MoveState.Idle)
    override suspend fun moveToAccount(email: String) = Unit
    override suspend fun eraseOldAccountCopy() = Unit
    override suspend fun eraseForever(sessionIds: List<String>) {
        if (eraseFails) throw java.io.IOException("offline")
        erasedForever += sessionIds
    }
    override fun requestSync(reason: String) {
        requests += reason
    }
    override suspend fun syncNow() = Unit
    override suspend fun uploadNow(sessionIds: List<String>) {
        if (uploadFails) throw java.io.IOException("offline")
        notInDrive.removeAll(sessionIds.toSet())
    }
    override suspend fun queueNow(sessionIds: List<String>): Set<String> {
        queueRequests += sessionIds
        if (queueFails) throw com.pocketide.sync.SyncException("Your chats' key is not ready on this phone yet.")
        return sessionIds.filterTo(HashSet()) { it in notInDrive }
    }
    override suspend fun restorePlan(): RestorePlan = throw UnsupportedOperationException()
    override suspend fun restore(choice: RestoreChoice) = Unit
    override suspend fun fetchSession(sessionId: String) {
        fetched += sessionId
        onFetch(sessionId)
    }
    override suspend fun takeOver() = Unit
    override suspend fun moveToAnotherAccount() = Unit
    override suspend fun deleteEverything() = Unit
    override fun schedule() = Unit
}

class FakeMedia(private val items: Map<String, List<MediaItem>> = emptyMap(), private val folder: (String) -> File? = { null }) : MediaLibrary {
    val deleted = mutableListOf<MediaItem>()
    val added = mutableListOf<MediaItem>()

    override fun forSession(sessionId: String): Flow<List<MediaItem>> = flowOf(items[sessionId].orEmpty())
    override suspend fun add(sessionId: String, source: File, name: String, from: String): MediaItem {
        val dir = folder(sessionId) ?: throw UnsupportedOperationException()
        val copy = source.copyTo(File(dir.apply { mkdirs() }, name))
        return MediaItem(sessionId, copy, name, MediaKind.OTHER, copy.length(), 0, onPhone = true, backedUp = false, source = from)
            .also { added += it }
    }
    override fun kindOf(name: String, head: ByteArray) = MediaKind.OTHER
    override suspend fun delete(item: MediaItem) {
        deleted += item
    }
    override fun shareUri(item: MediaItem): android.net.Uri = throw UnsupportedOperationException()
}

internal class TestSessionEnv(
    override val projects: Projects,
    override val computer: Computer,
    override val git: GitGate,
    override val gitHubAuth: GitHubAuth = FakeGitHubAuth(),
    override val secrets: ProjectSecrets = FakeSecrets(),
    override val rooms: FakeRooms = FakeRooms(),
    override val sync: FakeSync = FakeSync(),
    override val media: MediaLibrary = FakeMedia(),
) : SessionEnv {
    override val deviceId = "phone-1"
    override fun agentName(agentId: String) = if (agentId == "kilo") "Kilo Code" else agentId
}

fun project(isPrivate: Boolean = true, defaultBranch: String = "main") =
    Project(id = PROJECT_ID, owner = "alice", repo = "demo", defaultBranch = defaultBranch, isPrivate = isPrivate, addedAt = 0, lastActivityAt = 0, cloned = true)

fun testDirs(root: File) = AppDirs(File(root, "files"), File(root, "cache"))

/** Makes [dirs]' bare clone of [PROJECT_ID] look complete to the phone side, with these refs. */
fun fakeBareClone(dirs: AppDirs, vararg refs: String): File {
    val bare = dirs.bareRepo(PROJECT_ID)
    File(bare, "objects").mkdirs()
    File(bare, "HEAD").writeText("ref: refs/heads/main\n")
    refs.forEach { writeRef(bare, it) }
    return bare
}

fun writeRef(bare: File, ref: String, sha: String = "1".repeat(40)) {
    File(bare, ref).apply { parentFile?.mkdirs() }.writeText("$sha\n")
}
