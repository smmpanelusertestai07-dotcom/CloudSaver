package app.cloudsaver

import app.cloudsaver.util.CrashLog
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
        // The system can send app data even where the app cannot: Auto Backup
        // copies the database and settings to the account holder's Drive
        // without needing the internet permission, because the system does the
        // sending. An app that promises nothing leaves the phone has to opt
        // out of that too.
        assertTrue(
            "Android's own cloud backup must be off",
            manifest.contains("android:allowBackup=\"false\"")
        )
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
    fun `the daily snapshot cannot grow without bound, and never drops proof`() {
        val store = File(main, "engine/SnapshotStore.kt").readText()
        assertTrue(store.contains("MAX_REBUILDABLE_ITEMS"))
        val build = store.substringAfter("suspend fun build()").substringBefore("val batches")
        // The split is the whole safety argument: a row with evidence or a
        // delivered copy is irreplaceable - losing it could let a file reach
        // the cloud twice - so only queue state is ever trimmed.
        assertTrue(
            build.contains("it.outputSha256 != null || Evidence.parse(it.evidence) != Evidence.NONE")
        )
        assertTrue("evidenced rows are always written", build.contains("critical + rebuildable"))
        assertTrue("and the trim is recorded", build.contains("trimmed"))
        // The ledger itself is never capped: it is what stops a second upload.
        val ledger = store.substringAfter("val ledger = db.ledger().all()")
            .substringBefore("val access")
        assertFalse("the ledger must be written whole", ledger.contains("take("))
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
    fun `a snapshot restore lands whole or not at all`() {
        // The first launch after a reinstall restores before setup is done -
        // the moment a person is most likely to swipe the app away - and the
        // rows used to go in one commit at a time. Whatever had landed stayed,
        // the next launch saw a non-empty table, wrote RESTORE_DONE and never
        // read the snapshot again: half a history, no ledger, taken for done.
        val store = File(main, "engine/SnapshotStore.kt").readText()
        val merge = store.substringAfter("private suspend fun mergeLocked(")
            .substringBefore("private suspend fun mergeRows(")
        assertTrue(
            "the rows must go in under db.withTransaction, so an interrupted " +
                "restore leaves the table empty for the next launch to retry",
            merge.contains("db.withTransaction { mergeRows(snapshot) }")
        )
        val rows = store.substringAfter("private suspend fun mergeRows(")
        assertFalse(
            "settings live in DataStore and have no place inside a Room transaction",
            rows.contains("optionsRepo.importMap")
        )
        // And the two facts a scan cannot rebuild travel with the rows.
        assertTrue(store.contains("keptUri = row.keptUri"))
        assertTrue(store.contains("neverOptimise = row.neverOptimise"))
        assertTrue(rows.contains("keptUri = mapped.keptUri"))
        assertTrue(rows.contains("neverOptimise = mapped.neverOptimise"))
    }

    @Test
    fun `a process start does not cancel the run a new photo just triggered`() {
        // FAST mode's content trigger wakes the process to run; the process
        // then ran ensure(), and ensure() re-armed the trigger with REPLACE,
        // cancelling the very run it had been woken for. Only the worker's
        // own re-arm, after it has consumed the trigger, may replace.
        val scheduler = File(main, "work/Scheduler.kt").readText()
        val ensure = scheduler.substringAfter("fun ensure(").substringBefore("fun enqueueContentTrigger(")
        assertTrue(
            "ensure() must keep a pending trigger, not replace it",
            ensure.contains("enqueueContentTrigger(context)") && !ensure.contains("force = true")
        )
        val trigger = scheduler.substringAfter("fun enqueueContentTrigger(")
            .substringBefore("fun cancelAll(")
        assertTrue(
            "the trigger policy must be KEEP unless the caller forces a replace",
            trigger.contains("if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP")
        )
        val worker = File(main, "work/CompressWorker.kt").readText()
        assertTrue(
            "the worker has consumed its trigger and must replace it",
            worker.contains("Scheduler.enqueueContentTrigger(context, force = true)")
        )
    }

    @Test
    fun `the Alerts switch says when notifications are blocked`() {
        // Setup asks for the notification permission once and offers Skip;
        // the system lets it be revoked later. The switch then sat ON while
        // every alert was dropped at posting time, and nothing said so.
        val options = File(main, "ui/screens/OptionsScreen.kt").readText()
        val afterSwitch = options.substringAfter("checked = o.warningsNotif")
        assertTrue(
            "the row that says notifications are off must follow the Alerts switch",
            afterSwitch.take(200).contains("AlertsPermissionRow(wanted = o.warningsNotif)")
        )
        val row = options.substringAfter("private fun AlertsPermissionRow(")
        assertTrue(row.contains("Permissions.hasNotifications(context)"))
        assertTrue(
            "it must re-check on resume, because the permission is granted on another screen",
            row.contains("LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME)")
        )
        assertTrue(row.contains("launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)"))
        assertTrue(
            "once the system stops asking, the only way back is its settings page",
            row.contains("OemPages.openNotificationSettings(context)")
        )
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

    /**
     * A launch that dies twice in a row must not be tried a third time.
     *
     * With no crash reporter, an app that crashes as it opens is a dead
     * icon whose own log nobody can reach. The launcher notes when it
     * started before it composes anything, and two deaths inside the
     * window put a plain recovery page in front of the app: try again,
     * share the log, open app info. The page uses no stored theme and no
     * dynamic colour, because reading either may be the thing that dies.
     */
    @Test
    fun `two young crashes in a row open the recovery page instead of the app`() {
        val activity = File(main, "MainActivity.kt").readText()
            .substringAfter("override fun onCreate(")
        val noted = activity.indexOf("CrashLog.noteLaunchStarted(this)")
        val checked = activity.indexOf("CrashLog.startupCrashStreak(this) >= CrashLog.RECOVERY_AFTER")
        val recovery = activity.indexOf("RecoveryScreen(")
        val appIndex = activity.indexOf("App(vm)")
        assertTrue("the launch must be noted before anything is composed", noted in 0 until checked)
        assertTrue("the streak is checked before the app is composed", checked in 0 until recovery)
        assertTrue("the recovery page comes before the app", recovery < appIndex)
        assertTrue(
            "trying again must clear the streak, or the page is a trap",
            activity.substringAfter("RecoveryScreen(").substringBefore("return")
                .contains("CrashLog.clearStartupStreak(this)")
        )
        assertTrue(
            "a launch that lives past the window ends the streak",
            activity.contains("CrashLog.STARTUP_WINDOW_MS") &&
                activity.substringAfter("postDelayed").contains("clearStartupStreak")
        )

        val page = File(main, "ui/screens/RecoveryScreen.kt").readText()
        assertTrue("try again", page.contains("R.string.recovery_try"))
        assertTrue("share the log", page.contains("R.string.recovery_share"))
        assertTrue("app info", page.contains("R.string.recovery_app_info"))
        assertTrue(
            "the page must not depend on the stored theme or dynamic colour",
            page.contains("CloudSaverTheme(mode = ThemeMode.SYSTEM, dynamicColor = false)")
        )

        // One crash is an accident; two is a pattern. A single crash must
        // never lock someone out of the app they were using.
        assertEquals(2, CrashLog.RECOVERY_AFTER)
        assertTrue(
            "the window is seconds, not minutes: a crash while working is not a failed launch",
            CrashLog.STARTUP_WINDOW_MS in 5_000L..30_000L
        )
    }
}
