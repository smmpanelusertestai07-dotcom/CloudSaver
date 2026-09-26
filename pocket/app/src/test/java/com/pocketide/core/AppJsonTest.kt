package com.pocketide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppJsonTest {
    @Test
    fun `settings a 3_0 phone saved still load, keeping what still means something`() {
        // 3.0.0 wrote Drive, vault and phone-computer fields that 4.0.0 no longer has.
        val saved = """
            {"theme":"DARK","mobileDailyLimitMb":200,"phoneLimitGb":8,"appLock":true,"onboardingDone":true,
             "gitHubAppClientId":"Iv23liAbCdEfGhIjKlMn","gitHubAppSlug":"pocketide","claudeChatsInAccount":true}
        """.trimIndent()
        val settings = AppJson.decodeFromString(Settings.serializer(), saved)
        assertEquals(ThemeMode.DARK, settings.theme)
        assertTrue(settings.appLock)
        assertEquals("Iv23liAbCdEfGhIjKlMn", settings.gitHubAppClientId)
        assertEquals(NewComputerChoices(), settings.newComputer)
    }

    @Test
    fun `deleting the phone's data keeps only this copy's GitHub App`() {
        val used = Settings(theme = ThemeMode.DARK, appLock = true, lastComputer = "x-1", gitHubAppClientId = "Iv23li", gitHubAppSlug = "app")
        val after = used.afterDeleteEverything()
        assertEquals(Settings(gitHubAppClientId = "Iv23li", gitHubAppSlug = "app"), after)
        assertFalse(after.onboardingDone)
    }

    @Test
    fun `the safe choices are the defaults`() {
        val defaults = Settings()
        assertFalse("screenshots are never blocked; App lock is the owner's choice", defaults.appLock)
        assertEquals(30, defaults.newComputer.idleMinutes)
        assertEquals(30, defaults.newComputer.keepDays)
        assertEquals("", defaults.newComputer.machine)
    }
}
