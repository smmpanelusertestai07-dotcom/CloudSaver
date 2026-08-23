package app.cloudsaver.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * One row per original gallery item, keyed by its path-independent fingerprint.
 * Enum-typed fields are stored as their enum names (stable, snapshot-friendly).
 */
@Entity(
    tableName = "items",
    indices = [Index(value = ["fingerprint"], unique = true), Index("state"), Index("captureAt")]
)
data class ItemRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val mediaStoreId: Long? = null,
    val contentUri: String? = null,
    val displayName: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val captureAt: Long,
    val dateAdded: Long = 0,
    val durationMs: Long = 0,
    val mimeType: String,
    val isVideo: Boolean,
    val bucket: String? = null,
    val state: String,
    val evidence: String = "NONE",
    val goneReason: String? = null,
    val skipReason: String? = null,
    val stagePath: String? = null,
    val outputUri: String? = null,
    val outputName: String? = null,
    val outputBytes: Long? = null,
    val outputSha256: String? = null,
    val outputFolder: String? = null,
    val presetUsed: String? = null,
    val codecUsed: String? = null,
    val batchId: Long? = null,
    val releasedAt: Long? = null,
    val confirmedAt: Long? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val originalMissing: Boolean = false,
    val appDeletedCopy: Boolean = false,
    val fromImport: Boolean = false,
    /**
     * Bytes the cloud app transmitted while this copy was waiting, recorded
     * when the copy was graded. Kept for the details screen, so a claim about
     * a file can always be traced back to the number behind it.
     */
    val txObserved: Long = 0,
    /** Which encoder produced the copy, for the details screen. */
    val encoderName: String? = null,
    /** Times a vanished copy was re-sent; two is the limit. */
    val resendCount: Int = 0,
    val updatedAt: Long = 0
)

/**
 * On-battery work done today (13.G). Keyed by the local date, so the budget
 * simply resets at local midnight - no timer, no alarm.
 */
@Entity(tableName = "day_budget")
data class DayBudgetRow(
    @PrimaryKey val day: String,
    val videoEncodeMs: Long = 0,
    val photosOnBattery: Int = 0
)

/**
 * Permanent record of copies that reached the cloud, keyed by the copy's
 * SHA-256 and the original's fingerprint.
 *
 * This is what stops the queue growing forever. Once a copy is evidenced, the
 * item is finished for good: if the cloud later removes the file from the
 * upload folder - which is exactly what a cloud with a free-up feature does -
 * the app must not read that as "lost" and send it again. The ledger outlives
 * the item row and travels in every snapshot, so a reinstall cannot undo it.
 */
@Entity(tableName = "ledger", indices = [Index(value = ["outputSha256"], unique = true), Index("fingerprint")])
data class LedgerRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val outputSha256: String,
    val fingerprint: String,
    val displayName: String,
    val outputBytes: Long,
    val evidence: String,
    val confirmedAt: Long
)

/**
 * One line in the Activity log: what the app did, in the user's words.
 *
 * Everything that would otherwise only exist as a notification lands here
 * too, so nothing is lost to a swipe.
 */
@Entity(tableName = "activity", indices = [Index("atMs"), Index("kind")])
data class ActivityRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val kind: String,
    /** Optional detail rendered after the headline, already formatted. */
    val detail: String? = null,
    val count: Int = 0,
    val bytes: Long = 0,
    /** Filters Files when the row is tapped, when it makes sense. */
    val filterState: String? = null
)

/**
 * What a given cloud app can do, learned rather than asked.
 *
 * Defaults come from the registry; observing the app actually free up space
 * promotes it. The user is never asked a question about capabilities.
 */
@Entity(tableName = "cloud_capability")
data class CloudCapabilityRow(
    @PrimaryKey val cloudId: String,
    val hasFreeUpSpace: Boolean,
    val hasHashDedupe: Boolean,
    val packageName: String? = null,
    val lastSeenVersionCode: Long = 0,
    /** Set once the app is seen removing its own uploaded copies. */
    val learnedFreeUp: Boolean = false,
    val updatedAt: Long = 0
)

