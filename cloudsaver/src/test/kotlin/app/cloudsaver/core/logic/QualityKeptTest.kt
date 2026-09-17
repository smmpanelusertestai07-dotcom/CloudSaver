package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityKeptTest {

    @Test
    fun `a file already under the cap keeps every pixel`() {
        // The app re-saves it; it never shrinks it, and never upscales it.
        assertEquals(100, QualityKept.photoDetailKeptPercent(12.0, Preset.STORAGE_SAVER))
        assertEquals(100, QualityKept.photoDetailKeptPercent(16.0, Preset.STORAGE_SAVER))
        assertEquals(100, QualityKept.videoDetailKeptPercent(1920, Preset.STORAGE_SAVER))
        assertEquals(100, QualityKept.videoDetailKeptPercent(1280, Preset.STORAGE_SAVER))
    }

    @Test
    fun `a photo above the cap keeps the cap's share of its pixels`() {
        // 48 MP capped at 16 MP keeps a third.
        assertEquals(33, QualityKept.photoDetailKeptPercent(48.0, Preset.STORAGE_SAVER))
        // 24 MP capped at 8 MP on the most aggressive preset keeps a third too.
        assertEquals(33, QualityKept.photoDetailKeptPercent(24.0, Preset.MAX_SAVER))
    }

    @Test
    fun `video detail falls with the square of the long side`() {
        // 4K at 3840 capped to 1920 is half the width, so a quarter of the
        // pixels. This is why video saves so much more than photos do.
        assertEquals(25, QualityKept.videoDetailKeptPercent(3840, Preset.STORAGE_SAVER))
        assertEquals(25, QualityKept.videoDetailKeptPercent(2560, Preset.MAX_SAVER))
    }

    @Test
    fun `the figures come from the real encoder settings`() {
        // If a preset is retuned, these move with it rather than going stale.
        assertEquals(Presets.spec(Preset.STORAGE_SAVER).jpegQuality, QualityKept.jpegQuality(Preset.STORAGE_SAVER))
        assertEquals(Presets.spec(Preset.BALANCED).photoMaxMp, QualityKept.photoCapMp(Preset.BALANCED))
        assertEquals(Presets.spec(Preset.MAX_SAVER).videoLongSide, QualityKept.videoCapLongSide(Preset.MAX_SAVER))
    }

    @Test
    fun `the cap still beats the screen on every preset that claims to`() {
        // The reassurance the app offers has to be true before it is offered.
        assertTrue(QualityKept.screenHeadroom(Preset.STORAGE_SAVER) > 5.0)
        assertTrue(QualityKept.screenHeadroom(Preset.BALANCED) > 5.0)
        assertTrue(QualityKept.screenHeadroom(Preset.MAX_SAVER) > 2.0)
    }

    @Test
    fun `size kept is the complement of how much smaller`() {
        assertEquals(70, QualityKept.sizeKeptPercent(1000, 700))
        assertEquals(25, QualityKept.sizeKeptPercent(1000, 250))
        assertEquals(100, QualityKept.sizeKeptPercent(1000, 1000))
    }

    @Test
    fun `unknown inputs claim nothing rather than claiming loss`() {
        assertEquals(100, QualityKept.detailKeptPercent(0, 1000))
        assertEquals(100, QualityKept.detailKeptPercent(1000, 0))
        assertEquals(100, QualityKept.sizeKeptPercent(0, 0))
        // A copy that somehow grew is still "kept everything", not over 100.
        assertEquals(100, QualityKept.sizeKeptPercent(100, 500))
    }

    @Test
    fun `measured detail kept is null when pixels were never recorded`() {
        assertNull(QualityKept.measuredDetailKeptPercent(0, 0))
        assertNull(QualityKept.measuredDetailKeptPercent(12_000_000, 0))
        assertNull(QualityKept.measuredDetailKeptPercent(0, 8_000_000))
    }

    @Test
    fun `measured detail kept is the pixel ratio`() {
        // 48 MP down to 16 MP keeps a third of the pixels.
        assertEquals(33, QualityKept.measuredDetailKeptPercent(48_000_000, 16_000_000))
        // A file already under the cap is re-saved at its own size.
        assertEquals(100, QualityKept.measuredDetailKeptPercent(9_000_000, 9_000_000))
    }

    @Test
    fun `measured detail kept never reports zero for a real encode`() {
        // A pathological ratio still encoded something, so 1% is the floor:
        // "0% of your detail" would be a lie about a file that exists.
        assertEquals(1, QualityKept.measuredDetailKeptPercent(100_000_000_000L, 1_000))
    }
}
