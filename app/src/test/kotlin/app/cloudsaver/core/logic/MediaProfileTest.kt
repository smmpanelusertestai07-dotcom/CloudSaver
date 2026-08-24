package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProfileTest {

    @Test
    fun `a sample must be big enough and look like the gallery`() {
        assertFalse(MediaProfile.isMeasured(5, 4_000_000, 4_000_000))
        assertFalse("screenshots cannot speak for photographs",
            MediaProfile.isMeasured(100, 40_000, 4_000_000))
        assertTrue(MediaProfile.isMeasured(100, 3_500_000, 4_000_000))
    }

    @Test
    fun `with no gallery median, size is not held against the sample`() {
        assertTrue(MediaProfile.isMeasured(50, 40_000, 0))
    }

    @Test
    fun `error is the mean of the absolute relative misses`() {
        // 10 predicted / 10 actual = 0%, 15 / 10 = 50%. Mean 25%.
        val error = MediaProfile.meanAbsolutePercentageError(
            predicted = listOf(10L, 15L), actual = listOf(10L, 10L)
        )
        assertEquals(25.0, error, 0.001)
    }

    @Test
    fun `pairs with nothing to compare are ignored, not counted as perfect`() {
        assertEquals(
            0.0,
            MediaProfile.meanAbsolutePercentageError(listOf(0L, 0L), listOf(0L, 0L)),
            0.001
        )
        assertEquals(
            0.0,
            MediaProfile.meanAbsolutePercentageError(listOf(10L, 0L), listOf(10L, 0L)),
            0.001
        )
    }

    @Test
    fun `a reliable estimate stays a single number`() {
        val range = MediaProfile.estimateRange(10.0, errorPercent = 9.0)
        assertFalse(range.isRange)
        assertEquals(10.0, range.single, 0.001)
    }

    @Test
    fun `an unreliable estimate becomes a range`() {
        // Past a quarter out, a single figure is a claim the app cannot make.
        val range = MediaProfile.estimateRange(10.0, errorPercent = 40.0)
        assertTrue(range.isRange)
        assertTrue(range.low < 10.0 && range.high > 10.0)
    }

    @Test
    fun `the range threshold and the needsRange flag agree`() {
        val bad = MediaProfile.TypeProfile(errorPercent = MediaProfile.RANGE_THRESHOLD_PERCENT + 1)
        val good = MediaProfile.TypeProfile(errorPercent = MediaProfile.RANGE_THRESHOLD_PERCENT)
        assertTrue(bad.needsRange)
        assertFalse(good.needsRange)
    }

    @Test
    fun `HEVC is asked for fewer bits, so the same footage lands smaller`() {
        val videos = MediaProfile.TypeProfile(outMbPerMin = 100.0)
        val hevc = MediaProfile.videoMbPerMinFor(videos, VideoCodec.H264, VideoCodec.HEVC)
        assertTrue("HEVC must be the smaller figure", hevc < 100.0)
        assertEquals(
            100.0,
            MediaProfile.videoMbPerMinFor(videos, VideoCodec.H264, VideoCodec.H264),
            0.001
        )
    }

    @Test
    fun `months until full needs a pace to answer at all`() {
        assertEquals(10.0, MediaProfile.monthsUntilFull(100, 10), 0.001)
        assertTrue(MediaProfile.monthsUntilFull(100, 0) < 0)
    }

    @Test
    fun `shrink percent reads as a saving, not as a ratio`() {
        assertEquals(70, MediaProfile.TypeProfile(ratio = 0.3).shrinkPercent)
        assertEquals(0, MediaProfile.TypeProfile(ratio = 1.0).shrinkPercent)
    }

    @Test
    fun `an unmeasured type has no shrink figure at all`() {
        // A ratio of 0.0 means "nothing measured yet", and the arithmetic
        // turned that into (1 - 0) * 100, so a fresh install announced
        // "Photos come out about 100% smaller" on the calculator.
        assertNull(MediaProfile.TypeProfile().shrinkPercent)
        assertNull(MediaProfile.TypeProfile(ratio = 0.0, count = 3_471).shrinkPercent)
    }

    @Test
    fun `the photo share is this phone's real split`() {
        val profile = MediaProfile.Profile(
            photos = MediaProfile.TypeProfile(totalBytes = 30),
            videos = MediaProfile.TypeProfile(totalBytes = 70)
        )
        assertEquals(0.3, profile.photoShare, 0.001)
        assertEquals("an empty gallery is not 100% photos", 0.5, MediaProfile.Profile().photoShare, 0.001)
    }
}
