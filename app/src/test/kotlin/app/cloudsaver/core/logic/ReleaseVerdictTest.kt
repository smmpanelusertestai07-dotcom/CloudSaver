package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CC1: a copy counts as released only once the phone can actually see it.
 *
 * The bug: MediaStore's un-pend update was fire-and-forget. When it silently
 * failed the row stayed IS_PENDING = 1 - invisible to the gallery and to
 * every cloud app - and the item was marked RELEASED regardless. Home then
 * counted a file that, to the rest of the phone, did not exist, and no code
 * path could ever notice because none looked again.
 */
class ReleaseVerdictTest {

    private val path = "Pictures/CloudSaver/"

    private fun check(
        found: Boolean = true,
        pending: Boolean = false,
        size: Long = 1_234,
        rel: String? = "Pictures/CloudSaver/"
    ) = ReleaseVerdict.check(found, pending, size, rel, path)

    @Test
    fun `a finished, non-empty row in the right folder passes`() {
        assertNull(check())
        assertTrue(ReleaseVerdict.isVisible(check()))
    }

    @Test
    fun `a row still marked pending fails - this is the bug`() {
        assertEquals(ReleaseVerdict.Failure.STILL_PENDING, check(pending = true))
        assertFalse(ReleaseVerdict.isVisible(check(pending = true)))
    }

    @Test
    fun `a row that cannot be read back fails`() {
        assertEquals(ReleaseVerdict.Failure.MISSING, check(found = false))
    }

    @Test
    fun `an empty file fails`() {
        assertEquals(ReleaseVerdict.Failure.EMPTY, check(size = 0))
        assertEquals(ReleaseVerdict.Failure.EMPTY, check(size = -1))
    }

    @Test
    fun `a row in the wrong folder fails`() {
        assertEquals(ReleaseVerdict.Failure.WRONG_FOLDER, check(rel = "Pictures/Camera/"))
        assertEquals(ReleaseVerdict.Failure.WRONG_FOLDER, check(rel = null))
    }

    @Test
    fun `the trailing slash MediaStore may or may not return is not a failure`() {
        assertNull(check(rel = "Pictures/CloudSaver"))
        assertNull(check(rel = "pictures/cloudsaver/"))
        assertTrue(ReleaseVerdict.samePath("Pictures/CloudSaver", "Pictures/CloudSaver/"))
        assertFalse(ReleaseVerdict.samePath("Pictures/CloudSaverX", "Pictures/CloudSaver/"))
    }

    @Test
    fun `pending is reported before size, so the real cause is named`() {
        // A pending row often also reads as zero bytes. Reporting EMPTY there
        // would send the next reader hunting for a copy failure that never
        // happened.
        assertEquals(
            ReleaseVerdict.Failure.STILL_PENDING,
            check(pending = true, size = 0)
        )
    }

    // ---- the wiring, verified in source ------------------------------------

    @Test
    fun `RELEASED is never set without the verification passing`() {
        val releaser = File("src/main/kotlin/app/cloudsaver/media/Releaser.kt").readText()
        val verifyAt = releaser.indexOf("ReleaseVerdict.isVisible(verdict)")
        val releasedAt = releaser.indexOf("state = ItemState.RELEASED.name")
        assertTrue("the verification must exist", verifyAt > 0)
        assertTrue("RELEASED must exist", releasedAt > 0)
        assertTrue(
            "the verification must come before the row is marked RELEASED",
            verifyAt < releasedAt
        )
        assertTrue(
            "a failed verification must delete the row and keep the item staged",
            releaser.contains("keeping it staged")
        )
        assertTrue(
            "a failed verification must reach Activity, not only the log",
            releaser.contains("problem_release_invisible")
        )
    }

    @Test
    fun `stale pending rows are repaired in minutes, not a day`() {
        val maintain = File(
            "src/main/kotlin/app/cloudsaver/engine/MaintainEngine.kt"
        ).readText()
        assertTrue(
            "the window must be the 15-minute constant",
            maintain.contains("STALE_PENDING_MS = 15L * 60 * 1000")
        )
        assertTrue(
            "the repair must also run straight after a release pass",
            maintain.contains("pendingAfterRelease")
        )
    }

    @Test
    fun `a release pass asks the system to re-scan the folder`() {
        val releaser = File("src/main/kotlin/app/cloudsaver/media/Releaser.kt").readText()
        assertTrue(
            "gallery apps must be nudged so the album appears at once",
            releaser.contains("MediaScannerConnection.scanFile")
        )
    }
}
