package app.entesaver.data

import app.entesaver.core.logic.ItemState
import app.entesaver.data.db.ItemRow
import app.entesaver.data.db.leftFolderAtAfter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a copy left the folder is stamped by the write that takes it out of
 * RELEASED, and by no other: a later write to a long-gone row must not look
 * like a copy leaving now, and a row that never was in the folder has nothing
 * to stamp.
 */
class LeftFolderAtTest {

    private fun row(state: ItemState, leftFolderAt: Long? = null) = ItemRow(
        fingerprint = "fp",
        displayName = "a.jpg",
        sizeBytes = 1,
        dateModified = 1,
        captureAt = 1,
        mimeType = "image/jpeg",
        isVideo = false,
        state = state.name,
        leftFolderAt = leftFolderAt
    )

    @Test
    fun `every way out of RELEASED stamps the leave`() {
        for (to in ItemState.entries.filter { it != ItemState.RELEASED }) {
            assertEquals("$to", 7L, row(ItemState.RELEASED).leftFolderAtAfter(to.name, 7))
            // Released again after an earlier leave: the new leave wins.
            assertEquals("$to", 7L, row(ItemState.RELEASED, leftFolderAt = 3).leftFolderAtAfter(to.name, 7))
        }
    }

    @Test
    fun `a write that does not leave the folder keeps what was recorded`() {
        assertEquals(3L, row(ItemState.RELEASED, leftFolderAt = 3).leftFolderAtAfter(ItemState.RELEASED.name, 7))
        for (from in ItemState.entries.filter { it != ItemState.RELEASED }) {
            for (to in ItemState.entries) {
                assertEquals("$from to $to", 3L, row(from, leftFolderAt = 3).leftFolderAtAfter(to.name, 7))
                assertNull("$from to $to", row(from).leftFolderAtAfter(to.name, 7))
            }
        }
    }
}
