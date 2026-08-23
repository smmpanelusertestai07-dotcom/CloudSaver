package app.cloudsaver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.db.LedgerRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the upgrade path from the shipped schema.
 *
 * A wrong ALTER in a migration does not fail the build - it crashes the app
 * on the first launch after an update, on a phone that already holds the
 * user's whole backup state. So the test builds a database at the previous
 * version by hand, opens it through Room with the real migrations, and then
 * uses the tables that the migration was supposed to add.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-probe.db"

    @Before
    fun clean() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tidy() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun version2DatabaseSurvivesTheUpgrade() = runBlocking {
        seedVersion2()

        val db = Room.databaseBuilder(context, AppDb::class.java, dbName)
            .addMigrations(*AppDb.MIGRATIONS)
            .build()

        // Opening is where Room runs the migration and then validates that the
        // result matches the entities. Touching a table forces that to happen.
        val carried = db.items().all()
        assertEquals("the existing row must survive", 1, carried.size)
        assertEquals("e2e_before_upgrade.jpg", carried.first().displayName)
        assertEquals(
            "an older CONFIRMED row keeps its meaning",
            Evidence.CONFIRMED_EXACT,
            Evidence.parse(carried.first().evidence)
        )

        // The columns the migration added must be readable and writable.
        assertEquals(0L, carried.first().txObserved)
        assertEquals(0, carried.first().resendCount)
        db.items().update(carried.first().copy(txObserved = 4096, resendCount = 1))
        assertEquals(4096L, db.items().all().first().txObserved)

        // And so must the three tables it created.
        db.ledger().insert(
            LedgerRow(
                outputSha256 = "abc123",
                fingerprint = "fp-1",
                displayName = "e2e_before_upgrade.jpg",
                outputBytes = 1024,
                evidence = Evidence.CONFIRMED_EXACT.name,
                confirmedAt = 1_700_000_000_000
            )
        )
        assertNotNull("the ledger must be usable", db.ledger().bySha("abc123"))
        assertEquals(0, db.activity().recent(10).size)
        assertEquals(0, db.capabilities().all().size)

        // The unique index has to come across too, or the ledger stops being
        // a de-duplication record at all.
        db.ledger().insert(
            LedgerRow(
                outputSha256 = "abc123",
                fingerprint = "fp-2",
                displayName = "other.jpg",
                outputBytes = 2048,
                evidence = Evidence.VERIFIED.name,
                confirmedAt = 1_700_000_000_001
            )
        )
        assertEquals("a duplicate hash must not be stored twice", 1, db.ledger().all().size)

        db.close()
    }

    /** The schema as version 2 shipped it, written straight to SQLite. */
    private fun seedVersion2() {
        val path = context.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null)
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`fingerprint` TEXT NOT NULL, `mediaStoreId` INTEGER, `contentUri` TEXT, " +
                "`displayName` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                "`dateModified` INTEGER NOT NULL, `captureAt` INTEGER NOT NULL, " +
                "`dateAdded` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`mimeType` TEXT NOT NULL, `isVideo` INTEGER NOT NULL, `bucket` TEXT, " +
                "`state` TEXT NOT NULL, `evidence` TEXT NOT NULL, `goneReason` TEXT, " +
                "`skipReason` TEXT, `stagePath` TEXT, `outputUri` TEXT, `outputName` TEXT, " +
                "`outputBytes` INTEGER, `outputSha256` TEXT, `outputFolder` TEXT, " +
                "`presetUsed` TEXT, `codecUsed` TEXT, `batchId` INTEGER, " +
                "`releasedAt` INTEGER, `confirmedAt` INTEGER, `attempts` INTEGER NOT NULL, " +
                "`lastError` TEXT, `originalMissing` INTEGER NOT NULL, " +
                "`appDeletedCopy` INTEGER NOT NULL, `fromImport` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        raw.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_items_fingerprint` ON `items` (`fingerprint`)"
        )
        raw.execSQL("CREATE INDEX IF NOT EXISTS `index_items_state` ON `items` (`state`)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS `index_items_captureAt` ON `items` (`captureAt`)")
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `batches` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`releasedAt` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, " +
                "`folder` TEXT NOT NULL, `cloudPackage` TEXT, `verifiedAt` INTEGER)"
        )
        raw.execSQL(
            "CREATE TABLE IF NOT EXISTS `day_budget` (" +
                "`day` TEXT NOT NULL, `videoEncodeMs` INTEGER NOT NULL, " +
                "`photosOnBattery` INTEGER NOT NULL, PRIMARY KEY(`day`))"
        )
        // One row carrying the pre-3.1 evidence name, so the upgrade is tested
        // with data in it rather than on an empty file.
        raw.execSQL(
            "INSERT INTO `items` (fingerprint, displayName, sizeBytes, dateModified, " +
                "captureAt, dateAdded, durationMs, mimeType, isVideo, state, evidence, " +
                "attempts, originalMissing, appDeletedCopy, fromImport, updatedAt) " +
                "VALUES ('fp-1', 'e2e_before_upgrade.jpg', 2048, 1, 1, 1, 0, 'image/jpeg', " +
                "0, '${ItemState.RELEASED.name}', 'CONFIRMED', 0, 0, 0, 0, 1)"
        )
        raw.version = 2
        raw.close()
    }
}
