package app.cloudsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cloudsaver.util.AppLog
import app.cloudsaver.util.CrashLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BB3.4: a crash writes a readable local trace and raises the one-time flag,
 * and dismissing the card clears it.
 *
 * The app has no internet permission, so this log entry is the only evidence
 * a crash ever leaves. If this test fails, crashes are invisible again.
 */
@RunWith(AndroidJUnit4::class)
class CrashLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        CrashLog.clearPending(context)
        CrashLog.clearStartupStreak(context)
        AppLog.clear(context)
    }

    @After
    fun tidy() {
        CrashLog.clearPending(context)
        CrashLog.clearStartupStreak(context)
    }

    @Test
    fun simulatedCrashWritesTheTraceAndRaisesTheFlag() {
        assertFalse("no crash is pending before one happens", CrashLog.crashPending(context))

        CrashLog.simulateForTest(context, IllegalStateException("boom for the test"))

        assertTrue("the next launch must know", CrashLog.crashPending(context))
        val log = AppLog.readTail(context)
        assertTrue("the trace must be in the log", log.contains("boom for the test"))
        assertTrue("the entry must name the app version", log.contains(BuildConfig.VERSION_NAME))
        assertTrue(
            "the entry must carry the stack, not only the message",
            log.contains("IllegalStateException")
        )
    }

    @Test
    fun dismissingClearsTheFlagForGood() {
        CrashLog.simulateForTest(context, RuntimeException("second boom"))
        assertTrue(CrashLog.crashPending(context))

        CrashLog.clearPending(context)

        assertFalse("the card must not come back", CrashLog.crashPending(context))
    }

    @Test
    fun twoCrashesSoonAfterLaunchCountAsAFailedLaunch() {
        // The launcher notes when it started; a crash inside the window is
        // a launch that could not complete, and two in a row is the signal
        // the recovery page waits for.
        CrashLog.noteLaunchStarted(context)
        assertEquals(0, CrashLog.startupCrashStreak(context))

        CrashLog.simulateForTest(context, IllegalStateException("died at launch"))
        assertEquals(1, CrashLog.startupCrashStreak(context))

        CrashLog.simulateForTest(context, IllegalStateException("died at launch again"))
        assertEquals(2, CrashLog.startupCrashStreak(context))
        assertTrue(
            "two is the threshold, so the next launch shows the recovery page",
            CrashLog.startupCrashStreak(context) >= CrashLog.RECOVERY_AFTER
        )

        // Trying again, or living past the window, ends the streak.
        CrashLog.clearStartupStreak(context)
        assertEquals(0, CrashLog.startupCrashStreak(context))
    }

    @Test
    fun aCrashWithNoRecentLaunchStartsNoStreak() {
        // A crash while the app is being used is a bug, not a failed
        // launch: it must never lock the person out behind the recovery
        // page. With no launch noted there is no window to be inside of.
        context.getFileStreamPath("launch_started_at").delete()
        CrashLog.simulateForTest(context, RuntimeException("died mid-session"))
        assertEquals(0, CrashLog.startupCrashStreak(context))
        assertTrue("but the crash card is still raised", CrashLog.crashPending(context))
    }
}
