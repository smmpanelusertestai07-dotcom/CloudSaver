package app.cloudsaver.data.prefs

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.cloudsaver.core.logic.BackupScope
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.OutputMode
import app.cloudsaver.core.logic.Preset
import app.cloudsaver.core.logic.SpeedMode
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.core.logic.VideoCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "options")

/** All user options (section 6) + small persisted runtime state. */
data class Options(
    val scope: BackupScope = BackupScope.ALL,
    val excludedBuckets: Set<String> = emptySet(),
    val outputMode: OutputMode = OutputMode.SINGLE,
    val cloudSingle: String = "ente",
    val cloudPhotos: String = "ente",
    val cloudVideos: String = "ente",
    val speed: SpeedMode = SpeedMode.SMART,
    val dailyCapMb: Int = Defaults.DAILY_CAP_MB,
    val minFreeMb: Int = Defaults.MIN_FREE_MB,
    val maxExtraMb: Int = Defaults.MAX_EXTRA_MB,
    val preset: Preset = Preset.STORAGE_SAVER,
    val codec: VideoCodec = VideoCodec.H264,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** MediaStore volume for stage + output; "" = internal (primary). */
    val storageVolume: String = "",
    val appLock: Boolean = false,
    val warningsNotif: Boolean = true,
    /** A legacy placeholder image was cleaned up; show the notice once. */
    val placeholderRemoved: Boolean = false,
    /** The double-backup warning was read during setup (Z5.2). */
    val doubleBackupAck: Boolean = false,
    /** Old cloud app id after a switch; "" once the sheet was shown (Z10.1). */
    val cloudSwitchFrom: String = "",
    /** When the very first copy entered the upload folder (Z10.6). */
    val firstReleaseAt: Long = 0,
    /** "": chain unproven. "SUCCESS"/"STALLED": card pending. "DONE": dismissed. */
    val firstChainState: String = "",
    val showFreeUp: Boolean = false,
    val freeUpAllowVerified30: Boolean = false,
    val reprocessUnknown: Boolean = false,
    val pauseAll: Boolean = false,
    // runtime / bookkeeping
    val onboardingDone: Boolean = false,
    val onboardingStep: Int = 0,
    val confirmFlowStartedAt: Long = 0,
    val lastConfirmCount: Int = -1,
    val lastRunAt: Long = 0,
    val lastRunNote: String = "",
    val lastSnapshotDay: String = "",
    val fgsSessions: String = "",
    /** Last time the app observed the screen ON (13.G screen-off wait). */
    val lastInteractiveAt: Long = 0,
    /** Latest RunDecider.Wait name, shown on Home in plain English. */
    val waitReason: String = "NONE",
    val agedWarned: Boolean = false,
    val safetyPauseWarnedAt: Long = 0,
    val volumeWarnedAt: Long = 0,
    val oldFilesCleaned: Boolean = false,
    val copiesReattached: Boolean = false,
    /**
     * Consecutive per-file confirmations with no failure in between.
     *
     * The release pacing ladder climbs on this: proving the accounting works
     * on this phone is what earns the right to stop holding files back.
     */
    val cleanConfirmStreak: Int = 0,
    /** Files released since the last one sent alone as a proof sample. */
    val releasedSinceSample: Int = 0,
    /** A confirmation failed recently, so samples are taken twice as often. */
    val recentPacingFailure: Boolean = false,
    val cloudDetected: Boolean = false,
    /** Files seen in the upload folder last pass, so a shrink is detectable. */
    val lastOutputCount: Int = 0,
    /** Active CloudWatchdog.Problem name, or "" when the cloud looks healthy. */
    val cloudProblem: String = "",
    /** Alerts are silenced until this instant ("Mute for 7 days"). */
    val alertsMutedUntil: Long = 0,
    /** Which alert was posted last, and when - the 24 h de-duplication pair. */
    val lastAlertKey: String = "",
    val lastAlertAt: Long = 0,
    /** Newest Activity row the user has actually looked at. */
    val activitySeenAt: Long = 0,
    /** Days carried over when a day's upload allowance went unused. */
    val catchUpBytes: Long = 0,
    val catchUpDay: String = "",
    /** The one-time "I understand" tick before the first reclaim batch. */
    val reclaimUnderstood: Boolean = false,
    /** "Tell me when I can free more than X GB"; 0 = off. */
    val reclaimReminderGb: Int = 0
) {
    val dailyCapBytes: Long get() = if (dailyCapMb < 0) -1 else dailyCapMb * Defaults.MB
    val minFreeBytes: Long get() = minFreeMb * Defaults.MB
    val maxExtraBytes: Long get() = if (maxExtraMb < 0) -1 else maxExtraMb * Defaults.MB
}

