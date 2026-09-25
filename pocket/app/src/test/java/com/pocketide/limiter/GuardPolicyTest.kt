package com.pocketide.limiter

import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Thermal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardPolicyTest {

    private fun guard(snapshot: PhoneSnapshot, next: RoomKind? = RoomKind.HUB) = GuardPolicy.evaluate(snapshot, next).guard

    @Test
    fun `battery thresholds on battery power`() {
        val table = listOf(
            100 to Guard.OK, 21 to Guard.OK,
            20 to Guard.NO_NEW_HEAVY, 11 to Guard.NO_NEW_HEAVY,
            10 to Guard.PAUSE, 6 to Guard.PAUSE,
            5 to Guard.SAFE_STOP, 1 to Guard.SAFE_STOP, 0 to Guard.SAFE_STOP,
        )
        for ((percent, expected) in table) assertEquals("battery $percent %", expected, guard(phone(battery = percent)))
    }

    @Test
    fun `charging lifts every battery rule`() {
        for (percent in listOf(1, 5, 10, 20)) assertEquals("battery $percent % charging", Guard.OK, guard(phone(battery = percent, charging = true)))
    }

    @Test
    fun `heat thresholds, and charging does not lift heat`() {
        val table = mapOf(
            Thermal.NONE to Guard.OK,
            Thermal.LIGHT to Guard.OK,
            Thermal.MODERATE to Guard.NO_NEW_HEAVY,
            Thermal.SEVERE to Guard.PAUSE,
            Thermal.CRITICAL to Guard.SAFE_STOP,
            Thermal.EMERGENCY to Guard.SAFE_STOP,
            Thermal.SHUTDOWN to Guard.SAFE_STOP,
        )
        for ((thermal, expected) in table) {
            assertEquals("$thermal", expected, guard(phone(thermal = thermal)))
            assertEquals("$thermal charging", expected, guard(phone(thermal = thermal, charging = true)))
        }
    }

    @Test
    fun `the most restrictive condition wins`() {
        assertEquals(Guard.SAFE_STOP, guard(phone(battery = 50, thermal = Thermal.CRITICAL)))
        assertEquals(Guard.SAFE_STOP, guard(phone(battery = 4, thermal = Thermal.MODERATE)))
        assertEquals(Guard.PAUSE, guard(phone(battery = 15, thermal = Thermal.SEVERE)))
        assertEquals(Guard.NO_NEW_AGENTS, guard(phone(battery = 15, free = 100 * MB)))
    }

    @Test
    fun `memory below what one more room needs stops new agents`() {
        assertEquals(Guard.NO_NEW_AGENTS, guard(phone(free = 150 * MB), next = RoomKind.HUB))
        assertEquals(Guard.OK, guard(phone(free = 300 * MB), next = RoomKind.HUB))
        assertEquals(Guard.NO_NEW_AGENTS, guard(phone(free = 700 * MB), next = RoomKind.CODE_SERVER))
        assertEquals(Guard.OK, guard(phone(free = 900 * MB), next = RoomKind.CODE_SERVER))
        // Every room that may run already runs: memory alone does not raise the guard.
        assertEquals(Guard.OK, guard(phone(free = 100 * MB), next = null))
    }

    @Test
    fun `processes near Android's phantom cap stop new agents`() {
        assertEquals(Guard.OK, guard(phone(processes = 27)))
        assertEquals(Guard.NO_NEW_AGENTS, guard(phone(processes = 28)))
        assertEquals(Guard.NO_NEW_AGENTS, guard(phone(processes = 40), next = null))
    }

    @Test
    fun `an unread phone never blocks`() {
        assertEquals(Guard.OK, guard(PhoneSnapshot.UNKNOWN))
    }

    @Test
    fun `every guard but OK explains itself`() {
        val cases = listOf(phone(battery = 18), phone(battery = 8), phone(battery = 3), phone(thermal = Thermal.SEVERE), phone(free = 10 * MB))
        for (snapshot in cases) {
            val verdict = GuardPolicy.evaluate(snapshot, RoomKind.HUB)
            assertTrue(verdict.guard != Guard.OK)
            assertTrue(verdict.why.orEmpty().endsWith("."))
        }
        assertNull(GuardPolicy.evaluate(phone(), RoomKind.HUB).why)
        assertTrue(GuardPolicy.evaluate(phone(battery = 18), RoomKind.HUB).why.orEmpty().contains("18 %"))
    }

    @Test
    fun `next room is the cheapest one not running, or none when full`() {
        assertEquals(RoomKind.HUB, GuardPolicy.nextRoom(emptyList(), 2))
        assertEquals(RoomKind.HUB, GuardPolicy.nextRoom(listOf(RoomKind.CODE_SERVER), 2))
        assertEquals(RoomKind.CODE_SERVER, GuardPolicy.nextRoom(listOf(RoomKind.HUB), 3))
        assertNull(GuardPolicy.nextRoom(listOf(RoomKind.HUB, RoomKind.CODE_SERVER), 2))
    }

    @Test
    fun `max agents is two below 5 GB and three above, unless the owner chose`() {
        assertEquals(2, GuardPolicy.maxAgents(FOUR_GB_TOTAL, 0))
        assertEquals(2, GuardPolicy.maxAgents(4_900_000_000L, 0))
        assertEquals(3, GuardPolicy.maxAgents(5_000_000_000L, 0))
        assertEquals(3, GuardPolicy.maxAgents(EIGHT_GB_TOTAL, 0))
        assertEquals(1, GuardPolicy.maxAgents(EIGHT_GB_TOTAL, 1))
        assertEquals(3, GuardPolicy.maxAgents(FOUR_GB_TOTAL, 3))
        // RAM not read yet: the larger answer, so nothing is refused on a guess.
        assertEquals(3, GuardPolicy.maxAgents(0, 0))
    }

    private fun canStart(agent: String, kind: RoomKind, running: Map<String, RoomKind>, snapshot: PhoneSnapshot) =
        GuardPolicy.canStart(
            agent, kind, running,
            GuardPolicy.evaluate(snapshot, GuardPolicy.nextRoom(running.values, GuardPolicy.maxAgents(snapshot.totalRamBytes, 0))),
            snapshot, GuardPolicy.maxAgents(snapshot.totalRamBytes, 0), NAMES,
        )

    @Test
    fun `on a 4 GB phone one of Claude or Codex runs, plus Antigravity`() {
        val snapshot = phone(total = FOUR_GB_TOTAL, free = 1500 * MB)
        assertTrue(canStart("claude", RoomKind.CODE_SERVER, emptyMap(), snapshot).allowed)
        assertTrue(canStart("antigravity", RoomKind.HUB, mapOf("claude" to RoomKind.CODE_SERVER), snapshot).allowed)

        val second = canStart("codex", RoomKind.CODE_SERVER, mapOf("claude" to RoomKind.CODE_SERVER), snapshot)
        assertFalse(second.allowed)
        assertTrue(second.reason.orEmpty().contains("Stop Claude first"))

        val third = canStart("codex", RoomKind.CODE_SERVER, mapOf("claude" to RoomKind.CODE_SERVER, "antigravity" to RoomKind.HUB), snapshot)
        assertFalse(third.allowed)
        assertTrue(third.reason.orEmpty().contains("2 agents run at a time"))
    }

    @Test
    fun `on an 8 GB phone Claude and Codex run together`() {
        val snapshot = phone(total = EIGHT_GB_TOTAL, free = 3 * GB_BYTES)
        assertTrue(canStart("codex", RoomKind.CODE_SERVER, mapOf("claude" to RoomKind.CODE_SERVER), snapshot).allowed)
        assertTrue(
            canStart("antigravity", RoomKind.HUB, mapOf("claude" to RoomKind.CODE_SERVER, "codex" to RoomKind.CODE_SERVER), snapshot).allowed,
        )
    }

    @Test
    fun `an 8 GB phone still needs the memory for the room`() {
        val tight = phone(total = EIGHT_GB_TOTAL, free = 500 * MB)
        val decision = canStart("codex", RoomKind.CODE_SERVER, emptyMap(), tight)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.orEmpty().contains("Codex needs about 800 MB"))
        // With Antigravity already up, the next room is a code-server room, so the guard itself says so.
        val guarded = canStart("codex", RoomKind.CODE_SERVER, mapOf("antigravity" to RoomKind.HUB), tight)
        assertFalse(guarded.allowed)
        assertTrue(guarded.reason.orEmpty().contains("500 MB"))
    }

    @Test
    fun `a running agent may always be opened again`() {
        val hot = phone(thermal = Thermal.CRITICAL)
        assertTrue(canStart("claude", RoomKind.CODE_SERVER, mapOf("claude" to RoomKind.CODE_SERVER), hot).allowed)
    }

    @Test
    fun `a raised guard refuses new agents with its reason`() {
        val decision = canStart("claude", RoomKind.CODE_SERVER, emptyMap(), phone(battery = 15))
        assertFalse(decision.allowed)
        assertTrue(decision.reason.orEmpty().contains("15 %"))
    }

    @Test
    fun `a new room must not pass the phantom cap`() {
        val busy = phone(total = EIGHT_GB_TOTAL, free = 3 * GB_BYTES, processes = 26)
        assertFalse(canStart("codex", RoomKind.CODE_SERVER, mapOf("claude" to RoomKind.CODE_SERVER), busy).allowed)
    }

    @Test
    fun `heavy work waits for a calm phone with memory`() {
        assertTrue(GuardPolicy.canStartHeavy("Computer updates", GuardPolicy.evaluate(phone(), RoomKind.HUB), phone()).allowed)
        val warm = phone(thermal = Thermal.MODERATE)
        val decision = GuardPolicy.canStartHeavy("Computer updates", GuardPolicy.evaluate(warm, RoomKind.HUB), warm)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.orEmpty().startsWith("Computer updates waits."))
        val low = phone(lowMemory = true)
        assertFalse(GuardPolicy.canStartHeavy("Computer updates", GuardPolicy.evaluate(low, null), low).allowed)
    }

    @Test
    fun `idle rooms are closed only when that makes room`() {
        val snapshot = phone(total = FOUR_GB_TOTAL, free = 1500 * MB)
        val running = mapOf("claude" to RoomKind.CODE_SERVER)
        assertEquals(listOf("claude"), GuardPolicy.roomsToClose("codex", RoomKind.CODE_SERVER, running, listOf("claude"), snapshot, 2))
        // Claude is busy (not in the idle list): nothing may be closed.
        assertNull(GuardPolicy.roomsToClose("codex", RoomKind.CODE_SERVER, running, emptyList(), snapshot, 2))
        // Low battery: closing rooms would not help.
        assertNull(GuardPolicy.roomsToClose("codex", RoomKind.CODE_SERVER, running, listOf("claude"), phone(battery = 12), 2))
        // Already allowed: nothing to close.
        assertEquals(emptyList<String>(), GuardPolicy.roomsToClose("antigravity", RoomKind.HUB, running, listOf("claude"), snapshot, 2))
    }

    @Test
    fun `sizes read the way the owner reads them`() {
        assertEquals("780 MB", bytes(780 * MB))
        assertEquals("3.5 GB", bytes((3.5 * GB_BYTES).toLong()))
        assertEquals("0 MB", bytes(-5))
    }
}
