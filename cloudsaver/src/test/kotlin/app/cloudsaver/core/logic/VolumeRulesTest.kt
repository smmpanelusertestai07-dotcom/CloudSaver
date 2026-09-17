package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BB2: releases go to the chosen volume only while the probe passes, and
 * fall back to primary - never fail - otherwise.
 *
 * The documentation says non-primary volumes accept MediaStore inserts; many
 * phones disagree. Before the probe existed, the selected SD volume name was
 * handed straight to getContentUri and on those phones every release failed
 * with nothing on screen - the queue simply never drained.
 */
class VolumeRulesTest {

    @Test
    fun `no selection means primary, and is never a fallback`() {
        val d = VolumeRules.releaseVolume(selectedVolume = "", selectedWritable = false)
        assertEquals(VolumeRules.PRIMARY, d.volumeName)
        assertFalse(d.fellBack)
    }

    @Test
    fun `a writable SD card is honoured`() {
        val d = VolumeRules.releaseVolume("1234-abcd", selectedWritable = true)
        assertEquals("1234-abcd", d.volumeName)
        assertFalse(d.fellBack)
    }

    @Test
    fun `an unwritable SD card falls back to primary and says so`() {
        val d = VolumeRules.releaseVolume("1234-abcd", selectedWritable = false)
        assertEquals(VolumeRules.PRIMARY, d.volumeName)
        assertTrue("the caller must know, so it can record the reason", d.fellBack)
    }

    @Test
    fun `the option is offered exactly when the probe passes`() {
        assertTrue(VolumeRules.offerSdForOutput(probePassed = true))
        assertFalse(VolumeRules.offerSdForOutput(probePassed = false))
    }

    @Test
    fun `fat32 refuses files at 4 GB and over`() {
        assertTrue(VolumeRules.fitsOnFat32(VolumeRules.FAT32_MAX_BYTES))
        assertFalse(VolumeRules.fitsOnFat32(VolumeRules.FAT32_MAX_BYTES + 1))
        assertFalse(VolumeRules.fitsOnFat32(4L * 1024 * 1024 * 1024))
    }

    // ---- the wiring, verified in source ------------------------------------

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("$path must exist", f.isFile)
        return f.readText()
    }

    @Test
    fun `the releaser probes, verifies the landing volume, and falls back once`() {
        val releaser = source("src/main/kotlin/app/cloudsaver/media/Releaser.kt")
        assertTrue(
            "release volume must come from the rule plus the probe",
            releaser.contains("VolumeRules.releaseVolume") &&
                releaser.contains("Volumes.probeWritable")
        )
        assertTrue(
            "the insert's landing volume must be verified",
            releaser.contains("MediaStore.getVolumeName(itemUri")
        )
        assertTrue(
            "a refused SD insert must retry once on the primary volume",
            releaser.contains("retrying on internal")
        )
    }

    @Test
    fun `both volume pickers offer only volumes that passed the probe`() {
        val options = source("src/main/kotlin/app/cloudsaver/ui/screens/OptionsScreen.kt")
        assertTrue(
            "Settings must filter to writable volumes and explain the absence",
            options.contains("writableVolumes") &&
                options.contains("volume_sd_unwritable")
        )
        val onboarding = source("src/main/kotlin/app/cloudsaver/ui/screens/OnboardingScreen.kt")
        assertTrue(
            "setup must filter to writable volumes",
            onboarding.contains("writableVolumes")
        )
    }
}
