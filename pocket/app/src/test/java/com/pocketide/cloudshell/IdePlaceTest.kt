package com.pocketide.cloudshell

import com.pocketide.agents.Agent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdePlaceTest {
    private val account = "dev@example.com"

    @Test
    fun `each agent opens full screen, and the tools open Cloud Shell's pages`() {
        Agent.entries.forEach { agent ->
            val place = IdePlace.of(agent)
            assertEquals(agent, place.agent)
            assertEquals(agent.displayName, place.label)
            assertEquals(CloudShell.screen(agent, account), place.url(account))
        }
        assertEquals(CloudShell.terminal(account), IdePlace.TERMINAL.url(account))
        assertEquals(CloudShell.editor(account), IdePlace.FILES.url(account))
        assertNull(IdePlace.TERMINAL.agent)
    }

    @Test
    fun `only a place's own name picks it`() {
        IdePlace.entries.forEach { assertEquals(it, IdePlace.named(it.name)) }
        listOf(null, "", "codex", "https://example.com", "CLAUDE ").forEach { assertNull(it, IdePlace.named(it)) }
    }

    @Test
    fun `Chrome's menu has room for every other place`() {
        // Chrome shows at most five custom menu items; each tab lists every place but its own.
        assertEquals(true, IdePlace.entries.size - 1 <= CHROME_MENU_ITEMS)
    }

    private companion object {
        const val CHROME_MENU_ITEMS = 5
    }
}
