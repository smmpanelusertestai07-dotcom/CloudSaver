package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folders a maintenance pass looks in.
 *
 * A copy missing from the listing is read as "Ente took it", and that is what
 * eventually offers an original for deletion. A copy still waiting in a
 * folder the listing no longer covers would therefore be counted as uploaded
 * without ever having been sent. Every rule here exists to stop that.
 */
class OutputRootsTest {

    private val layout = OutputLayout(OutputMode.SINGLE)

    @Test
    fun `the old folder is watched even when nothing says so`() {
        val watched = OutputRoots.watched(layout, emptyList(), emptyList())
        assertTrue(watched.any { OutputRoots.same(it, Defaults.LEGACY_OUTPUT_DIR) })
        assertTrue(watched.any { OutputRoots.same(it, Defaults.OUTPUT_DIR) })
    }

    @Test
    fun `a folder moved away from and a folder a row names are both watched`() {
        val watched = OutputRoots.watched(
            OutputLayout(OutputMode.SINGLE, single = "Pictures/New one"),
            past = listOf("Pictures/Old one/"),
            releasedInto = listOf("Pictures/Somewhere else")
        )
        for (expected in listOf("Pictures/New one", "Pictures/Old one", "Pictures/Somewhere else")) {
            assertTrue("$expected must be listed", watched.any { OutputRoots.same(it, expected) })
        }
    }

    @Test
    fun `a folder inside another is listed by the outer one`() {
        val roots = OutputRoots.outermost(
            listOf("Pictures/EnteSaver", "Pictures/EnteSaver/Photos", "pictures/entesaver/", "Pictures/Mine")
        )
        assertEquals(listOf("Pictures/EnteSaver", "Pictures/Mine"), roots)
    }

    @Test
    fun `inside means the folder or below it, never a longer name`() {
        assertTrue(OutputRoots.isUnder("Pictures/EnteSaver/", "Pictures/EnteSaver"))
        assertTrue(OutputRoots.isUnder("Pictures/EnteSaver/Photos/", "Pictures/EnteSaver"))
        assertTrue(OutputRoots.isUnder("pictures/entesaver/photos", "Pictures/EnteSaver"))
        assertFalse(OutputRoots.isUnder("Pictures/EnteSaverOld/", "Pictures/EnteSaver"))
        assertFalse(OutputRoots.isUnder(null, "Pictures/EnteSaver"))
        assertFalse(OutputRoots.isUnder("", "Pictures/EnteSaver"))
    }

    @Test
    fun `the SQL takes a folder's name literally`() {
        val (clause, args) = OutputRoots.likeClause("relative_path", listOf("Pictures/my_photos 100%"))
        assertEquals("(relative_path LIKE ? ESCAPE '\\')", clause)
        assertEquals(listOf("Pictures/my\\_photos 100\\%/%"), args.toList())
        val (two, twoArgs) = OutputRoots.likeClause("p", listOf("Pictures/A", "Pictures/B"))
        assertEquals("(p LIKE ? ESCAPE '\\' OR p LIKE ? ESCAPE '\\')", two)
        assertEquals(2, twoArgs.size)
    }

    @Test
    fun `a folder of the person's own is the scanner's to leave alone`() {
        OutputRoots.remember(OutputLayout(OutputMode.SEPARATE, photos = "Pictures/Holiday"), listOf("Pictures/Before"))
        try {
            assertTrue(Defaults.isOutputPath("Pictures/Holiday/"))
            assertTrue(Defaults.isOutputPath("Pictures/Before/"))
            assertTrue(Defaults.isOutputPath(Defaults.LEGACY_OUTPUT_DIR + "/"))
            assertFalse(Defaults.isOutputPath("DCIM/Camera/"))
            assertTrue(Defaults.isAppOwnedPath(Defaults.KEPT_DIR + "/"))
        } finally {
            OutputRoots.remember(OutputLayout(), emptyList())
        }
    }

    @Test
    fun `copies in a folder still in use are not counted as the old folder's`() {
        val perFolder = mapOf(
            "Pictures/EnteSaver/" to 4,
            "Pictures/EnteSaver/Photos/" to 7,
            "Pictures/Other/" to 2
        )
        val inUse = listOf("Pictures/EnteSaver/Photos", "Pictures/EnteSaver/Videos")
        assertEquals(4, OutputRoots.waitingIn("Pictures/EnteSaver", perFolder, inUse))
        assertEquals(0, OutputRoots.waitingIn("Pictures/CloudSaver", perFolder, inUse))
        assertEquals(11, OutputRoots.waitingIn("Pictures/EnteSaver", perFolder, emptyList()))
    }
}
