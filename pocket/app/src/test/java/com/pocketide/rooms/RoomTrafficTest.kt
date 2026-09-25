package com.pocketide.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomTrafficTest {
    private val agents = mapOf("s1" to "claude")

    private fun agentOf(purpose: String, targetPort: Int = 4000, port: Int = targetPort) =
        RoomTraffic.agentOf(purpose, targetPort, port) { agents[it] }

    @Test fun `terminals and previews keep their session's room awake`() {
        assertEquals("claude", agentOf(RoomTraffic.terminalPurpose("s1")))
        assertEquals("claude", agentOf(RoomTraffic.previewPurpose("s1")))
        assertNull(agentOf(RoomTraffic.previewPurpose("gone")))
    }

    @Test fun `the agent's page counts only when it opens a dev server`() {
        assertNull("the page talking to its engine", agentOf(RoomTraffic.agentPurpose("codex")))
        assertEquals("codex", agentOf(RoomTraffic.agentPurpose("codex"), targetPort = 4000, port = 5173))
        assertNull(agentOf("something else"))
    }

    @Test fun `only previews leave their target port to the owner`() {
        assertEquals(true, RoomTraffic.isPreview(RoomTraffic.previewPurpose("s1")))
        assertEquals(false, RoomTraffic.isPreview(RoomTraffic.terminalPurpose("s1")))
    }
}