@Entity(tableName = "batches")
data class BatchRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val releasedAt: Long,
    val totalBytes: Long,
    val folder: String,
    val cloudPackage: String? = null,
    val verifiedAt: Long? = null
)

data class StateCount(val state: String, val cnt: Int)

/** Compression outcome sample for the cloud calculator. */
data class RatioSample(val sizeBytes: Long, val outputBytes: Long, val durationMs: Long)

@Dao
interface ItemDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ItemRow): Long

    @Update
    suspend fun update(row: ItemRow)

    @Query("SELECT * FROM items WHERE fingerprint = :fp LIMIT 1")
    suspend fun byFingerprint(fp: String): ItemRow?

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ItemRow?

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ItemRow>

    @Query("SELECT * FROM items WHERE state = :state")
    suspend fun byState(state: String): List<ItemRow>

    /** Drops a row entirely; used when a folder turns out to be another pipeline's output. */
    @androidx.room.Delete
    suspend fun delete(row: ItemRow)

    @Query(
        "SELECT * FROM items WHERE state = 'NEW' AND originalMissing = 0 " +
            "ORDER BY captureAt DESC LIMIT :limit"
    )
    suspend fun newestNew(limit: Int): List<ItemRow>


    @Query("SELECT * FROM items WHERE state = 'STAGED'")
    suspend fun staged(): List<ItemRow>

    @Query("SELECT * FROM items WHERE state = 'RELEASED'")
    suspend fun released(): List<ItemRow>

    /** Released copies still waiting on evidence, oldest first. */
    @Query(
        "SELECT * FROM items WHERE state = 'RELEASED' AND evidence = 'NONE' " +
            "ORDER BY releasedAt ASC"
    )
    suspend fun awaitingEvidence(): List<ItemRow>

    /**
     * Fresh items newest-first, then the backlog largest-first.
     *
     * Recent photos are what the user is thinking about, so they go first;
     * after that, the biggest files free the most space per minute of work,
     * which beats grinding through a thousand old screenshots.
     */
    @Query(
        "SELECT * FROM items WHERE state = 'NEW' AND originalMissing = 0 " +
            "AND ((isVideo = 0 AND :photos = 1) OR (isVideo = 1 AND :videos = 1)) " +
            "AND (bucket IS NULL OR bucket NOT IN (:excludedBuckets)) " +
            "ORDER BY (captureAt >= :freshAfter) DESC, " +
            "CASE WHEN captureAt >= :freshAfter THEN captureAt ELSE 0 END DESC, " +
            "CASE WHEN captureAt < :freshAfter THEN sizeBytes ELSE 0 END DESC " +
            "LIMIT :limit"
    )
    suspend fun nextByPriority(
        photos: Boolean,
        videos: Boolean,
        excludedBuckets: Collection<String>,
        freshAfter: Long,
        limit: Int
    ): List<ItemRow>

    @Query(
        "SELECT COALESCE(SUM(sizeBytes - outputBytes), 0) FROM items " +
            "WHERE outputBytes IS NOT NULL AND outputBytes < sizeBytes AND isVideo = :video"
    )
    fun savedBytesFlow(video: Boolean): Flow<Long>

    @Query("SELECT COUNT(*) FROM items WHERE outputBytes IS NOT NULL AND isVideo = :video")
    fun processedCountFlow(video: Boolean): Flow<Int>

    /** Copies taken byte-for-byte; lastError carries the reason for those. */
    @Query(
        "SELECT COUNT(*) FROM items WHERE outputBytes IS NOT NULL " +
            "AND lastError IS NOT NULL AND isVideo = :video"
    )
    suspend fun asIsCount(video: Boolean): Int

    @Query(
        "SELECT lastError AS state, COUNT(*) AS cnt FROM items " +
            "WHERE outputBytes IS NOT NULL AND lastError IS NOT NULL AND isVideo = :video " +
            "GROUP BY lastError ORDER BY cnt DESC LIMIT 5"
    )
    suspend fun asIsReasons(video: Boolean): List<StateCount>

    /** Bytes saved by work done since [fromMs] - what one run achieved. */
    @Query(
        "SELECT COALESCE(SUM(sizeBytes - outputBytes), 0) FROM items " +
            "WHERE outputBytes IS NOT NULL AND outputBytes < sizeBytes AND updatedAt >= :fromMs"
    )
    suspend fun savedBytesSince(fromMs: Long): Long

    /** Skip reasons with counts, most common first. */
    @Query(
        "SELECT skipReason AS state, COUNT(*) AS cnt FROM items " +
            "WHERE state = 'SKIP' AND skipReason IS NOT NULL " +
            "GROUP BY skipReason ORDER BY cnt DESC LIMIT 6"
    )
    suspend fun skipReasons(): List<StateCount>

    @Query("SELECT * FROM items WHERE state = 'GONE'")
    suspend fun gone(): List<ItemRow>

    @Query("SELECT COUNT(*) FROM items WHERE state = :state")
    suspend fun countByState(state: String): Int

    @Query("SELECT state, COUNT(*) AS cnt FROM items GROUP BY state")
    fun stateCountsFlow(): Flow<List<StateCount>>

    @Query(
        "SELECT COUNT(*) FROM items " +
            "WHERE evidence IN ('CONFIRMED_EXACT', 'CONFIRMED_PACED', 'CONFIRMED')"
    )
    fun confirmedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM items WHERE evidence = 'VERIFIED'")
    fun verifiedCountFlow(): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(sizeBytes - outputBytes), 0) FROM items " +
            "WHERE outputBytes IS NOT NULL AND outputBytes < sizeBytes"
    )
    fun savedBytesFlow(): Flow<Long>

    @Query("SELECT COUNT(*) FROM items WHERE outputBytes IS NOT NULL")
    fun processedCountFlow(): Flow<Int>

    @Query(
        "SELECT * FROM items WHERE displayName LIKE '%' || :q || '%' " +
            "ORDER BY captureAt DESC LIMIT :limit"
    )
    fun searchFlow(q: String, limit: Int): Flow<List<ItemRow>>

    /**
     * Originals whose copy the cloud itself collected. The copy vanished from
     * the upload folder while the cloud app was transmitting its bytes, which
     * is as direct as the evidence gets, so no waiting period applies.
     *
     * 'CONFIRMED' is the name older rows used for exactly this finding.
     */
    @Query(
        "SELECT * FROM items WHERE evidence IN ('CONFIRMED_EXACT', 'CONFIRMED') " +
            "AND originalMissing = 0 AND state IN ('RELEASED', 'GONE', 'DONE')"
    )
    suspend fun freeableConfirmed(): List<ItemRow>

    /**
     * Originals whose copy went out alone and matched the bytes sent. That is
     * an inference rather than an observation, so it has to settle first.
     */
    @Query(
        "SELECT * FROM items WHERE evidence = 'CONFIRMED_PACED' AND originalMissing = 0 " +
            "AND state IN ('RELEASED', 'GONE', 'DONE') " +
            "AND releasedAt IS NOT NULL AND releasedAt <= :maxReleasedAt"
    )
    suspend fun freeablePaced(maxReleasedAt: Long): List<ItemRow>

    @Query(
        "SELECT * FROM items WHERE evidence = 'VERIFIED' AND originalMissing = 0 " +
            "AND state IN ('RELEASED', 'GONE', 'DONE') " +
            "AND releasedAt IS NOT NULL AND releasedAt <= :maxReleasedAt"
    )
    suspend fun freeableVerified(maxReleasedAt: Long): List<ItemRow>

    @Query("SELECT * FROM items")
    suspend fun all(): List<ItemRow>

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(outputBytes), 0) FROM items WHERE state = 'RELEASED'")
    suspend fun releasedBytes(): Long

    /**
     * The headline on the Storage screen, which must agree with what Reclaim
     * space actually lists - otherwise the entry point to that screen stays
     * hidden while there are files behind it.
     */
    @Query(
        "SELECT COALESCE(SUM(sizeBytes), 0) FROM items " +
            "WHERE originalMissing = 0 AND state != 'FREED' AND (" +
            "evidence IN ('CONFIRMED_EXACT', 'CONFIRMED') " +
            "OR (evidence = 'CONFIRMED_PACED' AND releasedAt IS NOT NULL " +
            "AND releasedAt <= :settledBefore) " +
            "OR (:includeVerified AND evidence = 'VERIFIED' AND releasedAt IS NOT NULL " +
            "AND releasedAt <= :settledBefore))"
    )
    fun reclaimableBytesFlow(settledBefore: Long, includeVerified: Boolean): Flow<Long>

    @Query(
        "SELECT sizeBytes, outputBytes, durationMs FROM items " +
            "WHERE outputBytes IS NOT NULL AND isVideo = 0 AND presetUsed = :preset " +
            "ORDER BY updatedAt DESC LIMIT 500"
    )
    suspend fun photoRatioSamples(preset: String): List<RatioSample>

    @Query(
        "SELECT sizeBytes, outputBytes, durationMs FROM items " +
            "WHERE outputBytes IS NOT NULL AND isVideo = 1 AND presetUsed = :preset " +
            "AND codecUsed = :codec ORDER BY updatedAt DESC LIMIT 500"
    )
    suspend fun videoRatioSamples(preset: String, codec: String): List<RatioSample>
}

