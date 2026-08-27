package app.cloudsaver.util

import app.cloudsaver.util.Permissions.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BB1: the access-level decision, and the places that must obey it.
 *
 * Under Android 14's "Select photos", MediaStore answers every query as if
 * the handful the user picked were the whole gallery. The app used to treat
 * that grant as usable access and present the handful as fact. The decision
 * is now a three-way level, and everything that scans, counts or projects
 * asks for the level rather than the old boolean.
 */
class MediaAccessTest {

    private fun level(
        sdk: Int,
        images: Boolean = false,
        video: Boolean = false,
        selected: Boolean = false,
        legacy: Boolean = false
    ) = Permissions.mediaAccessFor(sdk, images, video, selected, legacy)

    @Test
    fun `api 29 knows only full or none`() {
        assertEquals(MediaAccess.FULL, level(sdk = 29, legacy = true))
        assertEquals(MediaAccess.NONE, level(sdk = 29, legacy = false))
        // The user-selected grant does not exist there; even if some OEM
        // reported it, it must not count.
        assertEquals(MediaAccess.NONE, level(sdk = 29, selected = true))
    }

    @Test
    fun `api 33 wants both halves of the gallery before it says full`() {
        assertEquals(MediaAccess.FULL, level(sdk = 33, images = true, video = true))
        // Android 13 asks for photos and videos separately, so a phone can
        // easily end up with one of the two. Half a gallery is not a full
        // view of it: the missing half is invisible to every query, and
        // calling that FULL is how the counts came to leave videos out.
        assertEquals(MediaAccess.PARTIAL, level(sdk = 33, images = true))
        assertEquals(MediaAccess.PARTIAL, level(sdk = 33, video = true))
        assertEquals(MediaAccess.NONE, level(sdk = 33))
        // READ_MEDIA_VISUAL_USER_SELECTED is API 34; on 33 it means nothing.
        assertEquals(MediaAccess.NONE, level(sdk = 33, selected = true))
        // The legacy permission is ignored on 33 - it grants nothing there.
        assertEquals(MediaAccess.NONE, level(sdk = 33, legacy = true))
    }

    @Test
    fun `api 34 distinguishes full from the user-selected handful`() {
        assertEquals(MediaAccess.FULL, level(sdk = 34, images = true, video = true))
        assertEquals(MediaAccess.PARTIAL, level(sdk = 34, selected = true))
        // Photos without videos hides as much of the phone as the handful
        // does, so it reads the same way.
        assertEquals(MediaAccess.PARTIAL, level(sdk = 34, images = true))
        assertEquals(MediaAccess.PARTIAL, level(sdk = 34, video = true))
        assertEquals(MediaAccess.NONE, level(sdk = 34))
        // Full always wins when both are granted, which Android does report
        // during the "keep access" flow.
        assertEquals(
            MediaAccess.FULL,
            level(sdk = 34, images = true, video = true, selected = true)
        )
    }

    // ---- the callers that must ask for the level, verified in source -------

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("$path must exist", file.isFile)
        return file.readText()
    }

    @Test
    fun `scanning refuses to run under partial access`() {
        val scanner = source("src/main/kotlin/app/cloudsaver/media/MediaScanner.kt")
        assertTrue(
            "MediaScanner.scan must check the access level itself, so no " +
                "caller can forget",
            scanner.contains("Permissions.mediaAccess(context) != Permissions.MediaAccess.FULL")
        )
        val worker = source("src/main/kotlin/app/cloudsaver/work/CompressWorker.kt")
        assertTrue(
            "the worker must stop under PARTIAL, not only under NONE",
            worker.contains("Permissions.mediaAccess(app) != Permissions.MediaAccess.FULL")
        )
    }

    @Test
    fun `home shows the card and files shows the chip`() {
        val home = source("src/main/kotlin/app/cloudsaver/ui/screens/HomeScreen.kt")
        assertTrue(
            "Home must surface the partial-access card",
            home.contains("partial_title") && home.contains("MediaAccess.PARTIAL")
        )
        val files = source("src/main/kotlin/app/cloudsaver/ui/screens/FilesScreen.kt")
        assertTrue(
            "Files must surface the partial-access chip",
            files.contains("partial_chip") && files.contains("MediaAccess.PARTIAL")
        )
    }

    @Test
    fun `storage and the calculator wait instead of showing numbers`() {
        for (screen in listOf("StorageScreen", "CalculatorScreen")) {
            val text = source("src/main/kotlin/app/cloudsaver/ui/screens/$screen.kt")
            assertTrue(
                "$screen must render the waiting text under PARTIAL, never a total",
                text.contains("partial_waiting") && text.contains("MediaAccess.PARTIAL")
            )
        }
    }

    @Test
    fun `snapshots record the access level they were taken under`() {
        val codec = source("src/main/kotlin/app/cloudsaver/core/logic/SnapshotCodec.kt")
        assertTrue(
            "the snapshot payload must carry mediaAccess",
            codec.contains("\"mediaAccess\"")
        )
    }
}
