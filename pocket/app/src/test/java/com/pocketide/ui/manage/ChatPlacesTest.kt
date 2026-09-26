package com.pocketide.ui.manage

import com.pocketide.agents.OfficialAgents
import com.pocketide.core.Settings
import com.pocketide.docs.ChatHomes
import com.pocketide.model.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPlacesTest {
    private val on = Settings(claudeChatsInAccount = true)
    private val off = Settings(claudeChatsInAccount = false)

    private fun session(agentId: String, backUp: Boolean = true, claudeAccount: Boolean = false) = SessionRecord(
        id = "s1", agentId = agentId, projectId = "octo/app", title = "Login", branch = "pocket/$agentId/2026-09-26-login",
        startedAt = 0, lastActivityAt = 0, backUp = backUp, claudeAccount = claudeAccount, deviceId = "phone",
    )

    private fun agent(settings: Settings, agentId: String) = ChatPlaces.all(settings).single { it.agentId == agentId }

    @Test fun `every official agent has an answer, in Help's order`() {
        assertEquals(OfficialAgents.all.map { it.id }, ChatPlaces.all(on).map { it.agentId })
        assertEquals(OfficialAgents.all.map { it.id }, ChatPlaces.all(off).map { it.agentId })
        assertTrue(ChatPlaces.all(on).all { it.page?.startsWith("https://") == true && !it.pageLabel.isNullOrBlank() })
        assertNull(ChatPlaces.forSession(session("someone-else")))
    }

    @Test fun `Claude's agent-level answer follows the switch, and only the Claude app may take its page`() {
        val kept = agent(on, OfficialAgents.CLAUDE)
        assertEquals(ChatPlaces.CLAUDE_ON, kept.kept)
        assertTrue(kept.note.orEmpty().contains("transcript"))
        assertTrue(kept.inOwnApp)
        assertEquals("https://claude.ai/code", kept.page)

        val local = agent(off, OfficialAgents.CLAUDE)
        assertEquals(ChatPlaces.CLAUDE_OFF, local.kept)
        assertFalse("off, it no longer says Anthropic stores new sessions", local.kept.startsWith("Your Claude account"))
        assertNull(local.note)

        assertEquals(agent(on, OfficialAgents.CODEX), agent(off, OfficialAgents.CODEX))
        assertTrue(ChatPlaces.all(on).filter { it.agentId != OfficialAgents.CLAUDE }.none { it.inOwnApp })
    }

    @Test fun `a Claude session is in the Claude account when it ran with Remote Control, whatever the switch says now`() {
        val ran = session(OfficialAgents.CLAUDE, claudeAccount = true)
        val place = ChatPlaces.forSession(ran)!!
        assertTrue(place.kept.contains(ChatPlaces.SESSION_IN_CLAUDE))
        assertTrue(place.note.orEmpty().contains("transcript"))
        assertEquals("https://claude.ai/code", place.page)
        assertTrue(place.inOwnApp)
        assertEquals(ChatHomes.CLAUDE_ACCOUNT_LINE, ChatPlaces.sessionLine(ran))

        val never = session(OfficialAgents.CLAUDE, claudeAccount = false)
        val local = ChatPlaces.forSession(never)!!
        assertTrue(local.kept.contains(ChatPlaces.SESSION_NOT_IN_CLAUDE))
        assertNull(local.note)
        assertNull("no page offered that does not hold this chat", local.page)
        assertNull(ChatPlaces.sessionLine(never))
    }

    @Test fun `a chat that is not backed up is never said to be in the Drive backup`() {
        for (agentId in OfficialAgents.all.map { it.id }) {
            val place = ChatPlaces.forSession(session(agentId, backUp = false))!!
            assertTrue(agentId, place.kept.startsWith(ChatPlaces.SESSION_NOT_BACKED_UP))
            assertFalse(agentId, place.kept.contains(ChatPlaces.SESSION_BACKED_UP))
            assertTrue(agentId, ChatPlaces.forSession(session(agentId))!!.kept.startsWith(ChatPlaces.SESSION_BACKED_UP))
        }
    }

    @Test fun `a Codex or Antigravity session offers no company page, since none of them shows it`() {
        for (agentId in listOf(OfficialAgents.CODEX, OfficialAgents.ANTIGRAVITY)) {
            val place = ChatPlaces.forSession(session(agentId, claudeAccount = true))!!
            assertNull(agentId, place.page)
            assertNull(agentId, place.pageLabel)
            assertNull(agentId, place.note)
            assertFalse(agentId, place.inOwnApp)
            assertNull(agentId, ChatPlaces.sessionLine(session(agentId, claudeAccount = true)))
        }
        assertTrue(ChatPlaces.forSession(session(OfficialAgents.CODEX))!!.kept.endsWith(ChatPlaces.SESSION_CODEX))
        assertTrue(ChatPlaces.forSession(session(OfficialAgents.ANTIGRAVITY))!!.kept.endsWith(ChatPlaces.SESSION_ANTIGRAVITY))
    }

    @Test fun `Codex points to its cloud tasks, and Antigravity to Jules, in the agent-level answer`() {
        val codex = agent(on, OfficialAgents.CODEX)
        assertEquals(ChatHomes.CODEX_CLOUD_LINE, codex.note)
        assertEquals("https://chatgpt.com/codex", codex.page)
        val antigravity = agent(on, OfficialAgents.ANTIGRAVITY)
        assertEquals(ChatHomes.JULES_LINE, antigravity.note)
        assertEquals("https://jules.google", antigravity.page)
    }
}
