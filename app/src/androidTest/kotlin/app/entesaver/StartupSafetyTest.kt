package app.entesaver

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A full or damaged phone must still open the app (StartupResilienceTest).
 *
 * Checked on the running app rather than the source alone: these handlers
 * only matter if the configuration WorkManager is actually given, and the
 * scope start-up actually runs in, carry them.
 */
@RunWith(AndroidJUnit4::class)
class StartupSafetyTest {

    private val app: EnteSaverApp = ApplicationProvider.getApplicationContext()

    @Test
    fun workManagerIsGivenHandlersForBrokenStorage() {
        val config = app.workManagerConfiguration
        assertNotNull(config.initializationExceptionHandler)
        assertNotNull(config.schedulingExceptionHandler)
    }

    @Test
    fun aFailingStartupStepDoesNotEndTheProcess() = runBlocking {
        assertNotNull(app.appScope.coroutineContext[CoroutineExceptionHandler])
        // Without the handler this throw would reach the thread's uncaught
        // handler and end the test process along with the app.
        val job = app.appScope.launch { throw IllegalStateException("storage full") }
        job.join()
        assertTrue(job.isCancelled)
    }
}
