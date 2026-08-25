package app.cloudsaver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cloudsaver.util.AppLog
import app.cloudsaver.util.CrashLog
import org.junit.After
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
        AppLog.clear(context)
    }

    @After
    fun tidy() {
        CrashLog.clearPending(context)
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
}
