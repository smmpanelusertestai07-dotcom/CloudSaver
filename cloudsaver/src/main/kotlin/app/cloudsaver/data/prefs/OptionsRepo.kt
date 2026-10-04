package app.cloudsaver.data.prefs

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.cloudsaver.core.logic.BackupScope
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.FolderName
import app.cloudsaver.core.logic.OutputLayout
import app.cloudsaver.core.logic.OutputMode
import app.cloudsaver.core.logic.OutputRoots
import app.cloudsaver.core.logic.Preset
import app.cloudsaver.core.logic.SpeedMode
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.core.logic.VideoCodec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(name = "options")

/** All user options (section 6) + small persisted runtime state. */
data class Options(
    val scope: BackupScope = BackupScope.ALL,
    val excludedBuckets: Set<String> = emptySet(),
    val outputMode: OutputMode = OutputMode.SINGLE,
    /**
     * The person's own folder for each kind of copy, as a relative path
     * ("Pictures/My photos"), or "" for the default. See [OutputLayout].
     */
    val folderSingle: String = "",
    val folderPhotos: String = "",
    val folderVideos: String = "",
    /**
     * Folders copies were released into before the person changed where they
     * go. Watched until no copy waits there any more, then dropped with a
     * one-time note that Ente can stop backing that folder up.
     */
    val pastOutputRoots: Set<String> = emptySet(),
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
    /** Files in the upload folder that CloudSaver did not create (DD2.1). */
    val foreignFiles: Int = 0,
    /**
     * A snapshot restore has already been attempted on this install.
     *
     * Restoring is a once-per-install event, not a state to sit in: the old
     * rule ("whenever the item table is empty") re-ran on every launch until
     * the first file was optimised, and each run re-imported the snapshot's
     * settings over choices the user had just made.
     */
    val restoreDone: Boolean = false,
    /** The one-time Kept-light-copies explanation was read (DD2.3). */
    val keptCardSeen: Boolean = false,
    /** "": chain unproven. "SUCCESS"/"STALLED": card pending. "DONE": dismissed. */
    val firstChainState: String = "",
    val showFreeUp: Boolean = false,
    val freeUpAllowVerified30: Boolean = false,
    /**
     * Where a light copy lands when an original is replaced.
     *
     * False (the default): its own album, Pictures/Light copies, which is
     * outside everything the scanner touches. True: the album the original
     * was in, carrying the original's date so the gallery timeline does not
     * move - the photo stays where it was and is simply smaller.
     */
    val keptInPlace: Boolean = false,
    val reprocessUnknown: Boolean = false,
    val pauseAll: Boolean = false,
    // runtime / bookkeeping
    val onboardingDone: Boolean = false,
    val onboardingStep: Int = 0,
    val confirmFlowStartedAt: Long = 0,
    val lastConfirmCount: Int = -1,
    val lastRunAt: Long = 0,
    /**
     * When a pass Android started last chose to wait on purpose - Battery
     * Saver on, no charger in charging-only mode, the day's allowance spent.
     * Separate from [lastRunAt] because that is the app keeping its word,
     * not the phone stopping it; told apart by nothing, two days of Battery
     * Saver became "the phone keeps stopping CloudSaver".
     */
    val lastWakeAt: Long = 0,
    val lastRunNote: String = "",
    /**
     * Why Android ended the last run, when Android ended it.
     *
     * Empty means the run finished on its own terms. Anything else is the
     * platform's own stop reason, and it is kept because a run the system cut
     * short still stamps [lastRunAt] - so every "is it running?" check stayed
     * green while the app completed a fraction of the work. Android 16 made
     * that the normal case rather than the rare one: a job running alongside
     * a foreground service is now inside the JobScheduler runtime quota, so
     * the app's own 40-minute window and its foreground-service ledger are no
     * longer the binding limit.
     */
    val lastStopReason: String = "",
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
     * Row ids of copies the maintenance pass chose to clear and could not.
     *
     * A copy adopted after a reinstall or a phone move belongs to the
     * install that made it, not this one, and Android refuses a silent
     * delete of somebody else's file. Those copies still counted against
     * the space the user allowed, so once enough of them piled up the
     * resource gate stopped every run - for good, because nothing could
     * ever remove them. They are listed here so Home can ask, once, through
     * Android's own dialog.
     */
    val copiesNeedConsent: Set<String> = emptySet(),
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
    /**
     * When each kind of alert was last posted - the 24 h de-duplication
     * record, one line per kind, as written by Notifications.
     *
     * It used to be a single key-and-time pair, which could only remember the
     * most recent alert. Two problems on the same day overwrote each other's
     * record, and each was then free to post again straight away: a phone
     * with a full cloud and a paused safety check could buzz twice an hour
     * all day, which is exactly what the once-a-day rule exists to stop.
     */
    val lastAlerts: String = "",
    /** Newest Activity row the user has actually looked at. */
    val activitySeenAt: Long = 0,
    /** Days carried over when a day's upload allowance went unused. */
    val catchUpBytes: Long = 0,
    val catchUpDay: String = "",
    /** The one-time "I understand" tick before the first reclaim batch. */
    val reclaimUnderstood: Boolean = false,
    /** "Tell me when I can free more than X GB"; 0 = off. */
    val reclaimReminderGb: Int = 0,
    /** How many "the phone stopped background work" alerts have been posted, ever. */
    val stallAlerts: Int = 0,
    /** When the last of them was posted. */
    val stallAlertAt: Long = 0
) {
    val dailyCapBytes: Long get() = if (dailyCapMb < 0) -1 else dailyCapMb * Defaults.MB
    val minFreeBytes: Long get() = minFreeMb * Defaults.MB
    val maxExtraBytes: Long get() = if (maxExtraMb < 0) -1 else maxExtraMb * Defaults.MB
    val layout: OutputLayout get() = OutputLayout(outputMode, folderSingle, folderPhotos, folderVideos)
}

