package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputPathsTest {

    @Test
    fun `single layout names one folder`() {
        assertEquals(listOf("Pictures/CloudSaver"), OutputPaths.forMode(OutputMode.SINGLE))
    }

    @Test
    fun `separate layout names both folders`() {
        assertEquals(
            listOf("Pictures/CloudSaver/Photos", "Pictures/CloudSaver/Videos"),
            OutputPaths.forMode(OutputMode.SEPARATE)
        )
    }

    @Test
    fun `the printed path is the path the app actually writes to`() {
        // These strings are typed into a different app by hand. If they drift
        // from Defaults, the user backs up the wrong folder.
        for (mode in OutputMode.entries) {
            for (path in OutputPaths.forMode(mode)) {
                assertTrue("$path must be inside the output folder", Defaults.isOutputPath(path))
            }
        }
    }

    @Test
    fun `the other layout is still watched`() {
        assertEquals(
            OutputPaths.forMode(OutputMode.SEPARATE),
            OutputPaths.otherModeFolders(OutputMode.SINGLE)
        )
        assertEquals(
            OutputPaths.forMode(OutputMode.SINGLE),
            OutputPaths.otherModeFolders(OutputMode.SEPARATE)
        )
    }

    @Test
    fun `joined reads as a sentence`() {
        assertEquals("Pictures/CloudSaver", OutputPaths.joined(OutputMode.SINGLE))
        assertTrue(OutputPaths.joined(OutputMode.SEPARATE).contains(" and "))
    }

    @Test
    fun `a MediaStore relative path maps back to its output folder`() {
        // MediaStore hands these back with a trailing slash.
        assertEquals(OutFolder.SINGLE, OutputPaths.folderFor("Pictures/CloudSaver/"))
        assertEquals(OutFolder.PHOTOS, OutputPaths.folderFor("Pictures/CloudSaver/Photos/"))
        assertEquals(OutFolder.VIDEOS, OutputPaths.folderFor("Pictures/CloudSaver/Videos/"))
        assertEquals(OutFolder.SINGLE, OutputPaths.folderFor("Pictures/CloudSaver"))
    }

    @Test
    fun `somebody else's folder is not one of ours`() {
        assertNull(OutputPaths.folderFor("DCIM/Camera/"))
        assertNull(OutputPaths.folderFor("Pictures/"))
        assertNull(OutputPaths.folderFor("Pictures/CloudSaverBackup/"))
        assertNull(OutputPaths.folderFor(""))
    }
}
