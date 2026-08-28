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
import app.cloudsaver.core.logic.ListFilters
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.Platform
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.cloudsaver.R
import app.cloudsaver.engine.ActivityLog

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
        /** The two kinds of proof, kept visibly apart (v3.0 C1). */
        const val GROUP_EXACT = "exact"
        const val GROUP_BY_SIZE = "by_size"

        private const val KEY_SELECTED = "reclaim.selected"
        private const val KEY_MODE = "reclaim.mode"
        private const val KEY_TARGET = "reclaim.target"
        private const val KEY_SORT = "reclaim.sort"
        private const val KEY_GROUP = "reclaim.group"
    }

    enum class Sort { LARGEST, OLDEST, ALBUM }
    enum class Grouping { EVIDENCE, ALBUM, MONTH, YEAR, TYPE }



    /** One row as the screen needs it: the rules' view plus what to draw. */
    data class Entry(
        val row: ItemRow,
        val candidate: ReclaimRules.Candidate
    ) {
        val id: Long get() = row.id
        val saving: Long get() = (row.sizeBytes - (row.outputBytes ?: 0L)).coerceAtLeast(0L)
    }

    val entries = MutableStateFlow<List<Entry>>(emptyList())

    /**
     * Where a replacement copy lands, live from the stored options so the
     * choice reads the same here as it does anywhere else it is shown.
     */
    val keptInPlace: StateFlow<Boolean> = repo.flow
        .map { it.keptInPlace }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setKeptInPlace(value: Boolean) {
        viewModelScope.launch { repo.setBool(OptionsRepo.K.KEPT_IN_PLACE, value) }
    }

    /**
     * Which cloud app holds each batch's copies (Z10.1). Proof belongs to the
     * app that was selected when the file was sent, so the confirmation sheet
     * names that app - not whichever app is selected today.
     */
    val holdingApps = MutableStateFlow<Map<Long, String>>(emptyMap())
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
            ?: Grouping.EVIDENCE
    )
    val suggestion = MutableStateFlow<Suggestions.Kind?>(null)
    val videosOnly = MutableStateFlow(false)
    val minSizeFilter = MutableStateFlow(0L)

    val skipFavourites = MutableStateFlow(true)
    val skipSmall = MutableStateFlow(true)

    /**
     * Whether this phone can undo a removal.
     *
     * Android 10 has no media trash. Offering "Move to trash" there and then
     * permanently deleting would be the worst thing this screen could do, so
     * the screen asks this and words itself accordingly.
     */
    val canUndoRemoval: Boolean = Platform.canTrash(Build.VERSION.SDK_INT)

    /** Result of the last batch, and of a dry run. */
    val lastResult = MutableStateFlow<ReclaimEngine.Result?>(null)
    val dryRun = MutableStateFlow<DryRun?>(null)
    val pendingIntent = MutableStateFlow<IntentSender?>(null)
    val busy = MutableStateFlow(false)

    private var prepared: ReclaimEngine.Prepared? = null
    private var pendingMode: ReclaimRules.Mode = ReclaimRules.Mode.REPLACE_WITH_LIGHT
    private var pendingTrash = true

    /**
     * A large selection is confirmed in chunks, one system dialog each.
     *
     * The whole selection used to go into a single request. Nothing in the
     * platform documents a maximum, but every URI rides into a PendingIntent
     * through a size-limited binder transaction, and a dialog listing a
     * thousand files is not something anyone can read before agreeing to it.
     * What the user actually approves is recorded chunk by chunk, so the
     * result reflects the taps rather than assuming the whole batch went.
     */
    private var consentChunks: ArrayDeque<List<Uri>> = ArrayDeque()
    private var consentCurrent: List<Uri> = emptyList()
    private val consentConfirmed = mutableSetOf<String>()
    private var consentPermanent = false

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
            holdingApps.value = rows.mapNotNull { it.batchId }.distinct()
                .mapNotNull { id ->
                    db.batches().byId(id)?.cloudPackage?.let { pkg -> id to pkg }
                }
                .toMap()
            // Both grades are offered, always. The old switch asked the user
            // to configure their own safety, which is a question nobody can
            // answer; the list simply separates the two and says what each
            // one means, and nothing in the weaker group starts selected.
            entries.value = rows.mapNotNull { row ->
                val candidate = row.toCandidate(now, ledgerByHash.containsKey(row.outputSha256))
                if (!ReclaimRules.isEligible(
                        candidate, healthy, allowVerifiedBySize = true,
                        skipFavourites = skipFavourites.value, skipSmall = skipSmall.value
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
    /**
     * The shared list filters, so Reclaim narrows the same way as every other
     * list rather than through its own private set of chips.
     */
    val listFilter = MutableStateFlow(ListFilters.State())

    fun visible(): List<Entry> {
        val now = System.currentTimeMillis()
        var list = entries.value
        val shared = listFilter.value
        if (!shared.isDefault || shared.query.isNotBlank()) {
            list = list.filter {
                ListFilters.matches(
                    ListFilters.Candidate(
                        id = it.id,
                        name = it.row.displayName,
                        album = it.row.bucket,
                        sizeBytes = it.row.sizeBytes,
                        isVideo = it.row.isVideo
                    ),
                    shared
                )
            }
        }
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
            Grouping.EVIDENCE -> list.groupBy {
                if (it.candidate.evidence == Evidence.VERIFIED) GROUP_BY_SIZE else GROUP_EXACT
            }
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

    fun clearSelection() {
        selected.value = emptySet()
        persistSelection()
    }

    /**
     * Hands one file to Reclaim, ticked and alone.
     *
     * Removing an original from anywhere else in the app goes through here
     * rather than doing its own deletion: Reclaim is where the eligibility
     * gate, the mode choice and the trash-first rule live, and a second path
     * that skipped any of them would be the one that eventually loses a photo.
     */
    fun selectOnly(id: Long) {
        selected.value = setOf(id)
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
            selectedEntries().map { it.candidate },
            entries.value.size,
            mode.value,
            // Without a trash to fall back on, every removal of an original is
            // permanent, whatever the button was called.
            permanent = permanent || (!canUndoRemoval && mode.value != ReclaimRules.Mode.COPIES_ONLY)
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
                    entry.candidate, healthy, allowVerifiedBySize = true,
                    skipFavourites = skipFavourites.value, skipSmall = skipSmall.value
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
                        it.candidate, healthy, allowVerifiedBySize = true,
                        skipFavourites = skipFavourites.value, skipSmall = skipSmall.value
                    )
                }
                // Anything that stopped qualifying while the list sat open is
                // named, not silently dropped: a batch that quietly does less
                // than it said it would is worse than one that explains.
                droppedAtAction = (chosen - stillGood.toSet()).map { it.row.displayName }
                val ready = engine.prepare(stillGood.map { it.row }, mode.value, o, now)
                prepared = ready
                pendingMode = mode.value
                pendingTrash = !permanent
                if (ready.uris.isEmpty()) {
                    lastResult.value = engine.finish(ready, emptySet(), mode.value, !permanent, now)
                    return@launch
                }
                if (!beginConsent(ready.uris, permanent)) {
                    pendingTrash = false
                    startLegacy(ready.uris)
                }
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Starts the walk through the confirmations for a selection.
     *
     * False when this phone has no batch dialog at all, which is the Android
     * 10 case: there the caller falls back to one consent per file.
     */
    private fun beginConsent(uris: List<Uri>, permanent: Boolean): Boolean {
        consentChunks = ArrayDeque(ReclaimRules.batches(uris))
        consentCurrent = emptyList()
        consentConfirmed.clear()
        consentPermanent = permanent
        return requestNextConsent()
    }

    /** Shows the next chunk's dialog; false when none is left to ask about. */
    private fun requestNextConsent(): Boolean {
        val next = consentChunks.removeFirstOrNull() ?: return false
        val request = requestFor(next, consentPermanent) ?: return false
        consentCurrent = next
        pendingIntent.value = request
        return true
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
        if (pendingDuplicateIds.isNotEmpty()) {
            pendingDuplicateIds = emptySet()
            dupeChunks.clear()
            dupeCurrent = emptyList()
            dupeIdByUri = emptyMap()
            dupeGranted.clear()
            finishDuplicateRemoval(legacyDeleted.size)
            return
        }
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
        if (pendingDuplicateIds.isNotEmpty()) {
            if (granted) dupeGranted += dupeCurrent.mapNotNull { dupeIdByUri[it.toString()] }
            dupeCurrent = emptyList()
            if (granted && nextDuplicateChunk()) return
            dupeChunks.clear()
            pendingDuplicateIds = emptySet()
            dupeIdByUri = emptyMap()
            val count = dupeGranted.size
            dupeGranted.clear()
            finishDuplicateRemoval(count)
            return
        }
        val ready = prepared ?: return
        if (granted) consentConfirmed += consentCurrent.map { it.toString() }
        consentCurrent = emptyList()
        // More of the selection still to confirm: ask for the next chunk
        // before anything is written down. A refusal ends the batch there,
        // with whatever was already agreed to.
        if (granted && requestNextConsent()) return
        consentChunks.clear()
        prepared = null
        val deleted = consentConfirmed.toSet()
        consentConfirmed.clear()
        viewModelScope.launch(Dispatchers.IO) {
            lastResult.value = engine.finish(
                ready, deleted, pendingMode, pendingTrash, System.currentTimeMillis()
            )
            clearSelection()
            load()
        }
    }

    /**
     * A duplicate removal has no reclaim batch behind it.
     *
     * Nothing was freed on the strength of upload evidence, so there is
     * nothing for the app to undo: the identical file is still on the phone,
     * and the extras are in the gallery trash for 30 days.
     */
    private fun finishDuplicateRemoval(removed: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (removed > 0) {
                duplicatesRemoved.value = removed
                ActivityLog(ctx).record(
                    ActivityLog.Kind.RECLAIMED,
                    count = removed,
                    detail = ctx.getString(R.string.dupes_removed_detail)
                )
            }
            loadDuplicates()
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

    /** Chunks of a batch still waiting to be untrashed, and what came back. */
    private var restoreChunks: ArrayDeque<List<Uri>> = ArrayDeque()
    private var restoreCurrent: List<Uri> = emptyList()
    private val restoreGranted = mutableSetOf<String>()

    /** Untrash, with the system dialog: the files are the gallery's, not ours. */
    fun restore(items: List<ReclaimItemRow>) {
        val uris = items.mapNotNull { row -> row.contentUri?.let { Uri.parse(it) } }
        // Nothing to restore from on Android 10: it has no trash, so those
        // batches were permanent and the history says so.
        if (uris.isEmpty() || Build.VERSION.SDK_INT < 30) return
        restoring = items
        // A restored batch is as large as the batch that created it, so it
        // goes back through the same chunked confirmation as the removal did.
        restoreChunks = ArrayDeque(ReclaimRules.batches(uris))
        restoreGranted.clear()
        pendingIntent.value = nextRestoreChunk()
    }

    private fun nextRestoreChunk(): IntentSender? {
        // Android 10 has no trash to put anything back into, and restore()
        // has already turned back on that phone. Stated again here so the
        // guard holds wherever this is called from.
        if (Build.VERSION.SDK_INT < 30) return null
        val next = restoreChunks.removeFirstOrNull() ?: return null
        restoreCurrent = next
        return MediaStore.createTrashRequest(ctx.contentResolver, next, false).intentSender
    }

    fun onRestoreResult(granted: Boolean) {
        pendingIntent.value = null
        if (granted) restoreGranted += restoreCurrent.map { it.toString() }
        restoreCurrent = emptyList()
        if (granted && restoreChunks.isNotEmpty()) {
            pendingIntent.value = nextRestoreChunk()
            return
        }
        restoreChunks.clear()
        val items = restoring
        restoring = emptyList()
        // Only the chunks the user actually allowed came back out of the
        // trash. Marking the rest as restored would put a lie in the history
        // for files that are still sitting in the gallery's bin.
        val back = items.filter { it.contentUri in restoreGranted }
        restoreGranted.clear()
        if (back.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (item in back) engine.onRestored(item)
            historyItems.value = db.reclaim().itemsOf(back.first().batchId)
            load()
        }
    }

    // ---- find space ----------------------------------------------------------

    val duplicateGroups = MutableStateFlow<List<DuplicateRules.Group>>(emptyList())
    val largest = MutableStateFlow<List<ItemRow>>(emptyList())

    /**
     * True while the first scan is running, so the screen can show skeleton
     * rows instead of an empty state that is about to be wrong.
     */
    val duplicatesLoading = MutableStateFlow(false)

    fun loadDuplicates() {
        viewModelScope.launch(Dispatchers.IO) {
            duplicatesLoading.value = true
            try {
                duplicateGroups.value = DuplicateScanner(ctx).groups()
            } finally {
                duplicatesLoading.value = false
            }
        }
    }

    /**
     * Makes a different file in the group the one that stays.
     *
     * The automatic choice is the oldest capture in the fullest album, which
     * is right nearly always and wrong when someone has deliberately filed a
     * copy somewhere. Swapping is local to this screen: the group is rebuilt
     * around the new keeper and the old keeper becomes a removable extra.
     */
    fun keepInstead(sha256: String, id: Long) {
        duplicateGroups.value = duplicateGroups.value.map { group ->
            if (group.sha256 != sha256) return@map group
            val next = group.all.firstOrNull { it.id == id } ?: return@map group
            group.copy(keeper = next, extras = group.all.filter { it.id != next.id })
        }
    }

    /**
     * Moves the ticked extras to the gallery trash.
     *
     * Safe by definition and not by evidence: an identical file stays on the
     * phone, so nothing is lost even if the copy never reached any cloud. It
     * still goes through Android's own dialog, and to the trash rather than
     * straight out, so 30 days of undo apply.
     */
    fun removeDuplicateExtras(chosen: Set<Long>) {
        if (busy.value) return
        if (chosen.isEmpty()) return
        busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Re-read now, not when the list was drawn: a file can be gone
                // or a group can have changed between the tap and the action.
                val fresh = DuplicateScanner(ctx).groups()
                duplicateGroups.value = fresh
                val stillExtras = fresh.flatMap { g -> g.extras }
                    .filter { it.id in chosen }
                val rows = stillExtras.mapNotNull { db.items().byId(it.id) }
                val uris = rows.mapNotNull { row ->
                    row.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                }
                if (uris.isEmpty()) return@launch
                pendingDuplicateIds = rows.map { it.id }.toSet()
                pendingTrash = true
                // A gallery full of copies produces thousands of extras, so
                // this goes through the same chunked confirmation as every
                // other removal rather than one dialog nobody can read.
                dupeIdByUri = rows.mapNotNull { row ->
                    row.contentUri?.let { it to row.id }
                }.toMap()
                dupeChunks = ArrayDeque(ReclaimRules.batches(uris))
                dupeGranted.clear()
                if (!nextDuplicateChunk()) startLegacy(uris)
            } finally {
                busy.value = false
            }
        }
    }

    /** Names that stopped qualifying between the tap and the action. */
    var droppedAtAction: List<String> = emptyList()
        private set

    fun clearDropped() {
        droppedAtAction = emptyList()
    }

    /** Ids awaiting Android's answer for a duplicate removal. */
    private var pendingDuplicateIds: Set<Long> = emptySet()

    /** The same chunked walk, for the extras: what is left, and what was allowed. */
    private var dupeChunks: ArrayDeque<List<Uri>> = ArrayDeque()
    private var dupeCurrent: List<Uri> = emptyList()
    private var dupeIdByUri: Map<String, Long> = emptyMap()
    private val dupeGranted = mutableSetOf<Long>()

    /** Shows the next chunk of extras; false when none is left to ask about. */
    private fun nextDuplicateChunk(): Boolean {
        val next = dupeChunks.removeFirstOrNull() ?: return false
        val request = requestFor(next, permanent = false) ?: return false
        dupeCurrent = next
        pendingIntent.value = request
        return true
    }

    /** How many extras the last removal actually took, for the result line. */
    val duplicatesRemoved = MutableStateFlow<Int?>(null)

    fun dismissDuplicatesResult() {
        duplicatesRemoved.value = null
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
