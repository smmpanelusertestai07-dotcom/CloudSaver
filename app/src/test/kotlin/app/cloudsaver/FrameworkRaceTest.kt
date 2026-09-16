package app.cloudsaver

import app.cloudsaver.util.FrameworkRace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's own teardown race is not this app's crash - and a real crash
 * that merely sounds like it still is.
 */
class FrameworkRaceTest {

    private fun withFrames(error: Throwable, vararg classes: String): Throwable {
        error.stackTrace = classes.map { StackTraceElement(it, "execute", "Unknown.java", 1) }.toTypedArray()
        return error
    }

    @Test
    fun `the platform's own race is recognised by message and frame together`() {
        val race = withFrames(
            IllegalArgumentException(
                "Activity client record must not be null to execute transaction item: " +
                    "TopResumedActivityChangeItem"
            ),
            "android.app.servertransaction.TransactionExecutor",
            "android.app.ActivityThread\$H"
        )
        assertTrue(FrameworkRace.isRace(race))
        // Wrapped by something of ours on the way out, the cause still decides.
        assertTrue(FrameworkRace.isRace(RuntimeException("wrapped", race)))
    }

    @Test
    fun `the same words from this app's own code are a real crash`() {
        // The frame is the half that keeps a genuine bug visible: an
        // IllegalArgumentException with the framework's sentence but thrown
        // from app code is ours to fix, and must raise the card.
        val ours = withFrames(
            IllegalArgumentException("Activity client record must not be null"),
            "app.cloudsaver.ui.App", "android.os.Handler"
        )
        assertFalse(FrameworkRace.isRace(ours))
        assertFalse(FrameworkRace.isRace(NullPointerException("nothing to do with Android")))
        val otherMessage = withFrames(
            IllegalArgumentException("Unable to find non-null record for something else entirely"),
            "android.app.servertransaction.TransactionExecutor"
        )
        assertFalse("without the word Activity it is not the known race", FrameworkRace.isRace(otherMessage))
    }

    @Test
    fun `the recorder writes the race down and raises nothing for it`() {
        val crash = File("src/main/kotlin/app/cloudsaver/util/CrashLog.kt").readText()
        val record = crash.substringAfter("private fun record(").substringBefore("\n    }\n")
        val judged = record.indexOf("FrameworkRace.isRace(throwable)")
        val flagged = record.indexOf("createNewFile()")
        assertTrue("the race must be judged before the flag is written", judged in 0 until flagged)
        val branch = record.substringAfter("FrameworkRace.isRace(throwable)").substringBefore("return")
        assertTrue("it is still logged", branch.contains("AppLog.log("))
        assertFalse("it raises no card", branch.contains("createNewFile()"))
    }
}
