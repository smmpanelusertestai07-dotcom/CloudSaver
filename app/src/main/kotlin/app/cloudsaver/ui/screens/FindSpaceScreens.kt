package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.ReclaimViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.util.Formats
import androidx.compose.foundation.layout.size
import app.cloudsaver.ui.components.FileRow
import app.cloudsaver.ui.components.ListSearchField
import app.cloudsaver.ui.components.ListTail
import app.cloudsaver.ui.components.SelectionBar
import app.cloudsaver.core.logic.ProofLine
import app.cloudsaver.core.logic.Projection
import app.cloudsaver.ui.components.ChipRow
import app.cloudsaver.ui.Routes

/** A page header with a back arrow, shared by the Find space screens. */
@Composable
private fun Page(
    nav: NavHostController,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(top = 8.dp, start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/**
 * Files that are byte-for-byte identical, and the one extra thing this screen
 * can do that no other can: remove a copy with no upload evidence at all.
 *
 * That is safe because the proof is local. An identical file stays on the
 * phone, so the content is provably still there whatever any cloud app did or
 * did not do. The screen says exactly that above the button, because a person
 * being asked to delete photographs deserves the actual reason.
 */
@Composable
fun DuplicatesScreen(vm: AppViewModel, rvm: ReclaimViewModel, nav: NavHostController) {
    val groups by rvm.duplicateGroups.collectAsStateWithLifecycle()
    val selection by rvm.duplicateSelection.collectAsStateWithLifecycle()
    val removed by rvm.duplicatesRemoved.collectAsStateWithLifecycle()
    val pending by rvm.pendingIntent.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(DupeSort.SPACE) }

    LaunchedEffect(Unit) { rvm.loadDuplicates() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> rvm.onDialogResult(result.resultCode == android.app.Activity.RESULT_OK) }
    LaunchedEffect(pending) {
        pending?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    Page(nav, stringResource(R.string.find_duplicates)) {
        if (groups.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.dupes_empty_title),
                body = stringResource(R.string.dupes_empty_body)
            )
            return@Page
        }
        val shown = remember(groups, query, sort) {
            val q = query.trim()
            val matched = if (q.isEmpty()) {
                groups
            } else {
                groups.filter { g ->
                    g.all.any { e ->
                        e.displayName.contains(q, true) || e.album?.contains(q, true) == true
                    }
                }
            }
            when (sort) {
                DupeSort.SPACE -> matched.sortedByDescending { it.reclaimableBytes }
                DupeSort.COPIES -> matched.sortedByDescending { it.extras.size }
                DupeSort.NAME -> matched.sortedBy { it.keeper.displayName.lowercase() }
            }
        }
        val selectedBytes = shown.flatMap { it.extras }
            .filter { it.id in selection }
            .sumOf { it.sizeBytes }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                item("search") {
                    ListSearchField(query, { query = it }, Modifier.padding(top = 8.dp))
                }
                item("sort") {
                    ChipRow(
                        options = listOf(
                            DupeSort.SPACE to stringResource(R.string.dupes_sort_space),
                            DupeSort.COPIES to stringResource(R.string.dupes_sort_copies),
                            DupeSort.NAME to stringResource(R.string.dupes_sort_name)
                        ),
                        selected = sort,
                        onSelect = { sort = it },
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                item("intro") {
                    AppCard(modifier = Modifier.padding(vertical = 8.dp), tonal = true) {
                        Text(
                            stringResource(R.string.dupes_intro),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { rvm.selectAllDuplicates(selection.isEmpty()) }) {
                            Text(
                                stringResource(
                                    if (selection.isEmpty()) R.string.list_select_all
                                    else R.string.list_clear_selection
                                )
                            )
                        }
                    }
                }
                for (group in shown) {
                    item("h-${group.sha256}") {
                        Text(
                            stringResource(
                                R.string.dupes_group_proof,
                                Formats.bytes(group.keeper.sizeBytes)
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }
                    item("k-${group.sha256}") {
                        DuplicateEntryRow(
                            vm = vm,
                            entry = group.keeper,
                            isKeeper = true,
                            selected = null,
                            onToggle = {},
                            onKeepInstead = null
                        )
                    }
                    items(group.extras, key = { "e-${it.id}" }) { extra ->
                        DuplicateEntryRow(
                            vm = vm,
                            entry = extra,
                            isKeeper = false,
                            selected = extra.id in selection,
                            onToggle = { rvm.toggleDuplicate(extra.id) },
                            onKeepInstead = { rvm.keepInstead(group.sha256, extra.id) }
                        )
                    }
                }
            item("tail") { ListTail(extra = selection.isNotEmpty()) }
        }
        if (selection.isNotEmpty()) {
            SelectionBar(
                countLabel = stringResource(
                    R.string.list_selected, selection.size, Formats.bytes(selectedBytes)
                ),
                actionLabel = stringResource(R.string.dupes_remove_extras),
                onAction = { rvm.removeDuplicateExtras() },
                onClear = { rvm.selectAllDuplicates(false) }
            )
        }
    }

    removed?.let { count ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rvm.dismissDuplicatesResult() },
            confirmButton = {
                TextButton(onClick = { rvm.dismissDuplicatesResult() }) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.dupes_removed_title)) },
            text = {
                Text(
                    pluralStringResource(R.plurals.dupes_removed_body, count, count)
                )
            }
        )
    }
}

