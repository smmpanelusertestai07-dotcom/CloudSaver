package com.pocketide.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android's own reading of an APK, as the updater does it: this installed app's archive, read as
 * a downloaded update would be, gives the same facts as the installed app, and passes the rules
 * as a newer build would.
 */
@RunWith(AndroidJUnit4::class)
class UpdaterFactsOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun anArchiveReadsTheSameAsTheInstalledApp() {
        val packages = context.packageManager
        val self = apkFacts(packages.getPackageInfo(context.packageName, APK_FACTS_FLAGS))
        val archive = packages.getPackageArchiveInfo(context.applicationInfo.sourceDir, APK_FACTS_FLAGS)?.let(::apkFacts)
        assertNotNull("Android could not read the installed archive", archive)
        checkNotNull(archive)
        assertEquals(self.packageName, archive.packageName)
        assertEquals(self.versionCode, archive.versionCode)
        assertEquals(self.signers, archive.signers)
        assertTrue(self.signers.isNotEmpty())
        self.signers.forEach { assertTrue(it, Regex("[0-9a-f]{64}").matches(it)) }
        assertNull(UpdateRules.problem(archive.copy(versionCode = self.versionCode + 1), self, self.signers.first()))
    }
}
