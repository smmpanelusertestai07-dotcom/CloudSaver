package com.pocketide.lock

import com.pocketide.core.Clock
import com.pocketide.model.AccessState
import com.pocketide.model.LinkHealth
import com.pocketide.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What the guard reads from the other modules. Flows are opened only once the guard starts. */
internal interface AccessSources {
    fun unsupported(): String?
    suspend fun githubHealth(): LinkHealth
    suspend fun driveHealth(): LinkHealth
    fun leaseHolder(): Flow<String?>
    fun syncStatus(): Flow<SyncStatus>
    fun keyOnlyOnPhone(): Flow<Boolean>
}

/**
 * Keeps [AccessState] current: the two links are asked on [check] (app start, back to the
 * front, every 15 minutes), while the lease, the storage wait and the key follow their modules
 * live. A link that was revoked stays revoked until it answers OK: going offline afterwards
 * proves nothing.
 */
internal class AccessGuardImpl(
    private val sources: AccessSources,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val schedule: () -> Unit,
) : AccessGuard {
    private val flow = MutableStateFlow(AccessState())
    override val state: StateFlow<AccessState> = flow

    private val checking = Mutex()
    private var lease: String? = null
    private var sync: SyncStatus = SyncStatus.Idle
    private var keyOnlyOnPhone = false

    /** Follows the live inputs; called once by the module after construction. */
    fun start() {
        scope.launch {
            combine(sources.leaseHolder(), sources.syncStatus(), sources.keyOnlyOnPhone()) { holder, status, key ->
                synchronized(this@AccessGuardImpl) {
                    lease = holder
                    sync = status
                    keyOnlyOnPhone = key
                }
            }.collect { publish() }
        }
    }

    override suspend fun check() = checking.withLock {
        flow.update { current ->
            current.copy(
                github = current.github.takeUnless { it == LinkHealth.NOT_CONNECTED } ?: LinkHealth.CHECKING,
                drive = current.drive.takeUnless { it == LinkHealth.NOT_CONNECTED } ?: LinkHealth.CHECKING,
            )
        }
        val (github, drive) = coroutineScope {
            val github = async { ask(sources::githubHealth) }
            val drive = async { ask(sources::driveHealth) }
            github.await() to drive.await()
        }
        flow.update { current -> current.copy(github = settle(current.github, github), drive = settle(current.drive, drive)) }
        publish()
    }

    override fun startPeriodicChecks() = schedule()

    private fun publish() {
        val facts = synchronized(this) {
            val current = flow.value
            AccessFacts(sources.unsupported(), current.github, current.drive, lease, sync, keyOnlyOnPhone)
        }
        flow.update { it.copy(lock = AccessRules.lock(facts, clock.now()), banner = AccessRules.banner(facts)) }
    }

    /** Null when the answer did not come: an error or no reply within [ASK_TIMEOUT_MS]. */
    private suspend fun ask(health: suspend () -> LinkHealth): LinkHealth? = try {
        withTimeoutOrNull(ASK_TIMEOUT_MS) { health() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun settle(before: LinkHealth, answer: LinkHealth?): LinkHealth = when {
        answer == null -> if (before == LinkHealth.CHECKING) LinkHealth.OFFLINE else before
        before == LinkHealth.REVOKED && answer == LinkHealth.OFFLINE -> LinkHealth.REVOKED
        else -> answer
    }

    private companion object {
        const val ASK_TIMEOUT_MS = 30_000L
    }
}
