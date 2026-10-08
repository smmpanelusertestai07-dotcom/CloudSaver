package app.entesaver

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A full or damaged phone must still open the app.
 *
 * Start-up runs in every process, background wakes included, and the phones
 * this app is installed on are the ones running out of space. Three places
 * throw when storage fails: WorkManager opening its own database, the
 * start-up coroutine writing settings, and a damaged settings file read.
 * Each one unhandled is a crash a second after every start, with no way in
 * to free the space that would end it. The device test (StartupSafetyTest)
 * checks the same handlers on a running app.
 */
class StartupResilienceTest {

    private val main = File("src/main/kotlin/app/entesaver")

    @Test
    fun `WorkManager failing to start or schedule does not crash the app`() {
        val app = File(main, "EnteSaverApp.kt").readText()
        assertTrue(app.contains(".setInitializationExceptionHandler"))
        assertTrue(app.contains(".setSchedulingExceptionHandler"))
    }

    @Test
    fun `the screen watching WorkManager does not crash when its database cannot open`() {
        // The handler above only covers WorkManager's own thread. Home's
        // "running" flow queries the same database from the ViewModel, and
        // without failSoft that throw ended the app every time it opened.
        val scheduler = File(main, "work/Scheduler.kt").readText()
        val running = scheduler.substringAfter("fun runningFlow(").substringBefore("\n    fun ")
        assertTrue("runningFlow needs failSoft", running.contains(".failSoft("))
    }

    @Test
    fun `a failed start-up step does not crash the app`() {
        val app = File(main, "EnteSaverApp.kt").readText()
        val scope = app.substringAfter("val appScope = CoroutineScope(").substringBefore("\n    )")
        assertTrue("appScope needs a CoroutineExceptionHandler", scope.contains("CoroutineExceptionHandler"))
    }

    @Test
    fun `a damaged settings file is started afresh, not thrown on every read`() {
        val repo = File(main, "data/prefs/OptionsRepo.kt").readText()
        assertTrue(repo.contains("corruptionHandler = ReplaceFileCorruptionHandler"))
    }
}
