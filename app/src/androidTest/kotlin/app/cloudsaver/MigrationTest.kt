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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun version2DatabaseSurvivesEveryUpgrade() = runBlocking {
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

        // v5's pixel counters: absent on an upgraded row, which is exactly how
        // the UI tells "not measured" apart from "no detail kept".
        assertEquals(0L, carried.first().srcPixels)
        assertEquals(0L, carried.first().outPixels)
        db.items().update(
            carried.first().copy(srcPixels = 48_000_000, outPixels = 16_000_000)
        )
        assertEquals(16_000_000L, db.items().all().first().outPixels)

        // v7's priority column, and the reason it exists: an upgraded row
        // must carry 0 - "no jump asked for" - and its captureAt must be
        // untouched, because that is the file's real shooting date. Before v7
        // the queue-jump wrote `now` into captureAt, so asking for one file
        // first rewrote its date in the details dialog, in the Newest sort,
        // and on the copy handed to the cloud.
        assertEquals("an upgraded row has asked for nothing", 0L, carried.first().priorityAt)
        val realDate = carried.first().captureAt
        db.items().update(carried.first().copy(priorityAt = 1_700_000_000_000L))
        val bumped = db.items().all().first()
        assertEquals(1_700_000_000_000L, bumped.priorityAt)
        assertEquals("jumping the queue must not touch the shooting date", realDate, bumped.captureAt)

        // v6's indices: proven by asking SQLite, not assumed. Room validates
        // entity indices at open, but only for entities it knows - a typo in
        // the migration SQL would surface here first.
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'items'"
        ).use { c ->
            val names = mutableSetOf<String>()
            while (c.moveToNext()) names += c.getString(0)
            for (expected in listOf(
                "index_items_isVideo", "index_items_bucket", "index_items_sizeBytes",
                "index_items_evidence", "index_items_batchId"
            )) {
                assertTrue("$expected must exist after migration", expected in names)
            }
        }

        // v4's columns and tables have to be there too.
        assertEquals(0, db.reclaim().itemsOf(1).size)
        assertNull("nothing hashed yet", carried.first().originalSha256)
        assertNull("no profile until something is processed", db.profile().get("STORAGE_SAVER", "H264"))
        db.profile().put(
            app.cloudsaver.data.db.MediaProfileRow(
                preset = "STORAGE_SAVER", codec = "H264", photoCount = 1, updatedAt = 1
            )
        )
        assertNotNull(db.profile().get("STORAGE_SAVER", "H264"))
        val batchId = db.reclaim().insertBatch(
            app.cloudsaver.data.db.ReclaimBatchRow(
                atMs = 1_700_000_000_000, mode = "FREE_UP_FULLY",
                itemCount = 1, freedBytes = 2048, trashed = true
            )
        )
        db.reclaim().insertItems(
            listOf(
                app.cloudsaver.data.db.ReclaimItemRow(
                    batchId = batchId, fingerprint = "fp-1",
                    displayName = "e2e_before_upgrade.jpg", originalBytes = 2048,
                    optimisedBytes = 1024, trashed = true
                )
            )
        )
        assertEquals(1, db.reclaim().itemsOf(batchId).size)

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
