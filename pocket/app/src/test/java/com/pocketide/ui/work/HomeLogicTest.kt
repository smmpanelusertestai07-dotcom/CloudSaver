package com.pocketide.ui.work

import android.provider.Settings
import com.pocketide.github.RepoInfo
import com.pocketide.model.Thermal
import com.pocketide.rooms.RoomState
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.home.batteryLabel
import com.pocketide.ui.screens.home.filterRepos
import com.pocketide.ui.screens.home.heatLabel
import com.pocketide.ui.screens.home.needsPackageUri
import com.pocketide.ui.screens.home.repoId
import com.pocketide.ui.screens.home.repoNameProblem
import com.pocketide.ui.screens.home.roomLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {
    private fun repo(owner: String, name: String, pushedAt: String?) =
        RepoInfo(owner, name, isPrivate = true, defaultBranch = "main", sizeKb = 1, cloneUrl = "", htmlUrl = "", pushedAt = pushedAt)

    @Test
    fun reposFilterAndPutNewOnesFirst() {
        val repos = listOf(
            repo("Me", "Old-App", "2026-01-01T00:00:00Z"),
            repo("me", "new-app", "2026-09-01T00:00:00Z"),
            repo("me", "added", "2026-09-20T00:00:00Z"),
            repo("org", "tool", null),
        )
        val added = setOf("me/added")
        assertEquals(listOf("new-app", "Old-App", "tool", "added"), filterRepos(repos, "", added).map { it.name })
        assertEquals(listOf("new-app", "Old-App"), filterRepos(repos, "APP", added).map { it.name })
        assertEquals(listOf("added"), filterRepos(repos, "added", added).map { it.name })
        assertEquals(listOf("tool"), filterRepos(repos, "org/", added).map { it.name })
        assertEquals("me/old-app", repoId(repos[0]))
    }

    @Test
    fun repoNamesFollowGitHubRules() {
        assertNull(repoNameProblem("my-app"))
        assertNull(repoNameProblem("  my_app.v2  "))
        assertNotNull(repoNameProblem(""))
        assertNotNull(repoNameProblem(".."))
        assertNotNull(repoNameProblem("my app"))
        assertNotNull(repoNameProblem("app.git"))
        assertNotNull(repoNameProblem("a".repeat(101)))
        assertNotNull(repoNameProblem("ऐप"))
    }

    @Test
    fun heatAndBattery() {
        assertEquals("Cool" to Tone.OK, heatLabel(Thermal.NONE))
        assertEquals(Tone.WARN, heatLabel(Thermal.MODERATE).second)
        assertEquals(Tone.ERROR, heatLabel(Thermal.SEVERE).second)
        assertEquals("Too hot" to Tone.ERROR, heatLabel(Thermal.SHUTDOWN))
        assertEquals("80%" to Tone.OK, batteryLabel(80, charging = false))
        assertEquals("15%" to Tone.WARN, batteryLabel(15, charging = false))
        assertEquals("5%" to Tone.ERROR, batteryLabel(5, charging = false))
        assertEquals("5% charging" to Tone.OK, batteryLabel(5, charging = true))
    }

    @Test
    fun roomStateWording() {
        assertEquals("Ready" to Tone.NEUTRAL, roomLabel(null))
        assertEquals("Ready" to Tone.NEUTRAL, roomLabel(RoomState.Stopped))
        assertEquals("Installing Claude Code" to Tone.WARN, roomLabel(RoomState.Starting("Installing Claude Code")))
        assertEquals("Running · 512 MB" to Tone.OK, roomLabel(RoomState.Running("u", null, 512L * 1024 * 1024)))
        assertEquals("Stopped: out of memory" to Tone.ERROR, roomLabel(RoomState.Failed("out of memory")))
    }

    @Test
    fun settingsPagesThatNeedThePackage() {
        assertTrue(needsPackageUri(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        assertTrue(needsPackageUri(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
        assertFalse(needsPackageUri(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        assertFalse(needsPackageUri(Settings.ACTION_WIFI_SETTINGS))
    }
}