/**
 * One file inside a duplicate group.
 *
 * The full folder is always shown, for the keeper and every extra alike. Two
 * entries showing only "Camera" and "Camera" are indistinguishable, and being
 * unable to tell which copy you are about to delete is exactly the moment
 * someone stops trusting the screen.
 */
@Composable
private fun DuplicateEntryRow(
    vm: AppViewModel,
    entry: app.cloudsaver.core.logic.DuplicateRules.Entry,
    isKeeper: Boolean,
    selected: Boolean?,
    onToggle: (Boolean) -> Unit,
    onKeepInstead: (() -> Unit)?
) {
    val keepInsteadLabel = stringResource(R.string.dupes_keep_instead)
    val actions = buildList {
        onKeepInstead?.let { add(keepInsteadLabel to it) }
    }
    FileRow(
        name = entry.displayName,
        context = entry.path.ifEmpty { entry.album.orEmpty() },
        size = Formats.bytes(entry.sizeBytes),
        proof = if (isKeeper) {
            stringResource(R.string.dupes_this_one_stays)
        } else {
            stringResource(R.string.proof_identical)
        },
        thumbnail = { DuplicateThumb(vm, entry) },
        actions = actions,
        selected = selected,
        onSelectedChange = if (selected != null) onToggle else null
    )
}

/** Reuses the Files thumbnail by looking the row up once. */
@Composable
private fun DuplicateThumb(vm: AppViewModel, entry: app.cloudsaver.core.logic.DuplicateRules.Entry) {
    val row by androidx.compose.runtime.produceState<app.cloudsaver.data.db.ItemRow?>(null, entry.id) {
        value = vm.itemById(entry.id)
    }
    row?.let { Thumbnail(it) } ?: androidx.compose.foundation.layout.Box(Modifier.size(52.dp))
}

/**
 * The largest originals, what optimising them would save, and the two things
 * worth doing about them.
 *
 * "Remove" is offered only for files already confirmed backed up, and it is
 * absent rather than greyed out for the rest: a disabled control invites the
 * question "why not?", and the answer - "because we cannot prove your cloud
 * has it" - belongs in the proof line, not in a tooltip nobody opens.
 */
