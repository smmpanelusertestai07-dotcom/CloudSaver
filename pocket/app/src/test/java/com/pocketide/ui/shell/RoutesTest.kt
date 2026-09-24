package com.pocketide.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class RoutesTest {
    @Test
    fun projectIdsWithSlashesStayOneArgument() {
        assertEquals("project/octo%2Fmy-app", Routes.project("octo/my-app"))
    }

    @Test
    fun encodingKeepsUnreservedAndEscapesTheRest() {
        assertEquals("a-b_c.d~e", Routes.encode("a-b_c.d~e"))
        assertEquals("a%20b%3Fc%26d%3De%23f%25", Routes.encode("a b?c&d=e#f%"))
        assertEquals("%E0%A4%A8%E0%A4%AE", Routes.encode("नम"))
    }

    @Test
    fun encodingRoundTripsThroughPercentDecoding() {
        val ids = listOf("pocket/claude/2026-09-24-login-fix", "weird id+with spaces", "ü/ß", "100%")
        for (id in ids) {
            // URLDecoder turns "+" into a space, which encode() never emits, so it decodes like Uri.decode here.
            assertEquals(id, URLDecoder.decode(Routes.encode(id), "UTF-8"))
        }
    }

    @Test
    fun optionalArgumentsAreLeftOutWhenMissing() {
        assertEquals("help", Routes.help(null))
        assertEquals("help", Routes.help(""))
        assertEquals("help?section=terms", Routes.help("terms"))
        assertEquals("secrets", Routes.secrets(null))
        assertEquals("schedules?projectId=o%2Fr", Routes.schedules("o/r"))
    }

    @Test
    fun everyPatternBelongsToATab() {
        assertEquals(Tab.HOME, Routes.tabOf(Routes.PROJECT))
        assertEquals(Tab.HOME, Routes.tabOf(Routes.AGENT))
        assertEquals(Tab.CHATS, Routes.tabOf(Routes.TRANSCRIPT))
        assertEquals(Tab.CHATS, Routes.tabOf(Routes.WAITING_UPLOADS))
        assertEquals(Tab.ACTIVITY, Routes.tabOf(Routes.USAGE))
        assertEquals(Tab.SETTINGS, Routes.tabOf(Routes.YOUR_DATA))
        assertEquals(Tab.SETTINGS, Routes.tabOf(Routes.HELP))
        assertEquals(Tab.HOME, Routes.tabOf(null))
        Tab.entries.forEach { assertEquals(it, Routes.tabOf(it.route)) }
    }

    @Test
    fun onlyTheAgentHidesTheBars() {
        assertFalse(Routes.showsBottomBar(Routes.AGENT))
        assertTrue(Routes.showsBottomBar(Routes.PROJECT))
        assertTrue(Routes.isTab(Routes.CHATS))
        assertFalse(Routes.isTab(Routes.PROJECT))
    }
}
