package com.pocketide.ui.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentTrustTest {
    @Test
    fun `the official namespaces and ordinary publishers pass`() {
        assertNull(AgentTrust.problem("anthropic.claude-code"))
        assertNull(AgentTrust.problem("openai.chatgpt"))
        assertNull(AgentTrust.problem("Google.google-antigravity"))
        assertNull(AgentTrust.problem("kilocode.kilo-code"))
        assertNull(AgentTrust.problem("saoudrizwan.claude-dev"))
        assertNull(AgentTrust.problem("rooveterinaryinc.roo-cline"))
    }

    @Test
    fun `one or two letters away from an official publisher is refused`() {
        for (id in listOf("anthrop1c.claude-code", "anthropc.claude-code", "openal.chatgpt", "open-ai.chatgpt", "gooogle.antigravity", "goggle.gemini")) {
            assertNotNull(id, AgentTrust.problem(id))
        }
    }

    @Test
    fun `look-alike letters from other alphabets are refused`() {
        // Cyrillic "а" and "о" look like Latin a and o.
        assertNotNull(AgentTrust.problem("аnthropic.claude-code"))
        assertNotNull(AgentTrust.problem("openai.chatgpt​"))
        assertNotNull(AgentTrust.problem("gооgle.antigravity"))
    }

    @Test
    fun `malformed ids are refused`() {
        for (id in listOf("", "noDot", ".name", "ns.", "ns/name", "ns.name with space", "ns..", "../etc.passwd")) {
            assertNotNull("'$id'", AgentTrust.problem(id))
        }
    }

    @Test
    fun `edit distance`() {
        assertEquals(0, AgentTrust.distance("openai", "openai"))
        assertEquals(1, AgentTrust.distance("openal", "openai"))
        assertEquals(2, AgentTrust.distance("opneai", "openai"))
        assertEquals(6, AgentTrust.distance("", "openai"))
    }
}
