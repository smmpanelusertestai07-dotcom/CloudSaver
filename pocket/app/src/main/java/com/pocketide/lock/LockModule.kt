package com.pocketide.lock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketide.AppGraph
import com.pocketide.model.LinkHealth
import com.pocketide.sync.SyncStatus
import com.pocketide.vault.KeyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

fun createAccessGuard(graph: AppGraph): AccessGuard {
    val watchingForeground = AtomicBoolean(false)
    return AccessGuardImpl(
        sources = GraphAccessSources(graph),
        clock = graph.clock,
        scope = graph.scope,
        schedule = { schedulePeriodicChecks(graph, watchingForeground) },
    ).also { it.start() }
}

fun createAppLock(graph: AppGraph): AppLock = AndroidAppLock(graph.context, graph.settings, graph.scope)

/** Every 15 minutes through WorkManager, and each time the app comes back to the front. */
private fun schedulePeriodicChecks(graph: AppGraph, watchingForeground: AtomicBoolean) {
    AccessCheckWorker.schedule(graph.context)
    if (!watchingForeground.compareAndSet(false, true)) return
    // Lifecycle observers must be added on the main thread.
    graph.scope.launch(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                graph.scope.launch { runCatching { graph.access.check() } }
            }
        })
    }
}

/**
 * The other modules as the guard reads them. A module that fails to come up reads as "nothing
 * to report" rather than taking the guard down: the guard must keep working to lock at all.
 */
private class GraphAccessSources(private val graph: AppGraph) : AccessSources {
    override fun unsupported(): String? = runCatching { graph.limiter.unsupportedReason() }.getOrNull()

    override suspend fun githubHealth(): LinkHealth = graph.gitHubAuth.health()

    override suspend fun driveHealth(): LinkHealth = graph.driveAuth.health()

    override fun leaseHolder(): Flow<String?> = live(null) { graph.sync.leaseHolder }

    override fun syncStatus(): Flow<SyncStatus> = live(SyncStatus.Idle) { graph.sync.status }

    override fun keyOnlyOnPhone(): Flow<Boolean> = live(false) { graph.vault.state.map { it is KeyState.OnlyOnPhone } }

    private fun <T> live(fallback: T, source: () -> Flow<T>): Flow<T> =
        flow { emitAll(source()) }.catch { emit(fallback) }
}
