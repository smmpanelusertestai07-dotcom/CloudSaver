package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Z4: a copy that came back from the cloud is recognised anywhere, never
 * optimised again, and never granted upload proof by its name alone.
 */
class ReturnedCopyTest {

    @Test
    fun `the identifier is read out of an output-pattern name`() {
        assertEquals(
            "a1b2c3d4e5f60718",
            ScanSources.pipelineIdOf("IMG_0001__a1b2c3d4e5f60718.jpg")
        )
        // MediaStore's own de-duplication suffix survives the match.
        assertEquals(
            "a1b2c3d4e5f60718",
            ScanSources.pipelineIdOf("IMG_0001__a1b2c3d4e5f60718 (1).jpg")
        )
        assertNull(ScanSources.pipelineIdOf("IMG_0001.jpg"))
        assertNull("15 hex digits is not the pattern", ScanSources.pipelineIdOf("x__a1b2c3d4e5f6071.jpg"))
    }

    @Test
    fun `cloud app media directories are off limits`() {
        val packages = listOf("io.ente.photos", "mega.privacy.android.app")
        assertTrue(
            ScanSources.isCloudLocalPath("Android/media/io.ente.photos/photos/", packages)
        )
        assertTrue(
            "case must not matter - paths come back in either",
            ScanSources.isCloudLocalPath("android/media/MEGA.privacy.android.app/x/", packages)
        )
        assertFalse(ScanSources.isCloudLocalPath("DCIM/Camera/", packages))
        assertFalse(
            "another app's media dir is not a cloud dir",
            ScanSources.isCloudLocalPath("Android/media/com.whatsapp/", packages)
        )
        assertFalse(ScanSources.isCloudLocalPath(null, packages))
    }

    @Test
    fun `a filename match never grants upload proof`() {
        // The rule Z4.1 explicitly keeps: BB4.6.
        assertEquals(Evidence.NONE, ReattachRules.evidence)
    }

    @Test
    fun `the scanner handles returned copies per file, not only per folder`() {
        val scanner = File("src/main/kotlin/app/cloudsaver/media/MediaScanner.kt").readText()
        assertTrue(
            "each found file must be checked by name before it can be queued",
            scanner.contains("ScanSources.isPipelineName(f.displayName)")
        )
        assertTrue(
            "a returned copy is recorded, never queued",
            scanner.contains("recordReturnedCopy")
        )
        assertTrue(
            "returned copies carry their own skip reason so Files can say so",
            scanner.contains("SKIP_RETURNED_COPY")
        )
        assertTrue(
            "cloud apps' own media folders are excluded from the sweep",
            scanner.contains("isCloudLocalPath")
        )
    }
}