class OptionsRepo(private val context: Context) {

    object K {
        val SCOPE = stringPreferencesKey("scope")
        val EXCLUDED_BUCKETS = stringSetPreferencesKey("excludedBuckets")
        val OUTPUT_MODE = stringPreferencesKey("outputMode")
        val FOLDER_SINGLE = stringPreferencesKey("folderSingle")
        val FOLDER_PHOTOS = stringPreferencesKey("folderPhotos")
        val FOLDER_VIDEOS = stringPreferencesKey("folderVideos")
        val PAST_OUTPUT_ROOTS = stringSetPreferencesKey("pastOutputRoots")
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
        val KEPT_IN_PLACE = booleanPreferencesKey("keptInPlace")
        val REPROCESS_UNKNOWN = booleanPreferencesKey("reprocessUnknown")
        val PAUSE_ALL = booleanPreferencesKey("pauseAll")
        val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
        val PLACEHOLDER_REMOVED = booleanPreferencesKey("placeholderRemoved")
        val DOUBLE_BACKUP_ACK = booleanPreferencesKey("doubleBackupAck")
        val CLOUD_SWITCH_FROM = stringPreferencesKey("cloudSwitchFrom")
        val FIRST_RELEASE_AT = longPreferencesKey("firstReleaseAt")
        val FOREIGN_FILES = intPreferencesKey("foreignFiles")
        val RESTORE_DONE = booleanPreferencesKey("restoreDone")
        val KEPT_CARD_SEEN = booleanPreferencesKey("keptCardSeen")
        val FIRST_CHAIN_STATE = stringPreferencesKey("firstChainState")
        val ONBOARDING_STEP = intPreferencesKey("onboardingStep")
        val CONFIRM_STARTED_AT = longPreferencesKey("confirmFlowStartedAt")
        val LAST_CONFIRM_COUNT = intPreferencesKey("lastConfirmCount")
        val LAST_RUN_AT = longPreferencesKey("lastRunAt")
        val LAST_WAKE_AT = longPreferencesKey("lastWakeAt")
        val LAST_RUN_NOTE = stringPreferencesKey("lastRunNote")
        val LAST_STOP_REASON = stringPreferencesKey("lastStopReason")
        val LAST_SNAPSHOT_DAY = stringPreferencesKey("lastSnapshotDay")
        val FGS_SESSIONS = stringPreferencesKey("fgsSessions")
        val LAST_INTERACTIVE_AT = longPreferencesKey("lastInteractiveAt")
        val WAIT_REASON = stringPreferencesKey("waitReason")
        val AGED_WARNED = booleanPreferencesKey("agedWarned")
        val SAFETY_WARNED_AT = longPreferencesKey("safetyPauseWarnedAt")
        val VOLUME_WARNED_AT = longPreferencesKey("volumeWarnedAt")
        val OLD_FILES_CLEANED = booleanPreferencesKey("oldFilesCleaned")
        val COPIES_REATTACHED = booleanPreferencesKey("copiesReattached")
        val COPIES_NEED_CONSENT = stringSetPreferencesKey("copiesNeedConsent")
        val CLEAN_STREAK = intPreferencesKey("cleanConfirmStreak")
        val RELEASED_SINCE_SAMPLE = intPreferencesKey("releasedSinceSample")
        val RECENT_PACING_FAILURE = booleanPreferencesKey("recentPacingFailure")
        val CLOUD_DETECTED = booleanPreferencesKey("cloudDetected")
        val LAST_OUTPUT_COUNT = intPreferencesKey("lastOutputCount")
        val CLOUD_PROBLEM = stringPreferencesKey("cloudProblem")
        val ALERTS_MUTED_UNTIL = longPreferencesKey("alertsMutedUntil")
        val LAST_ALERTS = stringPreferencesKey("lastAlerts")
        val ACTIVITY_SEEN_AT = longPreferencesKey("activitySeenAt")
        val CATCH_UP_BYTES = longPreferencesKey("catchUpBytes")
        val CATCH_UP_DAY = stringPreferencesKey("catchUpDay")
        val RECLAIM_UNDERSTOOD = booleanPreferencesKey("reclaimUnderstood")
        val RECLAIM_REMINDER_GB = intPreferencesKey("reclaimReminderGb")
        val STALL_ALERTS = intPreferencesKey("stallAlerts")
        val STALL_ALERT_AT = longPreferencesKey("stallAlertAt")
    }

