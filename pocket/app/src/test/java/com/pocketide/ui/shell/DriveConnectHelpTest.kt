package com.pocketide.ui.shell

import com.pocketide.docs.DocsContent
import com.pocketide.docs.OwnerSetUp
import com.pocketide.google.DriveAuthResult
import com.pocketide.ui.manage.HelpRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Drive step and the Drive lock are the only screens a Drive sign-in failure shows on, and
 * both must pass the panel a way into Help: for a build Google does not know, it leads to the fix.
 */
class DriveConnectHelpTest {
    @Test
    fun `a build Google does not know leads to Help's Google Cloud set-up`() {
        val section = helpFor(DriveAuthResult.Failed("Google does not know this build of PocketIDE.", unknownBuild = true))
        assertEquals("google-cloud", section)
        val route = HelpRoute.resolve(section, DocsContent.sections, emptyList(), DocsContent.faq)
        assertEquals(HelpRoute.Page(OwnerSetUp.googleCloud), route)
    }

    @Test
    fun `other failures are fixed by trying again`() {
        assertNull(helpFor(DriveAuthResult.Failed("No connection to Google. Check the internet and try again.")))
    }
}
