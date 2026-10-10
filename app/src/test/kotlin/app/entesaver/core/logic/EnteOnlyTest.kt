package app.entesaver.core.logic

import app.entesaver.data.EarlierInstall
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ente Saver works with Ente Photos and nothing else - and says so to anyone
 * who used another cloud app before, once, without touching their files.
 */
class EnteOnlyTest {

    private val main = File("src/main/kotlin/app/entesaver")

    @Test
    fun `the phone is asked about Ente's three builds and the earlier Ente Saver, nothing else`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        val packages = Regex("""android:name="([^"]+)"""").findAll(queries).map { it.groupValues[1] }.toList()
        assertEquals(KnownClouds.ENTE + EarlierInstall.PACKAGE, packages)
        assertFalse("no picker of other apps is left", File(main, "data/CloudApps.kt").exists())
    }

    @Test
    fun `other clouds' own folders are still never scanned`() {
        assertTrue(
            ScanSources.isCloudLocalPath(
                "Android/media/mega.privacy.android.app/MEGA Downloads/", KnownClouds.ALL_PACKAGES
            )
        )
        assertTrue(
            ScanSources.isCloudLocalPath("Android/media/io.ente.photos/", KnownClouds.ALL_PACKAGES)
        )
    }

    @Test
    fun `a copy sent through another app before 11 is still named where it went`() {
        assertEquals("Ente Photos", KnownClouds.labelOf("io.ente.photos.fdroid"))
        assertEquals("MEGA", KnownClouds.labelOf("mega.privacy.android.app"))
        assertEquals("com.example.unknown", KnownClouds.labelOf("com.example.unknown"))
    }

    @Test
    fun `the one-time note is for people who chose another app`() {
        assertNull("Ente all along: nothing to say", KnownClouds.previousChoice("ente"))
        assertNull(KnownClouds.previousChoice(""))
        assertEquals("MEGA", KnownClouds.previousChoice("mega"))
        assertEquals("the old Other app entry has no name", "", KnownClouds.previousChoice("other"))
        // Read once, then replaced by Ente's id - which is what stops it.
        val vm = File(main, "ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("repo.setString(OptionsRepo.K.CLOUD_SINGLE, EnteApp.ID)"))
    }

    @Test
    fun `Ente's abilities are not something a backup can change`() {
        val repo = File(main, "data/prefs/OptionsRepo.kt").readText()
        val import = repo.substringAfter("suspend fun importMap(").substringBefore("private inline fun")
        assertFalse(import.contains("K.CLOUD_SINGLE"))
        assertTrue(CloudCapability.hasDisappearanceOracle(CloudCapability.ENTE))
    }
}
