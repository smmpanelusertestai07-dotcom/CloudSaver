package com.pocketide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppJsonTest {
    @Test
    fun `settings an older version saved still load, keeping what still means something`() {
        // 4.x wrote cloud computer and GitHub App fields that 5.0 no longer has.
        val saved = """
            {"theme":"DARK","appLock":true,"onboardingDone":true,"lastComputer":"x-1","stayConnected":true,
             "gitHubAppClientId":"Iv23liAbCdEfGhIjKlMn","gitHubAppSlug":"pocketide","newComputer":{"idleMinutes":60}}
        """.trimIndent()
        val settings = AppJson.decodeFromString(Settings.serializer(), saved)
        assertEquals(ThemeMode.DARK, settings.theme)
        assertTrue(settings.appLock)
        assertTrue(settings.onboardingDone)
        assertEquals("", settings.project)
    }

    @Test
    fun `deleting everything puts every choice back`() {
        val used = Settings(theme = ThemeMode.DARK, appLock = true, project = "app", updatesOnMobileData = true)
        val after = used.afterDeleteEverything()
        assertEquals(Settings(), after)
        assertFalse(after.onboardingDone)
    }

    @Test
    fun `the safe choices are the defaults`() {
        val defaults = Settings()
        assertFalse("screenshots are never blocked; App lock is the owner's choice", defaults.appLock)
        assertFalse("updates wait for Wi-Fi unless the owner allows mobile data", defaults.updatesOnMobileData)
        assertTrue(defaults.keyBar)
    }
}
