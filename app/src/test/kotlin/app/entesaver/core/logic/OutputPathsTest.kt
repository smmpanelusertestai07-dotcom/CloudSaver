package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputPathsTest {

    private val single = OutputLayout(OutputMode.SINGLE)
    private val separate = OutputLayout(OutputMode.SEPARATE)

    @Test
    fun `single layout names one folder`() {
        assertEquals(listOf(Defaults.OUTPUT_DIR), OutputPaths.current(single))
    }

    @Test
    fun `separate layout names both folders`() {
        assertEquals(
            listOf(Defaults.OUTPUT_DIR_PHOTOS, Defaults.OUTPUT_DIR_VIDEOS),
            OutputPaths.current(separate)
        )
    }

    @Test
    fun `the printed path is the path the app actually writes to`() {
        // These strings are picked in a different app by hand. If they drift
        // from what the release step uses, the user backs up the wrong folder.
        for (mode in OutputMode.entries) {
            val layout = OutputLayout(mode, photos = "Pictures/Holiday", videos = "Pictures/Clips")
            for (path in OutputPaths.current(layout)) {
                assertTrue("$path must be one of the release folders",
                    OutputLayout.folders(mode).any { layout.path(it) == path })
            }
        }
    }

    @Test
    fun `a name of the person's own replaces only its own kind`() {
        val own = OutputLayout(OutputMode.SEPARATE, photos = "Pictures/Holiday")
        assertEquals(listOf("Pictures/Holiday", Defaults.OUTPUT_DIR_VIDEOS), own.current)
        assertTrue(own.isCustom(OutFolder.PHOTOS))
        assertTrue(!own.isCustom(OutFolder.VIDEOS))
    }

    @Test
    fun `the other layout is still watched`() {
        assertEquals(separate.current, single.otherMode)
        assertEquals(single.current, separate.otherMode)
    }

    @Test
    fun `joined reads as a sentence`() {
        assertEquals(Defaults.OUTPUT_DIR, OutputPaths.joined(single))
        assertTrue(OutputPaths.joined(separate).contains(" and "))
    }

    @Test
    fun `a MediaStore relative path maps back to its output folder`() {
        // MediaStore hands these back with a trailing slash.
        assertEquals(OutFolder.SINGLE, OutputPaths.folderFor(Defaults.OUTPUT_DIR + "/", single))
        assertEquals(OutFolder.PHOTOS, OutputPaths.folderFor(Defaults.OUTPUT_DIR_PHOTOS + "/", single))
        assertEquals(OutFolder.VIDEOS, OutputPaths.folderFor(Defaults.OUTPUT_DIR_VIDEOS + "/", single))
        assertEquals(OutFolder.SINGLE, OutputPaths.folderFor(Defaults.OUTPUT_DIR, single))
        val own = OutputLayout(OutputMode.SEPARATE, photos = "Pictures/Holiday")
        assertEquals(OutFolder.PHOTOS, OutputPaths.folderFor("Pictures/holiday/", own))
    }

    @Test
    fun `somebody else's folder is not one of ours`() {
        assertNull(OutputPaths.folderFor("DCIM/Camera/", single))
        assertNull(OutputPaths.folderFor("Pictures/", single))
        assertNull(OutputPaths.folderFor(Defaults.OUTPUT_DIR + "Backup/", single))
        assertNull(OutputPaths.folderFor("", single))
    }
}
