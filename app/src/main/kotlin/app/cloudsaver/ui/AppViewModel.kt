package app.cloudsaver.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import app.cloudsaver.engine.CloudWatchdog
import app.cloudsaver.engine.MaintainEngine
import app.cloudsaver.engine.SnapshotStore
import app.cloudsaver.engine.UsageVerifier
import app.cloudsaver.media.MediaScanner
import app.cloudsaver.media.OutputInventory
import app.cloudsaver.media.Stager
import app.cloudsaver.util.Formats
import app.cloudsaver.util.Permissions
import app.cloudsaver.util.Storage
import app.cloudsaver.util.TamperCheck
import app.cloudsaver.util.Volumes
import app.cloudsaver.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    val repo = OptionsRepo.get(ctx)
    private val db = AppDb.get(ctx)

    val options: StateFlow<Options> =
        repo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Options())

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
        val skipped: Int = 0
    )

    val counters: StateFlow<Counters> = combine(
        db.items().stateCountsFlow(),
        db.items().confirmedCountFlow(),
        db.items().verifiedCountFlow()
    ) { states, confirmed, verified ->
        val byState = states.associate { it.state to it.cnt }
        Counters(
            waiting = byState[ItemState.NEW.name] ?: 0,
            inFolder = (byState[ItemState.STAGED.name] ?: 0) +
                (byState[ItemState.RELEASED.name] ?: 0),
            // Both count as backed up on Home; how strong the evidence is
            // belongs in the item's details, not in a headline number.
            confirmed = confirmed + verified,
            skipped = byState[ItemState.SKIP.name] ?: 0
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Counters())

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

    /** Files copied byte-for-byte, with the reasons, for the "kept as is" card. */
    data class AsIs(val count: Int = 0, val reasons: List<Pair<String, Int>> = emptyList())

    val asIs = MutableStateFlow(AsIs())

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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val searchResults: StateFlow<List<ItemRow>> = search
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
        val paused: Boolean = false
    )

    val health = MutableStateFlow(Health())

    fun refreshHealth() {
        viewModelScope.launch(Dispatchers.Default) {
            val o = repo.current()
            health.value = Health(
                batteryRestricted = !Permissions.isIgnoringBatteryOptimizations(ctx),
                usageAccessOff = !UsageVerifier.hasUsageAccess(ctx),
                cloudMissing = !CloudApps.isAppInstalled(ctx, o.cloudSingle),
                spaceLow = Storage.freeBytes(ctx) < o.minFreeBytes,
                paused = o.pauseAll
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

    data class StorageStats(val stageBytes: Long = 0, val outputBytes: Long = 0)

    val storageStats = MutableStateFlow(StorageStats())

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            storageStats.value = StorageStats(
                stageBytes = Storage.totalStageBytes(ctx),
                outputBytes = db.items().releasedBytes()
            )
        }
    }

    // ---- storage volumes (13.D) ----------------------------------------

    val volumes = MutableStateFlow<List<Volumes.Vol>>(emptyList())

    fun refreshVolumes() {
        viewModelScope.launch(Dispatchers.IO) {
            volumes.value = Volumes.list(ctx)
        }
    }

    // ---- cloud calculator (13.C) ----------------------------------------

    val calcGallery = MutableStateFlow<CapacityMath.Gallery?>(null)
    val calcRatios = MutableStateFlow<CapacityMath.Ratios?>(null)

    fun refreshCalculator() {
        viewModelScope.launch(Dispatchers.IO) {
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
                monthlyVideoBytes = totals.monthlyVideoBytes
            )
            val photoSamples = db.items().photoRatioSamples(o.preset.name).map {
                CapacityMath.Sample(it.sizeBytes, it.outputBytes)
            }
            val videoSamples = db.items().videoRatioSamples(o.preset.name, o.codec.name).map {
                CapacityMath.Sample(it.sizeBytes, it.outputBytes, it.durationMs / 60_000.0)
            }
            calcRatios.value = CapacityMath.ratios(photoSamples, videoSamples, o.codec)
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
            Storage.cleanTemp(ctx)
            refreshStorage()
        }
    }

    // ---- actions ------------------------------------------------------------

    fun runNow() {
        Scheduler.runNow(ctx)
        Scheduler.maintainNow(ctx)
    }

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

    fun setScope(v: BackupScope) = setStr(OptionsRepo.K.SCOPE, v.name)
    fun setOutputMode(v: OutputMode) = setStr(OptionsRepo.K.OUTPUT_MODE, v.name)
    fun setCloudSingle(v: String) {
        setStr(OptionsRepo.K.CLOUD_SINGLE, v)
        noteSettingChange(detail = CloudApps.byId(v).label)
        refreshCloudCaps()
    }
    fun setCloudPhotos(v: String) = setStr(OptionsRepo.K.CLOUD_PHOTOS, v)
    fun setCloudVideos(v: String) = setStr(OptionsRepo.K.CLOUD_VIDEOS, v)
    fun setPreset(v: Preset) {
        setStr(OptionsRepo.K.PRESET, v.name)
        noteSettingChange(detail = v.name)
    }
    fun setCodec(v: VideoCodec) = setStr(OptionsRepo.K.CODEC, v.name)
    fun setTheme(v: ThemeMode) = setStr(OptionsRepo.K.THEME, v.name)
    fun setDynamicColor(v: Boolean) = setBool(OptionsRepo.K.DYNAMIC_COLOR, v)
    fun setDailyCap(v: Int) = setInt(OptionsRepo.K.DAILY_CAP_MB, v)
    fun setMinFree(v: Int) = setInt(OptionsRepo.K.MIN_FREE_MB, v)
    fun setMaxExtra(v: Int) = setInt(OptionsRepo.K.MAX_EXTRA_MB, v)
    fun setAppLock(v: Boolean) = setBool(OptionsRepo.K.APP_LOCK, v)
    fun setWarningsNotif(v: Boolean) = setBool(OptionsRepo.K.WARNINGS_NOTIF, v)
    fun setShowFreeUp(v: Boolean) = setBool(OptionsRepo.K.SHOW_FREE_UP, v)
    fun setFreeUpVerified30(v: Boolean) = setBool(OptionsRepo.K.FREE_UP_VERIFIED30, v)
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
    }

    fun setStorageVolume(v: String) = setStr(OptionsRepo.K.STORAGE_VOLUME, v)

    fun setExcludedBuckets(v: Set<String>) {
        viewModelScope.launch { repo.setStringSet(OptionsRepo.K.EXCLUDED_BUCKETS, v) }
    }

    val buckets = MutableStateFlow<List<String>>(emptyList())

    /** Folders the app refuses to scan, with why - shown greyed out in the picker. */
    val lockedBuckets = MutableStateFlow<List<Pair<String, ScanSources.Reason>>>(emptyList())

    fun loadBuckets() {
        viewModelScope.launch(Dispatchers.IO) {
            val scanner = MediaScanner(ctx, db)
            buckets.value = runCatching { scanner.buckets() }.getOrDefault(emptyList())
            lockedBuckets.value = runCatching {
                scanner.excludedBucketReasons().toList().sortedBy { it.first }
            }.getOrDefault(emptyList())
        }
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
        }
    }

    // ---- test run (onboarding step 6) ---------------------------------------

    data class TestItem(val name: String, val before: Long, val after: Long)

    val testRun = MutableStateFlow<List<TestItem>?>(null)
    val testRunning = MutableStateFlow(false)

    fun startTestRun() {
        if (testRunning.value) return
        testRunning.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val o = repo.current()
                MediaScanner(ctx, db).scan()
                val stager = Stager(ctx, db)
                val picked = db.items().newestNew(12).filter { !it.isVideo }.take(3)
                    .ifEmpty { db.items().newestNew(3) }
                val results = mutableListOf<TestItem>()
                for (row in picked) {
                    if (stager.stageOne(row, o)) {
                        val updated = db.items().byId(row.id)
                        results += TestItem(
                            row.displayName, row.sizeBytes, updated?.outputBytes ?: row.sizeBytes
                        )
                    }
                }
                testRun.value = results
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

    val foreignUris = MutableStateFlow<List<Uri>>(emptyList())

    fun detectForeignFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val o = repo.current()
            if (o.oldFilesCleaned) {
                foreignUris.value = emptyList()
                return@launch
            }
            val knownFps = db.items().all()
                .filter { it.state == ItemState.RELEASED.name }
                .map { it.fingerprint }
                .toHashSet()
            val foreign = (OutputInventory(ctx).query() ?: emptyList()).filter { entry ->
                if (entry.ownedByUs) return@filter false
                val fp = Fingerprint.fpFromOutputName(entry.name)
                fp == null || fp !in knownFps
            }
            foreignUris.value = foreign.map { it.uri }
        }
    }

    fun onForeignCleaned() {
        viewModelScope.launch {
            repo.setBool(OptionsRepo.K.OLD_FILES_CLEANED, true)
            foreignUris.value = emptyList()
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