    val flow: Flow<Options> = context.dataStore.data.map { p ->
        Options(
            scope = enumOr(p[K.SCOPE], BackupScope.ALL),
            excludedBuckets = p[K.EXCLUDED_BUCKETS] ?: emptySet(),
            outputMode = enumOr(p[K.OUTPUT_MODE], OutputMode.SINGLE),
            folderSingle = p[K.FOLDER_SINGLE] ?: "",
            folderPhotos = p[K.FOLDER_PHOTOS] ?: "",
            folderVideos = p[K.FOLDER_VIDEOS] ?: "",
            pastOutputRoots = p[K.PAST_OUTPUT_ROOTS] ?: emptySet(),
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
            foreignFiles = p[K.FOREIGN_FILES] ?: 0,
            restoreDone = p[K.RESTORE_DONE] ?: false,
            keptCardSeen = p[K.KEPT_CARD_SEEN] ?: false,
            firstChainState = p[K.FIRST_CHAIN_STATE] ?: "",
            showFreeUp = p[K.SHOW_FREE_UP] ?: false,
            freeUpAllowVerified30 = p[K.FREE_UP_VERIFIED30] ?: false,
            keptInPlace = p[K.KEPT_IN_PLACE] ?: false,
            reprocessUnknown = p[K.REPROCESS_UNKNOWN] ?: false,
            pauseAll = p[K.PAUSE_ALL] ?: false,
            onboardingDone = p[K.ONBOARDING_DONE] ?: false,
            onboardingStep = p[K.ONBOARDING_STEP] ?: 0,
            confirmFlowStartedAt = p[K.CONFIRM_STARTED_AT] ?: 0,
            lastConfirmCount = p[K.LAST_CONFIRM_COUNT] ?: -1,
            lastRunAt = p[K.LAST_RUN_AT] ?: 0,
            lastWakeAt = p[K.LAST_WAKE_AT] ?: 0,
            lastRunNote = p[K.LAST_RUN_NOTE] ?: "",
            lastStopReason = p[K.LAST_STOP_REASON] ?: "",
            lastSnapshotDay = p[K.LAST_SNAPSHOT_DAY] ?: "",
            fgsSessions = p[K.FGS_SESSIONS] ?: "",
            lastInteractiveAt = p[K.LAST_INTERACTIVE_AT] ?: 0,
            waitReason = p[K.WAIT_REASON] ?: "NONE",
            agedWarned = p[K.AGED_WARNED] ?: false,
            safetyPauseWarnedAt = p[K.SAFETY_WARNED_AT] ?: 0,
            volumeWarnedAt = p[K.VOLUME_WARNED_AT] ?: 0,
            oldFilesCleaned = p[K.OLD_FILES_CLEANED] ?: false,
            copiesReattached = p[K.COPIES_REATTACHED] ?: false,
            copiesNeedConsent = p[K.COPIES_NEED_CONSENT] ?: emptySet(),
            cleanConfirmStreak = p[K.CLEAN_STREAK] ?: 0,
            releasedSinceSample = p[K.RELEASED_SINCE_SAMPLE] ?: 0,
            recentPacingFailure = p[K.RECENT_PACING_FAILURE] ?: false,
            cloudDetected = p[K.CLOUD_DETECTED] ?: false,
            lastOutputCount = p[K.LAST_OUTPUT_COUNT] ?: 0,
            cloudProblem = p[K.CLOUD_PROBLEM] ?: "",
            alertsMutedUntil = p[K.ALERTS_MUTED_UNTIL] ?: 0,
            lastAlerts = p[K.LAST_ALERTS] ?: "",
            activitySeenAt = p[K.ACTIVITY_SEEN_AT] ?: 0,
            catchUpBytes = p[K.CATCH_UP_BYTES] ?: 0,
            catchUpDay = p[K.CATCH_UP_DAY] ?: "",
            reclaimUnderstood = p[K.RECLAIM_UNDERSTOOD] ?: false,
            reclaimReminderGb = p[K.RECLAIM_REMINDER_GB] ?: 0,
            stallAlerts = p[K.STALL_ALERTS] ?: 0,
            stallAlertAt = p[K.STALL_ALERT_AT] ?: 0
        ).also { OutputRoots.remember(it.layout, it.pastOutputRoots) }
    }

