package app.litesaver.core.logic

import app.litesaver.data.prefs.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Options defaults and derived values (section 6 + preset table). */
class OptionsEffectsTest {

    @Test
    fun presetTableMatchesSpec() {
        assertEquals(PresetSpec(1920, 16, 82), Presets.spec(Preset.STORAGE_SAVER))
        assertEquals(PresetSpec(2560, 24, 85), Presets.spec(Preset.BALANCED))
        assertEquals(PresetSpec(1280, 8, 80), Presets.spec(Preset.MAX_SAVER))
        assertEquals(16_000_000L, Presets.photoMaxPixels(Preset.STORAGE_SAVER))
    }

    @Test
    fun defaultsMatchSpec() {
        val o = Options()
        assertEquals(BackupScope.ALL, o.scope)
        assertEquals(OutputMode.SINGLE, o.outputMode)
        assertEquals(Speed.CHARGING_ONLY, o.speed)
        assertEquals(250, o.dailyCapMb)
        assertEquals(1536, o.minFreeMb)
        assertEquals(1536, o.maxExtraMb)
        assertEquals(Preset.STORAGE_SAVER, o.preset)
        assertEquals(VideoCodec.H264, o.codec)
        assertEquals(ThemeMode.SYSTEM, o.theme)
        assertTrue(o.dynamicColor)
        assertTrue(o.warningsNotif)
        assertEquals("ente", o.cloudSingle)
        assertEquals("", o.storageVolume)
        // Dangerous things default OFF.
        assertTrue(!o.showFreeUp)
        assertTrue(!o.freeUpAllowVerified30)
        assertTrue(!o.reprocessUnknown)
        assertTrue(!o.pauseAll)
        assertTrue(!o.appLock)
    }

    @Test
    fun byteConversions() {
        val o = Options(dailyCapMb = 250, minFreeMb = 1536, maxExtraMb = 3072)
        assertEquals(250L * 1024 * 1024, o.dailyCapBytes)
        assertEquals(1536L * 1024 * 1024, o.minFreeBytes)
        assertEquals(3072L * 1024 * 1024, o.maxExtraBytes)
    }

    @Test
    fun unlimitedSentinels() {
        val o = Options(dailyCapMb = -1, maxExtraMb = -1)
        assertEquals(-1L, o.dailyCapBytes)
        assertEquals(-1L, o.maxExtraBytes)
    }

    @Test
    fun choiceListsMatchSpec() {
        assertEquals(listOf(250, 500, 1024, 2048, -1), Defaults.DAILY_CAP_CHOICES_MB)
        assertEquals(listOf(1536, 3072, 5120, -1), Defaults.MAX_EXTRA_CHOICES_MB)
        assertEquals(listOf(1536, 3072, 5120), Defaults.MIN_FREE_CHOICES_MB)
        assertEquals(5, Defaults.KEEP_MIN_DAYS)
        assertEquals(10, Defaults.AGED_DAYS)
        assertEquals(40, Defaults.MAX_RUN_MIN)
        assertEquals(19_800_000L, Defaults.FGS_BUDGET_MS) // 5.5 h
    }

    @Test
    fun outputFoldersAreUnderPicturesNeverDcim() {
        assertEquals("Pictures/LiteSaver", Defaults.outFolderRelPath(OutFolder.SINGLE))
        assertEquals("Pictures/LiteSaver/Photos", Defaults.outFolderRelPath(OutFolder.PHOTOS))
        assertEquals("Pictures/LiteSaver/Videos", Defaults.outFolderRelPath(OutFolder.VIDEOS))
        for (folder in OutFolder.entries) {
            assertTrue(!Defaults.outFolderRelPath(folder).startsWith("DCIM"))
        }
    }
}
