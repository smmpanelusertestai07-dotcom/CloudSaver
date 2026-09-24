package com.pocketide.ui.work

import com.pocketide.media.MediaKind
import com.pocketide.ui.screens.project.APK_MIME
import com.pocketide.ui.screens.project.MediaLimits
import com.pocketide.ui.screens.project.MAX_ADDED_BYTES
import com.pocketide.ui.screens.project.boundedSize
import com.pocketide.ui.screens.project.copyLimited
import com.pocketide.ui.screens.project.safeFileName
import com.pocketide.ui.screens.project.signerFingerprints
import com.pocketide.ui.screens.project.certificateFingerprint
import com.pocketide.ui.screens.project.mediaSummary
import com.pocketide.ui.screens.project.shareMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

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
    fun pickedNamesBecomePlainFileNames() {
        assertEquals("report.pdf", safeFileName("report.pdf"))
        assertEquals("passwd", safeFileName("../../etc/passwd"))
        assertEquals("evil.txt", safeFileName("C:\\Windows\\evil.txt"))
        assertEquals("bashrc", safeFileName(".bashrc"))
        assertEquals("file", safeFileName(".."))
        assertEquals("file", safeFileName(null))
        assertEquals("file", safeFileName("   "))
        assertEquals("file", safeFileName("\u0000\u0001"))
        // A right-to-left mark would make "photo\u202Egpj.exe" read as "photoexe.jpg".
        assertEquals("photo_gpj.exe", safeFileName("photo\u202Egpj.exe"))
        assertEquals("a_b_c.txt", safeFileName("a:b*c.txt"))
        val long = safeFileName("x".repeat(300) + ".webm")
        assertEquals(100, long.length)
        assertTrue(long.endsWith(".webm"))
        assertEquals(100, safeFileName("y".repeat(300)).length)
        assertEquals("héllo wörld.png", safeFileName("héllo wörld.png"))
    }

    @Test
    fun copyingStopsAtTheLimit() {
        val out = ByteArrayOutputStream()
        assertEquals(10L, copyLimited(ByteArrayInputStream(ByteArray(10) { 7 }), out, limit = 10))
        assertEquals(10, out.size())
        assertEquals(0L, copyLimited(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), limit = 0))
        val refused = assertThrows(IllegalArgumentException::class.java) {
            copyLimited(ByteArrayInputStream(ByteArray(200_000)), ByteArrayOutputStream(), limit = 100_000)
        }
        assertTrue(refused.message.orEmpty().contains("was not added"))
        // A stream that fails mid-way (the provider went away) fails the add; nothing half-made is kept by the caller.
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("gone")
        }
        assertThrows(IOException::class.java) { copyLimited(broken, ByteArrayOutputStream(), limit = 10) }
        assertEquals(100L * 1024 * 1024, MAX_ADDED_BYTES)
    }

    @Test
    fun summary() {
        assertEquals("1 file · 1.0 KB", mediaSummary(1, 1024))
        assertEquals("3 files · 12 MB", mediaSummary(3, 12L * 1024 * 1024))
    }
}