class OptionsRepo(private val context: Context) {

    object K {
        val SCOPE = stringPreferencesKey("scope")
        val EXCLUDED_BUCKETS = stringSetPreferencesKey("excludedBuckets")
        val OUTPUT_MODE = stringPreferencesKey("outputMode")
        val CLOUD_SINGLE = stringPreferencesKey("cloudSingle")
        val CLOUD_PHOTOS = stringPreferencesKey("cloudPhotos")
        val CLOUD_VIDEOS = stringPreferencesKey("cloudVideos")
        val SPEED = stringPreferencesKey("speed")
        val DAILY_CAP_MB = intPreferencesKey("dailyCapMb")
        val MIN_FREE_MB = intPreferencesKey("minFreeMb")
        val MAX_EXTRA_MB = intPreferencesKey("maxExtraMb")
        val PRESET = stringPreferencesKey("preset")
        val CODEC = stringPreferencesKey("codec")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamicColor")
        val STORAGE_VOLUME = stringPreferencesKey("storageVolume")
        val APP_LOCK = booleanPreferencesKey("appLock")
        val WARNINGS_NOTIF = booleanPreferencesKey("warningsNotif")
        val SHOW_FREE_UP = booleanPreferencesKey("showFreeUp")
        val FREE_UP_VERIFIED30 = booleanPreferencesKey("freeUpAllowVerified30")
        val REPROCESS_UNKNOWN = booleanPreferencesKey("reprocessUnknown")
        val PAUSE_ALL = booleanPreferencesKey("pauseAll")
        val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
        val PLACEHOLDER_REMOVED = booleanPreferencesKey("placeholderRemoved")
        val DOUBLE_BACKUP_ACK = booleanPreferencesKey("doubleBackupAck")
        val CLOUD_SWITCH_FROM = stringPreferencesKey("cloudSwitchFrom")
        val FIRST_RELEASE_AT = longPreferencesKey("firstReleaseAt")
        val FIRST_CHAIN_STATE = stringPreferencesKey("firstChainState")
        val ONBOARDING_STEP = intPreferencesKey("onboardingStep")
        val CONFIRM_STARTED_AT = longPreferencesKey("confirmFlowStartedAt")
        val LAST_CONFIRM_COUNT = intPreferencesKey("lastConfirmCount")
        val LAST_RUN_AT = longPreferencesKey("lastRunAt")
        val LAST_RUN_NOTE = stringPreferencesKey("lastRunNote")
        val LAST_SNAPSHOT_DAY = stringPreferencesKey("lastSnapshotDay")
        val FGS_SESSIONS = stringPreferencesKey("fgsSessions")
        val LAST_INTERACTIVE_AT = longPreferencesKey("lastInteractiveAt")
        val WAIT_REASON = stringPreferencesKey("waitReason")
        val AGED_WARNED = booleanPreferencesKey("agedWarned")
        val SAFETY_WARNED_AT = longPreferencesKey("safetyPauseWarnedAt")
        val VOLUME_WARNED_AT = longPreferencesKey("volumeWarnedAt")
        val OLD_FILES_CLEANED = booleanPreferencesKey("oldFilesCleaned")
        val COPIES_REATTACHED = booleanPreferencesKey("copiesReattached")
        val CLEAN_STREAK = intPreferencesKey("cleanConfirmStreak")
        val RELEASED_SINCE_SAMPLE = intPreferencesKey("releasedSinceSample")
        val RECENT_PACING_FAILURE = booleanPreferencesKey("recentPacingFailure")
        val CLOUD_DETECTED = booleanPreferencesKey("cloudDetected")
        val LAST_OUTPUT_COUNT = intPreferencesKey("lastOutputCount")
        val CLOUD_PROBLEM = stringPreferencesKey("cloudProblem")
        val ALERTS_MUTED_UNTIL = longPreferencesKey("alertsMutedUntil")
        val LAST_ALERT_KEY = stringPreferencesKey("lastAlertKey")
        val LAST_ALERT_AT = longPreferencesKey("lastAlertAt")
        val ACTIVITY_SEEN_AT = longPreferencesKey("activitySeenAt")
        val CATCH_UP_BYTES = longPreferencesKey("catchUpBytes")
        val CATCH_UP_DAY = stringPreferencesKey("catchUpDay")
        val RECLAIM_UNDERSTOOD = booleanPreferencesKey("reclaimUnderstood")
        val RECLAIM_REMINDER_GB = intPreferencesKey("reclaimReminderGb")
    }

