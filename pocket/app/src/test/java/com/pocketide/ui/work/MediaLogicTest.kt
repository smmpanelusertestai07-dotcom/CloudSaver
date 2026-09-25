package com.pocketide.ui.work

import com.pocketide.media.MediaKind
import com.pocketide.ui.screens.project.APK_MIME
import com.pocketide.ui.screens.project.MediaLimits
import com.pocketide.ui.screens.project.boundedSize
import com.pocketide.ui.screens.project.signerFingerprints
import com.pocketide.ui.screens.project.certificateFingerprint
import com.pocketide.ui.screens.project.mediaSummary
import com.pocketide.ui.screens.project.shareMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLogicTest {
    @Test
    fun boundedSizeKeepsAspectAndNeverEnlarges() {
        assertEquals(800 to 600, boundedSize(800, 600, 2048))
        assertEquals(2048 to 1536, boundedSize(4000, 3000, 2048))
        assertEquals(1024 to 2048, boundedSize(3000, 6000, 2048))
        assertEquals(112 to 1, boundedSize(20000, 10, 112))
        assertEquals(1 to 1, boundedSize(0, 100, 112))
    }

    @Test
    fun fingerprintMatchesApksignerFormat() {
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            certificateFingerprint("abc".toByteArray()),
        )
    }

    @Test
    fun onlySafeKindsWithinLimitsAreRendered() {
        assertTrue(MediaLimits.renderable(MediaKind.IMAGE, 1_000))
        assertFalse(MediaLimits.renderable(MediaKind.IMAGE, MediaLimits.IMAGE_BYTES + 1))
        assertTrue(MediaLimits.renderable(MediaKind.HTML, MediaLimits.HTML_BYTES))
        assertFalse(MediaLimits.renderable(MediaKind.HTML, MediaLimits.HTML_BYTES + 1))
        assertFalse(MediaLimits.renderable(MediaKind.TEXT, MediaLimits.TEXT_BYTES + 1))
        assertFalse(MediaLimits.renderable(MediaKind.PDF, MediaLimits.PDF_BYTES + 1))
        assertTrue(MediaLimits.renderable(MediaKind.VIDEO, Long.MAX_VALUE))
        assertFalse(MediaLimits.renderable(MediaKind.OTHER, 1))
    }

    @Test
    fun shareTypes() {
        assertEquals("image/png", shareMime(MediaKind.IMAGE, "shot.PNG"))
        assertEquals("image/jpeg", shareMime(MediaKind.IMAGE, "a.jpeg"))
        assertEquals("video/webm", shareMime(MediaKind.VIDEO, "run.webm"))
        assertEquals("video/mp4", shareMime(MediaKind.VIDEO, "run.mp4"))
        assertEquals(APK_MIME, shareMime(MediaKind.APK, "app-release.apk"))
        assertEquals("application/octet-stream", shareMime(MediaKind.OTHER, "blob"))
    }

    @Test
    fun signersFallBackToTheLegacyListWhenSigningInfoIsMissing() {
        val cert = "abc".toByteArray()
        val other = "xyz".toByteArray()
        val fingerprint = certificateFingerprint(cert)
        assertEquals(listOf(fingerprint), signerFingerprints(listOf(cert), listOf(other)))
        // API 29 and the first Android 13 release leave signingInfo empty.
        assertEquals(listOf(fingerprint), signerFingerprints(null, listOf(cert)))
        assertEquals(listOf(fingerprint), signerFingerprints(emptyList(), listOf(cert, cert)))
        assertEquals(emptyList<String>(), signerFingerprints(null, null))
        assertEquals("an empty certificate is no signer", emptyList<String>(), signerFingerprints(listOf(ByteArray(0)), null))
    }

    @Test
    fun summary() {
        assertEquals("1 file · 1.0 KB", mediaSummary(1, 1024))
        assertEquals("3 files · 12 MB", mediaSummary(3, 12L * 1024 * 1024))
    }
}
