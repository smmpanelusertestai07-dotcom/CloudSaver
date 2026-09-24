package com.pocketide.vault

import com.pocketide.core.AppJson
import com.pocketide.core.Clock
import com.pocketide.core.SecretBox
import com.pocketide.core.SecureStore
import com.pocketide.github.AccountUsage
import com.pocketide.github.GitHubAccount
import com.pocketide.github.GitHubApi
import com.pocketide.github.NotConnectedException
import com.pocketide.github.PullRequest
import com.pocketide.github.RepoFile
import com.pocketide.github.RepoInfo
import com.pocketide.github.RepoUsage
import com.pocketide.github.RunArtifact
import com.pocketide.github.WorkflowRun
import com.pocketide.google.DriveFile
import com.pocketide.google.DriveQuota
import com.pocketide.google.DriveStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** The Drive hidden folder in memory. */
class FakeDrive : DriveStore {
    private class Stored(val name: String, val bytes: ByteArray)

    private val files = LinkedHashMap<String, Stored>()
    private var nextId = 1

    /** Uploads still to fail, for interrupted-change tests. */
    var failUploads = 0

    fun bytes(name: String): ByteArray? = files.values.firstOrNull { it.name == name }?.bytes

    fun put(name: String, bytes: ByteArray) {
        val id = files.entries.firstOrNull { it.value.name == name }?.key ?: "d${nextId++}"
        files[id] = Stored(name, bytes.copyOf())
    }

    fun remove(name: String) {
        files.entries.removeIf { it.value.name == name }
    }

    fun names(): List<String> = files.values.map { it.name }

    override suspend fun list(): List<DriveFile> = files.map { (id, file) -> DriveFile(id, file.name, file.bytes.size.toLong(), null, null) }

    override suspend fun find(name: String): DriveFile? = list().firstOrNull { it.name == name }

    override suspend fun upload(name: String, source: File, existingId: String?): DriveFile = uploadBytes(name, source.readBytes(), existingId)

    override suspend fun uploadBytes(name: String, bytes: ByteArray, existingId: String?): DriveFile {
        if (failUploads > 0) {
            failUploads--
            throw IOException("connection reset")
        }
        val id = existingId ?: "d${nextId++}"
        files[id] = Stored(name, bytes.copyOf())
        return DriveFile(id, name, bytes.size.toLong(), null, null)
    }

    override suspend fun download(id: String, sink: OutputStream) = sink.write(files.getValue(id).bytes)

    override suspend fun open(id: String): InputStream = ByteArrayInputStream(files.getValue(id).bytes)

    override suspend fun delete(id: String) {
        files.remove(id)
    }

    override suspend fun quota() = DriveQuota(limitBytes = null, usageBytes = 0, usageInDriveBytes = 0, appDataBytes = 0, email = null)

    override fun withAccount(email: String): DriveStore = this
}

/** The owner's GitHub in memory: repos, collaborators, the Actions switch and file history. */
class FakeGitHub(private val owner: String = OWNER) : GitHubApi {
    class Repo(var isPrivate: Boolean, val collaborators: MutableList<String>) {
        var actionsEnabled = true
        val files = LinkedHashMap<String, RepoFile>()

        /** Every version ever written, like git history. */
        val history = ArrayList<Pair<String, ByteArray>>()
    }

    val repos = LinkedHashMap<String, Repo>()
    var connected = true

    /** File writes still to fail, for interrupted-change tests. */
    var failWrites = 0

    fun keyring(): Repo = repos.getValue(VaultKeyFiles.KEYRING_REPO)

    private fun ensureConnected() {
        if (!connected) throw NotConnectedException("GitHub access was removed")
    }

    private fun info(name: String, repo: Repo) = RepoInfo(owner, name, repo.isPrivate, "main", 0, "", "", null)

    override suspend fun repo(owner: String, name: String): RepoInfo? {
        ensureConnected()
        return repos[name]?.let { info(name, it) }
    }

    override suspend fun createPrivateRepo(name: String, description: String, autoInit: Boolean): RepoInfo {
        ensureConnected()
        check(name !in repos) { "name already exists on this account" }
        val repo = Repo(isPrivate = true, collaborators = mutableListOf(owner))
        repos[name] = repo
        return info(name, repo)
    }

