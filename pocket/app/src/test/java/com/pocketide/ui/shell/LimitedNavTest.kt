package com.pocketide.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** While storage is full the lock is the whole app: what it offers must work from there. */
class LimitedNavTest {
    private var stack = emptyList<String>()
    private val opened = mutableListOf<String>()
    private val nav = LimitedNav(openUrl = { opened += it }) { next -> stack = next(stack) }

    @Test
    fun yourDataAndRecentlyDeletedOpenWhileLocked() {
        nav.yourData()
        nav.recentlyDeleted()
        assertEquals(listOf(LimitedStack.YOUR_DATA, LimitedStack.RECENTLY_DELETED), stack)
        nav.back()
        assertEquals(listOf(LimitedStack.YOUR_DATA), stack)
    }

    @Test
    fun chatsCannotBeOpenedSoTheyDoNotLookTappable() {
        nav.transcript("s1")
        nav.agent("s1")
        nav.chats()
        assertTrue(stack.isEmpty())
        assertFalse(nav.opensChats)
    }

    @Test
    fun screensOutsideTheLockAreNotOffered() {
        nav.settings()
        nav.secrets(null)
        assertTrue(stack.isEmpty())
        assertFalse(nav.opensEveryScreen)
        // Your data hides its links to Settings and Secrets there, rather than showing dead rows.
        val source = File("src/main/java/com/pocketide/ui/screens/data/YourDataScreen.kt").readText()
        assertTrue(source.contains("if (nav.opensEveryScreen) {\n                    NavRow(Icons.Outlined.Key"))
        assertTrue(source.contains("if (nav.opensEveryScreen) NavRow(Icons.Outlined.Tune"))
        assertTrue(source.contains("nav::settings.takeIf { nav.opensEveryScreen }"))
    }

    @Test
    fun webPagesStillOpen() {
        nav.openExternal("https://one.google.com/storage")
        assertEquals(listOf("https://one.google.com/storage"), opened)
    }

    @Test
    fun yourDataDeletesChatsItselfAndPointsToRecentlyDeleted() {
        // The storage-full lock sends the owner here; Chats cannot be reached from it.
        val source = File("src/main/java/com/pocketide/ui/screens/data/YourDataScreen.kt").readText()
        assertTrue(source.contains("graph.sessions.delete(session.id)"))
        assertTrue(source.contains("nav.recentlyDeleted()"))
        assertTrue(source.contains("nav.opensChats"))
        assertFalse(source.contains("is in Chats"))
    }
}
