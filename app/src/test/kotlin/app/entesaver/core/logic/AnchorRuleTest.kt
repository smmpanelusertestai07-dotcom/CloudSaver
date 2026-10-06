package app.entesaver.core.logic

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
            copy(1, Evidence.CONFIRMED_EXACT, OutFolder.PHOTOS, captureAt = 10),
            copy(2, Evidence.CONFIRMED_EXACT, OutFolder.PHOTOS, captureAt = 20),
            copy(3, Evidence.CONFIRMED_EXACT, OutFolder.VIDEOS, captureAt = 5),
            copy(4, Evidence.CONFIRMED_EXACT, OutFolder.VIDEOS, captureAt = 50)
        )
        assertEquals(setOf(2L, 4L), DeletePlanner.anchors(copies))
    }

    @Test
    fun anchorSurvivesEvenWithMaximalPressure() {
        val copies = listOf(
            copy(1, Evidence.CONFIRMED_EXACT, OutFolder.SINGLE, captureAt = 10),
            copy(2, Evidence.CONFIRMED_EXACT, OutFolder.SINGLE, captureAt = 20)
        )
        val plan = DeletePlanner.plan(copies, bytesToFree = Long.MAX_VALUE / 2)
        assertTrue(1L in plan.ids)
        assertFalse(2L in plan.ids) // folder never empty
    }

    @Test
    fun tieBreaksOnHighestId() {
        val copies = listOf(
            copy(7, Evidence.CONFIRMED_EXACT, OutFolder.SINGLE, captureAt = 10),
            copy(8, Evidence.CONFIRMED_EXACT, OutFolder.SINGLE, captureAt = 10)
        )
        assertEquals(setOf(8L), DeletePlanner.anchors(copies))
    }

    @Test
    fun afterAFolderChangeTheAnchorSitsInTheNewFolder() {
        // The old folder holds the newest capture, but it is meant to run
        // empty: the new folder keeps its one copy, the old one keeps none.
        val old = DeletePlanner.Copy(
            1, 10_000_000, Evidence.CONFIRMED_EXACT, 30, OutFolder.SINGLE, captureAt = 90,
            place = "pictures/cloudsaver", anchorable = false
        )
        val newer = DeletePlanner.Copy(
            2, 10_000_000, Evidence.CONFIRMED_EXACT, 30, OutFolder.SINGLE, captureAt = 10,
            place = "pictures/entesaver"
        )
        val newest = DeletePlanner.Copy(
            3, 10_000_000, Evidence.CONFIRMED_EXACT, 30, OutFolder.SINGLE, captureAt = 20,
            place = "pictures/entesaver"
        )
        assertEquals(setOf(3L), DeletePlanner.anchors(listOf(old, newer, newest)))
        val plan = DeletePlanner.plan(listOf(old, newer, newest), bytesToFree = Long.MAX_VALUE / 2)
        assertEquals(setOf(1L, 2L), plan.ids.toSet())
    }

    @Test
    fun singleFileFolderIsUntouchable() {
        val copies = listOf(copy(1, Evidence.CONFIRMED_EXACT, OutFolder.SINGLE, captureAt = 1))
        val plan = DeletePlanner.plan(copies, bytesToFree = Long.MAX_VALUE / 2)
        assertTrue(plan.ids.isEmpty())
    }
}
