package com.pocketide.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    private fun v(text: String) = SemVer.parse(text)!!

    private fun accepts(range: String, version: String) = EngineRange.parse(range)!!.accepts(v(version))

    @Test
    fun versionsCompareAsSemverSays() {
        assertTrue(v("1.2.10") > v("1.2.9"))
        assertTrue(v("26.5908.31748") > v("26.908.40401"))
        assertTrue(v("1.0.0") > v("1.0.0-beta.11"))
        assertTrue(v("1.0.0-beta.11") > v("1.0.0-beta.2"))
        assertTrue(v("1.0.0-beta") > v("1.0.0-alpha.1"))
        assertEquals(v("1.2.0"), v("v1.2"))
        assertEquals(0, v("1.2.3+build.7").compareTo(v("1.2.3")))
        assertEquals("1.2.3-rc.1", v("1.2.3-rc.1").toString())
    }

    @Test
    fun whatIsNotAVersionIsRefused() {
        listOf("", "x", "1.2.3.4", "01.2.3", "1.2.3-", "1..2", "1.2.3-b@d").forEach { assertNull(it, SemVer.parse(it)) }
    }

    @Test
    fun engineRangesReadTheWayVsCodeReadsThem() {
        assertTrue(accepts("^1.94.0", "1.138.0"))
        assertFalse(accepts("^1.94.0", "2.0.0"))
        assertFalse(accepts("^1.200.0", "1.138.0"))
        // A pre-release tag in the range is left out, as VS Code does.
        assertTrue(accepts("^1.96.2-insider", "1.96.2"))
        assertTrue(accepts(">=1.80.0", "1.138.0"))
        assertTrue(accepts(">= 1.80.0 <2", "1.138.0"))
        assertFalse(accepts("~1.137.0", "1.138.0"))
        assertTrue(accepts("1.x", "1.138.0"))
        assertTrue(accepts("*", "1.138.0"))
        assertTrue(accepts("1.100.0 - 1.140", "1.138.9"))
        assertTrue(accepts("^2.0.0 || ^1.130.0", "1.138.0"))
        assertFalse(accepts("<1.100.0", "1.138.0"))
        assertTrue(accepts("^0.2.3", "0.2.9"))
        assertFalse(accepts("^0.2.3", "0.3.0"))
    }

    @Test
    fun aRangeThatCannotBeReadAcceptsNothing() {
        assertNull(EngineRange.parse("latest"))
        assertNull(EngineRange.parse("^1.x.y"))
        assertNull(EngineRange.parse("1 - 2 - 3"))
    }

    @Test
    fun onlyTheExtensionsOwnFoldersCount() {
        assertTrue(RoomPaths.holds("anthropic.claude-code-2.1.281-linux-arm64", "Anthropic.claude-code"))
        assertTrue(RoomPaths.holds("anthropic.claude-code-2.1.281-linux-arm64", "anthropic.claude-code", "2.1.281"))
        assertTrue(RoomPaths.holds("openai.chatgpt-26.908.40401", "openai.chatgpt", "26.908.40401"))
        assertFalse(RoomPaths.holds("anthropic.claude-code-2.1.2811-linux-arm64", "anthropic.claude-code", "2.1.281"))
        assertFalse(RoomPaths.holds("anthropic.claude-code-extra-1.0.0", "anthropic.claude-code"))
        assertFalse(RoomPaths.holds("pocketide.pocketide-companion-3.0.0", "anthropic.claude-code"))
    }

    @Test
    fun lookAlikesOfTheOfficialAgentsAreCaught() {
        assertEquals(null, Lookalikes.problem("cline", "cline"))
        assertEquals(null, Lookalikes.problem("anthropic", "claude-code"))
        assertTrue(Lookalikes.problem("anthrop1c", "claude-code") != null)
        assertTrue(Lookalikes.problem("0penai", "chat") != null)
        assertTrue(Lookalikes.problem("someone", "claude-code") != null)
        assertTrue(Lookalikes.problem("someone", "chatgpt2") != null)
        // Cyrillic "а" in place of the Latin letter.
        assertTrue(Lookalikes.problem("аnthropic", "claude-code") != null)
    }
}
