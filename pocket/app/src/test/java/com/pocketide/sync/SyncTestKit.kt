package com.pocketide.sync

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.core.Settings
import com.pocketide.core.SettingsStore
import com.pocketide.google.DriveAuthResult
import com.pocketide.google.DriveException
import com.pocketide.google.DriveFile
import com.pocketide.google.DriveQuota
import com.pocketide.google.DriveStore
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.vault.VaultCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant

/** A settable clock, starting on 24 Sep 2026. */
class FakeClock(var now: Long = 1_790_000_000_000L) : Clock {
    override fun now(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/**
 * age stand-in: a header naming the key generation, then the bytes XOR-ed, so a test can see that
 * nothing reached Drive in the clear and which key encrypted it.
 */
class FakeCipher(var generation: Int = 1) : VaultCipher {
    override fun encrypt(plain: InputStream, out: OutputStream) {
        out.write(encryptBytes(plain.readBytes()))
    }

    override fun decrypt(encrypted: InputStream, out: OutputStream) {
        out.write(decryptBytes(encrypted.readBytes()))
    }

    override fun encryptBytes(plain: ByteArray): ByteArray = HEADER + byteArrayOf(generation.toByte()) + plain.map { (it.toInt() xor KEY).toByte() }

    override fun decryptBytes(encrypted: ByteArray): ByteArray {
        require(encrypted.size > HEADER.size && encrypted.copyOfRange(0, HEADER.size).contentEquals(HEADER)) { "not encrypted" }
        return encrypted.copyOfRange(HEADER.size + 1, encrypted.size).map { (it.toInt() xor KEY).toByte() }.toByteArray()
    }

    companion object {
        val HEADER = "FAKEAGE1".toByteArray()
        private const val KEY = 0x5A

        fun generationOf(encrypted: ByteArray): Int = encrypted[HEADER.size].toInt()
    }
}

/** Every Google account the tests use; each has its own hidden folder. */
class FakeAccounts(private val clock: FakeClock) {
    private val stores = HashMap<String, FakeDrive>()
    operator fun get(email: String): FakeDrive = stores.getOrPut(email) { FakeDrive(this, email, clock) }
}

/** The Drive hidden folder in memory, with the failures the engine must survive. */
class FakeDrive(private val accounts: FakeAccounts, val email: String, private val clock: FakeClock) : DriveStore {
    class Stored(val name: String, var bytes: ByteArray, var modified: Long)

    val files = LinkedHashMap<String, Stored>()
    private val lock = Any()
    private var nextId = 1
    var offline = false
    var quotaBytes = Long.MAX_VALUE
    /** The next N uploads reach Drive but the answer is lost, as when the phone dies mid-request. */
    var loseUploadAnswers = 0
    /** The next N index writes fail before reaching Drive (the phone dies between upload and record). */
    var failIndexWrites = 0
    /** Runs before each upload with the file's name; may throw to fail it. */
    var beforeUpload: ((String) -> Unit)? = null
    /** Runs after each index write, before the engine reads it back (another phone writing at once). */
    var afterIndexWrite: (() -> Unit)? = null
    var uploads = 0
    var deletes = 0

    fun named(name: String): List<Stored> = files.values.filter { it.name == name }
    fun objectNames(): Set<String> = files.values.map { it.name }.filter { it.startsWith("o-") }.toSet()
    fun usedBytes(): Long = files.values.sumOf { it.bytes.size.toLong() }

    override suspend fun list(): List<DriveFile> = online { files.map { (id, f) -> info(id, f) } }

    override suspend fun find(name: String): DriveFile? = online { files.entries.firstOrNull { it.value.name == name }?.let { info(it.key, it.value) } }

    override suspend fun upload(name: String, source: File, existingId: String?): DriveFile = uploadBytes(name, source.readBytes(), existingId)

    override suspend fun uploadBytes(name: String, bytes: ByteArray, existingId: String?): DriveFile {
        beforeUpload?.invoke(name)
        if (name == RemoteIndex.NAME && failIndexWrites > 0) {
            failIndexWrites--
            throw DriveException.Offline()
        }
        val stored = online {
            val growth = bytes.size - (existingId?.let { files[it]?.bytes?.size } ?: 0)
            if (usedBytes() + growth > quotaBytes) throw DriveException.StorageFull()
            uploads++
            val id = existingId?.takeIf { it in files } ?: "id-${nextId++}"
            files[id] = Stored(name, bytes.copyOf(), clock.now)
            info(id, files.getValue(id))
        }
        if (name == RemoteIndex.NAME) afterIndexWrite?.invoke()
        val lose = synchronized(lock) { (loseUploadAnswers > 0).also { if (it) loseUploadAnswers-- } }
        if (lose) throw DriveException.Offline()
        return stored
    }

    override suspend fun download(id: String, sink: OutputStream) = online {
        sink.write((files[id] ?: throw DriveException.Other("File not found")).bytes)
    }

    override suspend fun open(id: String): InputStream = online { ByteArrayInputStream((files[id] ?: throw DriveException.Other("File not found")).bytes) }

    override suspend fun delete(id: String) = online {
        files.remove(id) ?: throw DriveException.Other("File not found")
        deletes++
        Unit
    }

    override suspend fun quota(): DriveQuota = online { DriveQuota(quotaBytes, usedBytes(), usedBytes(), usedBytes(), email) }

    override fun withAccount(email: String): DriveStore = accounts[email]

    private inline fun <T> online(block: () -> T): T = synchronized(lock) {
        if (offline) throw DriveException.Offline()
        block()
    }

    private fun info(id: String, f: Stored) = DriveFile(id, f.name, f.bytes.size.toLong(), Instant.ofEpochMilli(f.modified).toString(), md5(f.bytes))

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}

class FakeSettings(initial: Settings) : SettingsStore {
    private val flow = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = flow
    override fun update(change: (Settings) -> Settings) = flow.update(change)
}

internal class FakeNetwork(var online: Boolean = true, var metered: Boolean = false, var dataSaver: Boolean = false) : NetworkProbe {
    override fun online() = online
    override fun metered() = metered
    override fun dataSaverRestricted() = dataSaver
}

internal class MemoryCounters : CounterStore {
    val values = HashMap<String, Long>()
    override fun all(): Map<String, Long> = HashMap(values)
    override fun add(key: String, delta: Long) {
        values[key] = (values[key] ?: 0) + delta
    }
    override fun remove(keys: Collection<String>) {
        keys.forEach(values::remove)
    }
}

internal class RecordingNotifier : SyncNotifier {
    val posted = ArrayList<Notice>()
    override fun post(notice: Notice) {
        posted += notice
    }
}

internal class RecordingScheduler : SyncScheduling {
    var soon = 0
    var maintenance = 0
    var periodic = 0
    var cancelled = 0
    override fun requestSoon() {
        soon++
    }
    override fun requestMaintenance() {
        maintenance++
    }
    override fun schedulePeriodic() {
        periodic++
    }
    override fun cancelAll() {
        cancelled++
    }
}

/** One phone: its own folders, device, settings and key, sharing Google accounts with other phones. */
internal class TestPhone(
    val accounts: FakeAccounts,
    clock: FakeClock,
    deviceId: String = "phone-a",
    deviceName: String = "Phone A",
    var account: String = OWNER,
    val cipher: FakeCipher = FakeCipher(),
    settings: Settings = Settings(onboardingDone = true),
) : SyncPorts {
    val base: File = Files.createTempDirectory("pocket-sync-").toFile()
    override val dirs = AppDirs(File(base, "files"), File(base, "cache"))
    override val clock: FakeClock = clock
    override val device = DeviceIdentity(deviceId, deviceName)
    override val settings = FakeSettings(settings)
    override val network = FakeNetwork()
    val counters = MemoryCounters()
    override val budget = MeteredDataBudget({ this.settings.settings.value }, network, counters, clock)
    override val notifier = RecordingNotifier()
    override val scheduler = RecordingScheduler()

    var keyReady = true
    val sessions = ArrayList<SessionRecord>()
    val projects = ArrayList<Project>()
    val active = HashSet<String>()
    var secrets: ByteArray? = null
    var imported: ByteArray? = null
    val deletedLocally = ArrayList<String>()
    val adopted = ArrayList<SessionRecord>()
    val erasedSessions = ArrayList<String>()
    val runningRooms = HashSet<String>()
    var backgroundLimit: String? = null
    var roomsRunning = false
    var phone: PhoneSnapshot = PhoneSnapshot.UNKNOWN
    var newAccount: DriveAuthResult = DriveAuthResult.Failed("No account chosen")
    var onAuthorize: () -> Unit = {}
    var rekeys = 0
    var secureStoreWiped = false

    val engine = DriveSyncEngine(this)

    val drive: FakeDrive get() = accounts[account]

    override fun drive(): DriveStore = accounts[account]
    override fun drive(email: String): DriveStore = accounts[email]
    override fun account(): String = account
    override fun cipher(): VaultCipher? = cipher.takeIf { keyReady }
    override fun keyGeneration(): Int = cipher.generation
    override fun localSessions(): List<SessionRecord> = sessions.toList()
    override fun localProjects(): List<Project> = projects.toList()
    override fun activeSessionIds(): Set<String> = active.toSet()
    override fun roomsRunning() = roomsRunning || runningRooms.isNotEmpty()
    override fun roomRunning(agentId: String) = agentId in runningRooms
    override suspend fun stopRooms() {
        roomsRunning = false
        runningRooms.clear()
    }
    override suspend fun deleteSessionLocally(sessionId: String) {
        // Like the sessions module: what is still waiting is given a chance to upload first.
        runCatching { engine.uploadNow(listOf(sessionId)) }
        deletedLocally += sessionId
        val i = sessions.indexOfFirst { it.id == sessionId }
        if (i >= 0) sessions[i] = sessions[i].copy(status = SessionStatus.DELETED, deletedAt = clock.now())
    }
    override suspend fun adoptSessions(records: List<SessionRecord>) {
        adopted += records
        for (r in records) {
            val i = sessions.indexOfFirst { it.id == r.id }
            if (i >= 0) sessions[i] = r else sessions += r
        }
    }
    override suspend fun sessionsErased(sessionIds: List<String>) {
        erasedSessions += sessionIds
        sessions.removeAll { it.id in sessionIds }
    }
    override suspend fun adoptProjects(projects: List<Project>) {
        for (p in projects) {
            val i = this.projects.indexOfFirst { it.id == p.id }
            if (i >= 0) this.projects[i] = p else this.projects += p
        }
    }
    override fun backgroundLimit(): String? = backgroundLimit
    override suspend fun exportSecrets(): ByteArray? = secrets?.copyOf()
    override suspend fun importSecrets(bytes: ByteArray) {
        imported = bytes.copyOf()
    }
    override fun phone(): PhoneSnapshot = phone
    override fun computerIdle() = true
    override suspend fun authorizeNewAccount(): DriveAuthResult = newAccount.also { onAuthorize() }
    override suspend fun rekeyForMove() {
        rekeys++
        cipher.generation++
        accounts[account].uploadBytes("keyhalf-d", byteArrayOf(1, 2, 3), accounts[account].find("keyhalf-d")?.id)
    }
    override fun wipeSecureStore() {
        secureStoreWiped = true
    }

    /** A file in a room's home, as an agent would write it. */
    fun homeFile(agent: String, path: String): File = File(dirs.roomHome(agent), path).also { it.parentFile?.mkdirs() }

    fun mediaFile(agent: String, projectId: String, sessionId: String, name: String): File =
        File(dirs.sessionMedia(agent, projectId, sessionId), name).also { it.parentFile?.mkdirs() }

    fun state(): SyncState = SyncRepository(dirs).loadState(cipher)
    fun localIndex() = SyncRepository(dirs).loadIndex(cipher)
    fun queued(): List<QueueEntry> = UploadQueue(dirs.queue).entries(cipher)

    /** The index as it is in Drive now. */
    fun remoteIndex() = drive.named(RemoteIndex.NAME).singleOrNull()?.let { RemoteIndex().decode(cipher, it.bytes) }

    companion object {
        const val OWNER = "owner@example.com"
    }
}

fun session(id: String, projectId: String = "owner/app", agent: String = "claude", at: Long, deletedAt: Long? = null, backUp: Boolean = true, ref: String? = null) =
    SessionRecord(
        id = id, agentId = agent, projectId = projectId, title = "Chat $id", branch = "pocket/$agent/$id",
        startedAt = at, lastActivityAt = at, deletedAt = deletedAt, backUp = backUp, deviceId = "phone-a", agentSessionRef = ref,
    )

/** Where Claude keeps a session's transcript: a folder named after its worktree. */
fun claudeTranscript(projectId: String, sessionId: String, claudeSession: String) =
    ".claude/projects/${SessionMatcher.claudeDir(AppDirs.guestWorktree(projectId, sessionId))}/$claudeSession.jsonl"
