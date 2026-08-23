package app.litesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anchor rule: the newest remaining file of every output folder is never deleted. */
class AnchorRuleTest {

    private fun copy(
        id: Long,
        evidence: Evidence,
        folder: OutFolder,
        captureAt: Long
    ) = DeletePlanner.Copy(id, 10_000_000, evidence, ageDays = 30, folder = folder, captureAt = captureAt)

    @Test
    fun newestPerFolderIsTheAnchor() {
        val copies = listOf(
            copy(1, Evidence.CONFIRMED, OutFolder.PHOTOS, captureAt = 10),
            copy(2, Evidence.CONFIRMED, OutFolder.PHOTOS, captureAt = 20),
            copy(3, Evidence.CONFIRMED, OutFolder.VIDEOS, captureAt = 5),
            copy(4, Evidence.CONFIRMED, OutFolder.VIDEOS, captureAt = 50)
        )
        assertEquals(setOf(2L, 4L), DeletePlanner.anchors(copies))
    }

    @Test
    fun anchorSurvivesEvenWithMaximalPressure() {
        val copies = listOf(
            copy(1, Evidence.CONFIRMED, OutFolder.SINGLE, captureAt = 10),
            copy(2, Evidence.CONFIRMED, OutFolder.SINGLE, captureAt = 20)
        )
        val plan = DeletePlanner.plan(copies, bytesToFree = Long.MAX_VALUE / 2)
        assertTrue(1L in plan.ids)
        assertFalse(2L in plan.ids) // folder never empty
    }

    @Test
    fun tieBreaksOnHighestId() {
        val copies = listOf(
            copy(7, Evidence.CONFIRMED, OutFolder.SINGLE, captureAt = 10),
            copy(8, Evidence.CONFIRMED, OutFolder.SINGLE, captureAt = 10)
        )
        assertEquals(setOf(8L), DeletePlanner.anchors(copies))
    }

    @Test
    fun singleFileFolderIsUntouchable() {
        val copies = listOf(copy(1, Evidence.CONFIRMED, OutFolder.SINGLE, captureAt = 1))
        val plan = DeletePlanner.plan(copies, bytesToFree = Long.MAX_VALUE / 2)
        assertTrue(plan.ids.isEmpty())
    }
}
