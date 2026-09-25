package com.pocketide.ui.work

import com.pocketide.projects.ProjectTrust
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.project.BranchName
import com.pocketide.ui.screens.project.sleepsSoon
import com.pocketide.ui.screens.project.stoppedText
import com.pocketide.ui.screens.project.trustText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionToolsTest {
    @Test
    fun `the chosen part of a branch is what follows its date`() {
        assertEquals("pocket/claude/2026-09-25-", BranchName.prefix("pocket/claude/2026-09-25-login-page"))
        assertEquals("login-page", BranchName.tail("pocket/claude/2026-09-25-login-page"))
        assertEquals("pocket/codex/", BranchName.prefix("pocket/codex/odd"))
        assertEquals("odd", BranchName.tail("pocket/codex/odd"))
        assertEquals("main", BranchName.tail("main"))
    }

    @Test
    fun `sleep chip shows only in the last ten minutes, rounded up`() {
        val now = 1_000_000L
        assertNull(sleepsSoon("Claude", null, now))
        assertNull(sleepsSoon("Claude", now + 10 * 60_000, now))
        assertEquals("Claude sleeps in 10 min", sleepsSoon("Claude", now + 10 * 60_000 - 1, now))
        assertEquals("Codex sleeps in 1 min", sleepsSoon("Codex", now + 5_000, now))
        assertEquals("Codex sleeps in 1 min", sleepsSoon("Codex", now - 5_000, now))
    }

    @Test
    fun `a stopped room says the room's own reason`() {
        assertEquals("Claude: Stopped. Nothing was lost.", stoppedText("Claude", "Stopped. Nothing was lost."))
        assertTrue(stoppedText("Claude", null).contains("Nothing was lost"))
        assertTrue(stoppedText("Claude", " ").startsWith("Claude stopped"))
    }

    @Test
    fun `trust reads as whose code it is`() {
        assertEquals("Your code" to Tone.OK, trustText(ProjectTrust.YOURS))
        assertEquals("Someone else's code" to Tone.WARN, trustText(ProjectTrust.SOMEONE_ELSES))
    }
}