    suspend fun current(): Options = flow.first()

    /**
     * A choice, once made, is written even if the screen that made it goes.
     *
     * Every setter here is called from a view-model scope, which is cancelled
     * the moment the activity finishes - so ticking an album and immediately
     * leaving the app could cancel the write mid-transaction and lose the
     * tick. Losing a decision someone has already made is not an acceptable
     * outcome of closing an app, and the write itself is milliseconds.
     */
    private suspend fun write(
        edit: suspend (MutablePreferences) -> Unit
    ) = withContext(NonCancellable) {
        context.dataStore.edit { edit(it) }
    }

    suspend fun setString(key: Preferences.Key<String>, value: String) {
        write { it[key] = value }
    }

    suspend fun setInt(key: Preferences.Key<Int>, value: Int) {
        write { it[key] = value }
    }

    suspend fun setLong(key: Preferences.Key<Long>, value: Long) {
        write { it[key] = value }
    }

    suspend fun setBool(key: Preferences.Key<Boolean>, value: Boolean) {
        write { it[key] = value }
    }

    suspend fun setStringSet(
        key: Preferences.Key<Set<String>>,
        value: Set<String>
    ) {
        write { it[key] = value }
    }

    /**
     * One album in or out, read and written in a single step.
     *
     * The pickers used to send the whole set, worked out from what the
     * screen last showed. Two quick taps on a slow phone both started from
     * the same set, so the second write put back the album the first had
     * just taken out - which is how an album unticked in setup turned up
     * ticked in Settings.
     */
    suspend fun setBucketIncluded(name: String, include: Boolean) {
        write { p ->
            val excluded = p[K.EXCLUDED_BUCKETS] ?: emptySet()
            p[K.EXCLUDED_BUCKETS] = if (include) excluded - name else excluded + name
        }
    }

