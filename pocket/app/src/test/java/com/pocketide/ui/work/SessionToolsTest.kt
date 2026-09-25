package com.pocketide.ui.work

import com.pocketide.bridge.PortListener
import com.pocketide.builds.BuildTemplate
import com.pocketide.ui.screens.project.buildCost
import com.pocketide.usage.BuildEstimate
import com.pocketide.projects.ProjectTrust
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.project.BranchName
import com.pocketide.ui.screens.project.Reach
import com.pocketide.ui.screens.project.loopbackRequest
import com.pocketide.ui.screens.project.reachByPort
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
    fun `listeners on every address are visible on Wi-Fi`() {
        val reach = reachByPort(listOf(PortListener(5173, onNetwork = false), PortListener(8000, onNetwork = true)))
        assertEquals(mapOf(5173 to Reach.PHONE_ONLY, 8000 to Reach.WIFI), reach)
        assertEquals("Only this phone", Reach.PHONE_ONLY.label)
        assertTrue(loopbackRequest(8000).contains("port 8000"))
        assertTrue(loopbackRequest(8000).contains("127.0.0.1"))
    }

    @Test
    fun `the cost line uses the owner's own averages`() {
        val android = BuildTemplate("android-release", "Android", "", "a.yml", "ubuntu-latest")
        val ios = BuildTemplate("ios-simulator", "iPhone", "", "i.yml", "macos-latest", minutesMultiplier = 10)
        val windows = BuildTemplate("windows", "Windows", "", "w.yml", "windows-latest", minutesMultiplier = 2)
        val estimate = BuildEstimate(null, null, "", minutesLeft = 1200, androidMinutesEach = 8, iosMinutesEach = 90)
        assertEquals("A run counts about 8 minutes; 1200 included minutes left this month.", buildCost(android, estimate, publicRepo = false))
        assertEquals("A run counts about 90 minutes; 1200 included minutes left this month.", buildCost(ios, estimate, publicRepo = false))
        assertEquals("Each minute counts 2×; 1200 included minutes left this month.", buildCost(windows, estimate, publicRepo = false))
        assertEquals("Each minute counts 2×.", buildCost(windows, null, publicRepo = false))
        assertNull(buildCost(android, null, publicRepo = false))
        assertTrue(buildCost(ios, estimate, publicRepo = true).orEmpty().startsWith("Free"))
    }

    @Test
    fun `trust reads as whose code it is`() {
        assertEquals("Your code" to Tone.OK, trustText(ProjectTrust.YOURS))
        assertEquals("Someone else's code" to Tone.WARN, trustText(ProjectTrust.SOMEONE_ELSES))
    }
}