@Composable
fun BiggestFilesScreen(vm: AppViewModel, rvm: ReclaimViewModel, nav: NavHostController) {
    val all by rvm.largest.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(BiggestSort.LARGEST) }
    LaunchedEffect(Unit) {
        rvm.loadLargest()
        vm.refreshProfile()
    }

    Page(nav, stringResource(R.string.find_biggest)) {
        if (all.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.biggest_empty_title),
                body = stringResource(R.string.biggest_empty_body)
            )
            return@Page
        }
        val rows = remember(all, query, sort, profile) {
            val q = query.trim()
            all.filter {
                q.isEmpty() || it.displayName.contains(q, true) ||
                    it.bucket?.contains(q, true) == true
            }.let { list ->
                when (sort) {
                    BiggestSort.LARGEST -> list.sortedByDescending { it.sizeBytes }
                    BiggestSort.OLDEST -> list.sortedBy {
                        if (it.captureAt > 0) it.captureAt else it.dateModified
                    }
                    BiggestSort.SAVED -> list.sortedByDescending { savingFor(it, profile) }
                }
            }
        }
        val totalBytes = rows.sumOf { it.sizeBytes }
        val couldSave = rows.sumOf { savingFor(it, profile) }
        val rough = profile.photos.ratio <= 0.0 || profile.videos.ratio <= 0.0

        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item("search") {
                ListSearchField(query, { query = it }, Modifier.padding(top = 8.dp))
            }
            item("sort") {
                ChipRow(
                    options = listOf(
                        BiggestSort.LARGEST to stringResource(R.string.list_sort_largest),
                        BiggestSort.OLDEST to stringResource(R.string.list_sort_oldest),
                        BiggestSort.SAVED to stringResource(R.string.list_sort_saved)
                    ),
                    selected = sort,
                    onSelect = { sort = it },
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            item("summary") {
                AppCard(modifier = Modifier.padding(vertical = 10.dp), tonal = true) {
                    Text(
                        stringResource(
                            if (rough) R.string.biggest_header_rough
                            else R.string.biggest_header,
                            rows.size,
                            Formats.bytes(totalBytes),
                            Formats.bytes(couldSave)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (rows.isEmpty()) {
                item("nomatch") {
                    Text(
                        stringResource(R.string.biggest_no_match, query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
            items(rows, key = { it.id }) { row ->
                BiggestRow(
                    row = row,
                    saving = savingFor(row, profile),
                    onOpen = { vm.openInViewer(row) },
                    onOptimise = { rvm.optimiseFirst(listOf(row.id)) },
                    onNever = { rvm.setNeverOptimise(row.id, true) },
                    onRemove = {
                        // Straight into Reclaim with this one ticked: that is
                        // where the eligibility gate and trash-first rule are.
                        rvm.selectOnly(row.id)
                        nav.navigate(Routes.FREE_UP)
                    }
                )
            }
            item("tail") { ListTail() }
        }
    }
}

private enum class BiggestSort { LARGEST, OLDEST, SAVED }

private enum class DupeSort { SPACE, COPIES, NAME }

/**
 * What optimising this one file would save, through the shared projection so
 * it agrees with every other screen - including when nothing is measured yet.
 */
private fun savingFor(
    row: app.cloudsaver.data.db.ItemRow,
    profile: app.cloudsaver.core.logic.MediaProfile.Profile
): Long {
    row.outputBytes?.let { return (row.sizeBytes - it).coerceAtLeast(0L) }
    val measured = if (row.isVideo) profile.videos.ratio else profile.photos.ratio
    return Projection.forItem(row.sizeBytes, row.isVideo, measured)
}

@Composable
private fun BiggestRow(
    row: app.cloudsaver.data.db.ItemRow,
    saving: Long,
    onOpen: () -> Boolean,
    onOptimise: () -> Unit,
    onNever: () -> Unit,
    onRemove: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val couldNotOpen = stringResource(R.string.biggest_cannot_open)
    val openLabel = stringResource(R.string.list_open)
    val optimiseLabel = stringResource(R.string.list_optimise_first)
    val neverLabel = stringResource(R.string.list_never)
    val removeLabel = stringResource(R.string.list_remove)
    val proofKind = ProofLine.forItem(
        app.cloudsaver.core.logic.Evidence.parse(row.evidence),
        isDuplicateExtra = row.duplicateOf != null
    )
    val actions = buildList {
        add(openLabel to {
            if (!onOpen()) {
                android.widget.Toast
                    .makeText(context, couldNotOpen, android.widget.Toast.LENGTH_SHORT)
                    .also { it.show() }
            }
            Unit
        })
        if (row.outputBytes == null) add(optimiseLabel to onOptimise)
        // Offered only where the proof allows it, and absent otherwise rather
        // than greyed: a disabled control raises a question the row cannot
        // answer, and the proof line above already gives the reason.
        if (ProofLine.allowsRemoval(proofKind)) add(removeLabel to onRemove)
        add(neverLabel to onNever)
    }
    FileRow(
        name = row.displayName,
        context = detailLine(row),
        size = Formats.bytes(row.sizeBytes),
        proof = proofLabel(proofKind),
        thumbnail = { Thumbnail(row) },
        actions = actions,
        trailingNote = if (saving > 0) {
            stringResource(R.string.biggest_saving, Formats.bytes(saving))
        } else {
            null
        },
        onClick = {
            if (!onOpen()) {
                val toast = android.widget.Toast.makeText(
                    context, couldNotOpen, android.widget.Toast.LENGTH_SHORT
                )
                toast.show()
            }
        }
    )
}

/** The one plain "how we know" line, in the same words on every screen. */
@Composable
fun proofLabel(kind: ProofLine.Kind): String = stringResource(
    when (kind) {
        ProofLine.Kind.CLOUD_REMOVED_COPY -> R.string.proof_cloud_removed
        ProofLine.Kind.UPLOAD_SIZE_MATCHED -> R.string.proof_size_matched
        ProofLine.Kind.IDENTICAL_COPY_KEPT -> R.string.proof_identical
        ProofLine.Kind.TIME_ONLY -> R.string.proof_time_only
        ProofLine.Kind.WAITING -> R.string.proof_waiting
    }
)

/** Kind, when it was taken, its album, and how long it runs if it runs at all. */
@Composable
private fun detailLine(row: app.cloudsaver.data.db.ItemRow): String {
    val kind = stringResource(if (row.isVideo) R.string.kind_video else R.string.kind_photo)
    val taken = Formats.date(if (row.captureAt > 0) row.captureAt else row.dateModified)
    val album = row.bucket ?: stringResource(R.string.biggest_no_album)
    return if (row.isVideo && row.durationMs > 0) {
        stringResource(
            R.string.biggest_detail_video, kind, Formats.duration(row.durationMs), taken
        ) + " \u00b7 " + album
    } else {
        stringResource(R.string.biggest_detail, kind, taken) + " \u00b7 " + album
    }
}

/**
 * Every reclaim batch of the last 30 days, which is exactly how long the
 * system trash keeps the files it describes.
 */
@Composable
fun ReclaimHistoryScreen(rvm: ReclaimViewModel, nav: NavHostController) {
    val batches by rvm.history.collectAsStateWithLifecycle(emptyList())
    val items by rvm.historyItems.collectAsStateWithLifecycle()
    val pending by rvm.pendingIntent.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> rvm.onRestoreResult(r.resultCode == android.app.Activity.RESULT_OK) }
    LaunchedEffect(pending) {
        pending?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    Page(nav, stringResource(R.string.reclaim_history)) {
        if (batches.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.history_empty_title),
                body = stringResource(R.string.history_empty_body)
            )
            return@Page
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(batches, key = { it.id }) { batch ->
                AppCard(
                    modifier = Modifier.padding(vertical = 5.dp),
                    onClick = { rvm.loadBatch(batch) }
                ) {
                    Text(
                        Formats.dateTime(batch.atMs),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.history_line,
                            batch.itemCount,
                            batch.itemCount,
                            Formats.bytes(batch.freedBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (batch.trashed) {
                            stringResource(
                                R.string.history_trashed,
                                Formats.date(batch.atMs + 30L * 86_400_000L)
                            )
                        } else {
                            stringResource(R.string.history_permanent)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (batch.trashed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    val shown = items.filter { it.batchId == batch.id }
                    for (item in shown) {
                        KeyValueRow(
                            item.displayName,
                            Formats.bytes(item.originalBytes)
                        )
                    }
                    if (batch.trashed && shown.isNotEmpty()) {
                        val restorable = shown.filter { it.restoredAt == null }
                        if (restorable.isEmpty()) {
                            // Nothing left to offer; everything here came back.
                        } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                            OutlinedButton(
                                onClick = { rvm.restore(restorable) },
                                modifier = Modifier.padding(top = 8.dp)
                            ) { Text(stringResource(R.string.history_restore)) }
                        } else {
                            // Android cannot report trash state for files the
                            // app no longer owns, so it says where to look
                            // rather than promising a button that may fail.
                            Text(
                                stringResource(R.string.history_unknown),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
            item("tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
