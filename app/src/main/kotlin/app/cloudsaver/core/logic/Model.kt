package app.cloudsaver.core.logic

/** Item lifecycle. GONE carries a [GoneReason]; a maintain pass promotes GONE to DONE. */
enum class ItemState { NEW, STAGED, RELEASED, GONE, DONE, SKIP, FREED, UNKNOWN }

enum class GoneReason { CONFIRMED, APP_DELETED, USER_DELETED }

/**
 * How well the app knows a released copy reached the cloud. Ordinal order is
 * strength order, so `maxOf` picks the better of two findings.
 *
 *  - AGED            time alone; no network evidence at all
 *  - VERIFIED        a whole batch's bytes were transmitted
 *  - CONFIRMED_PACED the copy went out alone and the transmitted bytes match
 *                    its size before anything else was sent
 *  - CONFIRMED_EXACT the copy vanished from the upload folder, the app did
 *                    not remove it, and its bytes were transmitted
 */
enum class Evidence {
    NONE, AGED, VERIFIED, CONFIRMED_PACED, CONFIRMED_EXACT;

    /** True for the two per-file grades that may offer an original for reclaim. */
    val isPerFile: Boolean get() = this == CONFIRMED_PACED || this == CONFIRMED_EXACT

    companion object {
        /**
         * Rows written before the two grades existed say "CONFIRMED"; those
         * came from the disappearance check, which is now CONFIRMED_EXACT.
         */
        fun parse(name: String?): Evidence = when (name) {
            null, "" -> NONE
            "CONFIRMED" -> CONFIRMED_EXACT
            else -> entries.firstOrNull { it.name == name } ?: NONE
        }
    }
}

enum class BackupScope { ALL, PHOTOS, VIDEOS }

enum class OutputMode { SINGLE, SEPARATE }

/** Scheduling mode (13.G). SMART is the default. */
enum class SpeedMode { SMART, CHARGING_ONLY, FAST }

enum class Preset { STORAGE_SAVER, BALANCED, MAX_SAVER }

enum class VideoCodec { H264, HEVC }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Which public output folder a released copy lives in. */
enum class OutFolder { SINGLE, PHOTOS, VIDEOS }

object Defaults {
    const val DAILY_CAP_MB = 250
    val DAILY_CAP_CHOICES_MB = listOf(250, 500, 1024, 2048, -1) // -1 = unlimited

    const val MAX_EXTRA_MB = 1536
    val MAX_EXTRA_CHOICES_MB = listOf(1536, 3072, 5120, -1) // -1 = unlimited

    const val MIN_FREE_MB = 1536
    val MIN_FREE_CHOICES_MB = listOf(1536, 3072, 5120)

    const val KEEP_MIN_DAYS = 5
    const val AGED_DAYS = 10
    const val MAX_RUN_MIN = 40
    const val BATTERY_MAX_TEMP_TENTHS_C = 420 // 42.0 °C

    // Scheduling (13.G).
    const val SMART_BATTERY_FLOOR = 30
    const val FAST_BATTERY_FLOOR = 25
    /** Charging floor for video encodes, and the hard floor for "Run now". */
    const val CHARGING_BATTERY_FLOOR = 15
    const val SMART_BUDGET_MS = 30 * 60_000L // 30 min of on-battery encoding
    const val FAST_BUDGET_MS = 60 * 60_000L
    const val SMART_SCREEN_OFF_WAIT_MS = 2 * 60_000L
    const val PHOTO_CAP_ON_BATTERY = 200

    const val FGS_BUDGET_MS = 19_800_000L // 5.5 h per rolling 24 h
    const val FGS_WINDOW_MS = 86_400_000L

    const val CONFIRM_WINDOW_MS = 86_400_000L // "Confirm uploads" pressed within last 24 h

    const val SAFETY_TX_MIN_BYTES = 5L * 1024 * 1024 // below this over 3 days => "TX ~ 0"
    const val SAFETY_TX_DAYS = 3

