package com.pocketide.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How Android's signing certificates become the signers the update rules compare. */
class ApkFactsTest {
    private val current = "current key".toByteArray()
    private val rotatedAway = "rotated-away key".toByteArray()

    /** SHA-256 of "current key", as `sha256sum` prints it: the form of BuildConfig.SIGNING_CERT_SHA256. */
    private val currentHex = "48bbd0fad44ba079d34d51b4488fe120f4c8f5a7f9a17b3caf1e9543f2c1f0f9"

    @Test fun `each signer is the certificate's SHA-256 in lower-case hex, as the release key is pinned`() {
        assertEquals(setOf(currentHex), signerDigests(listOf(current), null))
    }

    @Test fun `the certificates that sign the contents now are used, never the older signatures`() {
        assertEquals(setOf(currentHex), signerDigests(contentsSigners = listOf(current), signatures = listOf(rotatedAway)))
    }

    @Test fun `without signingInfo, as on Android 10 and the first Android 13, the signatures are used`() {
        assertEquals(setOf(currentHex), signerDigests(contentsSigners = null, signatures = listOf(current)))
    }

    @Test fun `the pinned key passes the rules, and an unsigned file has no signers, which they refuse`() {
        val self = ApkFacts("com.pocketide", 310, "3.1.0", signerDigests(listOf(current), null))
        assertNull(UpdateRules.problem(self.copy(versionCode = 311), self, currentHex))
        val unsigned = self.copy(versionCode = 311, signers = signerDigests(null, null))
        assertEquals("The downloaded file is not signed.", UpdateRules.problem(unsigned, self, currentHex))
    }
}
