package com.pocketide.sessions

import com.pocketide.core.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutosaverTest {
    private val minute = 60_000L

    private class Pushes {
        val calls = mutableListOf<String>()
        var answer: String? = null
    }

    private fun TestScope.autosaver(pushes: Pushes) =
        Autosaver(Clock { testScheduler.currentTime }, backgroundScope) { id -> pushes.calls += id; pushes.answer }

    @Test
    fun `the first save pushes at once`() = runTest {
        val pushes = Pushes()
        assertNull(autosaver(pushes).save("a"))
        assertEquals(listOf("a"), pushes.calls)
    }

    @Test
    fun `saves inside five minutes answer from the last push and one more push follows at the end`() = runTest {
        val pushes = Pushes()
        val saver = autosaver(pushes)
        pushes.answer = "GitHub is not reachable."
        assertEquals("GitHub is not reachable.", saver.save("a"))

        advanceTimeBy(minute)
        pushes.answer = null
        assertEquals("GitHub is not reachable.", saver.save("a"))
        advanceTimeBy(minute)
        saver.save("a")
        assertEquals("no push inside the window", 1, pushes.calls.size)

        advanceTimeBy(3 * minute - 1)
        runCurrent()
        assertEquals(1, pushes.calls.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("exactly one follow-up when the window ends", 2, pushes.calls.size)
        assertNull("the follow-up's result is the one given now", saver.save("a"))

        advanceTimeBy(20 * minute)
        runCurrent()
        assertEquals(3, pushes.calls.size)
        saver.save("a")
        assertEquals("a save after a quiet window pushes at once", 4, pushes.calls.size)
    }

    @Test
    fun `each session has its own window`() = runTest {
        val pushes = Pushes()
        val saver = autosaver(pushes)
        saver.save("a")
        saver.save("b")
        saver.save("a")
        assertEquals(listOf("a", "b"), pushes.calls)
    }

    @Test
    fun `a forgotten session gets no follow-up`() = runTest {
        val pushes = Pushes()
        val saver = autosaver(pushes)
        saver.save("a")
        advanceTimeBy(minute)
        saver.save("a")
        saver.forget("a")
        advanceTimeBy(10 * minute)
        runCurrent()
        assertEquals(1, pushes.calls.size)
    }
}
