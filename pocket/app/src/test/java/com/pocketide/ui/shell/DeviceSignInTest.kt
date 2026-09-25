package com.pocketide.ui.shell

import com.pocketide.github.DeviceCode
import com.pocketide.github.DevicePoll
import com.pocketide.github.GitHubAccount
import com.pocketide.github.GitHubAuth
import com.pocketide.github.NotConnectedException
import com.pocketide.model.LinkHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSignInTest {
    private val account = GitHubAccount("octo", 1, "Octo Cat", null)

    /** GitHub's side, scripted: each poll takes the next answer (or throws it). */
    private class FakeAuth(private val code: DeviceCode, answers: List<Any>) : GitHubAuth {
        val queue = ArrayDeque(answers)
        val pollTimes = mutableListOf<Long>()
        var clock: () -> Long = { 0 }
        var startFailure: Exception? = null
        var starts = 0
        var gate: CompletableDeferred<Unit>? = null

        override val account: StateFlow<GitHubAccount?> = MutableStateFlow(null)
        override val configured = true
        override suspend fun startDeviceFlow(): DeviceCode {
            starts++
            gate?.await()
            startFailure?.let { throw it }
            return code
        }
        override suspend fun poll(code: DeviceCode): DevicePoll {
            pollTimes += clock()
            return when (val next = queue.removeFirstOrNull() ?: DevicePoll.Pending) {
                is Exception -> throw next
                else -> next as DevicePoll
            }
        }
        override suspend fun token() = throw NotConnectedException("not used")
        override suspend fun health() = LinkHealth.OK
        override fun installUrl() = "https://github.com/apps/pocketide/installations/new"
        override suspend fun signOut() = Unit
    }

    private fun code(interval: Int = 5, expiresAt: Long = 900_000) = DeviceCode("dev", "WDJB-MJHT", "https://github.com/login/device", expiresAt, interval)

    private fun TestScope.signIn(auth: FakeAuth): DeviceSignIn {
        auth.clock = { testScheduler.currentTime }
        return DeviceSignIn(auth, backgroundScope) { testScheduler.currentTime }
    }

    @Test
    fun approvalAfterSomePollsConnects() = runTest {
        val auth = FakeAuth(code(), listOf(DevicePoll.Pending, DevicePoll.Pending, DevicePoll.Connected(account)))
        val flow = signIn(auth)
        flow.start()
        runCurrent()
        assertEquals(DeviceSignIn.State.Waiting(code()), flow.state.value)
        advanceTimeBy(15_001)
        assertEquals(DeviceSignIn.State.Connected(account), flow.state.value)
        assertEquals(listOf(5_000L, 10_000L, 15_000L), auth.pollTimes)
    }

    @Test
    fun neverPollsFasterThanGitHubAskedEvenAfterSlowDown() = runTest {
        val auth = FakeAuth(code(interval = 1), listOf(DevicePoll.SlowDown(10), DevicePoll.Pending, DevicePoll.Denied))
        val flow = signIn(auth)
        flow.start()
        advanceTimeBy(60_000)
        assertEquals(DeviceSignIn.State.Denied, flow.state.value)
        // 1 s asked is raised to the 5 s floor; after slow_down, 10 s between polls.
        assertEquals(listOf(5_000L, 15_000L, 25_000L), auth.pollTimes)
    }

    @Test
    fun aDroppedConnectionKeepsTheCodeAndShowsOffline() = runTest {
        val auth = FakeAuth(code(), listOf(IOException("reset"), IOException("reset"), DevicePoll.Connected(account)))
        val flow = signIn(auth)
        flow.start()
        advanceTimeBy(5_001)
        assertEquals(DeviceSignIn.State.Waiting(code(), offline = true), flow.state.value)
        advanceTimeBy(5_000)
        assertEquals(DeviceSignIn.State.Waiting(code(), offline = true), flow.state.value)
        advanceTimeBy(5_000)
        assertEquals(DeviceSignIn.State.Connected(account), flow.state.value)
    }

    @Test
    fun theCodeExpiresOnTheClockEvenWhileOffline() = runTest {
        val auth = FakeAuth(code(expiresAt = 12_000), List(10) { IOException("offline") })
        val flow = signIn(auth)
        flow.start()
        advanceTimeBy(60_000)
        assertEquals(DeviceSignIn.State.Expired, flow.state.value)
        assertEquals(2, auth.pollTimes.size)
    }

    @Test
    fun gitHubsOwnAnswersEndTheWait() = runTest {
        val expired = FakeAuth(code(), listOf(DevicePoll.Expired))
        signIn(expired).also { it.start(); advanceTimeBy(6_000); assertEquals(DeviceSignIn.State.Expired, it.state.value) }

        val token = "ghu_" + "a".repeat(36)
        val failed = FakeAuth(code(), listOf(DevicePoll.Failed("bad_verification_code $token")))
        signIn(failed).also {
            it.start()
            advanceTimeBy(6_000)
            val state = it.state.value as DeviceSignIn.State.Failed
            assertTrue(state.why, !state.why.contains(token))
        }
    }

    @Test
    fun aFailedStartSaysWhyInPlainWords() = runTest {
        val auth = FakeAuth(code(), emptyList()).apply { startFailure = IOException("unreachable") }
        val flow = signIn(auth)
        flow.start()
        runCurrent()
        assertEquals(DeviceSignIn.State.Failed("No connection to GitHub. Check the internet and try again."), flow.state.value)

        auth.startFailure = IllegalStateException("HTTP 500 from https://github.com/login/device/code")
        flow.start()
        runCurrent()
        assertEquals(DeviceSignIn.State.Failed("GitHub sign-in could not start. Try again in a minute."), flow.state.value)
    }

    @Test
    fun startingAgainDropsTheOldCodeAndItsLateAnswer() = runTest {
        val auth = FakeAuth(code(), listOf(DevicePoll.Pending))
        val flow = signIn(auth)
        flow.start()
        advanceTimeBy(5_001)
        val gate = CompletableDeferred<Unit>()
        auth.gate = gate
        flow.start()
        runCurrent()
        assertEquals(DeviceSignIn.State.Starting, flow.state.value)
        // The first sign-in's timer must not poll or publish any more.
        advanceTimeBy(30_000)
        assertEquals(DeviceSignIn.State.Starting, flow.state.value)
        assertEquals(1, auth.pollTimes.size)
        gate.complete(Unit)
        runCurrent()
        assertTrue(flow.state.value is DeviceSignIn.State.Waiting)
        assertEquals(2, auth.starts)
    }

    @Test
    fun resetStopsPollingAndReturnsToIdle() = runTest {
        val auth = FakeAuth(code(), emptyList())
        val flow = signIn(auth)
        flow.start()
        advanceTimeBy(5_001)
        flow.reset()
        advanceTimeBy(60_000)
        assertEquals(DeviceSignIn.State.Idle, flow.state.value)
        assertEquals(1, auth.pollTimes.size)
    }
}
