package app.cloudsaver

import app.cloudsaver.util.Errand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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
        val main = File("src/main/kotlin/app/cloudsaver")
        val files = listOf(
            "util/OemPages.kt", "util/PowerPages.kt", "ui/components/AlbumPicker.kt",
            "ui/AppViewModel.kt", "data/CloudApps.kt", "ui/screens/RecoveryScreen.kt",
            "ui/screens/HelpScreens.kt"
        )
        for (name in files) {
            val text = File(main, name).readText()
            val launches = Regex("""\bstartActivity\(""").findAll(text).count()
            assertTrue("$name launches nothing?", launches > 0)
            // OemPages and PowerPages announce inline on every launch; the
            // rest launch once and announce once, just above.
            assertTrue("$name must call Errand.begin() before it starts an activity", text.contains("Errand.begin()"))
            if (name.startsWith("util/")) {
                val announced = Regex("""Errand\.begin\(\)""").findAll(text).count()
                assertTrue(
                    "$name: $launches launches, $announced announcements",
                    announced >= launches
                )
            }
        }
        val app = File(main, "ui/App.kt").readText()
        assertTrue(app.contains("if (Errand.expecting()) Errand.left() else unlocked = false"))
        assertTrue(app.contains("if (Errand.returnedNeedsLock()) unlocked = false"))
        // And a bounded grace: an open-ended one is a lock anyone walks past.
        val errand = File(main, "util/Errand.kt").readText()
        assertTrue(errand.contains("const val GRACE_MS = 120_000L"))
    }

    @Test
    fun `with the lock on, the whole window stays out of screenshots and recents`() {
        // Android takes the recents thumbnail before the lock is back up on
        // the way in, so securing only the locked screen left the file lists
        // in the thumbnail of a locked app.
        val app = File("src/main/kotlin/app/cloudsaver/ui/App.kt").readText()
        assertTrue(app.contains("if (options.appLock) SecureScreen()"))
    }
}
