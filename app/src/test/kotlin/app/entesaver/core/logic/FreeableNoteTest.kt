package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Space can be freed" is said when it is worth a tap, and not again until it has doubled. */
class FreeableNoteTest {

    private val gb = 1_000_000_000L

    @Test
    fun `nothing is said for a gigabyte or less`() {
        assertFalse(FreeableNote.due(0, 0))
        assertFalse(FreeableNote.due(gb, 0))
        assertTrue(FreeableNote.due(gb + 1, 0))
    }

    @Test
    fun `said again only once it has doubled`() {
        val said = 3 * gb / 2
        assertFalse(FreeableNote.due(2 * gb, said))
        assertFalse(FreeableNote.due(3 * gb - 1, said))
        assertTrue(FreeableNote.due(3 * gb, said))
    }

    @Test
    fun `freeing it starts the count again`() {
        assertEquals(0L, FreeableNote.remembered(gb / 2, 5 * gb))
        assertEquals(5 * gb, FreeableNote.remembered(2 * gb, 5 * gb))
        assertTrue(FreeableNote.due(gb + 1, FreeableNote.remembered(gb + 1, 0)))
    }

    @Test
    fun `the note and the Storage tab mark the same gigabyte`() {
        assertEquals(TabBadges.RECLAIMABLE_DOT_BYTES, FreeableNote.THRESHOLD_BYTES)
    }
}
