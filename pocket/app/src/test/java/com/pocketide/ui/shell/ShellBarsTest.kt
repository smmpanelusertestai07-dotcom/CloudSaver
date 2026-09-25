package com.pocketide.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shell draws the title bar and the access banner for the four tabs (AppNav). A tab screen
 * that drew its own as well would show the owner two bars, or the same banner twice.
 */
class ShellBarsTest {
    private val screens = File("src/main/java/com/pocketide/ui/screens")

    private val tabScreens = mapOf(
        Tab.HOME to ("home/HomeScreen.kt" to "HomeScreen"),
        Tab.CHATS to ("chats/ChatsScreens.kt" to "ChatsScreen"),
        Tab.ACTIVITY to ("activity/ActivityScreen.kt" to "ActivityScreen"),
        Tab.SETTINGS to ("settings/SettingsScreen.kt" to "SettingsScreen"),
    )

    /** The source of one top-level function, up to its closing brace at the start of a line. */
    private fun body(file: String, function: String): String {
        val text = File(screens, file).readText()
        val start = text.indexOf("\nfun $function(")
        assertTrue("$function is not in $file", start >= 0)
        val end = text.indexOf("\n}\n", start)
        return text.substring(start, if (end < 0) text.length else end)
    }

    @Test
    fun everyTabIsCovered() {
        assertTrue(tabScreens.keys == Tab.entries.toSet())
    }

    @Test
    fun tabScreensDrawNoTitleBarOfTheirOwn() {
        tabScreens.forEach { (tab, where) ->
            val source = body(where.first, where.second)
            assertFalse("${tab.label} draws a second title bar", source.contains("TopAppBar("))
            // A tab is a root: there is nothing to go back to.
            assertFalse("${tab.label} has a Back arrow", source.contains("nav::back") || source.contains("nav.back()"))
        }
    }

    @Test
    fun homeLeavesTheAccessBannerToTheShell() {
        val home = File(screens, "home/HomeScreen.kt").readText()
        assertFalse(home.contains("graph.access"))
        assertFalse(home.contains(".banner"))
    }
}
