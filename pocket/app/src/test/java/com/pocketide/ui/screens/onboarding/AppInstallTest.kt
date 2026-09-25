package com.pocketide.ui.screens.onboarding

import com.pocketide.vault.FakeGitHub
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInstallTest {
    private val gitHub = FakeGitHub()

    @Test
    fun `continue waits while the GitHub App is missing, and is offered once it is installed`() = runTest {
        gitHub.appInstalled = false
        val missing = appInstall(gitHub, "octo")
        assertEquals(AppInstall.MISSING, missing)
        assertFalse(missing.canContinue)
        assertTrue("asked again after the owner comes back from GitHub", missing.askAgainOnReturn)

        gitHub.appInstalled = true
        val installed = appInstall(gitHub, "octo")
        assertEquals(AppInstall.INSTALLED, installed)
        assertTrue(installed.canContinue)
        assertFalse(installed.askAgainOnReturn)
        assertFalse(AppInstall.CHECKING.canContinue)
    }

    @Test
    fun `a check GitHub cannot answer never blocks set-up`() = runTest {
        gitHub.connected = false
        val unknown = appInstall(gitHub, "octo")
        assertEquals(AppInstall.UNKNOWN, unknown)
        assertTrue(unknown.canContinue)
    }
}
