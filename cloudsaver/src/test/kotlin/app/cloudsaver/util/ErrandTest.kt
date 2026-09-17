package app.cloudsaver.util

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
        val main = File("src/main/kotlin/app/cloudsaver")
        val launchers = main.walkTopDown()
            .filter { it.extension == "kt" && it.readText().contains("startActivity(") }
            .toList()
        assertTrue("nothing launches anything?", launchers.size >= 5)
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
        assertTrue(app.contains("if (Errand.expecting()) Errand.left() else vm.unlocked.value = false"))
        assertTrue(app.contains("if (Errand.returnedNeedsLock()) vm.unlocked.value = false"))
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
        assertTrue(app.contains("HideWhileLocked(options.appLock)"))
    }
}
