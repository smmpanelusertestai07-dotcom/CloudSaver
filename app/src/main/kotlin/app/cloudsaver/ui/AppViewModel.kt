package app.cloudsaver.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cloudsaver.R
import app.cloudsaver.core.logic.QualityKept
import app.cloudsaver.core.logic.ActivityWording
import app.cloudsaver.core.logic.BackupScope
import app.cloudsaver.core.logic.CapacityMath
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.DeviceDefaults
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.EvidenceRules
import app.cloudsaver.core.logic.Fingerprint
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.OutputMode
import app.cloudsaver.core.logic.Pacing
import app.cloudsaver.core.logic.Preset
import app.cloudsaver.core.logic.Projection
import app.cloudsaver.core.logic.ScanSources
import app.cloudsaver.core.logic.SpeedMode
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.core.logic.VideoCodec
import app.cloudsaver.data.CloudApps
import app.cloudsaver.data.db.ActivityRow
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.engine.ActivityLog
import app.cloudsaver.core.logic.MediaProfile
import app.cloudsaver.engine.CloudWatchdog
import app.cloudsaver.engine.DuplicateScanner
import app.cloudsaver.engine.ProfileBuilder
import app.cloudsaver.engine.MaintainEngine
import app.cloudsaver.engine.SnapshotStore
import app.cloudsaver.engine.UsageVerifier
import app.cloudsaver.media.MediaScanner
import app.cloudsaver.media.OutputInventory
import app.cloudsaver.media.Stager
import app.cloudsaver.util.Formats
import app.cloudsaver.util.Permissions
import app.cloudsaver.util.PowerPages
import app.cloudsaver.util.Storage
import app.cloudsaver.util.TamperCheck
import app.cloudsaver.util.Volumes
import app.cloudsaver.work.Gates
import app.cloudsaver.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Minimum time between Home status-line changes (G2). */
        const val STATUS_DEBOUNCE_MS = 800L

        /** The most the trial ever touches. Enough to prove it, cheap to run. */
        const val TRIAL_SIZE = 3

        /**
         * How long the search waits for the typing to stop.
         *
         * Long enough that a word is one query rather than five, short enough
         * that the pause between words already shows results.
         */
        const val SEARCH_DEBOUNCE_MS = 220L

        /** Nothing run for this long, with work waiting, means the OS killed us. */
        const val BACKGROUND_STALL_MS = 48 * 3_600_000L
    }

    private val ctx get() = getApplication<Application>()
    val repo = OptionsRepo.get(ctx)
    private val db = AppDb.get(ctx)

    val options: StateFlow<Options> =
        repo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Options())

    /**
     * False until DataStore has actually handed over the stored options.
     *
     * [options] has to start on something, and that something is the defaults
     * - which say setup has not been done. Rendering on that first value
     * flashed the welcome card at every returning user for a frame or two, and
     * made a resumed setup restart from the beginning.
     */
    val optionsLoaded: StateFlow<Boolean> = repo.flow
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 13.A tamper evidence: true = re-signed/modified copy, deletions disabled. */
    val tampered = MutableStateFlow(false)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            tampered.value = TamperCheck.isModified(ctx)
        }
    }

    // ---- counters (Home) ----------------------------------------------------

    /** The four stages an item passes through, as Home shows them. */
    data class Counters(
        val waiting: Int = 0,
        val inFolder: Int = 0,
        val confirmed: Int = 0,
        /** Problems only: unreadable, too large, encoder refused, and so on. */
        val skipped: Int = 0,
        /** Files handled once under an identical twin. Not a problem. */
        val duplicates: Int = 0,
        /** Compressed and waiting for a pacing slot, not yet in the folder. */
        val heldBack: Int = 0
    )

    val counters: StateFlow<Counters> = combine(
        db.items().stateCountsFlow(),
        db.items().confirmedCountFlow(),
        db.items().verifiedCountFlow(),
        db.items().problemSkippedCountFlow(),
        db.items().duplicatesHandledCountFlow()
    ) { states, confirmed, verified, problems, duplicates ->
        val byState = states.associate { it.state to it.cnt }
        Counters(
            // CC1.3: "in the upload folder" means a file the cloud app can
            // actually see. A STAGED item is compressed and held back by the
            // pacing limit - it is not in the folder, and counting it there
            // is what made the tile disagree with the gallery.
            waiting = (byState[ItemState.NEW.name] ?: 0) +
                (byState[ItemState.STAGED.name] ?: 0),
            inFolder = byState[ItemState.RELEASED.name] ?: 0,
            heldBack = byState[ItemState.STAGED.name] ?: 0,
            // Both count as backed up on Home; how strong the evidence is
            // belongs in the item's details, not in a headline number.
            confirmed = confirmed + verified,
            // Real problems only. A duplicate was handled under its twin,
            // which is the app working, not the app failing.
            skipped = problems,
            duplicates = duplicates
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Counters())

    /**
     * The Home status line, slowed to human speed.
     *
     * A run that finishes twenty files a minute updated this text twenty
     * times, and a caption strobing between "12 files in the queue" and
     * "Everything is backed up" reads as an error, not as progress. One change
     * per 800 ms is fast enough to feel live and slow enough to read.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val statusWaiting: StateFlow<Int> = counters
        .map { it.waiting }
        .debounce(STATUS_DEBOUNCE_MS)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val savedBytes: StateFlow<Long> =
        db.items().savedBytesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val processedCount: StateFlow<Int> =
        db.items().processedCountFlow().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Savings split by media kind.
     *
     * One combined figure hides the thing people actually want to know: a
     * single 4K clip can outweigh a thousand photos, and someone deciding
     * whether to keep videos on needs those two numbers apart.
     */
    data class Savings(
        val photoBytes: Long = 0,
        val photoCount: Int = 0,
        val videoBytes: Long = 0,
        val videoCount: Int = 0
    ) {
        val totalBytes: Long get() = photoBytes + videoBytes
        val totalCount: Int get() = photoCount + videoCount
    }

    val savings: StateFlow<Savings> = combine(
        db.items().savedBytesFlow(false),
        db.items().processedCountFlow(false),
        db.items().savedBytesFlow(true),
        db.items().processedCountFlow(true)
    ) { pb, pc, vb, vc -> Savings(pb, pc, vb, vc) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Savings())

    /**
     * How much this phone's own files actually shrank.
     *
     * The preset percentages are an estimate for the encoder settings; this is
     * the measurement. Showing both, clearly labelled, is the difference
     * between a claim and a number.
     */
    data class MeasuredQuality(
        val photoShrinkPercent: Int = 0,
        val photoCount: Int = 0,
        val videoShrinkPercent: Int = 0,
        val videoCount: Int = 0
    ) {
        val hasAny: Boolean get() = photoCount > 0 || videoCount > 0
    }

    val measuredQuality = MutableStateFlow(MeasuredQuality())

    fun refreshMeasuredQuality() {
        viewModelScope.launch(Dispatchers.IO) {
            val o = repo.current()
            val photos = db.items().photoRatioSamples(o.preset.name)
            val videos = db.items().videoRatioSamples(o.preset.name, o.codec.name)
            fun shrink(rows: List<app.cloudsaver.data.db.RatioSample>): Int {
                val original = rows.sumOf { it.sizeBytes }
                if (original <= 0) return 0
                val output = rows.sumOf { it.outputBytes }
                return (((original - output).toDouble() / original) * 100).toInt().coerceIn(0, 100)
            }
            measuredQuality.value = MeasuredQuality(
                photoShrinkPercent = shrink(photos),
                photoCount = photos.size,
                videoShrinkPercent = shrink(videos),
                videoCount = videos.size
            )
        }
    }

    /** Files copied byte-for-byte, with the reasons, for the "kept as is" card. */
    data class AsIs(val count: Int = 0, val reasons: List<Pair<String, Int>> = emptyList())

    val asIs = MutableStateFlow(AsIs())

    /** Why items were skipped, as chips under the Skipped tile. */
    val skipReasons = MutableStateFlow<List<Pair<String, Int>>>(emptyList())

    fun refreshSkipReasons() {
        viewModelScope.launch(Dispatchers.IO) {
            skipReasons.value = db.items().skipReasons()
                .map { it.state to it.cnt }
        }
    }

    fun refreshAsIs() {
        viewModelScope.launch(Dispatchers.IO) {
            val reasons = (db.items().asIsReasons(false) + db.items().asIsReasons(true))
                .groupBy { it.state }
                .map { (reason, rows) -> reason to rows.sumOf { it.cnt } }
                .sortedByDescending { it.second }
            asIs.value = AsIs(
                count = db.items().asIsCount(false) + db.items().asIsCount(true),
                reasons = reasons
            )
        }
    }

    // ---- activity log -------------------------------------------------------

    private val activityLog = ActivityLog(ctx)

    /** Which of the three groups the Activity screen is showing. */
    val activityFilter = MutableStateFlow<ActivityLog.Group?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activityRows: StateFlow<List<ActivityRow>> = activityFilter
        .flatMapLatest { group ->
            if (group == null) {
                db.activity().recentFlow(ActivityLog.RETENTION_ROWS)
            } else {
                db.activity().byKindsFlow(
                    ActivityLog.Kind.entries.filter { it.group == group }.map { it.name },
                    ActivityLog.RETENTION_ROWS
                )
            }
        }
        // Three taps through the quality presets is one decision, not three
        // events, and a history that records each of them buries the rest.
        .map { rows ->
            ActivityWording.coalesce(
                rows = rows,
                isSettingChange = { it.kind == ActivityLog.Kind.SETTINGS_CHANGED.name },
                detailOf = { it.detail },
                atMsOf = { it.atMs }
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Unread dot on Home: anything logged since the screen was last opened. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activityUnread: StateFlow<Int> = options
        .map { it.activitySeenAt }
        .flatMapLatest { seen -> db.activity().unreadCountFlow(seen) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun markActivitySeen() {
        viewModelScope.launch {
            repo.setLong(OptionsRepo.K.ACTIVITY_SEEN_AT, System.currentTimeMillis())
        }
    }

    fun clearActivity() {
        viewModelScope.launch(Dispatchers.IO) { activityLog.clear() }
    }

    fun exportActivity(uri: Uri, doneLabel: String, failLabel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val text = activityLog.exportText()
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray())
                } ?: error("no stream")
            }.isSuccess
            transferMessage.value = if (ok) doneLabel else failLabel
        }
    }

    /**
     * Re-keyed off options so the settled-by cutoff moves with the clock and
     * the opt-in switch: a value fixed at construction would go stale in a
     * process that stays alive for days.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reclaimableBytes: StateFlow<Long> = options
        .flatMapLatest { o ->
            db.items().reclaimableBytesFlow(
                settledBefore = System.currentTimeMillis() -
                    EvidenceRules.RECLAIM_MIN_DAYS * 86_400_000L,
                includeVerified = o.freeUpAllowVerified30
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // ---- files list ---------------------------------------------------------

    val search = MutableStateFlow("")

    /** Files list filter: an [ItemState] name, or null for everything. */
    val filesState = MutableStateFlow<String?>(null)

    enum class FilesSort { NEWEST, SAVED, LARGEST }

    val filesSort = MutableStateFlow(FilesSort.NEWEST)

    /**
     * Search results, one query behind the keyboard rather than one per key.
     *
     * Typing "beach" used to start five database queries and throw four of
     * them away, which on a large library is five scans of the items table
     * while the finger is still moving. The debounce is short enough that a
     * pause between words already shows results, and distinctUntilChanged
     * stops a re-emitted identical term from re-querying at all.
     */
    @OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    private val searchResults: StateFlow<List<ItemRow>> = search
        .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()
        .flatMapLatest { q -> db.items().searchFlow(q, 500) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The list the Files screen shows.
     *
     * Filtering and sorting happen here rather than in SQL because the query
     * is already capped at 500 rows: pushing them into the statement would
     * mean four near-identical queries for no measurable gain.
     */
    val items: StateFlow<List<ItemRow>> =
        combine(searchResults, filesState, filesSort) { rows, state, sort ->
            val filtered = when (state) {
                null -> rows
                // "Backed up" is a story rather than one state: an item can be
                // finished, its copy already tidied away, or its original
                // reclaimed, and all three read the same to the user.
                ItemState.DONE.name -> rows.filter {
                    it.state in setOf(
                        ItemState.DONE.name, ItemState.GONE.name, ItemState.FREED.name
                    )
                }
                ItemState.RELEASED.name -> rows.filter {
                    it.state in setOf(ItemState.STAGED.name, ItemState.RELEASED.name)
                }
                else -> rows.filter { it.state == state }
            }
            when (sort) {
                FilesSort.NEWEST -> filtered.sortedByDescending { it.captureAt }
                FilesSort.SAVED -> filtered.sortedByDescending {
                    it.outputBytes?.let { out -> it.sizeBytes - out } ?: 0L
                }
                FilesSort.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- health chips -------------------------------------------------------

    data class Health(
        val batteryRestricted: Boolean = false,
        val usageAccessOff: Boolean = false,
        val cloudMissing: Boolean = false,
        val spaceLow: Boolean = false,
        val paused: Boolean = false,
        /**
         * Nothing has run for two days while work was waiting. Every other
         * check can be green and the app still be dead, because the phone
         * simply stopped scheduling it - and that failure is invisible.
         */
        val backgroundWorkStopped: Boolean = false,

        /** The chosen SD card is gone; work is paused safely (Z3.3). */
        val volumeMissing: Boolean = false,

        // Everything the Home action needs to decide whether a run the user
        // asks for could actually go ahead.
        val thermalThrottled: Boolean = false,
        val batteryPct: Int = 0,
        val plugged: Boolean = false,
        val freeBytes: Long = 0
    )

    val health = MutableStateFlow(Health())

    /**
     * How much of the gallery the app can see, re-read on every ON_START and
     * permission result. Home, Files, Storage and the calculator all observe
     * it: under PARTIAL nothing scans and no total is shown as a number,
     * because the number would describe the user's selection, not their
     * gallery (BB1).
     */
    val mediaAccess = MutableStateFlow(Permissions.MediaAccess.FULL)

    /**
     * The app died last time (BB3). One card, once; dismissing clears the
     * flag, and the trace is already in the log for the Share button.
     */
    val crashPending = MutableStateFlow(false)

    /**
     * Which cloud app holds this item's copy (Z10.1): the app recorded on the
     * batch the file went out with, never the one selected today.
     */
    suspend fun holdingAppLabel(row: ItemRow): String? {
        val pkg = row.batchId?.let { db.batches().byId(it)?.cloudPackage } ?: return null
        return CloudApps.ALL.firstOrNull { pkg in it.packages }?.label ?: pkg
    }

    /**
     * The phone's screen lock was removed, so nothing can verify anyone: the
     * app lock turns itself off, visibly. Silent would read as broken, and
     * staying on would brick the app behind a door with no key.
     */
    fun disableLockNoCredential() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!repo.current().appLock) return@launch
            repo.setBool(OptionsRepo.K.APP_LOCK, false)
            activityLog.record(
                ActivityLog.Kind.PROBLEM,
                detail = ctx.getString(R.string.lock_disabled_no_credential)
            )
        }
    }

    /** Z5.2: the double-backup warning was on screen and the user moved on. */
    fun acknowledgeDoubleBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.setBool(OptionsRepo.K.DOUBLE_BACKUP_ACK, true)
        }
    }

    fun acknowledgeKeptCard() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.setBool(OptionsRepo.K.KEPT_CARD_SEEN, true)
        }
    }

    fun dismissCrashNotice() {
        app.cloudsaver.util.CrashLog.clearPending(ctx)
        crashPending.value = false
    }

    fun refreshHealth() {
        viewModelScope.launch(Dispatchers.Default) {
            val o = repo.current()
            val waiting = runCatching { db.items().countByState(ItemState.NEW.name) }
                .getOrDefault(0)
            val silentFor = System.currentTimeMillis() - o.lastRunAt
            val power = Gates.readPower(ctx, o.lastInteractiveAt, System.currentTimeMillis())
            val free = Storage.freeBytes(ctx)
            val access = Permissions.mediaAccess(ctx)
            crashPending.value = app.cloudsaver.util.CrashLog.crashPending(ctx)
            val hadPartial = mediaAccess.value == Permissions.MediaAccess.PARTIAL
            mediaAccess.value = access
            // Access became full again: recompute without waiting for the
            // next scheduled run, so the screens stop saying "waiting".
            if (hadPartial && access == Permissions.MediaAccess.FULL) {
                refreshCalculator()
                refreshProjection()
            }
            val volumeGone = o.storageVolume.isNotEmpty() &&
                Volumes.byName(ctx, o.storageVolume) == null
            // A returned card must be probed afresh, not trusted from cache.
            if (!volumeGone && o.storageVolume.isNotEmpty()) Unit else Volumes.invalidateProbes()
            health.value = Health(
                volumeMissing = volumeGone,
                batteryRestricted = !Permissions.isIgnoringBatteryOptimizations(ctx),
                usageAccessOff = !UsageVerifier.hasUsageAccess(ctx),
                cloudMissing = !CloudApps.isAppInstalled(ctx, o.cloudSingle),
                spaceLow = free < o.minFreeBytes,
                thermalThrottled = power.thermalThrottled ||
                    power.batteryTempTenthsC >= Defaults.BATTERY_MAX_TEMP_TENTHS_C,
                batteryPct = power.batteryPct,
                plugged = power.plugged,
                freeBytes = free,
                paused = o.pauseAll,
                backgroundWorkStopped = !o.pauseAll && waiting > 0 &&
                    o.lastRunAt > 0 && silentFor > BACKGROUND_STALL_MS
            )
        }
    }

    // ---- today's upload allowance (D3) --------------------------------------

    /**
     * How much of today's upload allowance is left, and when it refills.
     *
     * "Waiting" with no reason is the complaint every background app gets. If
     * the app is holding files back because the daily limit is spent, it says
     * so, and says when that stops being true.
     */
    data class Budget(
        val usedBytes: Long = 0,
        val totalBytes: Long = 0,
        val resetsAt: Long = 0,
        val carriedBytes: Long = 0
    ) {
        val unlimited: Boolean get() = totalBytes < 0
        val spent: Boolean get() = !unlimited && usedBytes >= totalBytes
        val fraction: Float
            get() = if (unlimited || totalBytes <= 0) {
                0f
            } else {
                (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            }
    }

    val budget = MutableStateFlow(Budget())

    fun refreshBudget() {
        viewModelScope.launch(Dispatchers.IO) {
            val o = repo.current()
            val now = System.currentTimeMillis()
            val total = Pacing.dailyBudgetWithCatchUp(
                o.dailyCapBytes,
                if (Formats.dayKey(now) == o.catchUpDay) o.catchUpBytes else 0L
            )
            budget.value = Budget(
                usedBytes = db.batches().bytesSince(Formats.startOfDay(now)),
                totalBytes = total,
                resetsAt = Formats.nextMidnight(now),
                carriedBytes = if (Formats.dayKey(now) == o.catchUpDay) o.catchUpBytes else 0L
            )
        }
    }

    // ---- storage screen -----------------------------------------------------

    data class StorageStats(
        val stageBytes: Long = 0,
        val outputBytes: Long = 0,
        val tempBytes: Long = 0,
        /** How much the last Clear actually freed, so the button proves itself. */
        val lastTempFreed: Long? = null
    )

    val storageStats = MutableStateFlow(StorageStats())

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            storageStats.value = storageStats.value.copy(
                stageBytes = Storage.totalStageBytes(ctx),
                outputBytes = db.items().releasedBytes(),
                tempBytes = Storage.totalTempBytes(ctx)
            )
        }
    }

    // ---- storage volumes (13.D) ----------------------------------------

    val volumes = MutableStateFlow<List<Volumes.Vol>>(emptyList())

    /**
     * Volumes that really accept gallery inserts, by probe (BB2). The
     * primary always does; an SD card is offered as the storage location
     * only when its name is in here, and is otherwise absent - not greyed -
     * with one line saying why.
     */
    val writableVolumes = MutableStateFlow<Set<String>>(emptySet())

    fun refreshVolumes() {
        viewModelScope.launch(Dispatchers.IO) {
            val found = Volumes.list(ctx)
            volumes.value = found
            writableVolumes.value = found
                .filter { it.isPrimary || Volumes.probeWritable(ctx, it.mediaVolumeName) }
                .map { it.mediaVolumeName }
                .toSet()
        }
    }

    // ---- cloud calculator (13.C) ----------------------------------------

    val calcGallery = MutableStateFlow<CapacityMath.Gallery?>(null)
    val calcRatios = MutableStateFlow<CapacityMath.Ratios?>(null)

    /**
     * Which figures the calculator is using.
     *
     * Null means "decide for me": measured where this phone has enough
     * representative data, typical otherwise. A user choice pins it, and the
     * badge always names the one in use, so no number is ever unattributed.
     */
    val calcSource = MutableStateFlow<CapacityMath.Source?>(null)

    fun setCalcSource(source: CapacityMath.Source?) {
        calcSource.value = source
        refreshCalculator()
    }

    fun refreshCalculator() {
        viewModelScope.launch(Dispatchers.IO) {
            // Under partial access a totals() sweep would count the selection
            // and present it as the gallery. The screen shows "waiting for
            // full access" instead of any number (BB1.3).
            if (Permissions.mediaAccess(ctx) != Permissions.MediaAccess.FULL) {
                calcGallery.value = null
                return@launch
            }
            val o = repo.current()
            // Summed straight off the cursor: the calculator needs five
            // numbers, not a copy of the gallery in memory.
            val totals = runCatching { MediaScanner(ctx, db).totals(o.excludedBuckets) }
                .getOrDefault(MediaScanner.Totals())
            calcGallery.value = CapacityMath.Gallery(
                photoBytes = totals.photoBytes,
                videoBytes = totals.videoBytes,
                videoMinutes = totals.videoMinutes,
                monthlyPhotoBytes = totals.monthlyPhotoBytes,
                monthlyVideoBytes = totals.monthlyVideoBytes,
                videoCount = totals.videoCount
            )
            val photoSamples = db.items().photoRatioSamples(o.preset.name).map {
                CapacityMath.Sample(it.sizeBytes, it.outputBytes)
            }
            val videoSamples = db.items().videoRatioSamples(o.preset.name, o.codec.name).map {
                CapacityMath.Sample(it.sizeBytes, it.outputBytes, it.durationMs / 60_000.0)
            }
            // The gallery's own median is what the sample is judged against:
            // twenty screenshots must not get to speak for a library of
            // photographs.
            calcRatios.value = CapacityMath.ratios(
                photo = photoSamples,
                video = videoSamples,
                codec = o.codec,
                source = calcSource.value ?: CapacityMath.Source.MEASURED,
                galleryPhotoMedian = if (totals.photoCount > 0) {
                    totals.photoBytes / totals.photoCount
                } else {
                    0L
                },
                galleryVideoMedian = if (totals.videoCount > 0) {
                    totals.videoBytes / totals.videoCount
                } else {
                    0L
                }
            )
        }
    }

    // ---- media profile: the one source every estimate reads from ------------

    val profile = MutableStateFlow(MediaProfile.Profile())

    fun refreshProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            profile.value = ProfileBuilder(ctx).current(repo.current())
        }
    }

    /** Rebuild on demand, for the screens that must not show a stale figure. */
    fun rebuildProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            ProfileBuilder(ctx).rebuild(repo.current())
            profile.value = ProfileBuilder(ctx).current(repo.current())
        }
    }

    /**
     * What finishing the queue would save.
     *
     * Photos and videos are projected separately and added, from this phone's
     * measured ratios where they exist and typical ratios where they do not.
     * It used to report zero until something had been measured, which for a
     * queue of 249 MB of video read as "about 0 MB could be saved" - not
     * cautious, just wrong. The basis is carried alongside so the screen can
     * say whether the figure is measured or an estimate.
     */
    val projectedSavings = MutableStateFlow(Projection.Estimate(0L, Projection.Basis.TYPICAL))

    fun refreshProjection() {
        viewModelScope.launch(Dispatchers.IO) {
            val p = ProfileBuilder(ctx).current(repo.current())
            projectedSavings.value = Projection.forQueue(
                photoBytes = db.items().pendingBytesByType(video = false),
                videoBytes = db.items().pendingBytesByType(video = true),
                measuredPhotoRatio = p.photos.ratio,
                measuredVideoRatio = p.videos.ratio,
                photoCount = db.items().pendingCountByType(video = false),
                videoCount = db.items().pendingCountByType(video = true)
            )
        }
    }

    // ---- find space (v2.3 G1) -----------------------------------------------

    data class FindSpace(
        val duplicateBytes: Long = 0,
        val duplicateGroups: Int = 0,
        val biggestBytes: Long = 0,
        val reclaimableBytes: Long = 0,
        val reclaimableCount: Int = 0
    )

    val findSpace = MutableStateFlow(FindSpace())

    fun refreshFindSpace() {
        viewModelScope.launch(Dispatchers.IO) {
            val groups = runCatching { DuplicateScanner(ctx).groups() }.getOrDefault(emptyList())
            val largest = runCatching { db.items().largest(50) }.getOrDefault(emptyList())
            val candidates = runCatching { db.items().reclaimCandidates() }.getOrDefault(emptyList())
            findSpace.value = FindSpace(
                duplicateBytes = groups.sumOf { it.reclaimableBytes },
                duplicateGroups = groups.size,
                biggestBytes = largest.sumOf { it.sizeBytes },
                reclaimableBytes = candidates.sumOf { it.sizeBytes },
                reclaimableCount = candidates.size
            )
        }
    }

    // These three back one card each, on screens most launches never reach.
    // Started lazily so a cold start does not pay for three table scans
    // nobody asked for, and kept alive briefly across a rotation.
    private val screenLocal = SharingStarted.WhileSubscribed(5_000)

    val reclaimHistoryCount: StateFlow<Int> = db.reclaim().recentBatchesFlow(50)
        .map { it.size }
        .stateIn(viewModelScope, screenLocal, 0)

    val keptBytes: StateFlow<Long> =
        db.items().keptBytesFlow().stateIn(viewModelScope, screenLocal, 0L)

    val neverOptimiseCount: StateFlow<Int> = db.items().neverOptimiseCountFlow()
        .stateIn(viewModelScope, screenLocal, 0)

    /** One row by id, for screens that hold ids rather than rows. */
    suspend fun itemById(id: Long): ItemRow? =
        withContext(Dispatchers.IO) { runCatching { db.items().byId(id) }.getOrNull() }

    fun clearNeverOptimise() {
        viewModelScope.launch(Dispatchers.IO) { db.items().clearNeverOptimise() }
    }

    // ---- per-item controls (v2.2 B) -----------------------------------------

    /** Bring one file to the front of the queue. */
    /** The same jump-the-queue action for a whole selection. */
    fun optimiseNow(ids: List<Long>) {
        for (id in ids) optimiseNow(id)
    }

    fun optimiseNow(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = db.items().byId(id) ?: return@launch
            val now = System.currentTimeMillis()
            db.items().update(
                row.copy(
                    state = ItemState.NEW.name,
                    skipReason = null,
                    attempts = 0,
                    // The queue is ordered by capture date, so "next" means
                    // looking like the newest thing in the gallery.
                    captureAt = now,
                    updatedAt = now
                )
            )
        }
    }

    /** A permanent, reversible "leave this one alone". */
    fun setNeverOptimise(id: Long, never: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = db.items().byId(id) ?: return@launch
            val now = System.currentTimeMillis()
            db.items().update(
                row.copy(
                    neverOptimise = never,
                    state = if (never) ItemState.SKIP.name else ItemState.NEW.name,
                    skipReason = if (never) "user_excluded" else null,
                    updatedAt = now
                )
            )
        }
    }

    // ---- kept light copies (v2.4 J2) ----------------------------------------

    val keptCopies = MutableStateFlow<List<ItemRow>>(emptyList())

    fun loadKeptCopies() {
        viewModelScope.launch(Dispatchers.IO) {
            keptCopies.value = db.items().keptCopies()
        }
    }

    /**
     * Removes one kept light copy. No Android dialog is needed - the app
     * created the file - which is exactly why the confirm sheet has to say
     * what is being given up.
     */
    fun removeKeptCopy(row: ItemRow) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = row.keptUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (uri != null) runCatching { ctx.contentResolver.delete(uri, null, null) }
            db.items().update(
                row.copy(
                    keptUri = null,
                    // Still reclaimed, just without the local copy now. It
                    // must not go back in the queue: the cloud has it.
                    state = ItemState.FREED.name,
                    updatedAt = System.currentTimeMillis()
                )
            )
            loadKeptCopies()
        }
    }

    /**
     * Copies the light copies into a folder the user picked, for anyone who
     * wants them outside any app-created location entirely.
     */
    fun copyKeptCopiesTo(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, treeUri)
            if (tree == null) {
                transferMessage.value = ctx.getString(R.string.transfer_failed)
                return@launch
            }
            var copied = 0
            for (row in db.items().keptCopies()) {
                val source = row.keptUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    ?: continue
                val name = row.outputName ?: row.displayName
                val target = tree.createFile(row.mimeType, name) ?: continue
                val ok = runCatching {
                    ctx.contentResolver.openInputStream(source)?.use { input ->
                        ctx.contentResolver.openOutputStream(target.uri)?.use { output ->
                            input.copyTo(output, 128 * 1024)
                        } ?: error("no output")
                    } ?: error("no input")
                }.isSuccess
                if (ok) copied++ else target.delete()
            }
            transferMessage.value = ctx.resources.getQuantityString(R.plurals.uninstall_move_done, copied, copied)
        }
    }

    // ---- device-aware recommendations (F4) ----------------------------------

    /**
     * What these limits should be on *this* phone.
     *
     * A 64 GB phone and a 512 GB phone should not reserve the same headroom,
     * and a cap that suits a metered connection is wrong for someone on Wi-Fi
     * all week. Nothing is changed behind the user's back: the numbers are
     * offered as a one-tap suggestion and only when what is stored has drifted
     * far enough to be worth mentioning.
     */
    data class Recommended(
        val dailyCapMb: Int = Defaults.DAILY_CAP_MB,
        val minFreeMb: Int = Defaults.MIN_FREE_MB,
        val maxExtraMb: Int = Defaults.MAX_EXTRA_MB,
        val capLooksWrong: Boolean = false,
        val freeLooksWrong: Boolean = false
    )

    val recommended = MutableStateFlow(Recommended())

    fun refreshRecommended() {
        viewModelScope.launch(Dispatchers.IO) {
            val o = repo.current()
            val total = Storage.totalBytes(ctx, o.storageVolume)
            val free = Storage.freeBytes(ctx, o.storageVolume)
            // No usage history to read offline, so assume the cautious middle
            // rather than inventing a Wi-Fi share the app cannot measure.
            val cap = nearestChoice(
                DeviceDefaults.dailyCapMb(total, free, 0.0),
                Defaults.DAILY_CAP_CHOICES_MB
            )
            val minFree = nearestChoice(
                DeviceDefaults.reserveMb(total), Defaults.MIN_FREE_CHOICES_MB
            )
            val maxExtra = nearestChoice(
                DeviceDefaults.ownLimitMb(free, cap), Defaults.MAX_EXTRA_CHOICES_MB
            )
            recommended.value = Recommended(
                dailyCapMb = cap,
                minFreeMb = minFree,
                maxExtraMb = maxExtra,
                capLooksWrong = DeviceDefaults.looksWrong(o.dailyCapMb, cap),
                freeLooksWrong = DeviceDefaults.looksWrong(o.minFreeMb, minFree)
            )
        }
    }

    fun applyRecommended() {
        val r = recommended.value
        viewModelScope.launch {
            repo.setInt(OptionsRepo.K.DAILY_CAP_MB, r.dailyCapMb)
            repo.setInt(OptionsRepo.K.MIN_FREE_MB, r.minFreeMb)
            repo.setInt(OptionsRepo.K.MAX_EXTRA_MB, r.maxExtraMb)
            refreshRecommended()
        }
    }

    /** Settings offer fixed steps, so a computed figure has to land on one. */
    private fun nearestChoice(value: Int, choices: List<Int>): Int =
        choices.filter { it > 0 }.minByOrNull { kotlin.math.abs(it - value) } ?: value

    fun cleanTemp() {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = Storage.cleanTemp(ctx)
            storageStats.value = storageStats.value.copy(lastTempFreed = freed)
            refreshStorage()
        }
    }

    // ---- actions ------------------------------------------------------------

    /**
     * A run the user asked for.
     *
     * It skips everything the scheduler was waiting for - charger, screen off,
     * today's battery budget - because those exist to avoid surprising
     * someone, and a tap is not a surprise. The safety guards (heat, a nearly
     * flat battery, free space) still apply; [HomeAction] decides whether the
     * button was offered at all.
     */
    fun optimiseNow() {
        Scheduler.runNow(ctx)
        Scheduler.maintainNow(ctx)
        viewModelScope.launch(Dispatchers.IO) {
            activityLog.record(ActivityLog.Kind.OPTIMISED, detail = ctx.getString(R.string.activity_started_by_you))
        }
    }

    /** True while a compression run is actually executing. */
    val running: StateFlow<Boolean> = Scheduler.runningFlow(ctx)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun quickMaintain() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { MaintainEngine(ctx).confirmPass() }
            refreshHealth()
        }
    }

    // ---- confirm-uploads flow ----------------------------------------------

    /**
     * Whether the chosen cloud app removes its own uploads.
     *
     * "Verify backup" works by watching copies leave the upload folder, which
     * only a cloud with a free-up feature ever does. On the others the button
     * can only ever report nothing, so it is not offered - a control that
     * always fails is worse than no control.
     */
    val cloudHasFreeUp = MutableStateFlow(false)

    fun refreshCloudCaps() {
        viewModelScope.launch(Dispatchers.IO) {
            val o = repo.current()
            cloudHasFreeUp.value = CloudWatchdog(ctx).capsFor(o.cloudSingle).hasFreeUpSpace
        }
    }

    val confirmResult = MutableStateFlow<Int?>(null)
    private var confirmPending = false

    fun startConfirmFlow() {
        viewModelScope.launch {
            repo.setLong(OptionsRepo.K.CONFIRM_STARTED_AT, System.currentTimeMillis())
            confirmPending = true
            CloudApps.launch(ctx, repo.current().cloudSingle)
        }
    }

    fun dismissConfirmResult() {
        confirmResult.value = null
    }

    /** The app being in the foreground proves the screen is on (13.G). */
    fun noteScreenOn() {
        viewModelScope.launch {
            repo.setLong(OptionsRepo.K.LAST_INTERACTIVE_AT, System.currentTimeMillis())
        }
    }

    fun onResumed() {
        noteScreenOn()
        if (confirmPending) {
            confirmPending = false
            viewModelScope.launch(Dispatchers.Default) {
                val n = runCatching { MaintainEngine(ctx).confirmPass() }.getOrDefault(0)
                confirmResult.value = n
                repo.setInt(OptionsRepo.K.LAST_CONFIRM_COUNT, n)
            }
        }
        refreshHealth()
    }

    private var lastMediaMaintain = 0L

    /** Output-folder ContentObserver (foreground only): throttled quick pass. */
    fun onMediaChanged() {
        val now = System.currentTimeMillis()
        if (now - lastMediaMaintain < 5_000) return
        lastMediaMaintain = now
        quickMaintain()
    }

    // ---- deep links from alerts ---------------------------------------------

    /** Route an alert asked for, consumed once by the navigation host. */
    val deepLink = MutableStateFlow<String?>(null)

    fun consumeDeepLink(route: String?) {
        if (!route.isNullOrEmpty()) deepLink.value = route
    }

    fun clearDeepLink() {
        deepLink.value = null
    }

    // ---- option setters -----------------------------------------------------

    fun setScope(v: BackupScope) {
        setStr(OptionsRepo.K.SCOPE, v.name)
        noteSettingChange(
            detail = ActivityWording.encode(ActivityWording.Setting.SCOPE, v.name)
        )
    }

    fun setOutputMode(v: OutputMode) {
        setStr(OptionsRepo.K.OUTPUT_MODE, v.name)
        noteSettingChange(
            detail = ActivityWording.encode(ActivityWording.Setting.LAYOUT, v.name)
        )
    }
    fun setCloudSingle(v: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val previous = repo.current().cloudSingle
            repo.setString(OptionsRepo.K.CLOUD_SINGLE, v)
            // Z10.1: proof belongs to the app that was selected when a file
            // was sent, so a switch resets everything learned about the OLD
            // app - capability, pacing confidence, the watchdog's memory -
            // and queues the one-time sheet explaining where old files live.
            if (previous.isNotEmpty() && previous != v) {
                repo.setString(OptionsRepo.K.CLOUD_SWITCH_FROM, previous)
                repo.setInt(OptionsRepo.K.CLEAN_STREAK, 0)
                repo.setBool(OptionsRepo.K.RECENT_PACING_FAILURE, false)
                repo.setString(OptionsRepo.K.CLOUD_PROBLEM, "")
                repo.setLong(OptionsRepo.K.LAST_ALERT_AT, 0)
            }
        }
        noteSettingChange(
            detail = ActivityWording.encode(
                ActivityWording.Setting.CLOUD_APP, CloudApps.byId(v).label
            )
        )
        refreshCloudCaps()
    }

    /** Z10.6: the first-chain card was read, either way. */
    fun dismissFirstChainNotice() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.setString(OptionsRepo.K.FIRST_CHAIN_STATE, "DONE")
        }
    }

    /** The switch sheet was read; do not show it again. */
    fun dismissCloudSwitchNotice() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.setString(OptionsRepo.K.CLOUD_SWITCH_FROM, "")
        }
    }
    fun setCloudPhotos(v: String) = setStr(OptionsRepo.K.CLOUD_PHOTOS, v)
    fun setCloudVideos(v: String) = setStr(OptionsRepo.K.CLOUD_VIDEOS, v)
    fun setPreset(v: Preset) {
        setStr(OptionsRepo.K.PRESET, v.name)
        noteSettingChange(
            detail = ActivityWording.encode(ActivityWording.Setting.QUALITY, v.name)
        )
    }
    fun setCodec(v: VideoCodec) {
        setStr(OptionsRepo.K.CODEC, v.name)
        noteSettingChange(
            detail = ActivityWording.encode(ActivityWording.Setting.CODEC, v.name)
        )
    }

    fun setTheme(v: ThemeMode) {
        setStr(OptionsRepo.K.THEME, v.name)
        noteSettingChange(
            detail = ActivityWording.encode(ActivityWording.Setting.THEME, v.name)
        )
    }
    fun setDynamicColor(v: Boolean) = setBool(OptionsRepo.K.DYNAMIC_COLOR, v)
    fun setDailyCap(v: Int) = setInt(OptionsRepo.K.DAILY_CAP_MB, v)
    fun setMinFree(v: Int) = setInt(OptionsRepo.K.MIN_FREE_MB, v)
    fun setMaxExtra(v: Int) = setInt(OptionsRepo.K.MAX_EXTRA_MB, v)
    fun setAppLock(v: Boolean) = setBool(OptionsRepo.K.APP_LOCK, v)
    fun setWarningsNotif(v: Boolean) = setBool(OptionsRepo.K.WARNINGS_NOTIF, v)
    fun setShowFreeUp(v: Boolean) = setBool(OptionsRepo.K.SHOW_FREE_UP, v)
    fun setFreeUpVerified30(v: Boolean) = setBool(OptionsRepo.K.FREE_UP_VERIFIED30, v)
    fun setReclaimUnderstood(v: Boolean) = setBool(OptionsRepo.K.RECLAIM_UNDERSTOOD, v)
    fun setReclaimReminderGb(v: Int) = setInt(OptionsRepo.K.RECLAIM_REMINDER_GB, v)
    fun setReprocessUnknown(v: Boolean) {
        setBool(OptionsRepo.K.REPROCESS_UNKNOWN, v)
        if (v) {
            viewModelScope.launch(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                for (row in db.items().byState(ItemState.UNKNOWN.name)) {
                    db.items().update(
                        row.copy(state = ItemState.NEW.name, evidence = Evidence.NONE.name, updatedAt = now)
                    )
                }
            }
        }
    }

    fun setPauseAll(v: Boolean) {
        setBool(OptionsRepo.K.PAUSE_ALL, v)
        refreshHealth()
        // Worth a line: "why did it stop" is the first question a week later,
        // and a switch flipped once is exactly what nobody remembers doing.
        noteSettingChange(if (v) ActivityLog.Kind.PAUSED else ActivityLog.Kind.RESUMED)
    }

    private fun noteSettingChange(
        kind: ActivityLog.Kind = ActivityLog.Kind.SETTINGS_CHANGED,
        detail: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            activityLog.record(kind, detail = detail)
        }
    }

    fun setSpeed(v: SpeedMode) {
        viewModelScope.launch {
            repo.setString(OptionsRepo.K.SPEED, v.name)
            Scheduler.ensure(ctx, repo.current())
        }
        noteSettingChange(
            detail = ActivityWording.encode(ActivityWording.Setting.SPEED, v.name)
        )
    }

    fun setStorageVolume(v: String) = setStr(OptionsRepo.K.STORAGE_VOLUME, v)

    fun setExcludedBuckets(v: Set<String>) {
        viewModelScope.launch { repo.setStringSet(OptionsRepo.K.EXCLUDED_BUCKETS, v) }
    }

    val buckets = MutableStateFlow<List<String>>(emptyList())

    /** Folders the app refuses to scan, with why - shown greyed out in the picker. */
    val lockedBuckets = MutableStateFlow<List<Pair<String, ScanSources.Reason>>>(emptyList())

    /**
     * What the ticked albums actually hold, in bytes.
     *
     * A count of albums is not a quantity anyone can reason about: "2 albums"
     * could be forty photos or eighteen gigabytes, and the decision being
     * made on that screen is exactly how much of the gallery to hand over.
     * Null until it has really been measured - a zero here would read as an
     * empty gallery.
     */
    val selectedAlbumBytes = MutableStateFlow<Long?>(null)

    fun refreshSelectedAlbumBytes() {
        viewModelScope.launch(Dispatchers.IO) {
            if (Permissions.mediaAccess(ctx) != Permissions.MediaAccess.FULL) {
                selectedAlbumBytes.value = null
                return@launch
            }
            val o = repo.current()
            if (buckets.value.isNotEmpty() &&
                buckets.value.all { it in o.excludedBuckets }
            ) {
                // Nothing ticked is a real answer, and a cheap one.
                selectedAlbumBytes.value = 0L
                return@launch
            }
            val totals = runCatching { MediaScanner(ctx, db).totals(o.excludedBuckets) }
                .getOrNull() ?: return@launch
            selectedAlbumBytes.value = totals.photoBytes + totals.videoBytes
        }
    }

    fun loadBuckets() {
        viewModelScope.launch(Dispatchers.IO) {
            val scanner = MediaScanner(ctx, db)
            buckets.value = runCatching { scanner.buckets() }.getOrDefault(emptyList())
            lockedBuckets.value = runCatching {
                scanner.excludedBucketReasons().toList().sortedBy { it.first }
            }.getOrDefault(emptyList())
        }
    }

    // ---- cloud detection and linking (A2, A3, A5) ---------------------------

    /**
     * What was found on this phone, and whether the app committed to it.
     *
     * One installed cloud app is not a guess, so it is stored immediately -
     * otherwise Settings kept saying "Other app" for someone who clearly has
     * Ente, and every capability decision downstream was made on the wrong
     * assumption. Two or more is a genuine choice and gets a picker; none
     * means "Other app" and the generic checklist.
     */
    data class CloudDetection(
        val installed: List<app.cloudsaver.data.CloudApp> = emptyList(),
        val chosen: app.cloudsaver.data.CloudApp = CloudApps.byId("other"),
        val needsChoice: Boolean = false
    )

    val cloudDetection = MutableStateFlow(CloudDetection())

    fun detectAndPersistCloud() {
        viewModelScope.launch(Dispatchers.IO) {
            val o = repo.current()
            val installed = CloudApps.SELECTABLE.filter {
                it.packages.isNotEmpty() && CloudApps.installedPackage(ctx, it) != null
            }
            val alreadyChosen = o.cloudDetected
            val chosen = when {
                alreadyChosen -> CloudApps.byId(o.cloudSingle)
                installed.size == 1 -> installed.first()
                installed.isEmpty() -> CloudApps.byId("other")
                else -> CloudApps.byId(o.cloudSingle)
            }
            // Only commit where there is nothing to decide. With two installed
            // the app must not pick for the user and then act on that choice.
            if (!alreadyChosen && installed.size <= 1) {
                persistCloud(chosen.id)
            }
            cloudDetection.value = CloudDetection(
                installed = installed,
                chosen = chosen,
                needsChoice = !alreadyChosen && installed.size > 1
            )
            refreshCloudCaps()
        }
    }

    private suspend fun persistCloud(id: String) {
        repo.setString(OptionsRepo.K.CLOUD_SINGLE, id)
        repo.setString(OptionsRepo.K.CLOUD_PHOTOS, id)
        repo.setString(OptionsRepo.K.CLOUD_VIDEOS, id)
        repo.setBool(OptionsRepo.K.CLOUD_DETECTED, true)
    }

    /** The user picked from the "Use a different app" list. */
    fun chooseCloud(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            persistCloud(id)
            cloudDetection.value = cloudDetection.value.copy(
                chosen = CloudApps.byId(id), needsChoice = false
            )
            noteSettingChange(
                detail = ActivityWording.encode(
                    ActivityWording.Setting.CLOUD_APP, CloudApps.byId(id).label
                )
            )
            refreshCloudCaps()
        }
    }

    /**
     * Everything about the link that can actually be checked, checked.
     *
     * "Set it up in the other app and trust that it worked" is the step people
     * get wrong, and the app finds out days later. What is verifiable here is
     * verifiable now: the app is installed, the folder exists, and bytes have
     * moved recently. Anything else is reported as a specific next step rather
     * than a green tick.
     */
    enum class LinkState { CONNECTED, NO_APP, NO_FOLDER, NO_TRAFFIC, CANNOT_TELL }

    val linkState = MutableStateFlow<LinkState?>(null)

    fun verifyCloudLink() {
        viewModelScope.launch(Dispatchers.IO) {
            val o = repo.current()
            val app = CloudApps.byId(o.cloudSingle)
            val pkg = CloudApps.installedPackage(ctx, app)
            val folderHasFiles = (OutputInventory(ctx).query() ?: emptyList()).isNotEmpty()
            linkState.value = when {
                app.packages.isNotEmpty() && pkg == null -> LinkState.NO_APP
                !folderHasFiles -> LinkState.NO_FOLDER
                pkg == null -> LinkState.CANNOT_TELL
                else -> {
                    val uid = CloudApps.uidOf(ctx, pkg)
                    val now = System.currentTimeMillis()
                    val tx = uid?.let {
                        UsageVerifier.txBytesForUid(ctx, it, now - 86_400_000L, now)
                    }
                    when {
                        tx == null -> LinkState.CANNOT_TELL
                        tx < 1_000_000 -> LinkState.NO_TRAFFIC
                        else -> LinkState.CONNECTED
                    }
                }
            }
        }
    }

    fun clearLinkState() {
        linkState.value = null
    }

    // ---- background-work requirements (B1) ----------------------------------

    val powerRequirements = MutableStateFlow<List<PowerPages.Requirement>>(emptyList())

    fun refreshPowerRequirements() {
        viewModelScope.launch(Dispatchers.Default) {
            powerRequirements.value = PowerPages.requirementsFor(
                vendor = PowerPages.vendor(),
                ignoringBatteryOptimizations = Permissions.isIgnoringBatteryOptimizations(ctx)
            )
        }
    }

    fun openPowerPage(id: String) {
        PowerPages.open(ctx, id)
    }

    // ---- onboarding ---------------------------------------------------------

    fun setOnboardingStep(step: Int) = setInt(OptionsRepo.K.ONBOARDING_STEP, step)

    fun finishOnboarding() {
        viewModelScope.launch {
            repo.setBool(OptionsRepo.K.ONBOARDING_DONE, true)
            if (!repo.current().cloudDetected) {
                val detected = CloudApps.detectDefault(ctx)
                repo.setString(OptionsRepo.K.CLOUD_SINGLE, detected.id)
                repo.setString(OptionsRepo.K.CLOUD_PHOTOS, detected.id)
                repo.setString(OptionsRepo.K.CLOUD_VIDEOS, detected.id)
                repo.setBool(OptionsRepo.K.CLOUD_DETECTED, true)
            }
            Scheduler.ensure(ctx, repo.current())
            Scheduler.runNow(ctx)
        }
    }

    fun restartOnboarding() {
        viewModelScope.launch {
            repo.setBool(OptionsRepo.K.ONBOARDING_DONE, false)
            repo.setInt(OptionsRepo.K.ONBOARDING_STEP, 0)
            // Setup is unfinished again, so nothing should be scheduled until
            // it is finished again.
            Scheduler.cancelAll(ctx)
        }
    }

    // ---- test run (onboarding step 6) ---------------------------------------

    data class TestItem(
        val name: String,
        val before: Long,
        val after: Long,
        /** Pixels kept, or null when the encoder did not record them. */
        val keptPercent: Int? = null
    )

    /**
     * How many photos the trial would actually do.
     *
     * The button used to promise "3 files" whatever was there, so a phone
     * with two waiting photos was told a number the app could not deliver.
     */
    val trialSize: StateFlow<Int> = db.items().waitingPhotoCountFlow()
        .map { it.coerceAtMost(TRIAL_SIZE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Detail kept across this phone's optimised files, and the sample it was
     * measured on. Zero files means no figure, and the UI says nothing rather
     * than showing 0%.
     */
    data class DetailKept(val percent: Int, val files: Int)

    val detailKept: StateFlow<DetailKept?> = combine(
        db.items().detailKeptPercentFlow(),
        db.items().detailKeptSampleFlow()
    ) { percent, files ->
        if (files <= 0) null else DetailKept(percent.toInt().coerceIn(1, 100), files)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val testRun = MutableStateFlow<List<TestItem>?>(null)
    val testRunning = MutableStateFlow(false)

    fun startTestRun() {
        if (testRunning.value) return
        if (mediaAccess.value != Permissions.MediaAccess.FULL) return
        testRunning.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val o = repo.current()
                MediaScanner(ctx, db).scan()
                val stager = Stager(ctx, db)
                val picked = db.items().newestNewPhotos(TRIAL_SIZE)
                val results = mutableListOf<TestItem>()
                for (row in picked) {
                    if (stager.stageOne(row, o)) {
                        val updated = db.items().byId(row.id)
                        results += TestItem(
                            name = row.displayName,
                            before = row.sizeBytes,
                            after = updated?.outputBytes ?: row.sizeBytes,
                            keptPercent = updated?.let {
                                QualityKept.measuredDetailKeptPercent(it.srcPixels, it.outPixels)
                            }
                        )
                    }
                }
                testRun.value = results
                if (results.isNotEmpty()) {
                    activityLog.record(
                        ActivityLog.Kind.OPTIMISED,
                        detail = ctx.getString(R.string.trial_activity),
                        count = results.size,
                        bytes = results.sumOf { (it.before - it.after).coerceAtLeast(0) }
                    )
                }
            } finally {
                testRunning.value = false
            }
        }
    }

    // ---- Free-up originals tool --------------------------------------------

    val freeUpItems = MutableStateFlow<List<ItemRow>>(emptyList())

    fun loadFreeUp() {
        viewModelScope.launch(Dispatchers.Default) {
            val o = repo.current()
            val settled = System.currentTimeMillis() -
                EvidenceRules.RECLAIM_MIN_DAYS * 86_400_000L
            // Three grades, three different waiting periods. Seeing the cloud
            // take a copy is an observation and needs no delay; a byte count
            // that merely matched, and a batch total that covered the day, are
            // inferences, so those settle for a month first.
            val confirmed = db.items().freeableConfirmed()
            val paced = db.items().freeablePaced(settled)
            val verified = if (o.freeUpAllowVerified30) {
                db.items().freeableVerified(settled)
            } else {
                emptyList()
            }
            freeUpItems.value = (confirmed + paced + verified)
                .filter { it.state != ItemState.FREED.name && it.contentUri != null }
                .distinctBy { it.id }
                .sortedByDescending { it.sizeBytes }
        }
    }

    fun urisFor(rows: List<ItemRow>): List<Uri> =
        rows.mapNotNull { it.contentUri?.let(Uri::parse) }

    /**
     * Opens an item in whatever viewer the phone uses for it.
     *
     * Prefers the released copy when there is one - that is the file the user
     * is being told about - and falls back to the original. Read permission is
     * granted to the receiving app for that one uri only, and the chooser is
     * used so it works even where no default viewer is set.
     */
    fun openInViewer(row: ItemRow): Boolean {
        val uriString = row.outputUri ?: row.contentUri ?: return false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val mime = row.mimeType.ifEmpty { if (row.isVideo) "video/*" else "image/*" }
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        val chooser = android.content.Intent.createChooser(view, null).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ctx.startActivity(chooser) }.isSuccess
    }

    /** Clears the one-time notice about the removed legacy placeholder. */
    fun dismissPlaceholderNotice() {
        viewModelScope.launch {
            repo.setBool(OptionsRepo.K.PLACEHOLDER_REMOVED, false)
        }
    }

    fun onFreedConfirmed(rows: List<ItemRow>) {
        if (rows.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            for (row in rows) {
                db.items().update(
                    row.copy(state = ItemState.FREED.name, originalMissing = true, updatedAt = now)
                )
            }
            activityLog.record(
                ActivityLog.Kind.RECLAIMED,
                count = rows.size,
                bytes = rows.sumOf { it.sizeBytes },
                filterState = ItemState.FREED.name
            )
            loadFreeUp()
            // Snapshot right away rather than waiting for the daily pass: the
            // originals this just recorded as freed no longer exist, so this
            // state cannot be rebuilt by rescanning.
            runCatching { MaintainEngine(ctx).snapshotNow() }
        }
    }

    // ---- delete flows -------------------------------------------------------
    // API 30+: one system batch dialog (MediaStore.createDeleteRequest).
    // API 29: sequential RecoverableSecurityException flow, one consent per file.

    /** Emits when the API-29 flow needs the UI to launch a consent dialog. */
    val legacyDeleteIntent = MutableStateFlow<IntentSender?>(null)

    private var deleteOnDone: ((List<Uri>) -> Unit)? = null
    private var systemDialogUris: List<Uri> = emptyList()
    private val legacyQueue = ArrayDeque<Uri>()
    private val legacySucceeded = mutableListOf<Uri>()

    /**
     * Starts a delete of [uris]. Returns an IntentSender the UI must launch
     * (API 30+ batch dialog), or null when the API-29 sequential flow drives
     * itself via [legacyDeleteIntent]. [onDone] receives the deleted uris.
     */
    fun requestDelete(uris: List<Uri>, onDone: (List<Uri>) -> Unit): IntentSender? {
        deleteOnDone = onDone
        return if (Build.VERSION.SDK_INT >= 30) {
            systemDialogUris = uris
            MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender
        } else {
            systemDialogUris = emptyList()
            legacyQueue.clear()
            legacyQueue.addAll(uris)
            legacySucceeded.clear()
            processLegacyQueue()
            null
        }
    }

    /** UI reports the outcome of whichever consent dialog was shown. */
    fun onDeleteDialogResult(ok: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val uris = systemDialogUris
            systemDialogUris = emptyList()
            finishDelete(if (ok) uris else emptyList())
            return
        }
        legacyDeleteIntent.value = null
        if (legacyQueue.isNotEmpty()) {
            val uri = legacyQueue.removeFirst()
            if (ok) {
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        if (ctx.contentResolver.delete(uri, null, null) > 0) {
                            legacySucceeded.add(uri)
                        }
                    }
                    processLegacyQueue()
                }
                return
            }
        }
        processLegacyQueue()
    }

    private fun processLegacyQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            while (legacyQueue.isNotEmpty()) {
                val uri = legacyQueue.first()
                try {
                    if (ctx.contentResolver.delete(uri, null, null) > 0) {
                        legacySucceeded.add(uri)
                    }
                    legacyQueue.removeFirst()
                } catch (se: SecurityException) {
                    val sender = (se as? android.app.RecoverableSecurityException)
                        ?.userAction?.actionIntent?.intentSender
                    if (sender != null) {
                        legacyDeleteIntent.value = sender
                        return@launch
                    }
                    legacyQueue.removeFirst()
                } catch (e: Exception) {
                    legacyQueue.removeFirst()
                }
            }
            finishDelete(legacySucceeded.toList())
        }
    }

    private fun finishDelete(deleted: List<Uri>) {
        val callback = deleteOnDone
        deleteOnDone = null
        viewModelScope.launch { callback?.invoke(deleted) }
    }

    /** Marks the FreeUp rows whose originals were actually deleted as FREED. */
    fun onFreedByUris(deleted: List<Uri>) {
        if (deleted.isEmpty()) return
        val set = deleted.map { it.toString() }.toHashSet()
        onFreedConfirmed(freeUpItems.value.filter { it.contentUri in set })
    }

    // ---- old-install cleanup ------------------------------------------------

    val leftoverUris = MutableStateFlow<List<Uri>>(emptyList())

    fun detectLeftoverFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val o = repo.current()
            if (o.oldFilesCleaned) {
                leftoverUris.value = emptyList()
                return@launch
            }
            val knownFps = db.items().releasedFingerprints().toHashSet()
            val leftovers = (OutputInventory(ctx).query() ?: emptyList()).filter { entry ->
                if (entry.ownedByUs) return@filter false
                // Only a file named the way this pipeline names its output
                // can be an earlier install's leftover. Anything else in the
                // folder is the user's own file: this card used to sweep
                // those up too, and its Remove button would then have offered
                // the user's own photo for deletion under the label
                // "leftover". The user's files get a notice, never a button.
                val fp = Fingerprint.fpFromOutputName(entry.name)
                fp != null && fp !in knownFps
            }
            leftoverUris.value = leftovers.map { it.uri }
        }
    }

    fun onLeftoversCleaned() {
        viewModelScope.launch {
            repo.setBool(OptionsRepo.K.OLD_FILES_CLEANED, true)
            leftoverUris.value = emptyList()
        }
    }

    // ---- encrypted backup / restore -----------------------------------------

    val transferMessage = MutableStateFlow<String?>(null)

    /** Set when an import hit an encrypted file and needs the password. */
    val pendingImportUri = MutableStateFlow<Uri?>(null)
    val importPasswordWrong = MutableStateFlow(false)
    val transferBusy = MutableStateFlow(false)

    fun exportState(uri: Uri, password: String?, doneLabel: String, failLabel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            transferBusy.value = true
            val ok = SnapshotStore(ctx, db, repo).exportTo(uri, password)
            transferBusy.value = false
            transferMessage.value = if (ok) doneLabel else failLabel
        }
    }

    /**
     * Restores a backup file. An encrypted file with no password parks the uri
     * in [pendingImportUri] so the UI can ask for one and call this again.
     */
    fun importState(
        uri: Uri,
        password: String?,
        doneLabel: String,
        failLabel: String,
        wrongPasswordLabel: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            transferBusy.value = true
            val result = SnapshotStore(ctx, db, repo).importFrom(uri, password)
            transferBusy.value = false
            when (result) {
                is SnapshotStore.ImportResult.Success -> {
                    pendingImportUri.value = null
                    importPasswordWrong.value = false
                    transferMessage.value = "$doneLabel (${result.imported})"
                }
                SnapshotStore.ImportResult.NeedsPassword -> {
                    importPasswordWrong.value = false
                    pendingImportUri.value = uri
                }
                SnapshotStore.ImportResult.WrongPassword -> {
                    importPasswordWrong.value = true
                    pendingImportUri.value = uri
                    transferMessage.value = wrongPasswordLabel
                }
                SnapshotStore.ImportResult.Unreadable -> {
                    pendingImportUri.value = null
                    transferMessage.value = failLabel
                }
            }
        }
    }

    fun cancelPendingImport() {
        pendingImportUri.value = null
        importPasswordWrong.value = false
    }

    fun dismissTransferMessage() {
        transferMessage.value = null
    }

    // ---- tiny helpers -------------------------------------------------------

    private fun setStr(key: androidx.datastore.preferences.core.Preferences.Key<String>, v: String) {
        viewModelScope.launch { repo.setString(key, v) }
    }

    private fun setInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>, v: Int) {
        viewModelScope.launch { repo.setInt(key, v) }
    }

    private fun setBool(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, v: Boolean) {
        viewModelScope.launch { repo.setBool(key, v) }
    }
}
