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
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.Fingerprint
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.OutputMode
import app.cloudsaver.core.logic.Preset
import app.cloudsaver.core.logic.SpeedMode
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.core.logic.VideoCodec
import app.cloudsaver.data.CloudApps
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.engine.MaintainEngine
import app.cloudsaver.engine.SnapshotStore
import app.cloudsaver.engine.UsageVerifier
import app.cloudsaver.media.MediaScanner
import app.cloudsaver.media.OutputInventory
import app.cloudsaver.media.Stager
import app.cloudsaver.util.Permissions
import app.cloudsaver.util.Storage
import app.cloudsaver.util.TamperCheck
import app.cloudsaver.util.Volumes
import app.cloudsaver.work.Scheduler
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

    data class Counters(
        val waiting: Int = 0,
        val inFolder: Int = 0,
        val confirmed: Int = 0,
        val likely: Int = 0
    )

    val counters: StateFlow<Counters> = combine(
        db.items().stateCountsFlow(),
        db.items().confirmedCountFlow(),
        db.items().verifiedCountFlow()
    ) { states, confirmed, likely ->
        val byState = states.associate { it.state to it.cnt }
        Counters(
            waiting = (byState[ItemState.NEW.name] ?: 0) + (byState[ItemState.STAGED.name] ?: 0),
            inFolder = byState[ItemState.RELEASED.name] ?: 0,
            confirmed = confirmed,
            likely = likely
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Counters())

    val savedBytes: StateFlow<Long> =
        db.items().savedBytesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val processedCount: StateFlow<Int> =
        db.items().processedCountFlow().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val reclaimableBytes: StateFlow<Long> =
        db.items().reclaimableBytesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // ---- files list ---------------------------------------------------------

    val search = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<ItemRow>> = search
        .flatMapLatest { q -> db.items().searchFlow(q, 500) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
            val found = runCatching { MediaScanner(ctx, db).queryAll() }
                .getOrDefault(emptyList())
                .filter { it.bucket == null || it.bucket !in o.excludedBuckets }
            val cutoff = System.currentTimeMillis() / 1000 - 30L * 86_400
            var photoBytes = 0L
            var videoBytes = 0L
            var videoMinutes = 0.0
            var monthlyPhoto = 0L
            var monthlyVideo = 0L
            for (f in found) {
                if (f.isVideo) {
                    videoBytes += f.sizeBytes
                    videoMinutes += f.durationMs / 60_000.0
                    if (f.dateAdded >= cutoff) monthlyVideo += f.sizeBytes
                } else {
                    photoBytes += f.sizeBytes
                    if (f.dateAdded >= cutoff) monthlyPhoto += f.sizeBytes
                }
            }
            calcGallery.value = CapacityMath.Gallery(
                photoBytes, videoBytes, videoMinutes, monthlyPhoto, monthlyVideo
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

    // ---- option setters -----------------------------------------------------

    fun setScope(v: BackupScope) = setStr(OptionsRepo.K.SCOPE, v.name)
    fun setOutputMode(v: OutputMode) = setStr(OptionsRepo.K.OUTPUT_MODE, v.name)
    fun setCloudSingle(v: String) = setStr(OptionsRepo.K.CLOUD_SINGLE, v)
    fun setCloudPhotos(v: String) = setStr(OptionsRepo.K.CLOUD_PHOTOS, v)
    fun setCloudVideos(v: String) = setStr(OptionsRepo.K.CLOUD_VIDEOS, v)
    fun setPreset(v: Preset) = setStr(OptionsRepo.K.PRESET, v.name)
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

    fun loadBuckets() {
        viewModelScope.launch(Dispatchers.IO) {
            buckets.value = runCatching { MediaScanner(ctx, db).buckets() }.getOrDefault(emptyList())
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
            val confirmed = db.items().freeableConfirmed()
            val verified = if (o.freeUpAllowVerified30) {
                db.items().freeableVerified(System.currentTimeMillis() - 30L * 86_400_000L)
            } else {
                emptyList()
            }
            freeUpItems.value = (confirmed + verified)
                .filter { it.state != ItemState.FREED.name && it.contentUri != null }
                .distinctBy { it.id }
                .sortedByDescending { it.sizeBytes }
        }
    }

    fun urisFor(rows: List<ItemRow>): List<Uri> =
        rows.mapNotNull { it.contentUri?.let(Uri::parse) }

    fun onFreedConfirmed(rows: List<ItemRow>) {
        viewModelScope.launch(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            for (row in rows) {
                db.items().update(
                    row.copy(state = ItemState.FREED.name, originalMissing = true, updatedAt = now)
                )
            }
            loadFreeUp()
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
                    val sender = if (Build.VERSION.SDK_INT >= 29) {
                        (se as? android.app.RecoverableSecurityException)
                            ?.userAction?.actionIntent?.intentSender
                    } else {
                        null
                    }
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

    // ---- About --------------------------------------------------------------

    val apkSha = MutableStateFlow("")

    fun computeApkSha() {
        if (apkSha.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            apkSha.value = try {
                val path = ctx.applicationInfo.sourceDir
                val md = MessageDigest.getInstance("SHA-256")
                FileInputStream(path).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { b -> "%02x".format(b) }
            } catch (e: Exception) {
                "-"
            }
        }
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
