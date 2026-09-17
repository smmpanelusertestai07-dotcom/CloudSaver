package app.cloudsaver.util

import androidx.appcompat.app.AppCompatDelegate
import app.cloudsaver.core.logic.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The first frame is the colour the person chose. */
class FirstFrameTest {

    @Test
    fun `the three choices map to the three night modes, and System stays with the phone`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, FirstFrame.nightMode(ThemeMode.SYSTEM))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, FirstFrame.nightMode(ThemeMode.LIGHT))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, FirstFrame.nightMode(ThemeMode.DARK))
    }

    @Test
    fun `the choice is mirrored when set and applied before any window exists`() {
        val main = File("src/main/kotlin/app/cloudsaver")
        val app = File(main, "CloudSaverApp.kt").readText()
        val installed = app.indexOf("CrashLog.install(this)")
        val applied = app.indexOf("FirstFrame.apply(this)")
        val channels = app.indexOf("Notifications.createChannels(this)")
        assertTrue("applied at process start, after the crash recorder, before anything else",
            installed in 0 until applied && applied < channels)
        val vm = File(main, "ui/AppViewModel.kt").readText()
        val setter = vm.substringAfter("fun setTheme(v: ThemeMode) {").substringBefore("\n    }\n")
        assertTrue("the setter mirrors the choice", setter.contains("FirstFrame.remember(ctx, v)"))
        assertTrue("a choice made before the mirror existed is mirrored on load",
            vm.contains("FirstFrame.remember(ctx, repo.current().theme)"))
        // Never at runtime with a screen up: that recreates the activity.
        val first = File(main, "util/FirstFrame.kt").readText()
        val remember = first.substringAfter("fun remember(").substringBefore("\n    }\n")
        assertTrue(!remember.contains("setDefaultNightMode"))
        assertTrue(first.substringAfter("fun apply(").contains("AppCompatDelegate.setDefaultNightMode(nightMode(mode))"))
    }
}
