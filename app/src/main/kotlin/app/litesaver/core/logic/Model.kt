package app.litesaver.core.logic

/** Item lifecycle. GONE carries a [GoneReason]; a maintain pass promotes GONE to DONE. */
enum class ItemState { NEW, STAGED, RELEASED, GONE, DONE, SKIP, FREED, UNKNOWN }

enum class GoneReason { CONFIRMED, APP_DELETED, USER_DELETED }

/** Upload evidence for a released copy. Ordinal order == strength order. */
enum class Evidence { NONE, AGED, VERIFIED, CONFIRMED }

enum class BackupScope { ALL, PHOTOS, VIDEOS }

enum class OutputMode { SINGLE, SEPARATE }

enum class Speed { CHARGING_ONLY, ANYTIME, INSTANT }

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
    const val MIN_BATTERY_CHARGING = 30
    const val MIN_BATTERY_ANYTIME = 40
    const val BATTERY_MAX_TEMP_TENTHS_C = 420 // 42.0 °C

    const val FGS_BUDGET_MS = 19_800_000L // 5.5 h per rolling 24 h
    const val FGS_WINDOW_MS = 86_400_000L

    const val CONFIRM_WINDOW_MS = 86_400_000L // "Confirm uploads" pressed within last 24 h

    const val SAFETY_TX_MIN_BYTES = 5L * 1024 * 1024 // below this over 3 days => "TX ~ 0"
    const val SAFETY_TX_DAYS = 3

    const val STAGE_CAP_FACTOR = 2 // stage dir may hold at most 2 x DAILY_CAP

    const val MB = 1024L * 1024L

    // Pictures (never DCIM): keeps clouds with DCIM auto-backup from grabbing originals.
    const val OUTPUT_DIR = "Pictures/LiteSaver"
    const val OUTPUT_DIR_PHOTOS = "Pictures/LiteSaver/Photos"
    const val OUTPUT_DIR_VIDEOS = "Pictures/LiteSaver/Videos"
    const val DOCS_DIR = "Documents/LiteSaver"
    const val SNAPSHOT_NAME = "state.json"

    fun outFolderRelPath(folder: OutFolder): String = when (folder) {
        OutFolder.SINGLE -> OUTPUT_DIR
        OutFolder.PHOTOS -> OUTPUT_DIR_PHOTOS
        OutFolder.VIDEOS -> OUTPUT_DIR_VIDEOS
    }
}
