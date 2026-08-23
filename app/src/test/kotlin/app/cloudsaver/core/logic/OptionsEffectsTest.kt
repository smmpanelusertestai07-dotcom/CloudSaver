package app.cloudsaver.core.logic

import app.cloudsaver.data.prefs.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(SpeedMode.SMART, o.speed)
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
        assertEquals("Pictures/CloudSaver", Defaults.outFolderRelPath(OutFolder.SINGLE))
        assertEquals("Pictures/CloudSaver/Photos", Defaults.outFolderRelPath(OutFolder.PHOTOS))
        assertEquals("Pictures/CloudSaver/Videos", Defaults.outFolderRelPath(OutFolder.VIDEOS))
        for (folder in OutFolder.entries) {
            assertTrue(!Defaults.outFolderRelPath(folder).startsWith("DCIM"))
        }
    }

    @Test
    fun onlyTheRealOutputFolderCountsAsOurs() {
        for (folder in OutFolder.entries) {
            val path = Defaults.outFolderRelPath(folder)
            assertTrue(Defaults.isOutputPath(path))
            assertTrue(Defaults.isOutputPath("$path/"))
        }
        assertTrue(Defaults.isOutputPath("Pictures/CloudSaver/.cloudsaver/"))

        // A user folder that merely starts with the same letters is not ours.
        assertFalse(Defaults.isOutputPath("Pictures/CloudSaverBackup/"))
        assertFalse(Defaults.isOutputPath("Pictures/CloudSaver2/"))
        assertFalse(Defaults.isOutputPath("Pictures/CSTestShots/"))
        assertFalse(Defaults.isOutputPath("DCIM/Camera/"))
        assertFalse(Defaults.isOutputPath(null))
        assertFalse(Defaults.isOutputPath(""))

        // The SQL pattern must draw the same line.
        assertEquals("Pictures/CloudSaver/%", Defaults.OUTPUT_DIR_LIKE)
        assertTrue(sqlLike("Pictures/CloudSaver/", Defaults.OUTPUT_DIR_LIKE))
        assertTrue(sqlLike("Pictures/CloudSaver/Photos/", Defaults.OUTPUT_DIR_LIKE))
        assertFalse(sqlLike("Pictures/CloudSaverBackup/", Defaults.OUTPUT_DIR_LIKE))
    }

    /** Minimal stand-in for SQLite LIKE: only '%' is used in our patterns. */
    private fun sqlLike(value: String, pattern: String): Boolean =
        Regex(
            pattern.split("%").joinToString(".*") { Regex.escape(it) },
            RegexOption.IGNORE_CASE
        ).matches(value)
}
