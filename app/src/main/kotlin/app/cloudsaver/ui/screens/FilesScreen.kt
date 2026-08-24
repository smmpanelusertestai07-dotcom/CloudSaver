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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudsaver.R
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
import app.cloudsaver.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FilesScreen(vm: AppViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.search.collectAsStateWithLifecycle()
    val filter by vm.filesState.collectAsStateWithLifecycle()
    val sort by vm.filesSort.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<ItemRow?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.nav_files),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = query,
            onValueChange = { vm.search.value = it },
            label = { Text(stringResource(R.string.files_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        // Filters first, sort second: people look for "the ones that are done"
        // far more often than they look for "the biggest".
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilesChip(stringResource(R.string.filter_all), filter == null) {
                vm.filesState.value = null
            }
            FilesChip(stringResource(R.string.state_new), filter == ItemState.NEW.name) {
                vm.filesState.value = ItemState.NEW.name
            }
            FilesChip(
                stringResource(R.string.filter_in_progress),
                filter == ItemState.RELEASED.name
            ) { vm.filesState.value = ItemState.RELEASED.name }
            FilesChip(stringResource(R.string.state_done), filter == ItemState.DONE.name) {
                vm.filesState.value = ItemState.DONE.name
            }
            FilesChip(stringResource(R.string.state_skip), filter == ItemState.SKIP.name) {
                vm.filesState.value = ItemState.SKIP.name
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilesChip(
                stringResource(R.string.sort_newest),
                sort == AppViewModel.FilesSort.NEWEST
            ) { vm.filesSort.value = AppViewModel.FilesSort.NEWEST }
            FilesChip(
                stringResource(R.string.sort_saved),
                sort == AppViewModel.FilesSort.SAVED
            ) { vm.filesSort.value = AppViewModel.FilesSort.SAVED }
            FilesChip(
                stringResource(R.string.sort_largest),
                sort == AppViewModel.FilesSort.LARGEST
            ) { vm.filesSort.value = AppViewModel.FilesSort.LARGEST }
        }
        Spacer(Modifier.height(8.dp))

        if (items.isEmpty()) {
            EmptyState(
                title = when {
                    query.isNotEmpty() -> stringResource(R.string.files_no_match_title)
                    filter != null -> stringResource(R.string.files_no_match_title)
                    else -> stringResource(R.string.files_empty_title)
                },
                body = when {
                    query.isNotEmpty() -> stringResource(R.string.files_no_match_body)
                    filter != null -> stringResource(R.string.files_filter_empty)
                    else -> stringResource(R.string.files_empty)
                }
            )
        } else {
            // weight, not fillMaxSize: inside a Column the latter asks for
            // the parent's whole height, so the list overflowed the screen
            // and its rows drew on top of the header above it.
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items, key = { it.id }) { row ->
                    AppCard(
                        modifier = Modifier
                            .padding(vertical = 5.dp)
                            .animateItem(),
                        onClick = { detail = row }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Thumbnail(row)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(
                                    row.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    // IMG_20240517_181233.jpg cut at the right
                                    // loses the date, which is the part that
                                    // identifies the photo.
                                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis
                                )
                                Text(
                                    // The whole point of the app, per row: what
                                    // it was, what it is, and how much that saved.
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
            }
        }
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
            icon = {
                // Exactly the actions this file's state allows, from the one
                // rule every list obeys. An already-optimised copy is not
                // offered "never optimise": the work is done, the option
                // cannot undo it, and offering it is what made the counters
                // disagree with no way to tell why.
                Row {
                    for (action in RowActions.forItem(row.toActionRow())) {
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
                                vm.setNeverOptimise(row.id, true)
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
            },
            title = { Text(row.displayName, maxLines = 2) },
            text = {
                Column {
                    KeyValueRow(stringResource(R.string.detail_state), stateLabel(row))
                    KeyValueRow(stringResource(R.string.detail_evidence), evidenceLabel(row))
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
