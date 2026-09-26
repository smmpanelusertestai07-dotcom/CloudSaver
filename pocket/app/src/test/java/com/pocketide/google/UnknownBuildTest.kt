package com.pocketide.google

import com.pocketide.docs.DocsContent
import com.pocketide.docs.OwnerSetUp
import com.pocketide.docs.blockLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google's DEVELOPER_ERROR: the owner's Google Cloud project does not know this build. */
class UnknownBuildTest {
    private val fingerprint = "12:34:56:78:9A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0:12:34:56:78"

    @Test
    fun `the message names the package, the SHA-1 and where the steps are`() {
        val message = UnknownBuild.message("org.example.fake", fingerprint)
        assertTrue(message, message.contains("org.example.fake"))
        assertTrue(message, message.contains(fingerprint))
        assertTrue(message, message.contains("Google Cloud project") && message.contains("OAuth client of type Android"))
        assertTrue(message, message.contains("Help, \"${OwnerSetUp.GOOGLE_CLOUD_TITLE}\""))
        // The owner builds and signs the app: there is no other release to install.
        assertFalse(message, message.contains("official release"))
    }

    @Test
    fun `without the SHA-1 the message still says which certificate`() {
        val message = UnknownBuild.message("org.example.fake", null)
        assertTrue(message, message.contains("org.example.fake"))
        assertTrue(message, message.contains("the SHA-1 of the certificate that signed this build"))
        assertFalse(message, message.contains("null"))
    }

    @Test
    fun `the fingerprint is written as Google Cloud shows it`() {
        // SHA-1 of "abc" (FIPS 180 test vector).
        assertEquals(
            "A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D",
            UnknownBuild.sha1Fingerprint("abc".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `Help's steps cover everything the Google Cloud project needs`() {
        val section = DocsContent.sections.single { it.title == OwnerSetUp.GOOGLE_CLOUD_TITLE }
        val text = section.blocks.flatMap(::blockLines).joinToString(" ")
        for (fact in listOf("project", "Google Drive API", "drive.appdata", "In production", "type Android", "com.pocketide", "com.pocketide.debug", "SHA-1")) {
            assertTrue(fact, text.contains(fact))
        }
        assertFalse("outside the guide's word budget", section in DocsContent.guide)
    }
}
