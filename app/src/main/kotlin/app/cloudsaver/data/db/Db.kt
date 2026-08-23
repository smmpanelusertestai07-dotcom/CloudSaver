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

    @Query(
        "SELECT * FROM items WHERE state = 'NEW' AND originalMissing = 0 " +
            "ORDER BY captureAt DESC LIMIT :limit"
    )
    suspend fun newestNew(limit: Int): List<ItemRow>

    @Query("SELECT * FROM items WHERE state = 'STAGED'")
    suspend fun staged(): List<ItemRow>

    @Query("SELECT * FROM items WHERE state = 'RELEASED'")
    suspend fun released(): List<ItemRow>

    @Query("SELECT * FROM items WHERE state = 'GONE'")
    suspend fun gone(): List<ItemRow>

    @Query("SELECT COUNT(*) FROM items WHERE state = :state")
    suspend fun countByState(state: String): Int

    @Query("SELECT state, COUNT(*) AS cnt FROM items GROUP BY state")
    fun stateCountsFlow(): Flow<List<StateCount>>

    @Query("SELECT COUNT(*) FROM items WHERE evidence = 'CONFIRMED'")
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

    @Query(
        "SELECT * FROM items WHERE evidence = 'CONFIRMED' AND originalMissing = 0 " +
            "AND state IN ('RELEASED', 'GONE', 'DONE')"
    )
    suspend fun freeableConfirmed(): List<ItemRow>

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

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM items WHERE evidence = 'CONFIRMED' AND originalMissing = 0 AND state != 'FREED'")
    fun reclaimableBytesFlow(): Flow<Long>

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

@Database(
    entities = [ItemRow::class, BatchRow::class, DayBudgetRow::class],
    version = 2,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {

    abstract fun items(): ItemDao
    abstract fun batches(): BatchDao
    abstract fun dayBudget(): DayBudgetDao

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

        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDb::class.java,
                "cloudsaver.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
