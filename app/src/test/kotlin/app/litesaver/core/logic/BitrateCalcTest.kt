package app.litesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateCalcTest {

    @Test
    fun bitrateTable1080p30() {
        // 1920x1080x30x0.10 = 6.22 Mbps H.264
        assertEquals(6_220_800, BitrateCalc.targetBps(1920, 1080, 30f, VideoCodec.H264))
        // HEVC 0.065 => ~4.04 Mbps
        assertEquals(4_043_520, BitrateCalc.targetBps(1920, 1080, 30f, VideoCodec.HEVC))
    }

    @Test
    fun bitrateCapAndFloor() {
        // 4K60 H.264 would be ~49.8 Mbps -> capped at 12 Mbps.
        assertEquals(BitrateCalc.CAP_BPS, BitrateCalc.targetBps(3840, 2160, 60f, VideoCodec.H264))
        // Tiny video -> floored at 1 Mbps.
        assertEquals(BitrateCalc.FLOOR_BPS, BitrateCalc.targetBps(320, 240, 15f, VideoCodec.H264))
    }

    @Test
    fun invalidFpsFallsBackTo30() {
        assertEquals(
            BitrateCalc.targetBps(1920, 1080, 30f, VideoCodec.H264),
            BitrateCalc.targetBps(1920, 1080, Float.NaN, VideoCodec.H264)
        )
        assertEquals(
            BitrateCalc.targetBps(1920, 1080, 30f, VideoCodec.H264),
            BitrateCalc.targetBps(1920, 1080, 0f, VideoCodec.H264)
        )
    }

    @Test
    fun outputDimsKeepAspectAndEven() {
        // 4K -> long side 1920.
        assertEquals(1920 to 1080, BitrateCalc.outputDims(3840, 2160, 1920))
        // Portrait swaps too.
        assertEquals(1080 to 1920, BitrateCalc.outputDims(2160, 3840, 1920))
        // Under the limit: unchanged (evened).
        assertEquals(1280 to 720, BitrateCalc.outputDims(1280, 720, 1920))
        // Odd source becomes even.
        val (w, h) = BitrateCalc.outputDims(1281, 721, 1920)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test
    fun copyAsIsRule() {
        val target = BitrateCalc.targetBps(1920, 1080, 30f, VideoCodec.H264)
        // Small and efficient in mp4 -> copy.
        assertTrue(
            BitrateCalc.shouldCopyAsIs(1920, 1920, (target * 1.10).toLong(), target, true)
        )
        // Bitrate too high -> no copy.
        assertFalse(
            BitrateCalc.shouldCopyAsIs(1920, 1920, (target * 1.30).toLong(), target, true)
        )
        // Larger than preset -> no copy.
        assertFalse(BitrateCalc.shouldCopyAsIs(3840, 1920, target.toLong(), target, true))
        // Weird container -> no copy.
        assertFalse(BitrateCalc.shouldCopyAsIs(1920, 1920, target.toLong(), target, false))
        // Unknown source bitrate -> no copy (re-encode instead).
        assertFalse(BitrateCalc.shouldCopyAsIs(1920, 1920, 0, target, true))
    }

    @Test
    fun resultCheckCatchesEncoderMisbehaviour() {
        val target = 6_000_000
        // Good result.
        assertTrue(BitrateCalc.resultAcceptable(100_000_000, 40_000_000, 6_000_000, target, 60_000, 60_500))
        // Output not smaller than source -> reject.
        assertFalse(BitrateCalc.resultAcceptable(100, 100, 6_000_000, target, 60_000, 60_000))
        // Encoder ignored bitrate (>1.3x) -> reject.
        assertFalse(
            BitrateCalc.resultAcceptable(100_000_000, 90_000_000, 8_000_000, target, 60_000, 60_000)
        )
        // Duration drifted more than 2 s -> reject.
        assertFalse(
            BitrateCalc.resultAcceptable(100_000_000, 40_000_000, 6_000_000, target, 60_000, 63_000)
        )
        // Empty output -> reject.
        assertFalse(BitrateCalc.resultAcceptable(100, 0, 0, target, 60_000, 60_000))
    }

    @Test
    fun sampleSizeSelection() {
        // 8000x6000 = 48 MP; budget 16 MP -> sample 2 gives 12 MP.
        assertEquals(2, BitrateCalc.sampleSizeFor(8000, 6000, 16_000_000))
        // Already within budget -> 1.
        assertEquals(1, BitrateCalc.sampleSizeFor(4000, 3000, 16_000_000))
        // Very large -> powers of two only.
        val s = BitrateCalc.sampleSizeFor(16000, 12000, 8_000_000)
        assertTrue(s > 0 && (s and (s - 1)) == 0)
        assertTrue((16000L / s) * (12000L / s) <= 8_000_000L * 4)
    }
}