@Dao
interface BatchDao {

    @Insert
    suspend fun insert(batch: BatchRow): Long

    @Query("SELECT * FROM batches WHERE verifiedAt IS NULL")
    suspend fun unverified(): List<BatchRow>

    @Query("UPDATE batches SET verifiedAt = :at WHERE id = :id")
    suspend fun markVerified(id: Long, at: Long)

    @Query("UPDATE batches SET totalBytes = :bytes WHERE id = :id")
    suspend fun setTotalBytes(id: Long, bytes: Long)

    /** Drops a batch in which nothing was actually released. */
    @Query("DELETE FROM batches WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM batches WHERE releasedAt >= :fromMs")
    suspend fun bytesSince(fromMs: Long): Long

    @Query("SELECT MAX(releasedAt) FROM batches")
    suspend fun lastReleaseAt(): Long?

    @Query("SELECT * FROM batches")
    suspend fun all(): List<BatchRow>

    @Query("SELECT * FROM batches WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): BatchRow?
}

@Dao
interface DayBudgetDao {

    @Query("SELECT * FROM day_budget WHERE day = :day LIMIT 1")
    suspend fun byDay(day: String): DayBudgetRow?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: DayBudgetRow): Long

    @Query("UPDATE day_budget SET videoEncodeMs = videoEncodeMs + :ms WHERE day = :day")
    suspend fun addEncodeMs(day: String, ms: Long)

