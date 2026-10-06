package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNameTest {

    @Test
    fun `ordinary names are fine and land under Pictures`() {
        for (name in listOf("Ente backup", "Saver", "Photos 2026", "My_photos (light)", "फ़ोटो")) {
            assertNull(name, FolderName.problem(name))
        }
        assertEquals("Pictures/Ente backup", FolderName.pathOf("  Ente   backup "))
        assertEquals("Ente backup", FolderName.nameOf("Pictures/Ente backup/"))
    }

    @Test
    fun `names a folder cannot carry are refused with the reason`() {
        assertEquals(FolderName.Problem.EMPTY, FolderName.problem("   "))
        assertEquals(FolderName.Problem.TOO_LONG, FolderName.problem("x".repeat(41)))
        assertEquals(FolderName.Problem.BAD_CHARACTER, FolderName.problem("a/b"))
        assertEquals(FolderName.Problem.BAD_CHARACTER, FolderName.problem("what?"))
        assertEquals(FolderName.Problem.BAD_CHARACTER, FolderName.problem("100%"))
        // Hidden from the gallery is hidden from Ente: nothing would upload.
        assertEquals(FolderName.Problem.HIDDEN, FolderName.problem(".secret"))
        assertEquals(FolderName.Problem.RESERVED, FolderName.problem("Light copies"))
        assertEquals(FolderName.Problem.RESERVED, FolderName.problem("screenshots"))
    }

    @Test
    fun `photos and videos in separate folders cannot share one`() {
        assertEquals(
            FolderName.Problem.SAME_AS_OTHER,
            FolderName.problem("Holiday", other = "Pictures/holiday")
        )
        assertNull(FolderName.problem("Holiday", other = "Pictures/Clips"))
    }

    @Test
    fun `only values the setting could produce are stored`() {
        assertTrue(FolderName.isStorable(""))
        assertTrue(FolderName.isStorable("Pictures/Holiday"))
        // Existing installs stay on the folder they had until they move.
        assertTrue(FolderName.isStorable(Defaults.LEGACY_OUTPUT_DIR_PHOTOS))
        assertFalse(FolderName.isStorable("DCIM/Camera"))
        assertFalse(FolderName.isStorable("Pictures/a/b"))
        assertFalse(FolderName.isStorable("Pictures/.hidden"))
    }
}
