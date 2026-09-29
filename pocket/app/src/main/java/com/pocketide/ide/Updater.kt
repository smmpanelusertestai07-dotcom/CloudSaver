package com.pocketide.ide

import android.net.ConnectivityManager
import com.pocketide.AppGraph
import com.pocketide.linux.ComputerState
import com.pocketide.linux.UpdateOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the automatic updates are doing, for the Computer screen. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data class Running(val step: String) : UpdateStatus

    /** The last run, in the owner's words, one line per part. */
    data class Done(val lines: List<String>, val at: Long) : UpdateStatus
}

/**
 * Keeps the computer current without the owner doing anything: once a day while the computer is on,
 * Ubuntu's updates (security fixes included, through apt, checked against Ubuntu's signatures), a
 * newer release of every installed agent (from Open VSX, checked as at install), and the code-server
 * this app version pins. On mobile data only when the owner allows it.
 */
class Updater(private val graph: AppGraph) {
    private val oneAtATime = Mutex()
    private val mutableStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = mutableStatus.asStateFlow()

    /** Runs when a day has passed since the last run and the network allows it. */
    suspend fun runIfDue() {
        val settings = graph.settings.settings.value
        if (graph.clock.now() - settings.lastUpdate < DAY_MS) return
        if (metered() && !settings.updatesOnMobileData) return
        runNow()
    }

    /** Runs now (the owner tapped Update now, or a day has passed). */
    suspend fun runNow() = oneAtATime.withLock {
        if (graph.computer.state.value != ComputerState.Ready) return@withLock
        val lines = mutableListOf<String>()
        try {
            mutableStatus.value = UpdateStatus.Running("Checking Ubuntu's updates…")
            lines += "Ubuntu: " + words(graph.computer.updateBase())
            mutableStatus.value = UpdateStatus.Running("Checking the agents…")
            val agents = runCatching { graph.agents.updateAll() }
            lines += "Agents: " + agents.fold(
                onSuccess = { if (it.isEmpty()) "up to date." else "updated ${it.joinToString()}." },
                onFailure = { "could not be checked (${it.message ?: "no connection"})." },
            )
            mutableStatus.value = UpdateStatus.Running("Checking code-server…")
            lines += "code-server: " + words(graph.computer.updateCodeServer())
            graph.settings.update { it.copy(lastUpdate = graph.clock.now()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            lines += "Stopped: ${failure.message ?: "an unexpected error"}."
        } finally {
            mutableStatus.value = UpdateStatus.Done(lines, graph.clock.now())
        }
    }

    private fun words(outcome: UpdateOutcome): String = when (outcome) {
        UpdateOutcome.UpToDate -> "up to date."
        is UpdateOutcome.Updated -> outcome.detail
        is UpdateOutcome.Waiting -> outcome.why
        is UpdateOutcome.Failed -> outcome.why
    }

    private fun metered(): Boolean =
        graph.context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private companion object {
        /** A little under a day, so a phone opened at the same time each day updates each day. */
        const val DAY_MS = 20 * 60 * 60 * 1000L
    }
}
