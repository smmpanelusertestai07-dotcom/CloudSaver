package com.pocketide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTest {
    private val id = "3f1c2a9e-7b4d-4e0a-9c55-0d2b6f8a1e77"

    @Test
    fun `a failing step does not stop the ones after it`() {
        val ran = mutableListOf<String>()
        val failed = mutableListOf<String>()
        runEach(
            listOf(
                Step("limiter") { ran += "limiter" },
                Step("sync") { error("Drive is not ready") },
                Step("rooms") { ran += "rooms" },
            ),
        ) { name, _ -> failed += name }
        assertEquals(listOf("limiter", "rooms"), ran)
        assertEquals(listOf("sync"), failed)
    }

    @Test
    fun `a notification or shortcut opens its session`() {
        assertEquals(id, sessionToOpen(notice = id, shortcut = null, fromHistory = false))
        assertEquals(id, sessionToOpen(notice = null, shortcut = id, fromHistory = false))
    }

    @Test
    fun `a relaunch from Recents or a crafted id opens nothing`() {
        assertNull(sessionToOpen(notice = id, shortcut = null, fromHistory = true))
        assertNull(sessionToOpen(notice = "../../vault", shortcut = null, fromHistory = false))
        assertNull(sessionToOpen(notice = null, shortcut = null, fromHistory = false))
        assertEquals(id, sessionToOpen(notice = "not an id!", shortcut = id, fromHistory = false))
    }

    @Test
    fun `only other apps' content can be shared in`() {
        assertTrue(isShareable("content", "com.android.providers.media.documents", "com.pocketide"))
        assertFalse(isShareable("file", null, "com.pocketide"))
        assertFalse(isShareable("content", "com.pocketide.files", "com.pocketide"))
        assertFalse(isShareable("content", "com.pocketide.debug.files", "com.pocketide.debug"))
        assertFalse(isShareable(null, null, "com.pocketide"))
    }
}