    val flow: Flow<Options> = context.dataStore.data.map { p ->
        Options(
            scope = enumOr(p[K.SCOPE], BackupScope.ALL),
            excludedBuckets = p[K.EXCLUDED_BUCKETS] ?: emptySet(),
            outputMode = enumOr(p[K.OUTPUT_MODE], OutputMode.SINGLE),
            cloudSingle = p[K.CLOUD_SINGLE] ?: "ente",
            cloudPhotos = p[K.CLOUD_PHOTOS] ?: "ente",
            cloudVideos = p[K.CLOUD_VIDEOS] ?: "ente",
            speed = enumOr(p[K.SPEED], SpeedMode.SMART),
            // Snapped, so a limit stored by an older build still lands on one
            // of the chips instead of leaving the control looking unset.
            dailyCapMb = Defaults.snapToChoice(
                p[K.DAILY_CAP_MB] ?: Defaults.DAILY_CAP_MB, Defaults.DAILY_CAP_CHOICES_MB
            ),
            minFreeMb = Defaults.snapToChoice(
                p[K.MIN_FREE_MB] ?: Defaults.MIN_FREE_MB, Defaults.MIN_FREE_CHOICES_MB
            ),
            maxExtraMb = Defaults.snapToChoice(
                p[K.MAX_EXTRA_MB] ?: Defaults.MAX_EXTRA_MB, Defaults.MAX_EXTRA_CHOICES_MB
            ),
            preset = enumOr(p[K.PRESET], Preset.STORAGE_SAVER),
            codec = enumOr(p[K.CODEC], VideoCodec.H264),
            theme = enumOr(p[K.THEME], ThemeMode.SYSTEM),
            dynamicColor = p[K.DYNAMIC_COLOR] ?: false,
            storageVolume = p[K.STORAGE_VOLUME] ?: "",
            appLock = p[K.APP_LOCK] ?: false,
            warningsNotif = p[K.WARNINGS_NOTIF] ?: true,
            placeholderRemoved = p[K.PLACEHOLDER_REMOVED] ?: false,
            doubleBackupAck = p[K.DOUBLE_BACKUP_ACK] ?: false,
            cloudSwitchFrom = p[K.CLOUD_SWITCH_FROM] ?: "",
            firstReleaseAt = p[K.FIRST_RELEASE_AT] ?: 0,
            firstChainState = p[K.FIRST_CHAIN_STATE] ?: "",
            showFreeUp = p[K.SHOW_FREE_UP] ?: false,
            freeUpAllowVerified30 = p[K.FREE_UP_VERIFIED30] ?: false,
            reprocessUnknown = p[K.REPROCESS_UNKNOWN] ?: false,
            pauseAll = p[K.PAUSE_ALL] ?: false,
            onboardingDone = p[K.ONBOARDING_DONE] ?: false,
            onboardingStep = p[K.ONBOARDING_STEP] ?: 0,
            confirmFlowStartedAt = p[K.CONFIRM_STARTED_AT] ?: 0,
            lastConfirmCount = p[K.LAST_CONFIRM_COUNT] ?: -1,
            lastRunAt = p[K.LAST_RUN_AT] ?: 0,
            lastRunNote = p[K.LAST_RUN_NOTE] ?: "",
            lastSnapshotDay = p[K.LAST_SNAPSHOT_DAY] ?: "",
            fgsSessions = p[K.FGS_SESSIONS] ?: "",
            lastInteractiveAt = p[K.LAST_INTERACTIVE_AT] ?: 0,
            waitReason = p[K.WAIT_REASON] ?: "NONE",
            agedWarned = p[K.AGED_WARNED] ?: false,
            safetyPauseWarnedAt = p[K.SAFETY_WARNED_AT] ?: 0,
            volumeWarnedAt = p[K.VOLUME_WARNED_AT] ?: 0,
            oldFilesCleaned = p[K.OLD_FILES_CLEANED] ?: false,
            copiesReattached = p[K.COPIES_REATTACHED] ?: false,
            cleanConfirmStreak = p[K.CLEAN_STREAK] ?: 0,
            releasedSinceSample = p[K.RELEASED_SINCE_SAMPLE] ?: 0,
            recentPacingFailure = p[K.RECENT_PACING_FAILURE] ?: false,
            cloudDetected = p[K.CLOUD_DETECTED] ?: false,
            lastOutputCount = p[K.LAST_OUTPUT_COUNT] ?: 0,
            cloudProblem = p[K.CLOUD_PROBLEM] ?: "",
            alertsMutedUntil = p[K.ALERTS_MUTED_UNTIL] ?: 0,
            lastAlertKey = p[K.LAST_ALERT_KEY] ?: "",
            lastAlertAt = p[K.LAST_ALERT_AT] ?: 0,
            activitySeenAt = p[K.ACTIVITY_SEEN_AT] ?: 0,
            catchUpBytes = p[K.CATCH_UP_BYTES] ?: 0,
            catchUpDay = p[K.CATCH_UP_DAY] ?: "",
            reclaimUnderstood = p[K.RECLAIM_UNDERSTOOD] ?: false,
            reclaimReminderGb = p[K.RECLAIM_REMINDER_GB] ?: 0
        )
    }

