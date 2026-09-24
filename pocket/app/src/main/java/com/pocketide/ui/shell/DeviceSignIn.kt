package com.pocketide.ui.shell

import com.pocketide.AppGraph
import com.pocketide.core.Redact
import com.pocketide.github.DeviceCode
import com.pocketide.github.DevicePoll
import com.pocketide.github.GitHubAccount
import com.pocketide.github.GitHubAuth
import com.pocketide.github.NotConnectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * GitHub's device-code sign-in, kept outside any screen: the owner types the code in Chrome, and
 * the app lock may cover PocketIDE while they are away, which removes the screen that started it.
 * The code and its polling live here, in memory only (never on disk or in saved state), so the
 * screen shown after unlocking finds the same code still waiting, or already approved.
 */
class DeviceSignIn(
    private val auth: GitHubAuth,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Idle : State
        data object Starting : State
        /** [offline] is true while the last poll could not reach GitHub; the code stays valid. */
        data class Waiting(val code: DeviceCode, val offline: Boolean = false) : State
        data object Denied : State
        data object Expired : State
        data class Failed(val why: String) : State
        data class Connected(val account: GitHubAccount) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState
    private var job: Job? = null

    /** Bumped by every start and reset, so a replaced sign-in can never publish over its successor. */
    private var generation = 0

    /** Gets a new code and waits for the owner's answer on GitHub; a sign-in already running is dropped. */
    fun start() = synchronized(this) {
        job?.cancel()
        val mine = ++generation
        mutableState.value = State.Starting
        job = scope.launch {
            val code = try {
                auth.startDeviceFlow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(mine, State.Failed(explain(e)))
                return@launch
            }
            publish(mine, State.Waiting(code))
            publish(mine, waitForAnswer(mine, code))
        }
    }

    /** Back to the start once the screen has used the result. */
    fun reset() = synchronized(this) {
        job?.cancel()
        job = null
        generation++
        mutableState.value = State.Idle
    }

    private fun publish(from: Int, state: State) = synchronized(this) {
        if (from == generation) mutableState.value = state
    }

    private suspend fun waitForAnswer(mine: Int, code: DeviceCode): State {
        var pause = DeviceFlowTiming.pollDelayMs(code.intervalSeconds)
        while (true) {
            delay(pause)
            if (DeviceFlowTiming.expired(code.expiresAtMs, now())) return State.Expired
            val answer = try {
                auth.poll(code)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A dropped connection is not an answer: keep the code and keep asking.
                publish(mine, State.Waiting(code, offline = true))
                continue
            }
            publish(mine, State.Waiting(code, offline = false))
            when (answer) {
                DevicePoll.Pending -> Unit
                is DevicePoll.SlowDown -> pause = DeviceFlowTiming.pollDelayMs(answer.intervalSeconds)
                is DevicePoll.Connected -> return State.Connected(answer.account)
                DevicePoll.Denied -> return State.Denied
                DevicePoll.Expired -> return State.Expired
                is DevicePoll.Failed -> return State.Failed(Redact.text(answer.why))
            }
        }
    }

    private fun explain(e: Exception): String = when (e) {
        is NotConnectedException -> Redact.text(e.message ?: "GitHub sign-in is not available.")
        is IOException -> "No connection to GitHub. Check the internet and try again."
        else -> "GitHub sign-in could not start. Try again in a minute."
    }

    companion object {
        @Volatile private var instance: DeviceSignIn? = null

        /** The one sign-in of this app process; set-up and the "GitHub disconnected" lock share it. */
        fun of(graph: AppGraph): DeviceSignIn =
            instance ?: synchronized(this) {
                instance ?: DeviceSignIn(graph.gitHubAuth, graph.scope).also { instance = it }
            }
    }
}
