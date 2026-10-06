package app.entesaver.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A trip the app sends the person on comes back through the lock; a trip
 * that took too long does not.
 */
class ErrandTest {

    @Before
    fun fresh() = Errand.reset()

    @Test
    fun `nothing expected until the app says so`() {
        assertFalse(Errand.expecting(now = 1_000))
        assertFalse(Errand.returnedNeedsLock(now = 1_000))
    }

    @Test
    fun `an errand within the grace comes back unlocked`() {
        Errand.begin(now = 1_000)
        assertTrue(Errand.expecting(now = 1_000 + 30_000))
        Errand.left(now = 2_000)
        assertFalse("thirty seconds on a settings page is an errand", Errand.returnedNeedsLock(now = 2_000 + 30_000))
    }

    @Test
    fun `an errand that took longer than the grace locks on return`() {
        Errand.begin(now = 1_000)
        Errand.left(now = 2_000)
        assertTrue(Errand.returnedNeedsLock(now = 2_000 + Errand.GRACE_MS + 1))
    }

    @Test
    fun `the expectation itself expires, so a trip started long ago cannot excuse a later leave`() {
        Errand.begin(now = 1_000)
        assertFalse(Errand.expecting(now = 1_000 + Errand.GRACE_MS + 1))
    }

    @Test
    fun `a page that refused to open is no excuse for the next leave`() {
        // The grace was armed before a start that then threw, and stayed
        // armed: leaving by the home button within two minutes came back
        // unlocked. A failed start takes the trip back.
        Errand.begin(now = 1_000)
        Errand.cancel()
        assertFalse(Errand.expecting(now = 2_000))
        val main = File("src/main/kotlin/app/entesaver")
        val oem = File(main, "util/OemPages.kt").readText()
        assertTrue(oem.contains("} catch (e: Exception) {\n            Errand.cancel()\n            false"))
        val power = File(main, "util/PowerPages.kt").readText()
        assertTrue(power.contains("// Try the next component; skins rename these between versions.\n                Errand.cancel()"))
        assertTrue(File(main, "data/EnteApp.kt").readText().contains("Errand.cancel()"))
        assertTrue(File(main, "ui/AppViewModel.kt").readText().contains("Errand.cancel()"))
    }

    @Test
    fun `a dialog over the app ends the trip when the app is back in front`() {
        // Android's battery question is a dialog: the app pauses and resumes
        // without stopping, so nothing else ever closed the grace.
        Errand.begin(now = 1_000)
        Errand.resumed()
        assertFalse(Errand.expecting(now = 2_000))
        // A real trip is not cut short by it: it left, so only the return
        // decides.
        Errand.begin(now = 3_000)
        Errand.left(now = 3_100)
        Errand.resumed()
        assertFalse(Errand.returnedNeedsLock(now = 4_000))
        val app = File("src/main/kotlin/app/entesaver/ui/App.kt").readText()
        assertTrue(app.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {\n        Errand.resumed()"))
    }

    @Test
    fun `a return clears the trip, so the next leave is judged afresh`() {
        Errand.begin(now = 1_000)
        Errand.left(now = 2_000)
        Errand.returnedNeedsLock(now = 3_000)
        assertFalse(Errand.expecting(now = 3_000))
        assertFalse(Errand.returnedNeedsLock(now = 4_000))
    }

    @Test
    fun `every place the app sends the person out says so first`() {
        // Source-text rule: each outside launch is preceded by Errand.begin(),
        // or the lock meets the person on the way back from the app's own
        // errand - the fault this exists to remove.
        // The list is not written down here: it is every file that starts an
        // activity, found by looking. A rule with a hand-kept list only ever
        // covers the launches someone remembered to add to it.
        val main = File("src/main/kotlin/app/entesaver")
        val launchers = main.walkTopDown()
            .filter { it.extension == "kt" && it.readText().contains("startActivity(") }
            .toList()
        // Named, so that a regex that quietly stops matching is caught by
        // the absence rather than by a number that happens to still hold.
        val names = launchers.map { it.name }.toSet()
        for (expected in listOf(
            "OemPages.kt", "PowerPages.kt", "AlbumPicker.kt", "AppViewModel.kt", "EnteApp.kt"
        )) {
            assertTrue("$expected no longer appears to launch anything", expected in names)
        }
        for (file in launchers) {
            val text = file.readText()
            // A chooser opened because the last launch threw
            // ActivityNotFoundException is the same trip, announced once
            // above the try that holds both - so a launch only counts as a
            // new trip when no such catch stands between it and the one
            // before it.
            val hits = Regex("""\bstartActivity\(""").findAll(text).map { it.range.first }.toList()
            val trips = hits.filterIndexed { i, at ->
                i == 0 || !text.substring(hits[i - 1], at)
                    .contains("catch (e: ActivityNotFoundException)")
            }.size
            val announced = Regex("""Errand\.begin\(\)""").findAll(text).count()
            assertTrue(
                "${file.name}: $trips trips out, $announced announcements",
                announced >= trips
            )
        }
        val app = File(main, "ui/App.kt").readText()
        assertTrue(app.contains("if (Errand.expecting()) Errand.left() else vm.relock()"))
        assertTrue(app.contains("if (Errand.returnedNeedsLock()) vm.relock()"))
        // And a bounded grace: an open-ended one is a lock anyone walks past.
        val errand = File(main, "util/Errand.kt").readText()
        assertTrue(errand.contains("const val GRACE_MS = 120_000L"))
    }

    @Test
    fun `the Photos shortcut never opens a grace window`() {
        // Anything on the phone can start it, so it must not leave an
        // unlocked Ente Saver open behind it.
        val shortcut = File("src/main/kotlin/app/entesaver/OpenEnteActivity.kt").readText()
        assertTrue(shortcut.contains("EnteApp.launch(this, errand = false)"))
        assertTrue(shortcut.contains("EnteApp.openInstallPage(this, EnteApp.Source.PLAY, errand = false)"))
        val ente = File("src/main/kotlin/app/entesaver/data/EnteApp.kt").readText()
        assertTrue(ente.contains("if (errand) Errand.begin()"))
    }

    @Test
    fun `with the lock on, the whole window stays out of screenshots and recents`() {
        // Android takes the recents thumbnail before the lock is back up on
        // the way in, so securing only the locked screen left the file lists
        // in the thumbnail of a locked app.
        val app = File("src/main/kotlin/app/entesaver/ui/App.kt").readText()
        assertTrue(app.contains("HideWhileLocked(options.appLock)"))
    }
}
