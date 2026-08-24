package app.cloudsaver.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.cloudsaver.core.logic.DuplicateRules
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.ReclaimRules
import app.cloudsaver.core.logic.Suggestions
import app.cloudsaver.data.CloudApps
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.db.ReclaimBatchRow
import app.cloudsaver.data.db.ReclaimItemRow
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.engine.CloudWatchdog
import app.cloudsaver.engine.DuplicateScanner
import app.cloudsaver.engine.ReclaimEngine
import app.cloudsaver.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The one screen in the app that can destroy a user's photo.
 *
 * Its own view model, with a SavedStateHandle, because a selection of four
 * hundred files must survive a rotation or a process death - losing it and
 * silently starting again is how someone deletes the wrong batch.
 */
class ReclaimViewModel(
    app: Application,
    private val saved: SavedStateHandle
) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val db = AppDb.get(ctx)
    private val repo = OptionsRepo.get(ctx)
    private val engine = ReclaimEngine(ctx)

    companion object {
        private const val KEY_SELECTED = "reclaim.selected"
        private const val KEY_MODE = "reclaim.mode"
        private const val KEY_TARGET = "reclaim.target"
        private const val KEY_SORT = "reclaim.sort"
        private const val KEY_GROUP = "reclaim.group"
    }

    enum class Sort { LARGEST, OLDEST, ALBUM }
    enum class Grouping { NONE, ALBUM, MONTH, YEAR, TYPE }

    /** One row as the screen needs it: the rules' view plus what to draw. */
    data class Entry(
        val row: ItemRow,
        val candidate: ReclaimRules.Candidate
    ) {
        val id: Long get() = row.id
        val saving: Long get() = (row.sizeBytes - (row.outputBytes ?: 0L)).coerceAtLeast(0L)
    }

    val entries = MutableStateFlow<List<Entry>>(emptyList())
    val loading = MutableStateFlow(false)

    /** Selection, mode, target and view options all survive a rotation. */
    val selected = MutableStateFlow(saved.get<LongArray>(KEY_SELECTED)?.toSet() ?: emptySet())
    val mode = MutableStateFlow(
        saved.get<String>(KEY_MODE)?.let { runCatching { ReclaimRules.Mode.valueOf(it) }.getOrNull() }
            ?: ReclaimRules.Mode.REPLACE_WITH_LIGHT
    )
    val targetBytes = MutableStateFlow(saved.get<Long>(KEY_TARGET) ?: 0L)
    val sort = MutableStateFlow(
        saved.get<String>(KEY_SORT)?.let { runCatching { Sort.valueOf(it) }.getOrNull() }
            ?: Sort.LARGEST
    )
    val grouping = MutableStateFlow(
        saved.get<String>(KEY_GROUP)?.let { runCatching { Grouping.valueOf(it) }.getOrNull() }
            ?: Grouping.NONE
    )
    val suggestion = MutableStateFlow<Suggestions.Kind?>(null)
    val videosOnly = MutableStateFlow(false)
    val minSizeFilter = MutableStateFlow(0L)

    val skipFavourites = MutableStateFlow(true)
    val skipSmall = MutableStateFlow(true)

    /** Result of the last batch, and of a dry run. */
    val lastResult = MutableStateFlow<ReclaimEngine.Result?>(null)
    val dryRun = MutableStateFlow<DryRun?>(null)
    val pendingIntent = MutableStateFlow<IntentSender?>(null)
    val busy = MutableStateFlow(false)

    private var prepared: ReclaimEngine.Prepared? = null
    private var pendingMode: ReclaimRules.Mode = ReclaimRules.Mode.REPLACE_WITH_LIGHT
    private var pendingTrash = true

    data class DryRun(
        val count: Int,
        val freedBytes: Long,
        val dropped: List<Pair<String, ReclaimRules.Refusal>>
    )

    // ---- loading -------------------------------------------------------------

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            loading.value = true
            val o = repo.current()
            val healthy = cloudHealthy()
            val now = System.currentTimeMillis()
            val ledgerByHash = db.ledger().all().associateBy { it.outputSha256 }
            val rows = db.items().reclaimCandidates()
            entries.value = rows.mapNotNull { row ->
                val candidate = row.toCandidate(now, ledgerByHash.containsKey(row.outputSha256))
                if (!ReclaimRules.isEligible(
                        candidate, healthy, o.freeUpAllowVerified30,
                        skipFavourites.value, skipSmall.value
                    )
                ) {
                    null
                } else {
                    Entry(row, candidate)
                }
            }
            loading.value = false
        }
    }

    private suspend fun cloudHealthy(): Boolean {
        val o = repo.current()
        if (o.cloudProblem.isNotEmpty()) return false
        return CloudApps.isAppInstalled(ctx, o.cloudSingle)
    }

    private fun ItemRow.toCandidate(now: Long, inLedger: Boolean) = ReclaimRules.Candidate(
        id = id,
        fingerprint = fingerprint,
        sizeBytes = sizeBytes,
        optimisedBytes = outputBytes ?: 0L,
        evidence = Evidence.parse(evidence),
        confirmedAgeDays = Formats.daysBetween(confirmedAt ?: releasedAt ?: now, now),
        state = runCatching { ItemState.valueOf(state) }.getOrDefault(ItemState.UNKNOWN),
        hasLedgerEntry = inLedger,
        // The ledger is keyed by the copy's hash, so finding the row at all is
        // the hash check: a changed copy would hash to something else.
        ledgerHashMatches = inLedger,
        originalPresent = !originalMissing,
        inExcludedAlbum = false,
        isFavourite = false,
        addedDaysAgo = Formats.daysBetween(dateAdded * 1000, now),
        isVideo = isVideo,
        album = bucket,
        capturedAtMs = captureAt
    )

    /** The list after filters, sorting and any active suggestion. */
    fun visible(): List<Entry> {
        val now = System.currentTimeMillis()
        var list = entries.value
        suggestion.value?.let { kind ->
            val filter = Suggestions.ALL.first { it.kind == kind }
            val keep = Suggestions.apply(
                list.map { it.candidate }, filter,
                ageDaysOf = { c -> Formats.daysBetween(c.capturedAtMs, now) },
                nameOf = { c -> list.first { e -> e.id == c.id }.row.displayName }
            ).map { it.id }.toSet()
            list = list.filter { it.id in keep }
        }
        if (videosOnly.value) list = list.filter { it.row.isVideo }
        if (minSizeFilter.value > 0) list = list.filter { it.row.sizeBytes >= minSizeFilter.value }
        return when (sort.value) {
            Sort.LARGEST -> list.sortedByDescending { it.row.sizeBytes }
            Sort.OLDEST -> list.sortedBy { it.row.captureAt }
            Sort.ALBUM -> list.sortedWith(
                compareBy({ it.row.bucket ?: "" }, { -it.row.sizeBytes })
            )
        }
    }

    fun groups(): Map<String, List<Entry>> {
        val list = visible()
        return when (grouping.value) {
            Grouping.NONE -> mapOf("" to list)
            Grouping.ALBUM -> list.groupBy { it.row.bucket ?: "" }
            Grouping.MONTH -> list.groupBy { Formats.monthKey(it.row.captureAt) }
            Grouping.YEAR -> list.groupBy { Formats.yearKey(it.row.captureAt) }
            Grouping.TYPE -> list.groupBy { if (it.row.isVideo) "video" else "photo" }
        }
    }

    // ---- selection -----------------------------------------------------------

    fun toggle(id: Long) {
        selected.value = selected.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        persistSelection()
    }

    fun selectAllVisible() {
        selected.value = selected.value + visible().map { it.id }
        persistSelection()
    }

    fun selectGroup(key: String) {
        selected.value = selected.value + (groups()[key]?.map { it.id } ?: emptyList())
        persistSelection()
    }

    fun invertSelection() {
        val visible = visible().map { it.id }.toSet()
        selected.value = visible - selected.value
        persistSelection()
    }

    fun clearSelection() {
        selected.value = emptySet()
        persistSelection()
    }

    fun selectOnlyVideos() {
        selected.value = visible().filter { it.row.isVideo }.map { it.id }.toSet()
        persistSelection()
    }

    fun selectLargerThan(bytes: Long) {
        selected.value = visible().filter { it.row.sizeBytes >= bytes }.map { it.id }.toSet()
        persistSelection()
    }

    /** "Free 5 GB": pick largest-first until the number is met. */
    fun selectForTarget(bytes: Long) {
        targetBytes.value = bytes
        saved[KEY_TARGET] = bytes
        val chosen = ReclaimRules.selectForTarget(
            visible().map { it.candidate }, bytes, mode.value
        )
        selected.value = chosen.map { it.id }.toSet()
        persistSelection()
    }

    fun setMode(value: ReclaimRules.Mode) {
        mode.value = value
        saved[KEY_MODE] = value.name
    }

    fun setSort(value: Sort) {
        sort.value = value
        saved[KEY_SORT] = value.name
    }

    fun setGrouping(value: Grouping) {
        grouping.value = value
        saved[KEY_GROUP] = value.name
    }

    private fun persistSelection() {
        saved[KEY_SELECTED] = selected.value.toLongArray()
    }

    fun selectedEntries(): List<Entry> = visible().filter { it.id in selected.value }

    fun savedBytesForMode(m: ReclaimRules.Mode = mode.value): Long =
        ReclaimRules.savedBytes(selectedEntries().map { it.candidate }, m)

    fun needsSecondConfirmation(permanent: Boolean): Boolean =
        ReclaimRules.needsSecondConfirmation(
            selectedEntries().map { it.candidate }, entries.value.size, mode.value, permanent
        )

    // ---- dry run and export --------------------------------------------------

    /** Says exactly what would happen, and touches nothing. */
    fun previewResult() {
        viewModelScope.launch(Dispatchers.IO) {
            val o = repo.current()
            val healthy = cloudHealthy()
            val chosen = selectedEntries()
            val dropped = chosen.mapNotNull { entry ->
                ReclaimRules.refuse(
                    entry.candidate, healthy, o.freeUpAllowVerified30,
                    skipFavourites.value, skipSmall.value
                )?.let { entry.row.displayName to it }
            }
            val keptIds = dropped.map { it.first }.toSet()
            val surviving = chosen.filter { it.row.displayName !in keptIds }
            dryRun.value = DryRun(
                count = surviving.size,
                freedBytes = ReclaimRules.savedBytes(surviving.map { it.candidate }, mode.value),
                dropped = dropped
            )
        }
    }

    fun dismissDryRun() {
        dryRun.value = null
    }

    fun exportSelection(uri: Uri, doneLabel: String, failLabel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val text = buildString {
                    appendLine("name,album,original,optimised,confirmation,confirmed")
                    for (entry in selectedEntries()) {
                        val row = entry.row
                        appendLine(
                            listOf(
                                row.displayName.replace(',', ' '),
                                (row.bucket ?: "").replace(',', ' '),
                                Formats.bytes(row.sizeBytes),
                                Formats.bytes(row.outputBytes ?: 0L),
                                Evidence.parse(row.evidence).name,
                                Formats.date(row.confirmedAt ?: 0)
                            ).joinToString(",")
                        )
                    }
                }
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: error("no stream")
            }.isSuccess
            message.value = if (ok) doneLabel else failLabel
        }
    }

    val message = MutableStateFlow<String?>(null)

    fun dismissMessage() {
        message.value = null
    }

    // ---- the batch itself ----------------------------------------------------

    /**
     * Runs the batch. Copies-only never needs Android's dialog, because those
     * files belong to the app; anything touching an original always does.
     */
    fun start(permanent: Boolean) {
        val chosen = selectedEntries()
        if (chosen.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            busy.value = true
            try {
                val now = System.currentTimeMillis()
                if (mode.value == ReclaimRules.Mode.COPIES_ONLY) {
                    lastResult.value = engine.removeCopiesOnly(chosen.map { it.row }, now)
                    clearSelection()
                    load()
                    return@launch
                }
                // Re-check at the moment of action: a list can sit open while
                // the cloud app is uninstalled underneath it.
                val o = repo.current()
                val healthy = cloudHealthy()
                val stillGood = chosen.filter {
                    ReclaimRules.isEligible(
                        it.candidate, healthy, o.freeUpAllowVerified30,
                        skipFavourites.value, skipSmall.value
                    )
                }
                val ready = engine.prepare(stillGood.map { it.row }, mode.value, now)
                prepared = ready
                pendingMode = mode.value
                pendingTrash = !permanent
                if (ready.uris.isEmpty()) {
                    lastResult.value = engine.finish(ready, emptySet(), mode.value, !permanent, now)
                    return@launch
                }
                val request = requestFor(ready.uris, permanent)
                if (request == null) {
                    pendingTrash = false
                    startLegacy(ready.uris)
                } else {
                    pendingIntent.value = request
                }
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Trash first. A trashed file comes back from the gallery for 30 days;
     * a deleted one never does, and this is a batch operation on someone's
     * photographs.
     *
     * Returns null on Android 10, which has neither batch request: there the
     * caller falls back to one system consent per file.
     */
    private fun requestFor(uris: List<Uri>, permanent: Boolean): IntentSender? {
        if (Build.VERSION.SDK_INT < 30) return null
        val resolver = ctx.contentResolver
        return if (permanent) {
            MediaStore.createDeleteRequest(resolver, uris).intentSender
        } else {
            MediaStore.createTrashRequest(resolver, uris, true).intentSender
        }
    }

    // ---- Android 10: one consent per file ------------------------------------

    /**
     * Android 10 has no batch delete request and no trash, so each file is
     * attempted directly and the system's own recoverable-security prompt is
     * shown for the ones that need it. Slower, and the only thing that works
     * there.
     */
    private val legacyQueue = ArrayDeque<Uri>()
    private val legacyDeleted = mutableSetOf<String>()

    private fun startLegacy(uris: List<Uri>) {
        legacyQueue.clear()
        legacyQueue.addAll(uris)
        legacyDeleted.clear()
        pumpLegacy()
    }

    private fun pumpLegacy() {
        viewModelScope.launch(Dispatchers.IO) {
            while (legacyQueue.isNotEmpty()) {
                val uri = legacyQueue.first()
                try {
                    if (ctx.contentResolver.delete(uri, null, null) > 0) {
                        legacyDeleted += uri.toString()
                    }
                    legacyQueue.removeFirst()
                } catch (se: SecurityException) {
                    val sender = (se as? android.app.RecoverableSecurityException)
                        ?.userAction?.actionIntent?.intentSender
                    if (sender != null) {
                        pendingIntent.value = sender
                        return@launch
                    }
                    legacyQueue.removeFirst()
                } catch (e: Exception) {
                    legacyQueue.removeFirst()
                }
            }
            finishLegacy()
        }
    }

    private fun finishLegacy() {
        val ready = prepared ?: return
        prepared = null
        viewModelScope.launch(Dispatchers.IO) {
            lastResult.value = engine.finish(
                ready, legacyDeleted.toSet(), pendingMode, false, System.currentTimeMillis()
            )
            clearSelection()
            load()
        }
    }

    fun onDialogResult(granted: Boolean) {
        pendingIntent.value = null
        if (Build.VERSION.SDK_INT < 30 && legacyQueue.isNotEmpty()) {
            // One file's consent came back; take it and move to the next.
            val uri = legacyQueue.removeFirst()
            if (granted) {
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        if (ctx.contentResolver.delete(uri, null, null) > 0) {
                            legacyDeleted += uri.toString()
                        }
                    }
                    pumpLegacy()
                }
            } else {
                pumpLegacy()
            }
            return
        }
        val ready = prepared ?: return
        prepared = null
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = if (granted) ready.rows.mapNotNull { it.contentUri }.toSet() else emptySet()
            lastResult.value = engine.finish(
                ready, deleted, pendingMode, pendingTrash, System.currentTimeMillis()
            )
            clearSelection()
            load()
        }
    }

    fun dismissResult() {
        lastResult.value = null
    }

    // ---- history and restore -------------------------------------------------

    val history = db.reclaim().recentBatchesFlow(50)

    val historyItems = MutableStateFlow<List<ReclaimItemRow>>(emptyList())

    fun loadBatch(batch: ReclaimBatchRow) {
        viewModelScope.launch(Dispatchers.IO) {
            historyItems.value = db.reclaim().itemsOf(batch.id)
        }
    }

    private var restoring: List<ReclaimItemRow> = emptyList()

    /** Untrash, with the system dialog: the files are the gallery's, not ours. */
    fun restore(items: List<ReclaimItemRow>) {
        val uris = items.mapNotNull { row -> row.contentUri?.let { Uri.parse(it) } }
        // Nothing to restore from on Android 10: it has no trash, so those
        // batches were permanent and the history says so.
        if (uris.isEmpty() || Build.VERSION.SDK_INT < 30) return
        restoring = items
        pendingIntent.value =
            MediaStore.createTrashRequest(ctx.contentResolver, uris, false).intentSender
    }

    fun onRestoreResult(granted: Boolean) {
        pendingIntent.value = null
        val items = restoring
        restoring = emptyList()
        if (!granted || items.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (item in items) engine.onRestored(item)
            historyItems.value = db.reclaim().itemsOf(items.first().batchId)
            load()
        }
    }

    // ---- find space ----------------------------------------------------------

    val duplicateGroups = MutableStateFlow<List<DuplicateRules.Group>>(emptyList())
    val largest = MutableStateFlow<List<ItemRow>>(emptyList())

    fun loadDuplicates() {
        viewModelScope.launch(Dispatchers.IO) {
            duplicateGroups.value = DuplicateScanner(ctx).groups()
        }
    }

    fun loadLargest() {
        viewModelScope.launch(Dispatchers.IO) {
            largest.value = db.items().largest(50)
        }
    }

    /** Moves chosen items to the front of the queue. */
    fun optimiseFirst(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            for (id in ids) {
                val row = db.items().byId(id) ?: continue
                if (row.state != ItemState.SKIP.name && row.state != ItemState.NEW.name) continue
                // captureAt drives the queue order, so bringing an item
                // forward means making it look like the newest thing there is.
                db.items().update(
                    row.copy(
                        state = ItemState.NEW.name,
                        skipReason = null,
                        captureAt = now,
                        updatedAt = now
                    )
                )
            }
        }
    }

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
}