    override suspend fun collaborators(owner: String, name: String): List<String> {
        ensureConnected()
        return repos.getValue(name).collaborators.toList()
    }

    override suspend fun setActionsEnabled(owner: String, name: String, enabled: Boolean) {
        ensureConnected()
        repos.getValue(name).actionsEnabled = enabled
    }

    override suspend fun readFile(owner: String, name: String, path: String): RepoFile? {
        ensureConnected()
        return repos[name]?.files?.get(path)
    }

    override suspend fun writeFile(owner: String, name: String, path: String, bytes: ByteArray, message: String, sha: String?) {
        ensureConnected()
        if (failWrites > 0) {
            failWrites--
            throw IOException("connection reset")
        }
        val repo = repos.getValue(name)
        check(repo.files[path]?.sha == sha) { "409: the file changed since it was read" }
        repo.files[path] = RepoFile(path, sha1(bytes), bytes.copyOf())
        repo.history += path to bytes.copyOf()
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by the vault")

    override suspend fun me(): GitHubAccount = unused()
    override suspend fun repos(): List<RepoInfo> = unused()
    override suspend fun openPullRequest(owner: String, name: String, head: String, base: String, title: String, body: String): PullRequest = unused()
    override suspend fun pullRequest(owner: String, name: String, number: Int): PullRequest = unused()
    override suspend fun mergePullRequest(owner: String, name: String, number: Int, method: String): Boolean = unused()
    override suspend fun dispatchWorkflow(owner: String, name: String, workflowFile: String, ref: String, inputs: Map<String, String>) = unused()
    override suspend fun runs(owner: String, name: String, branch: String?): List<WorkflowRun> = unused()
    override suspend fun artifacts(owner: String, name: String, runId: Long): List<RunArtifact> = unused()
    override suspend fun downloadArtifact(artifact: RunArtifact, dest: File) = unused()
    override suspend fun setActionsSecret(owner: String, name: String, secretName: String, value: ByteArray) = unused()
    override suspend fun accountUsage(): AccountUsage = unused()
    override suspend fun repoUsage(owner: String, name: String): RepoUsage = unused()

    companion object {
        const val OWNER = "octo"
    }
}

/** Stands in for the Keystore: reversible, and never stores the plain bytes as they are. */
class TestBox : SecretBox {
    override fun seal(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 0x5a).toByte() }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.isNotEmpty() && sealed[0].toInt() == 7) { "Unknown sealed format" }
        return sealed.copyOfRange(1, sealed.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}

/** One owner's world: Drive, GitHub and the signed-in account, shared by every phone in a test. */
internal class Accounts(val drive: FakeDrive = FakeDrive(), val gitHub: FakeGitHub = FakeGitHub()) {
    val account = MutableStateFlow<GitHubAccount?>(GitHubAccount(FakeGitHub.OWNER, 1, null, null))
    var now = 1_000_000_000_000L
    val clock = Clock { now }

    fun halfD(): HalfDFile? = drive.bytes(VaultKeyFiles.HALF_D)?.let { AppJson.decodeFromString(HalfDFile.serializer(), it.toString(Charsets.UTF_8)) }

    fun halfG(): HalfGFile? = gitHub.repos[VaultKeyFiles.KEYRING_REPO]?.files?.get(VaultKeyFiles.HALF_G_PATH)
        ?.let { AppJson.decodeFromString(HalfGFile.serializer(), it.bytes.toString(Charsets.UTF_8)) }
}

/** A phone: its own sealed storage, and a vault that can be made again as after a restart. */
internal class TestPhone(private val accounts: Accounts, val dir: File) {
    val store = SecureStore(dir, TestBox())
    var passwordSetting: Boolean? = null

    fun vault(): VaultKeysImpl = VaultKeysImpl(
        phone = PhoneKeys(store, Dispatchers.Unconfined),
        remote = RemoteKeys(accounts.drive, accounts.gitHub),
        account = accounts.account,
        clock = accounts.clock,
        onPasswordChanged = { passwordSetting = it },
        random = SecureRandom(),
        passwordCost = Argon2Cost(memoryKiB = 256, iterations = 1, parallelism = 1),
        cpu = Dispatchers.Unconfined,
    )
}
