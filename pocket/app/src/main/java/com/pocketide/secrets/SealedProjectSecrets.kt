package com.pocketide.secrets

import com.pocketide.core.AppJson
import com.pocketide.core.Clock
import com.pocketide.core.SecureStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.Locale

/** Something the owner can act on, in one plain sentence. */
class SecretsException(message: String) : Exception(message)

/** One stored Variable or Secret, value included. Only this file and the vault blob hold it. */
@Serializable
internal data class StoredValue(
    val projectId: String?,
    val name: String,
    val kind: SecretKind,
    val value: String,
    val updatedAt: Long,
    val pushedToGitHub: Boolean = false,
    val agentId: String? = null,
    /** When the owner removed it. The name stays without its value, so the removal reaches other phones. */
    val removedAt: Long? = null,
    /** A global Secret has no repository of its own: the projects this value was sent to. */
    val sentTo: Set<String> = emptySet(),
) {
    val live: Boolean get() = removedAt == null

    fun key() = Key(projectId, name)

    fun removed(at: Long) = copy(value = "", updatedAt = at, pushedToGitHub = false, agentId = null, removedAt = at, sentTo = emptySet())

    fun describe() = ProjectValue(projectId, name, kind, updatedAt, pushedToGitHub, agentId, sentTo)

    /** Sent to [toProject]'s GitHub Actions: a project's own value is marked, a global one records the project. */
    fun sent(toProject: String) = if (projectId == null) copy(sentTo = sentTo + toProject) else copy(pushedToGitHub = true)

    // The generated toString would print the value; a stray log line must not.
    override fun toString() = "StoredValue($projectId, $name, $kind)"

    data class Key(val projectId: String?, val name: String)
}

/**
 * Variables and Secrets, sealed with the Keystore in one secure-store entry ("secrets.v1": a
 * JSON list). The screens see names and kinds only; values leave through [reveal] (after the
 * fingerprint), [variablesFor] (Variables, for a room's environment), [allValues] (the
 * check-post), [exportBlob] (the sync engine encrypts it) and [pushToGitHub].
 */
