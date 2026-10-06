package app.entesaver.work

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every worker the app schedules can still be made under the name an older
 * version stored it with. A worker added later is new, so it has no old name;
 * one that existed before 12.0 and is missing here would stop running on
 * every phone updated from then.
 */
class WorkerNamesTest {

    @Test
    fun `each worker that existed before 12_0 answers to its old name`() {
        val workers = File("src/main/kotlin/app/entesaver/work").listFiles()!!
            .filter { it.readText().contains(Regex("""class \w+\([^)]*\) :\s*CoroutineWorker""")) }
            .map { it.nameWithoutExtension }
            .toSet()
        assertEquals(setOf("CompressWorker", "MaintainWorker"), workers)
        assertEquals(
            workers.map { "app.cloudsaver.work.$it" }.toSet(),
            WorkerNames.previousNames
        )
    }

    @Test
    fun `WorkManager starts with that factory, not the library's default`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val removed = Regex(
            """<meta-data\s+android:name="androidx\.work\.WorkManagerInitializer"[^>]*tools:node="remove""""
        )
        assertTrue(removed.containsMatchIn(manifest))
        val app = File("src/main/kotlin/app/entesaver/EnteSaverApp.kt").readText()
        assertTrue(app.contains("setWorkerFactory(WorkerNames)"))
    }
}