    /**
     * Folders the person moved away from: still watched until they run empty.
     * A folder they move back to is current again, not a past one.
     */
    suspend fun addPastOutputRoots(roots: Collection<String>) {
        val add = roots.map { OutputRoots.normalize(it) }.filter { it.isNotEmpty() }
        if (add.isEmpty()) return
        write { it[K.PAST_OUTPUT_ROOTS] = (it[K.PAST_OUTPUT_ROOTS] ?: emptySet()) + add }
    }

    suspend fun removePastOutputRoots(roots: Collection<String>) {
        if (roots.isEmpty()) return
        write { p ->
            val left = (p[K.PAST_OUTPUT_ROOTS] ?: emptySet()).filter { kept ->
                roots.none { OutputRoots.same(it, kept) }
            }.toSet()
            p[K.PAST_OUTPUT_ROOTS] = left
        }
    }

    /** Remembers copies Android would not let the app delete on its own. */
    suspend fun addCopiesNeedingConsent(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val strings = ids.map { it.toString() }
        write { it[K.COPIES_NEED_CONSENT] = (it[K.COPIES_NEED_CONSENT] ?: emptySet()) + strings }
    }

    suspend fun removeCopiesNeedingConsent(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val strings = ids.map { it.toString() }.toSet()
        write { it[K.COPIES_NEED_CONSENT] = (it[K.COPIES_NEED_CONSENT] ?: emptySet()) - strings }
    }

    /** Options export for the snapshot (user-visible options only). */
    suspend fun exportMap(): Map<String, String> {
        val o = current()
        return mapOf(
            "scope" to o.scope.name,
            "excludedBuckets" to o.excludedBuckets.joinToString("|"),
            "outputMode" to o.outputMode.name,
            "folderSingle" to o.folderSingle,
            "folderPhotos" to o.folderPhotos,
            "folderVideos" to o.folderVideos,
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
    suspend fun importMap(
        map: Map<String, String>,
        onlyIfSetupUntouched: Boolean = false
    ) = withContext(NonCancellable) {
        context.dataStore.edit { p ->
            // Checked inside the same edit as the writes, so nothing the
            // person chooses can land between the check and the import.
            val setupStarted = (p[K.ONBOARDING_DONE] ?: false) || (p[K.ONBOARDING_STEP] ?: 0) != 0
            if (onlyIfSetupUntouched && setupStarted) return@edit
            map["scope"]?.let { p[K.SCOPE] = it }
            map["excludedBuckets"]?.let { s ->
                p[K.EXCLUDED_BUCKETS] = s.split('|').filter { it.isNotEmpty() }.toSet()
            }
            map["outputMode"]?.let { p[K.OUTPUT_MODE] = it }
            // A folder of the person's own comes back only if it is one the
            // folder setting itself could have produced.
            map["folderSingle"]?.takeIf { FolderName.isStorable(it) }?.let { p[K.FOLDER_SINGLE] = it }
            map["folderPhotos"]?.takeIf { FolderName.isStorable(it) }?.let { p[K.FOLDER_PHOTOS] = it }
            map["folderVideos"]?.takeIf { FolderName.isStorable(it) }?.let { p[K.FOLDER_VIDEOS] = it }
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