internal class SealedProjectSecrets(
    private val store: SecureStore,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    /** Writes one Secret as a GitHub Actions secret of the project. */
    private val pushSecret: suspend (projectId: String, name: String, value: ByteArray) -> Unit,
) : ProjectSecrets {

    private val lock = Mutex()
    private val described = MutableStateFlow<List<ProjectValue>>(emptyList())
    private var loaded = false

    override val values: StateFlow<List<ProjectValue>> = described.asStateFlow()

    /** Reads the stored names in the background, so the screens have them without asking. */
    suspend fun preload() {
        lock.withLock { read() }
    }

    override suspend fun set(projectId: String?, name: String, kind: SecretKind, value: CharArray) {
        SecretNames.require(name, kind)
        val stored = SecretNames.normalize(name)
        update { list ->
            val old = list.firstOrNull { it.live && it.matches(projectId, stored) }
            val entry = StoredValue(
                projectId = projectId,
                name = stored,
                kind = kind,
                value = String(value),
                updatedAt = clock.now(),
                // A changed value is not on GitHub yet; a Variable never goes there.
                pushedToGitHub = false,
                agentId = old?.agentId?.takeIf { kind == SecretKind.VARIABLE },
            )
            list.filterNot { it.matches(projectId, stored) } + entry
        }
    }

    override suspend fun reveal(projectId: String?, name: String): CharArray? =
        lock.withLock { read().firstOrNull { it.live && it.matches(projectId, name) }?.value?.toCharArray() }

    override suspend fun remove(projectId: String?, name: String) {
        update { list -> list.map { if (it.live && it.matches(projectId, name)) it.removed(clock.now()) else it } }
    }

    override suspend fun limitToRoom(projectId: String?, name: String, agentId: String?) {
        update { list ->
            val entry = list.firstOrNull { it.live && it.matches(projectId, name) } ?: throw SecretsException("${name.trim()} is not saved any more.")
            if (entry.kind != SecretKind.VARIABLE) throw SecretsException("Only a Variable can be limited to one room. Secrets never reach a room.")
            list.map { if (it === entry) it.copy(agentId = agentId, updatedAt = clock.now()) else it }
        }
    }

    override suspend fun variablesFor(projectId: String): Map<String, String> = environment(projectId, agentId = null)

    override suspend fun variablesFor(projectId: String, agentId: String): Map<String, String> = environment(projectId, agentId)

    override suspend fun allValues(): List<String> =
        lock.withLock { read().filter { it.live }.map { it.value }.filter { it.isNotBlank() }.distinct() }

    override suspend fun pushToGitHub(projectId: String, name: String) {
        val entry = lock.withLock {
            val live = read().filter { it.live }
            live.firstOrNull { it.matches(projectId, name) } ?: live.firstOrNull { it.matches(null, name) }
        } ?: throw SecretsException("${name.trim()} is not saved any more.")
        if (entry.kind != SecretKind.SECRET) throw SecretsException("Only Secrets go to GitHub. A Variable stays in the room.")
        val bytes = entry.value.toByteArray(Charsets.UTF_8)
        try {
            pushSecret(projectId, entry.name, bytes)
        } finally {
            bytes.fill(0)
        }
        // Only the value sent: one saved meanwhile is not on GitHub yet.
        update { list -> list.map { if (it == entry) it.sent(projectId) else it } }
    }

    override suspend fun exportBlob(): ByteArray = lock.withLock {
        // Refusing here keeps an unreadable or never-used store from replacing the vault's copy.
        check(withContext(io) { store.has(ENTRY) }) { "No Variables or Secrets are saved on this phone yet." }
        val list = try {
            read()
        } catch (e: SecretsException) {
            throw IllegalStateException(e.message, e)
        }
        encode(list)
    }

    override suspend fun importBlob(bytes: ByteArray) {
        val valid = parse(bytes)
        lock.withLock { write(valid) }
    }

    /**
     * Value by value, the later change wins, a removal included. On the same instant a value wins
     * over a removal and one already on GitHub over one that is not; then this phone's stays. A
     * store that cannot be read here takes the vault's copy whole.
     */
    override suspend fun mergeBlob(bytes: ByteArray) {
        val incoming = parse(bytes)
        lock.withLock {
            val local = try {
                read()
            } catch (_: SecretsException) {
                emptyList()
            }
            write((local + incoming).groupBy { it.key() }.values.map { same -> same.reduce(::later) })
        }
    }

    private fun later(mine: StoredValue, theirs: StoredValue): StoredValue {
        val winner = maxOf(mine, theirs, compareBy<StoredValue>({ it.updatedAt }, { it.live }, { it.pushedToGitHub }))
        // The same global value, sent from each phone to other projects: all of them have it.
        val same = mine.updatedAt == theirs.updatedAt && mine.live && theirs.live && mine.value == theirs.value
        return if (same) winner.copy(sentTo = mine.sentTo + theirs.sentTo) else winner
    }

    /** The vault's copy with every name checked, one entry per name. */
    private fun parse(bytes: ByteArray): List<StoredValue> {
        val incoming = try {
            AppJson.decodeFromString(LIST, bytes.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw SecretsException("The saved Variables and Secrets could not be read.")
        }
        return incoming.filter { SecretNames.problem(it.name, it.kind) == null }
            .map { it.copy(name = SecretNames.normalize(it.name)) }
            .associateBy { it.key() }
            .values.toList()
    }

    private suspend fun environment(projectId: String, agentId: String?): Map<String, String> = lock.withLock {
        read()
            .filter { it.live && it.kind == SecretKind.VARIABLE && (it.projectId == null || it.projectId == projectId) }
            .filter { it.agentId == null || it.agentId == agentId }
            .groupBy { it.name }
            // The project's value replaces the global one; a room-only value replaces a shared one.
            .mapValues { (_, same) -> same.maxBy { (if (it.projectId != null) 2 else 0) + (if (it.agentId != null) 1 else 0) }.value }
    }

    private suspend fun update(change: (List<StoredValue>) -> List<StoredValue>) {
        lock.withLock { write(change(read())) }
    }

    /** The stored list; call with [lock] held. */
    private suspend fun read(): List<StoredValue> = withContext(io) {
        val text = if (store.has(ENTRY)) {
            store.getString(ENTRY) ?: throw SecretsException("Variables and Secrets could not be opened on this phone. Restore them from your Drive.")
        } else {
            null
        }
        val list = text?.let { decode(it) }.orEmpty()
        if (!loaded) {
            loaded = true
            publish(list)
        }
        list
    }

    private suspend fun write(list: List<StoredValue>) {
        withContext(io) { store.put(ENTRY, encode(list)) }
        publish(list)
    }

    private fun publish(list: List<StoredValue>) {
        described.value = list.filter { it.live }.map { it.describe() }
            .sortedWith(compareBy({ it.projectId ?: "" }, { it.kind }, { it.name }))
    }

    private fun decode(text: String): List<StoredValue> = try {
        AppJson.decodeFromString(LIST, text)
    } catch (e: IllegalArgumentException) {
        throw SecretsException("Variables and Secrets could not be read on this phone. Restore them from your Drive.")
    }

    /** Sorted, so the same set always gives the same bytes and the sync engine uploads only changes. */
    private fun encode(list: List<StoredValue>): ByteArray =
        AppJson.encodeToString(LIST, list.sortedWith(compareBy({ it.projectId ?: "" }, { it.name }))).toByteArray(Charsets.UTF_8)

    private fun StoredValue.matches(projectId: String?, name: String) =
        this.projectId == projectId && this.name == name.trim().uppercase(Locale.ROOT)

    companion object {
        const val ENTRY = "secrets.v1"
        private val LIST = ListSerializer(StoredValue.serializer())
    }
}
