package com.pocketide.ui.manage

import com.pocketide.agents.OfficialAgents
import com.pocketide.core.Settings
import com.pocketide.docs.ChatHomes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPlacesTest {
    private val on = Settings(claudeChatsInAccount = true)
    private val off = Settings(claudeChatsInAccount = false)

    @Test fun `every official agent has an answer, in Help's order`() {
        assertEquals(OfficialAgents.all.map { it.id }, ChatPlaces.all(on).map { it.agentId })
        assertEquals(OfficialAgents.all.map { it.id }, ChatPlaces.all(off).map { it.agentId })
        assertTrue(ChatPlaces.all(on).all { it.page?.startsWith("https://") == true && !it.pageLabel.isNullOrBlank() })
        assertNull(ChatPlaces.of("someone-else", on))
    }

    @Test fun `a Claude session says it is also in the Claude account only while the switch is on`() {
        assertEquals(ChatHomes.CLAUDE_ACCOUNT_LINE, ChatPlaces.sessionLine(OfficialAgents.CLAUDE, on))
        assertNull(ChatPlaces.sessionLine(OfficialAgents.CLAUDE, off))
        assertNull(ChatPlaces.sessionLine(OfficialAgents.CODEX, on))
        assertNull(ChatPlaces.sessionLine(OfficialAgents.ANTIGRAVITY, on))
    }

    @Test fun `Claude's answer follows the switch, and only the Claude app may take its page`() {
        val kept = ChatPlaces.of(OfficialAgents.CLAUDE, on)!!
        assertEquals(ChatPlaces.CLAUDE_ON, kept.kept)
        assertTrue(kept.note.orEmpty().contains("transcript"))
        assertTrue(kept.inOwnApp)
        assertEquals("https://claude.ai/code", kept.page)

        val local = ChatPlaces.of(OfficialAgents.CLAUDE, off)!!
        assertEquals(ChatPlaces.CLAUDE_OFF, local.kept)
        assertFalse("off, it no longer says Anthropic stores new sessions", local.kept.startsWith("Your Claude account"))
        assertNull(local.note)

        assertEquals(ChatPlaces.of(OfficialAgents.CODEX, on), ChatPlaces.of(OfficialAgents.CODEX, off))
        assertTrue(ChatPlaces.all(on).filter { it.agentId != OfficialAgents.CLAUDE }.none { it.inOwnApp })
    }

    @Test fun `Codex points to its cloud tasks, and Antigravity to Jules`() {
        val codex = ChatPlaces.of(OfficialAgents.CODEX, on)!!
        assertEquals(ChatHomes.CODEX_CLOUD_LINE, codex.note)
        assertEquals("https://chatgpt.com/codex", codex.page)
        val antigravity = ChatPlaces.of(OfficialAgents.ANTIGRAVITY, on)!!
        assertEquals(ChatHomes.JULES_LINE, antigravity.note)
        assertEquals("https://jules.google", antigravity.page)
    }
}
