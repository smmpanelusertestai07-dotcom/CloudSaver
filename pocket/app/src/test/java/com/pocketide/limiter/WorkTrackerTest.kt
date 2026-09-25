package com.pocketide.limiter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTrackerTest {
    private var now = 0L
    private val tracker = WorkTracker { now }
    private val minute = 60_000L

    @Test
    fun `a busy room never gets sleepy, however long the turn`() {
        tracker.follow(setOf("claude"))
        tracker.setBusy("claude", "turn", true)
        now += 120 * minute
        assertEquals(emptyList<String>(), tracker.sleepy(setOf("claude"), 30 * minute))
        assertNull(tracker.work(setOf("claude"), 30 * minute).getValue("claude").sleepsAt)
    }

    @Test
    fun `idle time counts from the end of the last work`() {
        tracker.follow(setOf("claude"))
        tracker.setBusy("claude", "turn", true)
        now += 10 * minute
        tracker.setBusy("claude", "turn", false)
        val work = tracker.work(setOf("claude"), 30 * minute).getValue("claude")
        assertEquals(40 * minute, work.sleepsAt)
        now += 29 * minute
        assertEquals(emptyList<String>(), tracker.sleepy(setOf("claude"), 30 * minute))
        now += minute
        assertEquals(listOf("claude"), tracker.sleepy(setOf("claude"), 30 * minute))
    }

    @Test
    fun `terminal output and Preview traffic keep a room awake`() {
        tracker.follow(setOf("codex"))
        now += 25 * minute
        tracker.touch("codex")
        now += 25 * minute
        assertEquals(emptyList<String>(), tracker.sleepy(setOf("codex"), 30 * minute))
    }

    @Test
    fun `a room stays busy while any of its holds remains`() {
        tracker.follow(setOf("claude"))
        tracker.setBusy("claude", "turn", true)
        tracker.setBusy("claude", "command", true)
        tracker.setBusy("claude", "turn", false)
        assertTrue(tracker.isBusy("claude"))
        assertEquals(setOf("command"), tracker.work(setOf("claude"), 30 * minute).getValue("claude").busy)
        tracker.setBusy("claude", "command", false)
        assertTrue(!tracker.isBusy("claude"))
    }

    @Test
    fun `idle sleep off means no room ever sleeps`() {
        tracker.follow(setOf("claude"))
        now += 1000 * minute
        assertEquals(emptyList<String>(), tracker.sleepy(setOf("claude"), 0))
    }

    @Test
    fun `the longest idle room is closed first, and busy ones never`() {
        tracker.follow(setOf("claude", "codex", "antigravity"))
        now += minute
        tracker.touch("codex")
        tracker.setBusy("antigravity", "turn", true)
        now += 5 * minute
        assertEquals(listOf("claude", "codex"), tracker.idleLongest(setOf("claude", "codex", "antigravity"), 2 * minute))
        assertEquals(listOf("claude"), tracker.idleLongest(setOf("claude", "codex", "antigravity"), 5 * minute + 30_000))
    }

    @Test
    fun `stopped rooms are forgotten, so a restart starts a fresh clock`() {
        tracker.follow(setOf("claude"))
        tracker.setBusy("claude", "turn", true)
        tracker.follow(emptySet())
        now += 50 * minute
        tracker.follow(setOf("claude"))
        val work = tracker.work(setOf("claude"), 30 * minute).getValue("claude")
        assertEquals(emptySet<String>(), work.busy)
        assertEquals(now, work.lastActiveAt)
    }
}