    const val STAGE_CAP_FACTOR = 2 // stage dir may hold at most 2 x DAILY_CAP

    const val MB = 1024L * 1024L

    // Pictures (never DCIM): keeps clouds with DCIM auto-backup from grabbing originals.
    const val OUTPUT_DIR = "Pictures/CloudSaver"
    const val OUTPUT_DIR_PHOTOS = "Pictures/CloudSaver/Photos"
    const val OUTPUT_DIR_VIDEOS = "Pictures/CloudSaver/Videos"
    /**
     * Where automatic safety snapshots go, best first. All hidden: the app
     * never puts a visible file anywhere the user browses unless they tap
     * Export. Each entry is a relative directory; the last one is the
     * visible last resort, used only if every hidden option is refused.
     *
     *  1. beside the output copies, so the state travels with the folder,
     *  2. a hidden dot-folder in Documents, so it survives the output folder
     *     being deleted,
     *  3. a hidden dot-FILE, for MediaProvider builds that reject
     *     dot-directories,
     *  4. a visible file, explained in the FAQ, if nothing hidden works.
     */
    const val SNAPSHOT_DIR_OUTPUT = "Pictures/CloudSaver/.cloudsaver"
    const val SNAPSHOT_DIR_DOCUMENTS = "Documents/.cloudsaver"
    const val SNAPSHOT_DIR_DOTFILE = "Pictures/CloudSaver"
    const val SNAPSHOT_DIR_VISIBLE = "Documents/CloudSaver"

    const val SNAPSHOT_NAME = "state.json"
    const val SNAPSHOT_NAME_DOTFILE = ".cloudsaver.json"
    const val SNAPSHOT_NAME_VISIBLE = "backup.json"

    /** Snapshot targets in the order they are tried, as (directory, filename). */
    val SNAPSHOT_TARGETS: List<Pair<String, String>> = listOf(
        SNAPSHOT_DIR_OUTPUT to SNAPSHOT_NAME,
        SNAPSHOT_DIR_DOCUMENTS to SNAPSHOT_NAME,
        SNAPSHOT_DIR_DOTFILE to SNAPSHOT_NAME_DOTFILE,
        SNAPSHOT_DIR_VISIBLE to SNAPSHOT_NAME_VISIBLE
    )

    /**
     * Where older builds wrote the automatic snapshot. Read-only: recovery
     * still looks here so an upgrade keeps its state, but nothing is written
     * here again and the file is removed once it has been read.
     */
    val LEGACY_SNAPSHOT_TARGETS: List<Pair<String, String>> = listOf(
        SNAPSHOT_DIR_VISIBLE to SNAPSHOT_NAME
    )

    /** True for a snapshot target Android hides from the gallery and Files. */
    fun isHiddenSnapshotTarget(dir: String, name: String): Boolean =
        name.startsWith(".") || dir.split('/').any { it.startsWith(".") }

    fun outFolderRelPath(folder: OutFolder): String = when (folder) {
        OutFolder.SINGLE -> OUTPUT_DIR
        OutFolder.PHOTOS -> OUTPUT_DIR_PHOTOS
        OutFolder.VIDEOS -> OUTPUT_DIR_VIDEOS
    }

    /**
     * SQL pattern for "inside the output folder". The trailing slash matters:
     * without it, an unrelated user folder such as Pictures/CloudSaverBackup
     * would match too, and the app would treat those files as its own.
     */
    const val OUTPUT_DIR_LIKE = "$OUTPUT_DIR/%"

    /** True only for the output folder itself or something inside it. */
    fun isOutputPath(relativePath: String?): Boolean {
        if (relativePath.isNullOrEmpty()) return false
        val path = relativePath.trimEnd('/')
        return path == OUTPUT_DIR || path.startsWith("$OUTPUT_DIR/")
    }
}
