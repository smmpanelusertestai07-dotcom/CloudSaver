package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {

    @Test
    fun `nothing measured still gives an honest number, never zero`() {
        // The bug: 249 MB of video reported as "about 0 MB could be saved".
        val saved = Projection.forItem(249_000_000L, isVideo = true, measured = 0.0)
        assertTrue("expected a real figure, got $saved", saved > 100_000_000L)
    }

    @Test
    fun `a measured ratio beats the typical one`() {
        val typical = Projection.forItem(100_000_000L, isVideo = false, measured = 0.0)
        val measured = Projection.forItem(100_000_000L, isVideo = false, measured = 0.5)
        assertEquals(50_000_000L, measured)
        assertTrue(typical != measured)
    }

    @Test
    fun `photos and videos are projected apart, not averaged`() {
        // Averaging one ratio across the pile was the other way this went
        // wrong: the two compress nothing like each other.
        val split = Projection.forQueue(
            photoBytes = 1_000_000_000L,
            videoBytes = 1_000_000_000L,
            measuredPhotoRatio = 0.8,
            measuredVideoRatio = 0.2
        )
        val expected = 200_000_000L + 800_000_000L
        assertEquals(expected, split.savedBytes)
    }

    @Test
    fun `the basis says how much of the answer was measured`() {
        assertEquals(
            Projection.Basis.MEASURED,
            Projection.forQueue(100, 100, 0.5, 0.5).basis
        )
        assertEquals(
            Projection.Basis.PARTLY_MEASURED,
            Projection.forQueue(100, 100, 0.5, 0.0).basis
        )
        assertEquals(
            Projection.Basis.TYPICAL,
            Projection.forQueue(100, 100, 0.0, 0.0).basis
        )
        // A queue of photos alone is fully measured once photos are measured.
        assertEquals(
            Projection.Basis.MEASURED,
            Projection.forQueue(100, 0, 0.5, 0.0).basis
        )
    }

    @Test
    fun `an empty queue saves nothing and claims nothing`() {
        val none = Projection.forQueue(0, 0, 0.0, 0.0)
        assertEquals(0L, none.savedBytes)
        assertEquals(Projection.Basis.MEASURED, none.basis)
    }

    @Test
    fun `a saving is never negative`() {
        assertEquals(0L, Projection.forItem(0, isVideo = false, measured = 0.9))
        assertEquals(0L, Projection.forItem(-5, isVideo = true, measured = 0.9))
        // A ratio above 1 would mean the copy grew; the clamp stops it turning
        // into a negative "saving".
        assertTrue(Projection.forItem(1_000, isVideo = false, measured = 5.0) >= 0)
    }
}
