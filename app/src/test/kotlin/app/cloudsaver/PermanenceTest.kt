package app.cloudsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app has to keep working for years with nobody maintaining it.
 *
 * That is a design property, not a wish, and it rests on four things this
 * test refuses to let anyone undo: it depends on no server, it contains
 * nothing that expires, every version-dependent call has a fallback for an
 * Android that does not exist yet, and its database can only ever be migrated
 * - never dropped and rebuilt.
 */
class PermanenceTest {

    private val main = File("src/main/kotlin/app/cloudsaver")
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun sources(): List<File> =
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `nothing can reach the network, by permission or by code`() {
        // Both network permissions are stripped from the merged manifest, so
        // even a library that wanted to talk could not.
        for (permission in listOf("INTERNET", "ACCESS_NETWORK_STATE")) {
            val at = manifest.indexOf(permission)
            assertTrue("$permission must be declared only to remove it", at > 0)
            assertTrue(
                "$permission must carry tools:node=\"remove\"",
                manifest.substring(at, minOf(at + 200, manifest.length))
                    .contains("tools:node=\"remove\"")
            )
        }
        val banned = Regex("""HttpURLConnection|okhttp3|retrofit2|java\.net\.URL\(|java\.net\.Socket""")
        val offenders = sources()
            .filter { banned.containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue("these reach for the network: $offenders", offenders.isEmpty())
    }

    @Test
    fun `nothing expires, and no clock decides whether the app still works`() {
        // A hard-coded future date is how an unmaintained app dies on a
        // Tuesday for no reason its owner can see.
        val dateBomb = Regex("""20[3-9]\d-\d\d-\d\d""")
        val offenders = sources()
            .filter { dateBomb.containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue("these carry a hard-coded date: $offenders", offenders.isEmpty())
    }

    @Test
    fun `a future Android falls through to something that works`() {
        // Every version fork must end in an else. The two that would break
        // silently on an SDK that does not exist yet are named here: the
        // foreground-service type and the release-name table.
        val worker = File(main, "work/CompressWorker.kt").readText()
        val fgs = worker.substringAfter("SDK_INT >= 35").substringBefore("}")
        assertTrue(
            "the foreground service must start on an unknown future SDK",
            fgs.contains("else -> ForegroundInfo(")
        )
        val platform = File(main, "core/logic/Platform.kt").readText()
        val names = platform.substringAfter("fun releaseName").substringBefore("}")
        assertTrue("an unknown SDK must answer with its number", names.contains("else ->"))
    }

    @Test
    fun `the database is migrated, never dropped`() {
        val db = File(main, "data/db/Db.kt").readText()
        assertFalse(
            "a destructive fallback throws away the upload ledger, which is " +
                "the one thing that stops a second copy reaching the cloud",
            db.contains("fallbackToDestructiveMigration")
        )
        val version = Regex("""version = (\d+)""").find(db)!!.groupValues[1].toInt()
        val migrations = Regex("""MIGRATION_(\d+)_(\d+) = object""").findAll(db)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
        assertEquals(
            "every step from 1 to $version needs a migration",
            (1 until version).map { it to it + 1 },
            migrations
        )
        assertTrue(db.contains("addMigrations(*MIGRATIONS)"))
    }

    @Test
    fun `a setting, once chosen, is written even if the screen goes`() {
        // Every setter is called from a view-model scope, which dies with the
        // activity. A tick followed immediately by leaving the app could
        // cancel the write mid-transaction and lose the choice - which is
        // exactly what losing an album tick looks like from the outside.
        val repo = File(main, "data/prefs/OptionsRepo.kt").readText()
        assertTrue(repo.contains("withContext(NonCancellable)"))
        val setters = Regex("""suspend fun set[A-Za-z]+\([^)]*\)[^{]*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
        for (m in setters.findAll(repo)) {
            val body = m.groupValues[1]
            assertFalse(
                "a setter must go through the protected write: ${'$'}body",
                body.contains("dataStore.edit")
            )
        }
        assertTrue("the import is one transaction too", repo.contains("importMap(map: Map<String, String>) = withContext(NonCancellable)"))
    }

    @Test
    fun `the launch self-check runs in the order that survives a bad state`() {
        // Inside onCreate, not the import block above it - the imports are
        // alphabetical and say nothing about what runs first.
        val app = File(main, "CloudSaverApp.kt").readText()
            .substringAfter("override fun onCreate()")
        val crash = app.indexOf("CrashLog.install")
        val recovery = app.indexOf("StartupRecovery")
        val schedule = app.indexOf("Scheduler.ensure")
        assertTrue("the crash handler must be installed first", crash in 1 until recovery)
        assertTrue(
            "state must be restored before work is scheduled against it",
            recovery in 1 until schedule
        )
    }
}
