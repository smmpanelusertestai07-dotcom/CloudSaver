package com.pocketide.limiter

import android.content.Context
import android.content.ContextWrapper
import com.pocketide.core.Clock
import com.pocketide.core.Settings
import com.pocketide.core.SettingsStore
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Thermal
import com.pocketide.rooms.RoomState
import com.pocketide.rooms.Rooms
import com.pocketide.rooms.TerminalHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LimiterImplTest {

    private class FakeMonitor(start: PhoneSnapshot) : PhoneMonitor {
        val flow = MutableStateFlow(start)
        override val snapshot: StateFlow<PhoneSnapshot> = flow
        override fun start() = Unit
        override fun stop() = Unit
        override suspend fun refresh() = flow.value
    }

    private class FakeRooms(vararg running: String) : Rooms {
        val flow = MutableStateFlow<Map<String, RoomState>>(running.associateWith { RoomState.Running("http://127.0.0.1/", null, 0) })
        val stopped = mutableListOf<String>()
        override val states: StateFlow<Map<String, RoomState>> = flow
        override val previewPorts: StateFlow<Map<String, List<Int>>> = MutableStateFlow(emptyMap())
        override suspend fun open(agentId: String, sessionId: String): RoomState = RoomState.Running("http://127.0.0.1/", sessionId, 0)
        override suspend fun stop(agentId: String) {
            stopped += agentId
            flow.update { it - agentId }
        }
        override suspend fun stopAll() {
            stopped += flow.value.keys.sorted()
            flow.value = emptyMap()
        }
        override suspend fun terminal(sessionId: String) = TerminalHandle("", sessionId)
        override suspend fun configure(agentId: String) = Unit
        override suspend fun delete(agentId: String) = Unit
    }

    private class FakeSettings(start: Settings = Settings()) : SettingsStore {
        val flow = MutableStateFlow(start)
        override val settings: StateFlow<Settings> = flow
        override fun update(change: (Settings) -> Settings) = flow.update(change)
    }

    private class FakeHost : LimiterHost {
        val running = mutableListOf<Set<String>>()
        val notified = mutableListOf<RoomStop>()
        var opened = true
        override val vendor = Vendor.COLOR_OS
        override fun phoneFacts() = PhoneFacts(listOf("arm64-v8a"), 33, FOUR_GB_TOTAL, PlayServices.OK)
        override fun roomsRunning(agentIds: Set<String>) {
            running += agentIds
        }
        var exit: RoomStop? = null
        override fun lastExit(): RoomStop? = exit
        override fun forgetExit() {
            exit = null
        }
        override fun notifyStopped(stop: RoomStop) {
            notified += stop
        }
        override fun openFix(context: Context, conditionId: String) = opened
    }

    private object Kinds : AgentKinds {
        override fun kind(agentId: String) = if (agentId == "antigravity") RoomKind.HUB else RoomKind.CODE_SERVER
        override fun name(agentId: String) = NAMES(agentId)
    }

    private class Rig(scope: TestScope, snapshot: PhoneSnapshot, vararg running: String) {
        val monitor = FakeMonitor(snapshot)
        val rooms = FakeRooms(*running)
        val settings = FakeSettings()
        val host = FakeHost()
        val syncs = mutableListOf<String>()
        val limiter = LimiterImpl(
            phone = monitor,
            settings = settings,
            rooms = { rooms },
            sync = { syncs += it },
            agents = Kinds,
            host = host,
            scope = scope.backgroundScope,
            clock = Clock { scope.testScheduler.currentTime },
        )
    }

    @Test
    fun `a pause closes idle rooms at once and busy ones after their step`() = runTest {
        val rig = Rig(this, phone(battery = 50), "claude", "antigravity")
        rig.limiter.start()
        rig.limiter.setBusy("claude", "turn", true)
        runCurrent()

        rig.monitor.flow.value = phone(battery = 9)
        runCurrent()
        assertEquals(Guard.PAUSE, rig.limiter.guard.value)
        assertEquals(listOf("antigravity"), rig.rooms.stopped)
        assertTrue(rig.syncs.isNotEmpty())
        assertEquals(StopCause.BATTERY, rig.limiter.lastStop.value?.cause)
        assertTrue(rig.limiter.lastStop.value?.message.orEmpty().contains("9 %"))

        rig.limiter.setBusy("claude", "turn", false)
        runCurrent()
        assertEquals(listOf("antigravity", "claude"), rig.rooms.stopped)
    }

    @Test
    fun `a safe stop gives a busy room a short grace, then stops everything once`() = runTest {
        val rig = Rig(this, phone(), "claude")
        rig.limiter.start()
        rig.limiter.setBusy("claude", "write", true)
        runCurrent()

        rig.monitor.flow.value = phone(thermal = Thermal.CRITICAL)
        runCurrent()
        assertEquals(Guard.SAFE_STOP, rig.limiter.guard.value)
        assertEquals(emptyList<String>(), rig.rooms.stopped)

        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(listOf("claude"), rig.rooms.stopped)
        assertEquals(1, rig.host.notified.size)
        assertEquals(StopCause.HEAT, rig.host.notified.single().cause)

        advanceTimeBy(120_000)
        assertEquals(1, rig.host.notified.size)
    }

    @Test
    fun `a kill of the last process shows until the owner dismisses it`() = runTest {
        val rig = Rig(this, phone())
        val killed = RoomStop(listOf("claude"), StopCause.ANDROID, "Android closed PocketIDE. Nothing was lost.", 5)
        rig.host.exit = killed
        rig.limiter.start()
        runCurrent()
        assertEquals(killed, rig.limiter.lastStop.value)
        rig.limiter.dismissStop()
        assertEquals(null, rig.limiter.lastStop.value)
        assertEquals(null, rig.host.exit)
    }

    @Test
    fun `charging lifts the pause`() = runTest {
        val rig = Rig(this, phone(battery = 8))
        rig.limiter.start()
        runCurrent()
        assertEquals(Guard.PAUSE, rig.limiter.guard.value)
        rig.monitor.flow.value = phone(battery = 8, charging = true)
        runCurrent()
        assertEquals(Guard.OK, rig.limiter.guard.value)
    }

    @Test
    fun `idle rooms sleep after the idle time and working ones do not`() = runTest {
        val rig = Rig(this, phone(), "claude", "codex")
        rig.limiter.start()
        runCurrent()
        rig.limiter.setBusy("codex", "turn", true)
        advanceTimeBy(29 * 60_000L)
        assertEquals(emptyList<String>(), rig.rooms.stopped)
        advanceTimeBy(2 * 60_000L)
        assertEquals(listOf("claude"), rig.rooms.stopped)
        assertEquals(StopCause.IDLE, rig.limiter.lastStop.value?.cause)
        assertTrue(rig.limiter.lastStop.value?.message.orEmpty().endsWith("Nothing was lost."))
    }

    @Test
    fun `low memory closes the longest idle room, never a busy one`() = runTest {
        val rig = Rig(this, phone(total = EIGHT_GB_TOTAL, free = 3 * GB_BYTES), "claude", "codex")
        rig.limiter.start()
        runCurrent()
        rig.limiter.setBusy("claude", "turn", true)
        advanceTimeBy(3 * 60_000L)
        rig.monitor.flow.value = phone(total = EIGHT_GB_TOTAL, free = 3 * GB_BYTES, lowMemory = true)
        runCurrent()
        assertEquals(listOf("codex"), rig.rooms.stopped)
        assertEquals(StopCause.MEMORY, rig.limiter.lastStop.value?.cause)
    }

    @Test
    fun `the engine follows the running rooms`() = runTest {
        val rig = Rig(this, phone(), "claude")
        rig.limiter.start()
        runCurrent()
        rig.rooms.flow.value = emptyMap()
        runCurrent()
        assertEquals(listOf(setOf("claude"), emptySet()), rig.host.running)
    }

    @Test
    fun `making room closes an idle room that stands in the way`() = runTest {
        val rig = Rig(this, phone(free = 1500 * MB), "claude")
        rig.limiter.start()
        runCurrent()
        assertFalse(rig.limiter.canStartAgent("codex").allowed)
        assertTrue(rig.limiter.makeRoomFor("codex").allowed)
        assertEquals(listOf("claude"), rig.rooms.stopped)

        val busy = Rig(this, phone(free = 1500 * MB), "claude")
        busy.limiter.start()
        busy.limiter.setBusy("claude", "turn", true)
        assertFalse(busy.limiter.makeRoomFor("codex").allowed)
        assertEquals(emptyList<String>(), busy.rooms.stopped)
    }

    @Test
    fun `the owner's agent count wins over Auto`() = runTest {
        val rig = Rig(this, phone(total = EIGHT_GB_TOTAL, free = 4 * GB_BYTES), "claude")
        assertEquals(3, rig.limiter.maxAgents())
        rig.settings.flow.value = Settings(maxAgents = 1)
        assertEquals(1, rig.limiter.maxAgents())
        assertFalse(rig.limiter.canStartAgent("antigravity").allowed)
    }

    @Test
    fun `opening the phone maker's page counts as the one-time step`() = runTest {
        val rig = Rig(this, phone())
        rig.limiter.start()
        runCurrent()
        assertEquals(listOf(ConditionRules.OEM_BATTERY), rig.limiter.conditions.value.map { it.id })

        rig.host.opened = false
        assertFalse(rig.limiter.openFix(ContextWrapper(null), ConditionRules.OEM_BATTERY))
        assertFalse(rig.settings.flow.value.oemStepDone)

        rig.host.opened = true
        assertTrue(rig.limiter.openFix(ContextWrapper(null), ConditionRules.OEM_BATTERY))
        runCurrent()
        assertTrue(rig.settings.flow.value.oemStepDone)
        assertEquals(emptyList<Condition>(), rig.limiter.conditions.value)
    }
}