    suspend fun current(): Options = flow.first()

    suspend fun setString(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setLong(key: androidx.datastore.preferences.core.Preferences.Key<Long>, value: Long) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setBool(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setStringSet(
        key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>,
        value: Set<String>
    ) {
        context.dataStore.edit { it[key] = value }
    }

    /** Options export for the snapshot (user-visible options only). */
    suspend fun exportMap(): Map<String, String> {
        val o = current()
        return mapOf(
            "scope" to o.scope.name,
            "excludedBuckets" to o.excludedBuckets.joinToString("|"),
            "outputMode" to o.outputMode.name,
            "cloudSingle" to o.cloudSingle,
            "cloudPhotos" to o.cloudPhotos,
            "cloudVideos" to o.cloudVideos,
            "speed" to o.speed.name,
            "dailyCapMb" to o.dailyCapMb.toString(),
            "minFreeMb" to o.minFreeMb.toString(),
            "maxExtraMb" to o.maxExtraMb.toString(),
            "preset" to o.preset.name,
            "codec" to o.codec.name,
            "theme" to o.theme.name,
            "dynamicColor" to o.dynamicColor.toString(),
            "storageVolume" to o.storageVolume,
            "warningsNotif" to o.warningsNotif.toString(),
            "showFreeUp" to o.showFreeUp.toString()
        )
    }

    /** Restores options from a snapshot map (import). Never touches files. */
    suspend fun importMap(map: Map<String, String>) {
        context.dataStore.edit { p ->
            map["scope"]?.let { p[K.SCOPE] = it }
            map["excludedBuckets"]?.let { s ->
                p[K.EXCLUDED_BUCKETS] = s.split('|').filter { it.isNotEmpty() }.toSet()
            }
            map["outputMode"]?.let { p[K.OUTPUT_MODE] = it }
            map["cloudSingle"]?.let { p[K.CLOUD_SINGLE] = it }
            map["cloudPhotos"]?.let { p[K.CLOUD_PHOTOS] = it }
            map["cloudVideos"]?.let { p[K.CLOUD_VIDEOS] = it }
            map["speed"]?.let { p[K.SPEED] = it }
            // Only values the UI itself offers. A hand-edited backup could
            // otherwise set an absurd minimum-free figure, which makes the
            // resource gate refuse to run for good.
            map["dailyCapMb"]?.toIntOrNull()
                ?.takeIf { it in Defaults.DAILY_CAP_CHOICES_MB }
                ?.let { p[K.DAILY_CAP_MB] = it }
            map["minFreeMb"]?.toIntOrNull()
                ?.takeIf { it in Defaults.MIN_FREE_CHOICES_MB }
                ?.let { p[K.MIN_FREE_MB] = it }
            map["maxExtraMb"]?.toIntOrNull()
                ?.takeIf { it in Defaults.MAX_EXTRA_CHOICES_MB }
                ?.let { p[K.MAX_EXTRA_MB] = it }
            map["preset"]?.let { p[K.PRESET] = it }
            map["codec"]?.let { p[K.CODEC] = it }
            map["theme"]?.let { p[K.THEME] = it }
            map["dynamicColor"]?.let { p[K.DYNAMIC_COLOR] = it.toBoolean() }
            map["storageVolume"]?.let { p[K.STORAGE_VOLUME] = it }
            map["warningsNotif"]?.let { p[K.WARNINGS_NOTIF] = it.toBoolean() }
            map["showFreeUp"]?.let { p[K.SHOW_FREE_UP] = it.toBoolean() }
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T {
        if (value.isNullOrEmpty()) return fallback
        return try {
            enumValueOf<T>(value)
        } catch (e: IllegalArgumentException) {
            fallback
        }
    }

    companion object {
        /**
         * Only ever constructed with the application context, which lives as
         * long as the process - there is no activity here to leak. DataStore
         * itself must be a singleton, so this cannot be scoped narrower.
         */
        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var instance: OptionsRepo? = null

        fun get(context: Context): OptionsRepo = instance ?: synchronized(this) {
            instance ?: OptionsRepo(context.applicationContext).also { instance = it }
        }
    }
}
