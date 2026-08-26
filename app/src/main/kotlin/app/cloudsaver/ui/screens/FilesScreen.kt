package app.cloudsaver.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudsaver.R
import app.cloudsaver.ui.components.typeFilter
import app.cloudsaver.ui.components.sizeFilter
import app.cloudsaver.ui.components.selectionSummary
import app.cloudsaver.ui.components.rememberListSelection
import app.cloudsaver.ui.components.albumFilter
import app.cloudsaver.ui.components.SearchEmptyState
import app.cloudsaver.ui.components.ListScreenScaffold
import app.cloudsaver.ui.components.ListOption
import app.cloudsaver.ui.components.ListFilter
import app.cloudsaver.ui.components.ListActionBar
import app.cloudsaver.ui.components.FilteredEmptyState
import app.cloudsaver.core.logic.ListFilters
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.RowActions
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.BadgeTone
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.ui.components.StateBadge
import app.cloudsaver.util.OemPages
import app.cloudsaver.util.Permissions
import app.cloudsaver.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FilesScreen(vm: AppViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.search.collectAsStateWithLifecycle()
    val statusFilter by vm.filesState.collectAsStateWithLifecycle()
    val sort by vm.filesSort.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<ItemRow?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    // Y2.7: skipping a file is undoable, and says where the list lives. It
    // is a decision about someone's photograph made with one tap, so it needs
    // a way back that does not involve hunting through Settings.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val skippedMessage = stringResource(R.string.never_optimise_undo)
    val undoLabel = stringResource(R.string.undo)
    val onSkip: (Long) -> Unit = { id ->
        vm.setNeverOptimise(id, true)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = skippedMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) vm.setNeverOptimise(id, false)
        }
    }

    var type by rememberSaveable { mutableStateOf(ListFilters.Type.ALL) }
    var sizeBand by rememberSaveable { mutableStateOf(ListFilters.Size.ANY) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    val selection = rememberListSelection()

    // The status filter is applied by the query behind vm.items; the shared
    // chips narrow what came back. Both are filters, so the empty state has to
    // treat them the same way.
    val state = ListFilters.State(type, sizeBand, album, query = "")
    val rows = remember(items, state) {
        items.filter { ListFilters.matches(it.toCandidate(), state) }
    }
    val albums = remember(items) { ListFilters.albumCounts(items.map { it.toCandidate() }) }
    // Recomputed on a change, not on every frame: this list holds the whole
    // gallery, and the sum used to run again on each tick of a selection.
    val chosen = remember(rows, selection.ids) { rows.filter { it.id in selection } }
    val selectedBytes = remember(chosen) { chosen.sumOf { it.sizeBytes } }
    val anyFilter = statusFilter != null || !state.isDefault

    Box(Modifier.fillMaxSize()) {
    val mediaAccess by vm.mediaAccess.collectAsStateWithLifecycle()
    val appContext = LocalContext.current

    ListScreenScaffold(
        title = stringResource(R.string.nav_files),
        onBack = {},
        showBack = false,
        query = query,
        onQuery = { vm.search.value = it },
        filters = listOf(
            typeFilter(type) { type = it },
            statusFilterChip(statusFilter) { vm.filesState.value = it },
            albumFilter(album, albums) { album = it },
            sizeFilter(sizeBand) { sizeBand = it }
        ),
        sort = ListFilter(
            name = stringResource(R.string.filter_sort),
            valueLabel = null,
            options = listOf(
                ListOption(
                    stringResource(R.string.list_sort_newest),
                    sort == AppViewModel.FilesSort.NEWEST
                ) { vm.filesSort.value = AppViewModel.FilesSort.NEWEST },
                ListOption(
                    stringResource(R.string.list_sort_largest),
                    sort == AppViewModel.FilesSort.LARGEST
                ) { vm.filesSort.value = AppViewModel.FilesSort.LARGEST },
                ListOption(
                    stringResource(R.string.list_sort_saved),
                    sort == AppViewModel.FilesSort.SAVED
                ) { vm.filesSort.value = AppViewModel.FilesSort.SAVED }
            )
        ),
        selection = selection,
        matchingCount = rows.size,
        onSelectAll = { selection.selectAll(rows.map { it.id }) },
        intro = if (mediaAccess == Permissions.MediaAccess.PARTIAL) {
            {
                // The list below shows only what was scanned under full
                // access; nothing new arrives until access is full again.
                androidx.compose.material3.AssistChip(
                    onClick = { OemPages.openAppInfo(appContext) },
                    label = { Text(stringResource(R.string.partial_chip)) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            null
        },
        onResetFilters = {
            type = ListFilters.Type.ALL
            sizeBand = ListFilters.Size.ANY
            album = null
            vm.filesState.value = null
        },
        loading = false,
        isEmpty = rows.isEmpty(),
        emptyContent = {
            when {
                query.isNotBlank() -> SearchEmptyState(
                    term = query,
                    onClear = { vm.search.value = "" }
                )
                anyFilter -> FilteredEmptyState(
                    onReset = {
                        type = ListFilters.Type.ALL
                        sizeBand = ListFilters.Size.ANY
                        album = null
                        vm.filesState.value = null
                    }
                )
                else -> EmptyState(
                    title = stringResource(R.string.files_empty_title),
                    body = stringResource(R.string.files_empty)
                )
            }
        },
        actionBar = {
            // CC6: a mixed selection acts only on what the action can touch,
            // and says so. Five selected with two already optimised reads
            // "Optimise 3 of 5" with the skip named - acting on all five
            // would redo finished work, and acting on three silently reads
            // as the app losing count. At zero eligible the action is absent.
            val split = RowActions.splitForOptimise(
                chosen.map { it.id to it.toActionRow() }
            )
            if (split.eligible > 0) {
                ListActionBar(
                    // frees = false: this bar's action optimises. It writes a
                    // smaller copy and leaves the original where it is, so
                    // nothing is freed here.
                    summary = selectionSummary(
                        selection.size, Formats.bytes(selectedBytes), frees = false
                    ),
                    actionLabel = if (split.skipped > 0) {
                        stringResource(R.string.bulk_optimise_of, split.eligible, chosen.size)
                    } else {
                        stringResource(R.string.list_optimise_these_first)
                    },
                    note = if (split.skipped > 0) {
                        pluralStringResource(
                            R.plurals.bulk_skipped_note, split.skipped, split.skipped
                        )
                    } else {
                        null
                    },
                    onAction = {
                        vm.optimiseNow(split.eligibleIds)
                        selection.clear()
                    }
                )
            }
        }
    ) {
        items(rows, key = { it.id }) { row ->
            FilesRow(
                row = row,
                selected = if (selection.active) row.id in selection else null,
                onToggle = { selection.toggle(row.id) },
                onOpenDetail = { detail = row },
                onLongPress = { selection.toggle(row.id) }
            )
        }
    }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    openError?.let { message ->
        AlertDialog(
            onDismissRequest = { openError = null },
            confirmButton = {
                TextButton(onClick = { openError = null }) { Text(stringResource(R.string.ok)) }
            },
            text = { Text(message) }
        )
    }

    detail?.let { row ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = {
                TextButton(onClick = { detail = null }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                // Opening the copy, not just describing it, is what tells the
                // user the optimised file is really fine to keep.
                val cannotOpen = stringResource(R.string.detail_open_failed)
                TextButton(onClick = {
                    if (!vm.openInViewer(row)) {
                        openError = cannotOpen
                    } else {
                        detail = null
                    }
                }) { Text(stringResource(R.string.detail_open)) }
            },
            title = { Text(row.displayName, maxLines = 2) },
            text = {
                Column {
                    KeyValueRow(stringResource(R.string.detail_state), stateLabel(row))
                    KeyValueRow(stringResource(R.string.detail_evidence), evidenceLabel(row))
                    // Z10.1: the app holding the copy - the one it was sent
                    // to, which after a switch is not the one selected now.
                    val holder by androidx.compose.runtime.produceState<String?>(null, row.id) {
                        value = vm.holdingAppLabel(row)
                    }
                    holder?.let {
                        KeyValueRow(stringResource(R.string.detail_holding_app), it)
                    }
                    KeyValueRow(stringResource(R.string.detail_original), Formats.bytes(row.sizeBytes))
                    row.outputBytes?.let {
                        KeyValueRow(stringResource(R.string.detail_copy), Formats.bytes(it))
                        val saved = row.sizeBytes - it
                        if (saved > 0) {
                            KeyValueRow(
                                stringResource(R.string.detail_saved),
                                stringResource(
                                    R.string.detail_saved_value,
                                    Formats.bytes(saved),
                                    Formats.percentOf(saved, row.sizeBytes)
                                )
                            )
                        }
                    }
                    KeyValueRow(stringResource(R.string.detail_captured), Formats.dateTime(row.captureAt))
                    row.releasedAt?.let {
                        KeyValueRow(stringResource(R.string.detail_released), Formats.dateTime(it))
                    }
                    row.outputName?.let {
                        KeyValueRow(stringResource(R.string.detail_copy_name), it)
                    }
                    row.bucket?.let {
                        KeyValueRow(stringResource(R.string.detail_album), it)
                    }
                    row.presetUsed?.let {
                        KeyValueRow(
                            stringResource(R.string.detail_preset),
                            if (row.isVideo && row.codecUsed != null) {
                                "$it / ${row.codecUsed}"
                            } else {
                                it
                            }
                        )
                    }
                    row.outputFolder?.let { folder ->
                        // Where it went, so the folder to select in the cloud
                        // app is never a guess.
                        KeyValueRow(
                            stringResource(R.string.detail_folder),
                            app.cloudsaver.core.logic.Defaults.outFolderRelPath(
                                runCatching {
                                    app.cloudsaver.core.logic.OutFolder.valueOf(folder)
                                }.getOrDefault(app.cloudsaver.core.logic.OutFolder.SINGLE)
                            )
                        )
                    }
                    row.skipReason?.let {
                        KeyValueRow(stringResource(R.string.detail_reason), it)
                    }
                    // Items copied byte-for-byte explain themselves here.
                    row.lastError?.takeIf { row.outputBytes != null }?.let { error ->
                        KeyValueRow(
                            stringResource(R.string.detail_as_is),
                            asIsReasonLabel(error)
                        )
                    }
                    if (row.originalMissing) {
                        Text(
                            stringResource(R.string.detail_original_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Exactly the actions this file's state allows, from the
                    // one rule every list obeys. An already-optimised copy is
                    // not offered "never optimise": the work is done, the
                    // option cannot undo it, and offering it is what made the
                    // counters disagree with no way to tell why.
                    //
                    // Below the details, not above the name. These used to sit
                    // in the dialog's icon slot - the small decorative one that
                    // Material draws above the title - so "Never optimise this
                    // file", which is permanent, was the first thing on screen
                    // and the file it applied to was named underneath it. One
                    // button each to a line, because the labels are sentences.
                    val actions = RowActions.forItem(row.toActionRow())
                    if (actions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                    }
                    for (action in actions) {
                        when (action) {
                            RowActions.Action.OPTIMISE_FIRST -> TextButton(onClick = {
                                vm.optimiseNow(row.id)
                                detail = null
                            }) { Text(stringResource(R.string.detail_optimise_first)) }

                            RowActions.Action.TRY_AGAIN -> TextButton(onClick = {
                                vm.optimiseNow(row.id)
                                detail = null
                            }) { Text(stringResource(R.string.detail_try_again)) }

                            RowActions.Action.NEVER_OPTIMISE -> TextButton(onClick = {
                                onSkip(row.id)
                                detail = null
                            }) { Text(stringResource(R.string.never_optimise)) }

                            RowActions.Action.ALLOW_AGAIN -> TextButton(onClick = {
                                vm.setNeverOptimise(row.id, false)
                                detail = null
                            }) { Text(stringResource(R.string.detail_optimise_again)) }

                            // OPEN already has its own button; removal lives
                            // on Reclaim, which is the single deletion path.
                            else -> Unit
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun FilesChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/**
 * The item's own picture, so a list of file names becomes a list of photos.
 *
 * MediaStore's own thumbnail cache is used rather than decoding the full
 * image: it is what the gallery draws, so it is already on disk, and a Files
 * list must never be the reason a scroll stutters. A missing thumbnail is
 * normal - the original may be gone - and falls back to the app mark.
 */
@Composable
fun Thumbnail(row: ItemRow) {
    val context = LocalContext.current
    val source = row.outputUri ?: row.contentUri
    val shape = RoundedCornerShape(10.dp)
    val bitmap by produceState<Bitmap?>(initialValue = null, source) {
        value = source?.let { uriString ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        Uri.parse(uriString), Size(128, 128), null
                    )
                }.getOrNull()
            }
        }
    }
    Box(
        Modifier
            .size(52.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_stat_cloud),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private fun badgeTone(row: ItemRow): BadgeTone {
    val state = runCatching { ItemState.valueOf(row.state) }.getOrDefault(ItemState.UNKNOWN)
    return when (state) {
        ItemState.NEW -> BadgeTone.NEUTRAL
        ItemState.STAGED, ItemState.RELEASED -> BadgeTone.PROGRESS
        ItemState.GONE, ItemState.DONE, ItemState.FREED, ItemState.FREED_KEPT ->
            BadgeTone.SUCCESS
        ItemState.SKIP, ItemState.UNKNOWN -> BadgeTone.MUTED
    }
}

@Composable
fun stateLabel(row: ItemRow): String {
    val state = runCatching { ItemState.valueOf(row.state) }.getOrDefault(ItemState.UNKNOWN)
    return when (state) {
        ItemState.NEW -> stringResource(R.string.state_new)
        ItemState.STAGED -> stringResource(R.string.state_staged)
        ItemState.RELEASED -> stringResource(R.string.state_released)
        ItemState.GONE, ItemState.DONE -> stringResource(R.string.state_done)
        ItemState.SKIP -> stringResource(R.string.state_skip)
        ItemState.FREED -> stringResource(R.string.state_freed)
        ItemState.FREED_KEPT -> stringResource(R.string.state_freed_kept)
        ItemState.UNKNOWN -> stringResource(R.string.state_unknown)
    }
}

/** Plain-English explanation for a file that was copied instead of compressed. */
@Composable
fun asIsReasonLabel(reason: String): String = when (reason) {
    "motion_photo" -> stringResource(R.string.asis_motion_photo)
    "depth_photo" -> stringResource(R.string.asis_depth_photo)
    "multi_picture" -> stringResource(R.string.asis_multi_picture)
    "format_as_is" -> stringResource(R.string.asis_format)
    "already_efficient" -> stringResource(R.string.asis_already_small)
    "not_smaller" -> stringResource(R.string.asis_not_smaller)
    "hdr_not_supported" -> stringResource(R.string.asis_hdr)
    "removed_before_upload" -> stringResource(R.string.asis_removed_early)
    else -> stringResource(R.string.asis_other)
}

@Composable
fun evidenceLabel(row: ItemRow): String {
    return when (Evidence.parse(row.evidence)) {
        Evidence.CONFIRMED_EXACT -> stringResource(R.string.evidence_confirmed_exact)
        Evidence.CONFIRMED_PACED -> stringResource(R.string.evidence_confirmed_paced)
        Evidence.VERIFIED -> stringResource(R.string.evidence_verified)
        Evidence.AGED -> stringResource(R.string.evidence_aged)
        Evidence.NONE -> stringResource(R.string.evidence_none)
    }
}

/**
 * A stored row seen as the action rule needs it.
 *
 * One conversion, used by every screen, so no list can accidentally decide
 * "backed up" differently from the rule that governs deletion.
 */
fun ItemRow.toActionRow(): RowActions.Row = RowActions.Row(
    state = runCatching { ItemState.valueOf(state) }.getOrDefault(ItemState.UNKNOWN),
    evidence = Evidence.parse(evidence),
    neverOptimise = neverOptimise,
    originalMissing = originalMissing
)

/** The Status filter, the one chip that belongs to Files alone. */
@Composable
private fun statusFilterChip(selected: String?, onSelect: (String?) -> Unit): ListFilter {
    val options = listOf(
        null to stringResource(R.string.filter_all),
        ItemState.NEW.name to stringResource(R.string.state_new),
        ItemState.RELEASED.name to stringResource(R.string.filter_in_progress),
        ItemState.DONE.name to stringResource(R.string.state_done),
        ItemState.SKIP.name to stringResource(R.string.state_skip)
    )
    return ListFilter(
        name = stringResource(R.string.filter_status),
        valueLabel = options.firstOrNull { it.first == selected }?.second.takeIf { selected != null },
        options = options.map { (value, label) ->
            ListOption(label, value == selected) { onSelect(value) }
        }
    )
}

/** A stored row as the shared filters need to see it. */
private fun ItemRow.toCandidate() = ListFilters.Candidate(
    id = id,
    name = displayName,
    album = bucket,
    sizeBytes = sizeBytes,
    isVideo = isVideo
)

/**
 * One file on the Files screen.
 *
 * Long-press starts a selection and tap toggles once one is running, which is
 * the gesture every list in the app uses; outside selection a tap opens the
 * details sheet.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FilesRow(
    row: ItemRow,
    selected: Boolean?,
    onToggle: () -> Unit,
    onOpenDetail: () -> Unit,
    onLongPress: () -> Unit
) {
    AppCard(
        modifier = Modifier
            .padding(vertical = 5.dp)
            .combinedClickable(
                onClick = { if (selected != null) onToggle() else onOpenDetail() },
                onLongClick = onLongPress
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                androidx.compose.material3.Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggle() }
                )
                Spacer(Modifier.width(4.dp))
            }
            Thumbnail(row)
            Column(
                Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Text(
                    row.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    // IMG_20240517_181233.jpg cut at the right loses the date,
                    // which is the part that identifies the photo.
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis
                )
                Text(
                    // What it was, what it is, and how much that saved - as a
                    // sentence fragment rather than an arrow-and-numbers formula.
                    row.outputBytes?.let { copy ->
                        val saved = row.sizeBytes - copy
                        if (saved > 0) {
                            stringResource(
                                R.string.files_size_saving,
                                Formats.bytes(row.sizeBytes),
                                Formats.bytes(copy),
                                Formats.percentOf(saved, row.sizeBytes)
                            )
                        } else {
                            stringResource(
                                R.string.files_size_pair,
                                Formats.bytes(row.sizeBytes),
                                Formats.bytes(copy)
                            )
                        }
                    } ?: stringResource(
                        R.string.files_size_waiting,
                        Formats.bytes(row.sizeBytes)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                StateBadge(
                    stateLabel(row),
                    badgeTone(row),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