    @Query("UPDATE day_budget SET photosOnBattery = photosOnBattery + :n WHERE day = :day")
    suspend fun addPhotos(day: String, n: Int)

    /** Yesterday and older are useless; keep the table at one row. */
    @Query("DELETE FROM day_budget WHERE day <> :day")
    suspend fun pruneOlderThan(day: String)
}

@Dao
interface LedgerDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: LedgerRow): Long

    @Query("SELECT * FROM ledger WHERE outputSha256 = :sha LIMIT 1")
    suspend fun bySha(sha: String): LedgerRow?

    @Query("SELECT * FROM ledger WHERE fingerprint = :fp LIMIT 1")
    suspend fun byFingerprint(fp: String): LedgerRow?

    @Query("SELECT COUNT(*) FROM ledger WHERE outputSha256 = :sha OR fingerprint = :fp")
    suspend fun countFor(sha: String, fp: String): Int

    @Query("SELECT * FROM ledger")
    suspend fun all(): List<LedgerRow>
}

@Dao
interface ActivityDao {

    @Insert
    suspend fun insert(row: ActivityRow): Long

    @Query("SELECT * FROM activity ORDER BY atMs DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<ActivityRow>>

    @Query("SELECT * FROM activity WHERE kind IN (:kinds) ORDER BY atMs DESC LIMIT :limit")
    fun byKindsFlow(kinds: Collection<String>, limit: Int): Flow<List<ActivityRow>>

    @Query("SELECT * FROM activity ORDER BY atMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActivityRow>

    @Query("SELECT COUNT(*) FROM activity WHERE atMs > :since")
    fun unreadCountFlow(since: Long): Flow<Int>

    /** Retention: 30 days or 500 rows, whichever bites first. */
    @Query("DELETE FROM activity WHERE atMs < :before")
    suspend fun pruneOlderThan(before: Long)

    @Query(
        "DELETE FROM activity WHERE id NOT IN " +
            "(SELECT id FROM activity ORDER BY atMs DESC LIMIT :keep)"
    )
    suspend fun pruneBeyond(keep: Int)

    @Query("DELETE FROM activity")
    suspend fun clear()
}

@Dao
interface CloudCapabilityDao {

    @Query("SELECT * FROM cloud_capability WHERE cloudId = :id LIMIT 1")
    suspend fun byId(id: String): CloudCapabilityRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: CloudCapabilityRow)

    @Query("SELECT * FROM cloud_capability")
    suspend fun all(): List<CloudCapabilityRow>
}

@Database(
    entities = [
        ItemRow::class, BatchRow::class, DayBudgetRow::class,
        LedgerRow::class, ActivityRow::class, CloudCapabilityRow::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {

    abstract fun items(): ItemDao
    abstract fun batches(): BatchDao
    abstract fun dayBudget(): DayBudgetDao
    abstract fun ledger(): LedgerDao
    abstract fun activity(): ActivityDao
    abstract fun capabilities(): CloudCapabilityDao

    companion object {
        /** v2 adds the daily on-battery budget table (13.G). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `day_budget` (" +
                        "`day` TEXT NOT NULL, " +
                        "`videoEncodeMs` INTEGER NOT NULL, " +
                        "`photosOnBattery` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`day`))"
                )
            }
        }

        /**
         * v3 adds the upload ledger, the activity log, learned cloud
         * capabilities, and the three item columns paced release needs.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ledger` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`outputSha256` TEXT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`outputBytes` INTEGER NOT NULL, " +
                        "`evidence` TEXT NOT NULL, " +
                        "`confirmedAt` INTEGER NOT NULL)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ledger_outputSha256` " +
                        "ON `ledger` (`outputSha256`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_fingerprint` " +
                        "ON `ledger` (`fingerprint`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activity` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`atMs` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`detail` TEXT, " +
                        "`count` INTEGER NOT NULL, " +
                        "`bytes` INTEGER NOT NULL, " +
                        "`filterState` TEXT)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_activity_atMs` ON `activity` (`atMs`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_activity_kind` ON `activity` (`kind`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cloud_capability` (" +
                        "`cloudId` TEXT NOT NULL PRIMARY KEY, " +
                        "`hasFreeUpSpace` INTEGER NOT NULL, " +
                        "`hasHashDedupe` INTEGER NOT NULL, " +
                        "`packageName` TEXT, " +
                        "`lastSeenVersionCode` INTEGER NOT NULL, " +
                        "`learnedFreeUp` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                connection.execSQL("ALTER TABLE `items` ADD COLUMN `txObserved` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE `items` ADD COLUMN `encoderName` TEXT")
                connection.execSQL("ALTER TABLE `items` ADD COLUMN `resendCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Every migration, in order. Public so the instrumented suite can
         * open a database built at an older version and prove the upgrade
         * path works: a wrong ALTER here does not fail the build, it crashes
         * the app on the first launch after an update.
         */
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "cloudsaver.db"
            ).addMigrations(*MIGRATIONS).build().also { instance = it }
        }
    }
}
