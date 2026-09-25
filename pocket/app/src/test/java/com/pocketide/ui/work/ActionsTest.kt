package com.pocketide.ui.work

import androidx.compose.material3.SnackbarHostState
import com.pocketide.ui.screens.project.SessionShortcut
import com.pocketide.ui.screens.project.act
import com.pocketide.ui.screens.project.attempt
import com.pocketide.ui.screens.project.finish
import com.pocketide.ui.screens.project.plainReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionsTest {
    @Test
    fun confirmedWorkFinishesWhenTheScreenGoesAway() = runTest {
        var merged = false
        val screen = launch { finish { delay(1_000); merged = true } }
        advanceTimeBy(10)
        screen.cancel()
        advanceUntilIdle()
        assertTrue("a merge the owner confirmed must not stop half-way", merged)
    }

    @Test
    fun actFinishesTheWorkButSkipsNavigationOnceTheScreenIsGone() = runTest {
        val screen = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        var deleted = false
        var navigated = false
        screen.act(SnackbarHostState(), "Could not delete", then = { navigated = true }) {
            delay(1_000)
            deleted = true
        }
        advanceTimeBy(10)
        screen.cancel()
        advanceUntilIdle()
        assertTrue(deleted)
        assertFalse(navigated)
    }

    @Test
    fun actReportsAFailureInOnePlainSentence() = runTest {
        val screen = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val snackbar = SnackbarHostState()
        var navigated = false
        screen.act(snackbar, "Could not rename", then = { navigated = true }) {
            throw IllegalStateException("The session is gone.\nat com.pocketide.sessions.Line(1)")
        }
        runCurrent()
        assertEquals("Could not rename: The session is gone.", snackbar.currentSnackbarData?.visuals?.message)
        assertFalse(navigated)
        screen.cancel()
    }

    @Test
    fun attemptNeverSwallowsCancellation() = runTest {
        try {
            attempt { throw CancellationException("stop") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue(attempt { throw IllegalArgumentException("x") }.isFailure)
    }

    @Test
    fun reasonsAreShortAndRedacted() {
        assertEquals("Something went wrong. Please try again.", plainReason(RuntimeException()))
        assertEquals(240, plainReason(RuntimeException("y".repeat(1_000))).length)
        // Built at run time, so no literal in the sources looks like a real token.
        val token = "ghp_" + "a".repeat(36)
        val reason = plainReason(RuntimeException("push refused for $token"))
        assertFalse(reason.contains(token))
    }

    @Test
    fun shortcutsCarryOnlyASessionId() {
        assertTrue(SessionShortcut.isSessionId("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertFalse(SessionShortcut.isSessionId(""))
        assertFalse(SessionShortcut.isSessionId("../../secure"))
        assertFalse(SessionShortcut.isSessionId("a b"))
        assertFalse(SessionShortcut.isSessionId("x".repeat(65)))
        assertEquals("session:abc", SessionShortcut.shortcutId("abc"))
        assertNull(SessionShortcut.sessionIdFrom(null))
    }
}
