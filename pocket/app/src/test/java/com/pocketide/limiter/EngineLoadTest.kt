package com.pocketide.limiter

import com.pocketide.linux.ComputerState
import com.pocketide.rooms.RemoteControlState
import com.pocketide.rooms.RoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLoadTest {
    @Test fun `set-up, reset, repair and updates keep the service up with no room running`() {
        val installing = ComputerState.Installing("Downloading Ubuntu…", 0.423f, 42, 100)
        for (state in listOf(installing, ComputerState.Installing("Removing the old computer…", null, 0, 0), ComputerState.Updating("Repair"))) {
            val load = EngineLoad(emptyList(), EngineLoad.computerWork(state))
            assertFalse(state.toString(), load.idle)
            assertTrue("the CPU is held while the computer is worked on", load.working)
            assertEquals("Working on the computer", load.title)
        }
        assertEquals("Computer: Downloading Ubuntu… 42%", EngineLoad(emptyList(), EngineLoad.computerWork(installing)).text)
        assertEquals("Computer: Repair", EngineLoad(emptyList(), EngineLoad.computerWork(ComputerState.Updating("Repair"))).text)
    }

    @Test fun `only a computer at rest with no room lets the service go`() {
        for (state in listOf(ComputerState.Ready, ComputerState.NotInstalled, ComputerState.Broken("Set-up stopped.", "Try again."))) {
            assertNull(EngineLoad.computerWork(state))
            assertTrue(EngineLoad(emptyList(), EngineLoad.computerWork(state)).idle)
        }
    }

    @Test fun `rooms and the computer's work share the notification`() {
        val states = mapOf("claude" to RoomState.Running("u", "s1", 0), "codex" to RoomState.Stopped, "antigravity" to RoomState.Starting("Preparing the room"))
        assertEquals(setOf("claude", "antigravity"), EngineLoad.running(states))
        val lines = listOf(EngineLoad.Line("Antigravity", working = false, starting = true), EngineLoad.Line("Claude", working = true, starting = false))
        val load = EngineLoad(lines, "Ubuntu's security fixes")
        assertEquals("Computer running", load.title)
        assertEquals("Computer: Ubuntu's security fixes · Antigravity: starting · Claude: working", load.text)
        assertFalse(EngineLoad(listOf(EngineLoad.Line("Claude", working = false, starting = false)), null).working)
    }

    @Test fun `Remote Control keeps the service up with no room running, and is named in its notification`() {
        val names = mapOf("antigravity" to "Antigravity", "claude" to "Claude")
        val failedRoom = mapOf("antigravity" to RoomState.Failed("Its screen could not open."))

        val on = EngineLoad.of(failedRoom, emptyMap(), mapOf("antigravity" to RemoteControlState.On), ComputerState.Ready, names::getValue)

        assertFalse("its daemon runs in the room while the room itself is not open", on.idle)
        assertFalse("an idle daemon does not hold the phone awake", on.working)
        assertEquals("Antigravity Remote Control: on", on.text)
        val starting = EngineLoad.of(emptyMap(), emptyMap(), mapOf("antigravity" to RemoteControlState.Starting), ComputerState.Ready, names::getValue)
        assertEquals("Antigravity Remote Control: starting", starting.text)
        val turnedOff = mapOf("antigravity" to RemoteControlState.Off("It opened a port any app can use."))
        assertTrue(EngineLoad.of(failedRoom, emptyMap(), turnedOff, ComputerState.Ready, names::getValue).idle)
        val both = EngineLoad.of(
            mapOf("claude" to RoomState.Running("u", "s1", 0)), mapOf("claude" to true), mapOf("antigravity" to RemoteControlState.On),
            ComputerState.Ready, names::getValue,
        )
        assertEquals("Claude: working · Antigravity Remote Control: on", both.text)
    }
}
